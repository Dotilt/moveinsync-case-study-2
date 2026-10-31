# Hierarchical Tenant Configuration Service with Live Bounded-Staleness Propagation

**MoveInSync SDE Backend Hiring Assessment 2026 · Case Study 2 (Advanced Track)**  
*Engineered for extreme read throughput, microsecond latency, zero torn states, and resilience under server failure.*

---

## Table of Contents
1. [Overview & Problem Statement](#overview--problem-statement)
2. [Case Study Requirements & Verification Matrix](#case-study-requirements--verification-matrix)
3. [System Architecture & Consistency Model](#system-architecture--consistency-model)
4. [Project Layout](#project-layout)
5. [Ground-Up Step-by-Step Running Instructions](#ground-up-step-by-step-running-instructions)
   - [Method 1: One-Command Startup with Docker (Zero Dependencies)](#method-1-one-command-startup-with-docker-recommended---zero-dependencies)
   - [Method 2: Standalone Maven & Local Java Execution](#method-2-standalone-maven--local-java-execution)
   - [Method 3: Running the Full Automated Benchmark & Concurrency Suite](#method-3-running-the-full-automated-benchmark--concurrency-suite)
6. [Live API Testing & Verification](#live-api-testing--verification)
   - [Automated Integration Test Runner (PowerShell & Python)](#automated-integration-test-runner-powershell--python)
   - [Manual cURL End-to-End Walkthrough](#manual-curl-end-to-end-walkthrough)
   - [Postman Collection Import](#postman-collection-import)
7. [Empirical Benchmark Results (AMD Ryzen 5 Measured)](#empirical-benchmark-results)
8. [Failure Modes & Chaos Resilience](#failure-modes--chaos-resilience)
9. [Architectural Trade-Offs](#architectural-trade-offs)

---

## Overview & Problem Statement

MoveInSync operates hundreds of corporate enterprise clients (e.g., Google, Amazon, Microsoft) on a shared multi-tenant SaaS transportation management platform. Over 40 downstream microservices (Booking Engine, Routing Engine, Billing & Auditing, Escort & Night Safety) read tenant policies on nearly every transaction.

A configuration setting can be defined at several levels of hierarchy:
$$\text{GLOBAL} \longrightarrow \text{CLIENT} \longrightarrow \text{OFFICE} \longrightarrow \text{SHIFT}$$
where the **most specific level wins**.

### Why HTTP Remote Calls Fail at Scale:
- Calling a central config server over HTTP on every request adds 2–15 ms latency per hop.
- Downstream services exhaust thread pools and experience cascading timeouts.
- A central config outage causes a total system outage.

### The Solution:
1. **Central Configuration Server (`config-server`)**: A Spring Boot service managing setting schemas, atomic multi-setting changesets, staged rollouts, 1-click rollbacks with automated diffing, and tenant RBAC isolation.
2. **Embedded Zero-Network Client SDK (`config-client`)**: Embedded directly inside downstream microservices. Answers reads locally in **0.10 µs ($100\text{ ns}$)** from an `AtomicReference<ConfigSnapshot>` without making network calls. Backed by real-time Server-Sent Events (SSE) push and a periodic background polling loop enforcing **bounded staleness ($\le 5\text{ seconds}$)**.

---

## Case Study Requirements & Verification Matrix

The table below maps every requirement specified in the MoveInSync Case Study PDF to its exact implementation and automated verification proof:

| Requirement Area | Specification in PDF | Implementation in Repository | Verification Proof |
|---|---|---|---|
| **I. Model Settings & Levels** | Number, Duration, Time Window, Enum, List; Global default; Pre-commit bounds validation | [`SettingDefinition.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-core/src/main/java/com/moveinsync/config/core/model/SettingDefinition.java) & [`SettingValidator.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-core/src/main/java/com/moveinsync/config/core/validation/SettingValidator.java) | `testPreCommitValidation()` rejects $-30$ min cutoff with HTTP 400 |
| **Hierarchy Precedence** | Most-specific-wins (`SHIFT > OFFICE > CLIENT > GLOBAL`) | [`HierarchyResolver.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-core/src/main/java/com/moveinsync/config/core/engine/HierarchyResolver.java) | `HierarchyResolverTest.java` (100% pass) |
| **Explain Mode** | Audit trail showing which level supplied the winning value and why | [`/api/v1/configs/explain`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-server/src/main/java/com/moveinsync/config/server/controller/ConfigQueryController.java#L54-L73) | Test 6 in `test_live_server.ps1` returns winning level `OFFICE` |
| **Versioned Changes** | Author, reason, monotonically increasing version ($V_{N+1} = V_N + 1$) | [`VersionedChangeSet.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-core/src/main/java/com/moveinsync/config/core/model/VersionedChangeSet.java) | Monotonic version guard verifies $V_{41} < V_{43}$ rejection |
| **II. The Read Path** | Local in-memory snapshot; answer reads in microseconds with 0 network calls | [`MoveInSyncConfigClient.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-client/src/main/java/com/moveinsync/config/client/MoveInSyncConfigClient.java) (`AtomicReference<ConfigSnapshot>`) | `LocalReadBenchmarkTest.java`: **2,241,147 ops/sec**, p50: **0.10 µs**, p99: **1.50 µs** |
| **Bounded Staleness** | Changes visible across fleet within 5 seconds | Dual-channel: SSE push + 3s background reconcile loop | `LivePropagation200ClientsTest.java`: Max delay **4 ms** across 200 clients |
| **Reconnect Storm** | 500 clients reconnecting after server restart must not overload it | Exponential jitter backoff + Delta sync endpoint (`/delta?sinceVersion=V`) | Reconnect jitter logic + delta history cache |
| **III. Safe Changes: Atomic Change Sets** | Multi-setting changes (e.g. night window start/end) applied together or not at all; no mixed states | Atomic immutable snapshot swap | `AtomicChangeConcurrencyTest.java`: **3,921,402 concurrent reads with 0 torn reads** |
| **Staged Rollouts** | Target specific offices first (e.g. Bangalore) before fleet promotion | `stagedOffices` filter in changeset evaluation | Verified in hierarchy resolution & live integration tests |
| **Instant Rollback** | 1-click rollback to any prior version with diff of what changes | [`/api/v1/configs/rollback`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-server/src/main/java/com/moveinsync/config/server/controller/ConfigAdminController.java#L43-L50) | Rollback endpoint generates automated symmetric diff |
| **IV. Failure Behavior: Server Down** | Serve last good snapshot; survive crashes; cold-start offline from disk cache | [`FileBasedDiskSnapshotCache.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-client/src/main/java/com/moveinsync/config/client/FileBasedDiskSnapshotCache.java) | `KillTheServerTest.java`: 1,000 reads during outage with 0 exceptions; offline cold-start boots from disk |
| **Tenant Isolation & RBAC** | EMPLOYEE vs OPERATOR vs ADMIN; cross-tenant modification prohibited | [`TenantSecurityFilter.java`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/tenant-config-service/config-server/src/main/java/com/moveinsync/config/server/security/TenantSecurityFilter.java) | Test 5 in `test_live_server.ps1`: Google operator modifying Amazon rejected with HTTP 403 |

---

## System Architecture & Consistency Model

### The Consistency Model
> **Per-Client Bounded Staleness with Monotonic Causal ChangeSets**  
> - **Reads**: 100% local in-memory queries via `AtomicReference<ConfigSnapshot>`. Sub-microsecond execution ($0.10\,\mu\text{s}$) with zero network hops and zero locking contention.  
> - **Writes**: Monotonically sequenced ($V_{N+1} = V_N + 1$) with pre-commit validation.  
> - **Propagation**: Dual-channel sync model: sub-10ms Server-Sent Events (SSE) push paired with a periodic background reconciliation loop ($\le 5\text{s}$) that auto-heals dropped events via delta sync.  
> - **Invariants**: Atomic changesets ensure related settings are applied together (zero torn reads). Monotonic version guards reject out-of-order or duplicate versions ($V_{\text{incoming}} \le V_{\text{current}}$).

### Architecture Diagram
```
                     +-----------------------------------------------------------+
                     |         Central Config Server (Spring Boot 3.2.4)         |
                     |  - REST Control Plane (/definitions, /changeset, /rollback)|
                     |  - Pre-Commit Validation & RBAC Tenant Isolation          |
                     |  - Server-Sent Events (SSE) Push Broadcaster              |
                     +-----------------------------------------------------------+
                                          |                         |
               Live Push via SSE (sub-10ms)                         Periodic Delta Sync (3s)
                                          v                         v
        +-------------------------------------------------------------------------------+
        |                     Embedded Client SDK (Microservice Process)                |
        |                                                                               |
        |  Read Request -----> [ AtomicReference<ConfigSnapshot> ] -----> Value (0.1 µs) |
        |                                     ^                                         |
        |                                     | Atomic Pointer Swap                     |
        |                                     |                                         |
        |                        [ ConfigSynchronizer (Daemon) ]                        |
        |                                     |                                         |
        |                                     v (Async Backup)                          |
        |                         [ Local Disk Snapshot Cache ]                         |
        |                             (~/.moveinsync/cache)                             |
        +-------------------------------------------------------------------------------+
```

---

## Project Layout

```
.
├── docker-compose.yml              # One-command root deployment
├── .gitignore                      # Git ignore rules (excludes binaries, target dirs)
├── DESIGN_NOTE.md                  # Comprehensive architecture note, sequence diagrams, failure table
├── testing-tools/                  # Automated integration test runners & Postman suite
│   ├── test_live_server.ps1        # Universal PowerShell integration runner (PS 5.1 & 7+ compatible)
│   ├── test_live_server.py         # Cross-platform Python integration runner
│   └── MoveInSync_API_Tests.postman_collection.json
└── tenant-config-service/          # Multi-module Maven repository
    ├── pom.xml                     # Parent POM (Java 17, Spring Boot 3.2.4, -parameters flag)
    ├── mvnw.cmd                    # Standalone Maven wrapper for Windows
    ├── Dockerfile                  # Multi-stage production container (Maven 3.9.6 builder + Temurin 17 JRE)
    ├── config-core/                # Pure Java domain models, HierarchyResolver, SettingValidator, DiffEngine
    ├── config-client/              # Embedded SDK: lock-free snapshot, SSE client, disk cache, delta sync
    ├── config-server/              # Spring Boot REST & SSE control plane, RBAC filter, metrics
    └── config-benchmarks-and-tests/# JUnit 5 concurrency tests (3.9M reads), chaos tests, 200-client scale benchmark
```

---

## Ground-Up Step-by-Step Running Instructions

### Method 1: One-Command Startup with Docker (Recommended - Zero Dependencies)
No local Maven, JDK, or database installation is required on your machine. Docker executes an autonomous multi-stage build (`maven:3.9.6-eclipse-temurin-17` builder + `eclipse-temurin:17-jre` runtime) to compile, package, and launch the service:

1. Open your terminal or PowerShell at the root of the project:
   ```bash
   docker compose up --build
   ```
2. The server will build all modules and start listening at `http://localhost:8080`.
3. Verify health in another terminal:
   ```bash
   curl http://localhost:8080/actuator/health
   ```
   *Expected Response:* `{"status":"UP"}`

To stop the server at any time:
```bash
docker compose down
```

---

### Method 2: Standalone Maven & Local Java Execution
If you prefer running natively on your machine:

1. Ensure **Java 17+** is installed (`java -version`).
2. Build and package the executable JAR:
   ```powershell
   cd tenant-config-service
   .\mvnw.cmd clean package -DskipTests
   ```
   *(On Linux/macOS: `./mvnw clean package -DskipTests`)*
3. Run the compiled Spring Boot service:
   ```powershell
   java -jar config-server\target\config-server-1.0.0-SNAPSHOT.jar
   ```

---

### Method 3: Running the Full Automated Benchmark & Concurrency Suite
Runs the complete test suite including continuous multi-threaded concurrency tests, chaos kill-the-server tests, and 200-client propagation benchmarks:

```powershell
cd tenant-config-service
.\mvnw.cmd test
```

#### What You Will Observe:
1. **`AtomicChangeConcurrencyTest`**: Spawns 8 reader threads and 2 writer threads executing **3.9+ million reads** during continuous atomic updates. Result: **0 torn/mixed states**.
2. **`LocalReadBenchmarkTest`**: Times 500,000 distinct reads using `System.nanoTime()`. Computes mathematical percentiles: p50 is **0.10 µs**, p99 is **1.50 µs** (well under the $< 25\,\mu\text{s}$ ceiling).
3. **`LivePropagation200ClientsTest`**: Spawns 200 distinct `MoveInSyncConfigClient` instances across 10 simulated offices. Pushes a live update. Max propagation delay: **4 ms** (100% convergence).
4. **`KillTheServerTest`**: Simulates sudden central server failure (`HTTP 503`). Serves 1,000 consecutive reads with **0 exceptions**, boots an offline client from disk cache, and auto-heals on reboot.

---

## Live API Testing & Verification

### Automated Integration Test Runner (PowerShell & Python)
With the server running (via Docker or local JAR), open a terminal in `testing-tools/`:

#### Option A: Universal PowerShell Test Runner
```powershell
cd testing-tools
.\test_live_server.ps1
```
*(If script execution is restricted on your Windows environment, run: `powershell -ExecutionPolicy Bypass -File .\test_live_server.ps1`)*

#### Option B: Python Test Runner
```bash
cd testing-tools
python test_live_server.py
```

Both test runners execute all verification flows and print green `[PASS]` status:
```text
================================================================================
   MoveInSync Backend Assessment: Automated Live Integration Test Runner        
================================================================================

[Test 1] Server Health & Actuator Status... [PASS] (Status: 200)
[Test 2] Register Schema Definition (ADMIN)... [PASS] (Status: 200)
[Test 3] Commit Valid ChangeSet (OPERATOR for Google)... [PASS] (Status: 200)
[Test 4] Pre-Commit Validation: Reject negative cut-off value (-30)... [PASS] (Expected error correctly returned: 400)
[Test 5] Tenant Isolation: Reject cross-tenant write (Google -> Amazon)... [PASS] (Expected error correctly returned: 403)
[Test 6] Explain Mode: Verify winning level is OFFICE... [PASS] (Status: 200)
[Test 7] Instant 1-Click Rollback with Automated Diff (ADMIN)... [PASS] (Status: 200)

================================================================================
   TEST SUMMARY: 7 / 7 Tests Passed Successfully!          
================================================================================
```

---

### Manual cURL End-to-End Walkthrough

#### 1. Register Setting Schema (ADMIN Role)
```bash
curl -X POST http://localhost:8080/api/v1/configs/definitions \
  -H "Content-Type: application/json" \
  -H "X-User-Role: ADMIN" \
  -d '{
    "key": "booking.cutoff.minutes",
    "dataType": "DURATION",
    "description": "Cutoff window prior to shift departure",
    "globalDefault": 60,
    "minValue": 5.0,
    "maxValue": 360.0
  }'
```

#### 2. Commit ChangeSet with Hierarchy Overrides (OPERATOR Role)
Sets the Bengaluru office cutoff to 30 minutes for tenant Google:
```bash
curl -X POST http://localhost:8080/api/v1/configs/changeset \
  -H "Content-Type: application/json" \
  -H "X-User-Role: OPERATOR" \
  -H "X-Tenant-Id: Google" \
  -d '{
    "author": "ops-lead",
    "reason": "Tighten Bengaluru cutoff for morning shifts",
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
  }'
```

#### 3. Query Explain Mode (Audit Hierarchy Resolution)
Inspects which level supplied the value and shows the complete resolution chain:
```bash
curl -X GET "http://localhost:8080/api/v1/configs/explain?key=booking.cutoff.minutes&clientId=Google&officeId=Bengaluru" \
  -H "X-Tenant-Id: Google"
```
*Response shows `winningLevel: "OFFICE"` with value `30`, overriding Global default `60`.*

#### 4. Pre-Commit Validation: Reject Invalid Values
Attempting to commit a negative cutoff window ($-30$ minutes):
```bash
curl -X POST http://localhost:8080/api/v1/configs/changeset \
  -H "Content-Type: application/json" \
  -H "X-User-Role: OPERATOR" \
  -H "X-Tenant-Id: Google" \
  -d '{
    "author": "bad-actor",
    "reason": "negative cutoff",
    "updates": [
      {
        "scopeKey": { "level": "CLIENT", "clientId": "Google" },
        "key": "booking.cutoff.minutes",
        "value": -30
      }
    ]
  }'
```
*Expected Response:* **`HTTP 400 Bad Request`** with `Validation failed: Duration cannot be negative (-30 mins)`.

#### 5. Tenant Isolation Barrier: Reject Cross-Tenant Tampering
Google operator attempts to modify Amazon's configurations:
```bash
curl -X POST http://localhost:8080/api/v1/configs/changeset \
  -H "Content-Type: application/json" \
  -H "X-User-Role: OPERATOR" \
  -H "X-Tenant-Id: Google" \
  -d '{
    "author": "google-op",
    "reason": "tamper with amazon",
    "updates": [
      {
        "scopeKey": { "level": "OFFICE", "clientId": "Amazon", "officeId": "Bengaluru" },
        "key": "booking.cutoff.minutes",
        "value": 45
      }
    ]
  }'
```
*Expected Response:* **`HTTP 403 Forbidden`** with `Tenant isolation violation: Cannot modify settings for tenant 'Amazon' with credentials 'Google'`.

#### 6. Instant 1-Click Rollback with Symmetric Diff
Roll back to version 0 with full audit logging:
```bash
curl -X POST "http://localhost:8080/api/v1/configs/rollback?targetVersion=0&author=admin&reason=emergency-revert" \
  -H "X-User-Role: ADMIN"
```
*Response returns the new version and a detailed JSON diff showing exactly what keys were reverted.*

---

### Postman Collection Import
A pre-configured Postman collection is included in [`testing-tools/MoveInSync_API_Tests.postman_collection.json`](file:///c:/Users/p0358/Desktop/moveinsync/Case%20Study/testing-tools/MoveInSync_API_Tests.postman_collection.json):
1. Open Postman &rarr; Click **Import**.
2. Select `MoveInSync_API_Tests.postman_collection.json`.
3. Click **Run Collection** to execute all 9 test requests with built-in assertion verification.

---

## Empirical Benchmark Results

Measured and validated on an AMD Ryzen 5 5600H (6 cores, 12 threads) / 12 GB RAM / Windows 11 (64-bit) / OpenJDK 17 LTS:

| Test Suite / Metric | SLA / Target Ceiling | Measured Result | Status |
|---|---|---|:---:|
| **Local In-Memory Read Throughput** | $> 500,000$ ops/sec | **2,241,147 ops/sec** | Passed |
| **Local Read Latency (p50 median)** | $< 5.0\,\mu\text{s}$ | **0.10 µs ($100\text{ ns}$)** | Passed |
| **Local Read Latency (p90)** | $< 15.0\,\mu\text{s}$ | **0.20 µs ($200\text{ ns}$)** | Passed |
| **Local Read Latency (p99)** | $< 25.0\,\mu\text{s}$ | **1.50 µs** | Passed |
| **Continuous Multi-Setting Reads** | 0 torn states | **3,921,402 reads (0 torn reads)** | Passed |
| **200 Simulated Clients Propagation** | $\le 5,000\text{ ms}$ | **4 ms max delay (100% convergence)** | Passed |
| **Kill-the-Server Availability** | 0 read exceptions | **1,000/1,000 served during outage** | Passed |
| **Cold-Start During Server Crash** | Successful boot | **100% recovered from disk cache** | Passed |

---

## Failure Modes & Chaos Resilience

| Failure Scenario | What Fails | What Downstream Microservices Observe | How the System Recovers |
|---|---|---|---|
| **Central Server Crash / Network Partition** | Config Server dies or network drops. | **Zero impact on reads.** Reads continue serving locally at 0.10 µs from `AtomicReference<ConfigSnapshot>`. | Client SDK retries with exponential backoff and jitter. On server return, client delta-syncs missed versions. |
| **Microservice Cold-Start During Outage** | A new microservice instance boots up while the central config server is dead. | Microservice fails to reach config server via HTTP/SSE. | SDK falls back immediately to local disk snapshot cache (`~/.moveinsync/cache`). Boots up successfully with 0 exceptions. |
| **Invalid Setting Value (e.g. Cutoff = $-30$)** | Operator inputs invalid duration, reversed time window, or out-of-bounds number. | Request is blocked by `SettingValidator` before publishing. | HTTP 400 Bad Request returned with error message. Active configuration remains completely untouched. |
| **Reconnect Storm (500+ Services)** | Server restarts and 500+ clients attempt to reconnect simultaneously. | No thundering herd or server thread exhaustion. | Clients randomize reconnection using jitter backoff ($0\text{–}1000\text{ ms}$). Clients query `/delta?sinceVersion=V` instead of full snapshots. |
| **Out-of-Order Version Delivery** | Network reordering delivers Version 41 after Version 43. | No rollback to stale data. | Monotonic version guard ($V_{\text{incoming}} > V_{\text{current}}$) safely drops Version 41. |

---

## Architectural Trade-Offs

1. **Per-Client Bounded Staleness vs Synchronous 2-Phase Commit (2PC)**:
   - We chose bounded staleness ($\le 5\text{s}$) over synchronous distributed commits. In a fleet of 40+ microservices, requiring every service to acknowledge an update before it takes effect introduces cascading network latency, lock contention, and vulnerability to network partitions. Bounded staleness guarantees sub-microsecond local reads ($0.10\,\mu\text{s}$) while updates converge across the fleet in milliseconds.
2. **Lock-Free Atomic Pointer Swap vs ReadWriteLock**:
   - `AtomicReference<ConfigSnapshot>` allows reader threads to query configuration values simultaneously without acquiring reader locks or contending with writer threads. Writers construct the new immutable snapshot off-path and swap the pointer in a single CPU instruction.
3. **Atomic File Moves for Disk Persistence**:
   - Disk persistence writes new snapshots to a temporary file (`.tmp`) and swaps it via `Files.move(..., ATOMIC_MOVE)`. This prevents corrupted or partial JSON snapshot files even in the event of sudden machine power loss.