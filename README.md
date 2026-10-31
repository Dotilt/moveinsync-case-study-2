# Hierarchical Tenant Configuration Service with Live Propagation

MoveInSync Backend Hiring Assessment 2026 · Case Study 2 (Advanced Track)

---

## Overview

MoveInSync manages hundreds of enterprise corporate clients on a single multi-tenant transportation platform. Over 40 downstream microservices (such as Booking, Route Optimization, Billing, and Night Safety Escorts) read tenant policies on nearly every transaction.

Querying a central configuration server over HTTP for every request introduces high network latency (2–15 ms per check), exhausts thread pools, and creates a single point of failure.

This project implements:
1. **Central Configuration Server (`config-server`)**: A Spring Boot service managing schemas, atomic multi-setting changesets, staged rollouts, 1-click rollbacks with automated diffing, and tenant RBAC isolation.
2. **Embedded Zero-Network Client SDK (`config-client`)**: A library embedded directly in downstream microservices that answers reads locally from memory in **0.10 µs**, backed by Server-Sent Events (SSE) push and a 3-second self-healing polling loop enforcing **bounded staleness ($\le 5\text{s}$)**.

---

## Consistency Model

**Per-Client Bounded Staleness with Monotonic Causal ChangeSets**

- **Reads**: 100% local in-memory queries via `AtomicReference<ConfigSnapshot>`. Sub-microsecond execution with zero network hops and zero locking contention.
- **Writes**: Centralized and monotonically versioned ($V_{N+1} = V_N + 1$) with pre-commit validation.
- **Propagation**: Sub-10ms Server-Sent Events (SSE) live push paired with a periodic background version reconciliation check (every 3 seconds) that auto-heals dropped events via delta sync.
- **Invariants**: Atomic changesets ensure related settings are applied together (zero torn reads). Monotonic version guards reject out-of-order or duplicate versions ($V_{\text{incoming}} \le V_{\text{current}}$).

---

## Project Structure

```
.
├── docker-compose.yml              # One-command root startup
├── .gitignore                      # Clean ignore rules (no binaries/target)
├── DESIGN_NOTE.md                  # Consistency model, architecture & sequence diagrams, failure matrix
├── testing-tools/                  # Postman collection & automated live test scripts
│   ├── MoveInSync_API_Tests.postman_collection.json
│   ├── test_live_server.ps1
│   └── test_live_server.py
└── tenant-config-service/          # Multi-module Maven project
    ├── pom.xml                     # Parent POM (Java 17, Spring Boot 3.2.4)
    ├── mvnw.cmd                    # Standalone Maven wrapper
    ├── config-core/                # Pure domain models, validation & diff engine
    ├── config-client/              # Embedded SDK (in-memory snapshot, SSE sync, disk cache)
    ├── config-server/              # Spring Boot REST & SSE control plane
    └── config-benchmarks-and-tests/# Automated concurrency, chaos & scale suites
```

---

## Quickstart & Verification

### 1. Run Automated Test Suite (Zero Setup)
Runs all unit tests, continuous concurrency tests (3.9M reads), chaos kill-the-server tests, and 200-client propagation benchmarks:

```powershell
cd tenant-config-service
.\mvnw.cmd test
```
*(On Linux/macOS: `./mvnw test`)*

### 2. Run with Docker (One-Command Startup)
From the repository root:

```bash
docker compose up --build
```
The server will start at `http://localhost:8080`.

Verify health:
```bash
curl http://localhost:8080/actuator/health
```

---

## Empirical Benchmark Results

Tested on AMD Ryzen 7 / 16 GB RAM / Windows 11 / OpenJDK 17 LTS:

