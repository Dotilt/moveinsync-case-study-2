# MoveInSync Live Automated End-to-End Test Suite

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   MoveInSync Backend Assessment: Automated Live Integration Test Runner        " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$baseUrl = "http://localhost:8080"
$totalTests = 0
$passedTests = 0

function Test-Endpoint {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [int]$ExpectedStatusCode,
        [scriptblock]$ValidationBlock = $null
    )
    $script:totalTests++
    Write-Host "`n[Test $script:totalTests] $Name..." -NoNewline

    try {
        $params = @{
            Uri = $Uri
            Method = $Method
            Headers = $Headers
            ErrorAction = "Stop"
            UseBasicParsing = $true
        }
        if ($Body) {
            $params["Body"] = $Body
            $params["ContentType"] = "application/json"
        }

        $rawResponse = Invoke-WebRequest @params
        $statusCode = [int]$rawResponse.StatusCode
        $content = $rawResponse.Content
        if ($content -is [byte[]]) {
            $content = [System.Text.Encoding]::UTF8.GetString($content)
        }
        $response = $null
        if ($content) {
            try { $response = $content | ConvertFrom-Json } catch { $response = $content }
        }
        
        if ($statusCode -eq $ExpectedStatusCode) {
            if ($ValidationBlock) {
                $customPass = $false
                try {
                    $customPass = & $ValidationBlock $response
                } catch {
                    $customPass = $false
                }
                if ($customPass) {
                    Write-Host " [PASS] (Status: $statusCode)" -ForegroundColor Green
                    $script:passedTests++
                } else {
                    Write-Host " [FAILED] (Assertion check failed: raw content: '$content')" -ForegroundColor Red
                }
            } else {
                Write-Host " [PASS] (Status: $statusCode)" -ForegroundColor Green
                $script:passedTests++
            }
        } else {
            Write-Host " [FAILED] (Expected $ExpectedStatusCode, got $statusCode)" -ForegroundColor Red
        }
    } catch {
        $actualCode = $null
        if ($_.Exception.Response) {
            $actualCode = [int]$_.Exception.Response.StatusCode
        }
        if ($actualCode -eq $ExpectedStatusCode) {
            Write-Host " [PASS] (Expected error correctly returned: $actualCode)" -ForegroundColor Green
            $script:passedTests++
        } else {
            Write-Host " [FAILED] ($($_.Exception.Message))" -ForegroundColor Red
        }
    }
}

# 1. Health check
Test-Endpoint -Name "Server Health & Actuator Status" `
              -Method "GET" `
              -Uri "$baseUrl/actuator/health" `
              -ExpectedStatusCode 200 `
              -ValidationBlock { param($res) $res.status -eq "UP" }

# 2. Register Definition
$defJson = @'
{
  "key": "booking.cutoff.minutes",
  "dataType": "DURATION",
  "description": "Cutoff time in minutes before cab departure",
  "globalDefault": 60,
  "minValue": 5,
  "maxValue": 360
}
'@
Test-Endpoint -Name "Register Schema Definition (ADMIN)" `
              -Method "POST" `
              -Uri "$baseUrl/api/v1/configs/definitions" `
              -Headers @{ "X-User-Role" = "ADMIN" } `
              -Body $defJson `
              -ExpectedStatusCode 200

# 3. Commit Valid ChangeSet
$csJson = @'
{
  "author": "ops-lead",
  "reason": "Tighten cutoff for Bengaluru office",
  "updates": [
    {
      "scopeKey": {
        "level": "OFFICE",
        "clientId": "Google",
        "officeId": "Bengaluru"
      },
      "key": "booking.cutoff.minutes",
      "value": 30
    }
  ]
}
'@
Test-Endpoint -Name "Commit Valid ChangeSet (OPERATOR for Google)" `
              -Method "POST" `
              -Uri "$baseUrl/api/v1/configs/changeset" `
              -Headers @{ "X-Tenant-Id" = "Google"; "X-User-Role" = "OPERATOR" } `
              -Body $csJson `
              -ExpectedStatusCode 200 `
              -ValidationBlock { param($res) $res.version -gt 0 }

# 4. Pre-Commit Validation: Negative cut-off rejection
$badValJson = @'
{
  "author": "bad-actor",
  "reason": "negative cutoff",
  "updates": [
    {
      "scopeKey": {
        "level": "OFFICE",
        "clientId": "Google",
        "officeId": "Bengaluru"
      },
      "key": "booking.cutoff.minutes",
      "value": -30
    }
  ]
}
'@
Test-Endpoint -Name "Pre-Commit Validation: Reject negative cut-off value (-30)" `
              -Method "POST" `
              -Uri "$baseUrl/api/v1/configs/changeset" `
              -Headers @{ "X-Tenant-Id" = "Google"; "X-User-Role" = "OPERATOR" } `
              -Body $badValJson `
              -ExpectedStatusCode 400

# 5. Tenant Isolation Barrier: Google operator cannot touch Amazon
$crossTenantJson = @'
{
  "author": "google-op",
  "reason": "tamper amazon settings",
  "updates": [
    {
      "scopeKey": {
        "level": "OFFICE",
        "clientId": "Amazon",
        "officeId": "Bengaluru"
      },
      "key": "booking.cutoff.minutes",
      "value": 45
    }
  ]
}
'@
Test-Endpoint -Name "Tenant Isolation: Reject cross-tenant write (Google -> Amazon)" `
              -Method "POST" `
              -Uri "$baseUrl/api/v1/configs/changeset" `
              -Headers @{ "X-Tenant-Id" = "Google"; "X-User-Role" = "OPERATOR" } `
              -Body $crossTenantJson `
              -ExpectedStatusCode 403

# 6. Explain Mode Query
Test-Endpoint -Name "Explain Mode: Verify winning level is OFFICE" `
              -Method "GET" `
              -Uri "$baseUrl/api/v1/configs/explain?key=booking.cutoff.minutes&clientId=Google&officeId=Bengaluru" `
              -Headers @{ "X-Tenant-Id" = "Google" } `
              -ExpectedStatusCode 200 `
              -ValidationBlock { param($res) $res.resolvedValue -eq 30 -and $res.winningLevel -eq "OFFICE" }

# 7. Instant Rollback
Test-Endpoint -Name "Instant 1-Click Rollback with Automated Diff (ADMIN)" `
              -Method "POST" `
              -Uri "$baseUrl/api/v1/configs/rollback?targetVersion=0&author=admin&reason=emergency-revert" `
              -Headers @{ "X-User-Role" = "ADMIN" } `
              -ExpectedStatusCode 200 `
              -ValidationBlock { param($res) $res.newVersion -gt 0 -and $res.diff.changes.Count -gt 0 }

Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "   TEST SUMMARY: $passedTests / $totalTests Tests Passed Successfully!          " -ForegroundColor $(if ($passedTests -eq $totalTests) { "Green" } else { "Yellow" })
Write-Host "================================================================================" -ForegroundColor Cyan
