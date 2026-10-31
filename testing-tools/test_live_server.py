import http.client
import json
import urllib.parse
import sys

BASE_URL = "localhost:8080"

def run_tests():
    print("=" * 80)
    print(" MoveInSync Python Test Suite: Verifying Live Configuration Server")
    print("=" * 80)

    conn = http.client.HTTPConnection(BASE_URL)
    passed = 0
    total = 0

    def check(name, method, path, headers=None, body=None, expected_status=200):
        nonlocal passed, total
        total += 1
        print(f"\n[Test {total}] {name}...", end="")
        h = headers or {}
        b = json.dumps(body) if body else None
        if b and "Content-Type" not in h:
            h["Content-Type"] = "application/json"

        try:
            conn.request(method, path, body=b, headers=h)
            res = conn.getresponse()
            data = res.read().decode()
            if res.status == expected_status:
                print(f" [PASS] (Status: {res.status})")
                passed += 1
                try:
                    return json.loads(data)
                except Exception:
                    return data
            else:
                print(f" [FAILED] (Expected {expected_status}, got {res.status})")
                return None
        except Exception as e:
            print(f" [FAILED] (Connection error: {e})")
            return None

    # 1. Health
    check("Health Check", "GET", "/actuator/health")

    # 2. Version
    check("Inquire Version", "GET", "/api/v1/configs/version")

    # 3. Create Definition
    check("Register Schema Definition", "POST", "/api/v1/configs/definitions",
          headers={"X-User-Role": "ADMIN"},
          body={
              "key": "booking.cutoff.minutes",
              "dataType": "DURATION",
              "description": "Cutoff time",
              "globalDefault": 60,
              "minValue": 5,
              "maxValue": 360
          })

    # 4. Commit Valid ChangeSet
    check("Commit Valid ChangeSet (Operator)", "POST", "/api/v1/configs/changeset",
          headers={"X-Tenant-Id": "Google", "X-User-Role": "OPERATOR"},
          body={
              "author": "ops-lead",
              "reason": "tighten cutoff",
              "updates": [
                  {"scopeKey": {"level": "OFFICE", "clientId": "Google", "officeId": "Bengaluru"},
                   "key": "booking.cutoff.minutes", "value": 30}
              ]
          })

    # 5. Reject Negative Value
    check("Pre-commit Rejection (-30 mins)", "POST", "/api/v1/configs/changeset",
          headers={"X-Tenant-Id": "Google", "X-User-Role": "OPERATOR"},
          body={
              "author": "bad-actor",
              "reason": "bad value",
              "updates": [
                  {"scopeKey": {"level": "OFFICE", "clientId": "Google", "officeId": "Bengaluru"},
                   "key": "booking.cutoff.minutes", "value": -30}
              ]
          },
          expected_status=400)

    # 6. Tenant Isolation Violation
    check("Tenant Isolation (Google -> Amazon)", "POST", "/api/v1/configs/changeset",
          headers={"X-Tenant-Id": "Google", "X-User-Role": "OPERATOR"},
          body={
              "author": "google-op",
              "reason": "attack",
              "updates": [
                  {"scopeKey": {"level": "OFFICE", "clientId": "Amazon", "officeId": "Bengaluru"},
                   "key": "booking.cutoff.minutes", "value": 45}
              ]
          },
          expected_status=403)

    # 7. Explain Mode
    check("Explain Mode", "GET", "/api/v1/configs/explain?key=booking.cutoff.minutes&clientId=Google&officeId=Bengaluru",
          headers={"X-Tenant-Id": "Google"})

    # 8. Rollback
    check("Instant Rollback with Diff", "POST", "/api/v1/configs/rollback?targetVersion=0&author=admin&reason=emergency-revert",
          headers={"X-User-Role": "ADMIN"})

    print("\n" + "=" * 80)
    print(f" Summary: {passed}/{total} Tests Passed!")
    print("=" * 80)
    conn.close()

if __name__ == "__main__":
    run_tests()