| Test / Metric | Target Ceiling | Measured Result | Status |
|---|---|---|:---:|
| **Local In-Memory Read Throughput** | $> 500,000$ ops/sec | **2,241,147 ops/sec** | Passed |
| **Local Read Latency (p50)** | $< 5.0\,\mu\text{s}$ | **0.10 µs (100 ns)** | Passed |
| **Local Read Latency (p99)** | $< 25.0\,\mu\text{s}$ | **1.50 µs** | Passed |
| **Continuous Multi-Setting Reads** | 0 torn states | **3,921,402 reads (0 torn reads)** | Passed |
| **200 Simulated Clients Propagation** | $\le 5,000\text{ ms}$ | **4 ms max delay (100% convergence)** | Passed |
| **Kill-the-Server Availability** | 0 read exceptions | **1,000/1,000 served during outage** | Passed |
| **Cold-Start During Server Crash** | Successful boot | **100% recovered from disk cache** | Passed |

---

## How to Test the Live Server (REST API)

Start the server:
```powershell
cd tenant-config-service
java -jar config-server\target\config-server-1.0.0-SNAPSHOT.jar
```

In a second terminal, run the automated integration test script:
```powershell
cd testing-tools
.\test_live_server.ps1
```

Or test manually via curl:

### 1. Register Setting Schema
```bash
curl -X POST http://localhost:8080/api/v1/configs/definitions \
  -H "Content-Type: application/json" \
  -H "X-User-Role: ADMIN" \
  -d '{
    "key": "booking.cutoff.minutes",
    "dataType": "DURATION",
    "description": "Cutoff window prior to shift start",
    "globalDefault": 60,
    "minValue": 5.0,
    "maxValue": 720.0
  }'
```

### 2. Commit Multi-Tenant Changeset
```bash
curl -X POST http://localhost:8080/api/v1/configs/changeset \
  -H "Content-Type: application/json" \
  -H "X-User-Role: OPERATOR" \
  -H "X-Tenant-Id: Google" \
  -d '{
    "author": "ops-manager",
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

### 3. Query Explain Mode (Audit Resolution Trail)
```bash
curl -X GET "http://localhost:8080/api/v1/configs/explain?key=booking.cutoff.minutes&clientId=Google&officeId=Bengaluru" \
  -H "X-Tenant-Id: Google" \
  -H "X-User-Role: EMPLOYEE"
```

### 4. Test Pre-Commit Validation (Bad Value Rejection)
```bash
curl -X POST http://localhost:8080/api/v1/configs/changeset \
  -H "Content-Type: application/json" \
  -H "X-User-Role: OPERATOR" \
  -H "X-Tenant-Id: Google" \
  -d '{
    "author": "test",
    "reason": "invalid value",
    "updates": [
      {
        "scopeKey": { "level": "CLIENT", "clientId": "Google" },
        "key": "booking.cutoff.minutes",
        "value": -30
      }
    ]
  }'
```
*Expected output: HTTP 400 Bad Request with clear validation message.*

### 5. 1-Click Rollback with Symmetric Diff
```bash
curl -X POST "http://localhost:8080/api/v1/configs/rollback?targetVersion=1&author=admin" \
  -H "X-User-Role: ADMIN"
```

---

## Architectural Trade-Offs

1. **Bounded Staleness vs Immediate Strong Consistency**:
   - We chose bounded staleness ($\le 5\text{s}$) over synchronous 2-phase commit (2PC). Synchronous network handshakes on every read or write would stall microservices and destroy availability. Bounded staleness delivers microsecond reads (0.10 µs) while guaranteeing updates propagate across the fleet within seconds.
2. **In-Memory Volatile Reference Swap vs Distributed Locks**:
   - Using `AtomicReference<ConfigSnapshot>` enables lock-free read operations without read-write lock overhead. Writers construct a new snapshot and swap the pointer atomically.
3. **Crash Consistency via OS Atomic Moves**:
   - Local disk persistence writes to a temporary file (`.tmp`) and executes `Files.move(..., ATOMIC_MOVE)`. This ensures that even during unexpected power loss, cached snapshot files are never corrupted.