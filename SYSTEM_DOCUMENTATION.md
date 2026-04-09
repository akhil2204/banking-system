# Banking System — Complete Documentation

## Table of Contents
1. [System Overview](#1-system-overview)
2. [Architecture Flowchart](#2-architecture-flowchart)
3. [Services Built](#3-services-built)
4. [Database Schema](#4-database-schema)
5. [API Reference](#5-api-reference)
6. [Inter-Service Communication](#6-inter-service-communication)
7. [Key Design Patterns](#7-key-design-patterns)
8. [Transaction Flow Diagrams](#8-transaction-flow-diagrams)
9. [Docker Infrastructure](#9-docker-infrastructure)
10. [How to Run](#10-how-to-run)

---

## 1. System Overview

A production-grade microservices banking system built with Java 21 + Spring Boot 3.4.3 + Spring Cloud 2024.0.1.

```
Tech Stack
──────────
Language      : Java 21 (records, sealed types, pattern matching)
Framework     : Spring Boot 3.4.3
Cloud         : Spring Cloud 2024.0.1 (Eureka, Gateway, OpenFeign)
Database      : PostgreSQL 16 (one DB per service)
Migrations    : Flyway (ddl-auto: validate — Hibernate never creates schema)
Money math    : BigDecimal(precision=19, scale=4) — never float/double
Documentation : springdoc-openapi 2.8.6 (Swagger UI per service)
Containers    : Docker Compose
Resilience    : Resilience4j circuit breakers on all Feign clients
```

---

## 2. Architecture Flowchart

```
                           ┌─────────────────────────────────────────────┐
                           │              CLIENT / BROWSER                │
                           └───────────────────┬─────────────────────────┘
                                               │ HTTP
                                               ▼
                           ┌─────────────────────────────────────────────┐
                           │              API GATEWAY  :8080              │
                           │                                              │
                           │  /v1/users/**        → user-service         │
                           │  /v1/accounts/**     → account-service      │
                           │  /v1/transactions/** → transaction-service  │
                           │  /v1/notifications/** → notification-service│
                           │                                              │
                           │  • Load balancing (lb://)                   │
                           │  • Path rewriting (strips /v1)              │
                           │  • Response compression                     │
                           │  • Graceful shutdown (25s)                  │
                           │  • WebFlux (reactive, non-blocking)         │
                           └────────────┬────────────────────────────────┘
                                        │ Eureka service discovery
                                        │
              ┌─────────────────────────┼──────────────────────────────────┐
              │                         │                                   │
              ▼                         ▼                                   ▼
┌─────────────────────┐   ┌─────────────────────────┐   ┌──────────────────────────┐
│   USER-SERVICE      │   │   ACCOUNT-SERVICE       │   │  TRANSACTION-SERVICE     │
│   :8081             │◄──│   :8082                 │◄──│  :8083                   │
│                     │   │                         │   │                          │
│  • Create user      │   │  • Open account         │   │  • DEBIT (money out)     │
│  • List / get user  │   │  • Deposit / Withdraw   │   │  • CREDIT (money in)     │
│  • Update profile   │   │  • Balance query        │   │  • TRANSFER (two-leg)    │
│  • Soft delete      │   │  • Status management    │   │  • Saga compensation     │
│  • Status mgmt      │   │  • Soft close           │   │  • Notification dispatch │
│                     │   │                         │   │                          │
│  DB: banking_users  │   │  DB: banking_accounts   │   │  DB: banking_transactions│
│  :5433              │   │  :5434                  │   │  :5435                   │
└─────────────────────┘   └─────────────────────────┘   └──────────────────────────┘
                                                                    │
                                                                    │ Feign (fire-and-forget)
                                                                    ▼
                                                   ┌──────────────────────────────┐
                                                   │   NOTIFICATION-SERVICE       │
                                                   │   :8084                      │
                                                   │                              │
                                                   │  • Send notification         │
                                                   │  • Get by user               │
                                                   │  • Mark read / read-all      │
                                                   │  • Unread count              │
                                                   │  • Soft delete               │
                                                   │                              │
                                                   │  DB: banking_notifications   │
                                                   │  :5436                       │
                                                   └──────────────────────────────┘

                    ┌────────────────────────────────────────────┐
                    │          SERVICE-DISCOVERY (Eureka)        │
                    │          :8761                             │
                    │                                            │
                    │  All services register here on startup.   │
                    │  Gateway resolves lb://service-name        │
                    │  via Eureka — no hardcoded IPs.           │
                    └────────────────────────────────────────────┘
```

### Service Dependency Order (startup sequence)
```
service-discovery → api-gateway
                 → postgres-users → user-service
                 → postgres-accounts → account-service (needs user-service healthy)
                 → postgres-transactions → transaction-service (needs account-service healthy)
                 → postgres-notifications → notification-service
```

---

## 3. Services Built

| Service | Port | Database | Status |
|---|---|---|---|
| service-discovery | 8761 | None | ✓ Done |
| api-gateway | 8080 | None | ✓ Done |
| user-service | 8081 | banking_users (postgres:5433) | ✓ Done |
| account-service | 8082 | banking_accounts (postgres:5434) | ✓ Done |
| transaction-service | 8083 | banking_transactions (postgres:5435) | ✓ Done |
| notification-service | 8084 | banking_notifications (postgres:5436) | ✓ Done |

---

## 4. Database Schema

### user-service — `banking_users`

```sql
CREATE TABLE users (
    id         BIGSERIAL    PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name  VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    status     VARCHAR(255) NOT NULL,   -- ACTIVE | SUSPENDED | DELETED
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);
```

**Why BIGSERIAL?** PostgreSQL idiomatic auto-increment. `@GeneratedValue(strategy=IDENTITY)` on the entity maps to this.

**Why VARCHAR(255) for status?** `@Enumerated(EnumType.STRING)` stores the enum name as text. Never ORDINAL — ORDINAL breaks if enum order changes.

---

### account-service — `banking_accounts`

```sql
CREATE TABLE accounts (
    id             BIGSERIAL     PRIMARY KEY,
    user_id        BIGINT        NOT NULL,          -- logical FK, no DB constraint
    account_number VARCHAR(255)  NOT NULL,
    type           VARCHAR(255)  NOT NULL,          -- SAVINGS | CURRENT
    balance        NUMERIC(19,4) NOT NULL,          -- exact decimal, never FLOAT
    status         VARCHAR(255)  NOT NULL,          -- ACTIVE | SUSPENDED | CLOSED
    created_at     TIMESTAMP     NOT NULL,
    version        BIGINT,                          -- optimistic locking (@Version)
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number)
);
```

**Why no DB-level FK on user_id?** Each service owns its own database. Cross-DB foreign keys would couple two independent schemas. Referential integrity is enforced at the application layer via a Feign call to user-service on account creation.

**Why NUMERIC(19,4)?** Exact decimal arithmetic. `FLOAT`/`DOUBLE` cannot represent most decimal fractions exactly (IEEE 754 issue). `precision=19` supports values up to 999 trillion; `scale=4` gives four decimal places.

**Why `version BIGINT`?** Hibernate's `@Version` field. On every UPDATE, Hibernate appends `WHERE version = ?`. If two requests both read version=3, the first UPDATE succeeds (version→4) and the second finds no row → `ObjectOptimisticLockingFailureException` → 409 Conflict response.

---

### transaction-service — `banking_transactions`

```sql
CREATE TABLE transactions (
    id                BIGSERIAL     PRIMARY KEY,
    type              VARCHAR(255)  NOT NULL,   -- DEBIT | CREDIT | TRANSFER
    status            VARCHAR(255)  NOT NULL,   -- PENDING | COMPLETED | FAILED
    source_account_id BIGINT,                   -- NULL for CREDIT
    target_account_id BIGINT,                   -- NULL for DEBIT
    amount            NUMERIC(19,4) NOT NULL,
    description       VARCHAR(255),
    failure_reason    VARCHAR(512),             -- populated on FAILED status
    created_at        TIMESTAMP     NOT NULL,
    completed_at      TIMESTAMP                 -- NULL until COMPLETED
);
```

**Column nullability design:**
- `DEBIT`: `source_account_id` = debited account, `target_account_id` = NULL
- `CREDIT`: `source_account_id` = NULL, `target_account_id` = credited account
- `TRANSFER`: both columns populated

---

### notification-service — `banking_notifications`

```sql
CREATE TABLE notifications (
    id         BIGSERIAL     PRIMARY KEY,
    user_id    BIGINT        NOT NULL,          -- logical FK to user-service
    type       VARCHAR(255)  NOT NULL,          -- TRANSACTION_COMPLETED | TRANSACTION_FAILED | etc.
    channel    VARCHAR(255)  NOT NULL,          -- IN_APP | EMAIL | SMS
    title      VARCHAR(255)  NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    status     VARCHAR(255)  NOT NULL,          -- UNREAD | READ | DELETED
    created_at TIMESTAMP     NOT NULL,
    read_at    TIMESTAMP                        -- populated when status → READ
);

CREATE INDEX idx_notifications_user_id     ON notifications (user_id);
CREATE INDEX idx_notifications_user_status ON notifications (user_id, status);
```

**Why composite index on (user_id, status)?** Covers the most common query: "fetch only UNREAD notifications for user X". A single index scan satisfies both filter conditions.

---

## 5. API Reference

All external calls go through the API Gateway on port 8080 with the `/v1/` prefix.

---

### USER-SERVICE — `/v1/users`

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| POST | `/v1/users` | `CreateUserRequest` | `201 UserResponse` | Create a new user |
| GET | `/v1/users` | — | `200 Page<UserResponse>` | List all (paginated; `?status=ACTIVE`) |
| GET | `/v1/users/{id}` | — | `200 UserResponse` | Get user by ID |
| GET | `/v1/users/email/{email}` | — | `200 UserResponse` | Get user by email |
| PUT | `/v1/users/{id}` | `UpdateProfileRequest` | `200 UserResponse` | Update name/email |
| PUT | `/v1/users/{id}/status` | `UpdateStatusRequest` | `200 UserResponse` | Change status |
| DELETE | `/v1/users/{id}` | — | `204` | Soft delete (status→DELETED) |

**Request / Response Shapes:**

```json
// POST /v1/users — CreateUserRequest
{
  "firstName": "John",
  "lastName":  "Doe",
  "email":     "john.doe@example.com"
}

// UserResponse (returned by all user endpoints)
{
  "id":        1,
  "firstName": "John",
  "lastName":  "Doe",
  "email":     "john.doe@example.com",
  "status":    "ACTIVE",
  "createdAt": "2026-04-09T10:00:00"
}

// PUT /v1/users/{id} — UpdateProfileRequest
{
  "firstName": "Jane",
  "lastName":  "Smith",
  "email":     "jane.smith@example.com"
}

// PUT /v1/users/{id}/status — UpdateStatusRequest
{
  "status": "SUSPENDED"   // ACTIVE | SUSPENDED | DELETED
}
```

**Error responses:**
- `400` — Validation failed (blank name, invalid email format)
- `404` — User not found
- `409` — Email already in use

---

### ACCOUNT-SERVICE — `/v1/accounts`

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| POST | `/v1/accounts` | `CreateAccountRequest` | `201 AccountResponse` | Open new account |
| GET | `/v1/accounts` | — | `200 Page<AccountResponse>` | List all (paginated; `?status=`) |
| GET | `/v1/accounts/{id}` | — | `200 AccountResponse` | Get by ID |
| GET | `/v1/accounts/number/{accountNumber}` | — | `200 AccountResponse` | Get by account number |
| GET | `/v1/accounts/user/{userId}` | — | `200 Page<AccountResponse>` | All accounts for a user |
| GET | `/v1/accounts/{id}/balance` | — | `200 BalanceResponse` | Current balance only |
| PUT | `/v1/accounts/{id}/deposit` | `DepositRequest` | `200 AccountResponse` | Deposit funds |
| PUT | `/v1/accounts/{id}/withdraw` | `WithdrawRequest` | `200 AccountResponse` | Withdraw funds |
| PUT | `/v1/accounts/{id}/status` | `UpdateAccountStatusRequest` | `200 AccountResponse` | Change status |
| DELETE | `/v1/accounts/{id}` | — | `204` | Soft close (status→CLOSED) |

**Request / Response Shapes:**

```json
// POST /v1/accounts — CreateAccountRequest
{
  "userId": 1,
  "type":   "SAVINGS"   // SAVINGS | CURRENT
}

// AccountResponse
{
  "id":            1,
  "userId":        1,
  "accountNumber": "ACC-20260409-001",
  "type":          "SAVINGS",
  "balance":       "0.0000",
  "status":        "ACTIVE",
  "createdAt":     "2026-04-09T10:05:00"
}

// PUT /v1/accounts/{id}/deposit
{ "amount": 1000.00 }

// PUT /v1/accounts/{id}/withdraw
{ "amount": 250.00 }

// PUT /v1/accounts/{id}/status
{ "status": "SUSPENDED" }   // ACTIVE | SUSPENDED | CLOSED

// GET /v1/accounts/{id}/balance — BalanceResponse
{
  "accountId":     1,
  "accountNumber": "ACC-20260409-001",
  "balance":       "750.0000"
}
```

**Error responses:**
- `404` — Account not found / User not found (via Feign)
- `409` — Concurrent modification (optimistic lock) — client should retry
- `422` — User not active (on create) / Insufficient funds (on withdraw) / Account not active

---

### TRANSACTION-SERVICE — `/v1/transactions`

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| POST | `/v1/transactions/debit` | `DebitCreditRequest` | `201 TransactionResponse` | Debit (money out) |
| POST | `/v1/transactions/credit` | `DebitCreditRequest` | `201 TransactionResponse` | Credit (money in) |
| POST | `/v1/transactions/transfer` | `TransferRequest` | `201 TransactionResponse` | Transfer between accounts |
| GET | `/v1/transactions` | — | `200 Page<TransactionResponse>` | List all (`?status=&type=`) |
| GET | `/v1/transactions/{id}` | — | `200 TransactionResponse` | Get by ID |
| GET | `/v1/transactions/account/{accountId}` | — | `200 Page<TransactionResponse>` | All txns for an account |

**Request / Response Shapes:**

```json
// POST /v1/transactions/debit or /credit — DebitCreditRequest
{
  "accountId":   1,
  "amount":      250.00,
  "description": "ATM withdrawal"   // optional
}

// POST /v1/transactions/transfer — TransferRequest
{
  "sourceAccountId": 1,
  "targetAccountId": 2,
  "amount":           500.00,
  "description":      "Rent payment"  // optional
}

// TransactionResponse
{
  "id":              1,
  "type":            "DEBIT",        // DEBIT | CREDIT | TRANSFER
  "status":          "COMPLETED",   // PENDING | COMPLETED | FAILED
  "sourceAccountId": 1,
  "targetAccountId": null,
  "amount":          250.00,
  "description":     "ATM withdrawal",
  "failureReason":   null,
  "createdAt":       "2026-04-09T10:10:00",
  "completedAt":     "2026-04-09T10:10:00"
}
```

> **IMPORTANT:** Transfer always returns `201`. Check the `status` field — it may be `FAILED`. If failed, check `failureReason` for details.

**Error responses:**
- `400` — Same source and target account (self-transfer) / Validation failed
- `422` — Account not active or not found

---

### NOTIFICATION-SERVICE — `/v1/notifications`

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| POST | `/v1/notifications` | `SendNotificationRequest` | `201 NotificationResponse` | Send notification |
| GET | `/v1/notifications/user/{userId}` | — | `200 Page<NotificationResponse>` | Get for user (`?unreadOnly=true`) |
| GET | `/v1/notifications/user/{userId}/unread-count` | — | `200 UnreadCountResponse` | Unread count |
| GET | `/v1/notifications/{id}` | — | `200 NotificationResponse` | Get by ID |
| PUT | `/v1/notifications/{id}/read` | — | `200 NotificationResponse` | Mark as read |
| PUT | `/v1/notifications/user/{userId}/read-all` | — | `204` | Mark all as read |
| DELETE | `/v1/notifications/{id}` | — | `204` | Soft delete |

**Request / Response Shapes:**

```json
// POST /v1/notifications — SendNotificationRequest
{
  "userId":  1,
  "type":    "TRANSACTION_COMPLETED",
  "channel": "IN_APP",        // IN_APP | EMAIL | SMS
  "title":   "Debit Successful",
  "message": "250.00 debited from account ACC-20260409-001"
}

// NotificationResponse
{
  "id":        1,
  "userId":    1,
  "type":      "TRANSACTION_COMPLETED",
  "channel":   "IN_APP",
  "title":     "Debit Successful",
  "message":   "250.00 debited from account ACC-20260409-001",
  "status":    "UNREAD",       // UNREAD | READ | DELETED
  "createdAt": "2026-04-09T10:10:01",
  "readAt":    null
}

// GET /v1/notifications/user/{userId}/unread-count
{
  "userId":      1,
  "unreadCount": 3
}
```

---

## 6. Inter-Service Communication

### Feign Clients

All inter-service HTTP calls use **Spring Cloud OpenFeign** with **Eureka name resolution**. No hardcoded IPs or ports.

```
transaction-service ──Feign──► account-service  (withdraw, deposit, getAccountById)
account-service     ──Feign──► user-service      (getUserById to validate ACTIVE status)
transaction-service ──Feign──► notification-service (fire-and-forget after transactions)
```

### Circuit Breakers (Resilience4j)

Every Feign client has a **fallback factory** that activates when:
- The downstream service is down
- Requests are timing out
- Error rate exceeds threshold

| Feign Client | Fallback Behavior |
|---|---|
| `UserClient` (in account-service) | Throws `UserServiceUnavailableException` → 503 |
| `AccountClient` (in transaction-service) | Throws `AccountServiceUnavailableException` → circuit-aware fail |
| `NotificationClient` (in transaction-service) | Silently swallowed — notification failure NEVER fails a transaction |

---

## 7. Key Design Patterns

### Pattern 1 — PENDING→COMPLETED/FAILED State Machine
Every transaction is persisted as `PENDING` **before** any Feign call is made. This guarantees a record exists even if the service crashes mid-flight.

```
Transaction saved (PENDING)
    │
    ▼
Feign call to account-service
    ├── Success → update to COMPLETED + set completedAt
    └── Failure → update to FAILED + set failureReason
```

### Pattern 2 — Saga Compensation (Transfer)
Transfer is a two-leg operation with manual compensation:

```
Step 1: Withdraw from source
    ├── Fails → record FAILED, return (no compensation needed — money never left)
    └── Succeeds ↓

Step 2: Deposit to target
    ├── Fails → COMPENSATE: re-deposit to source
    │       ├── Compensation succeeds → record FAILED with "source refunded" note
    │       └── Compensation fails → record FAILED with "CRITICAL: MANUAL INTERVENTION REQUIRED"
    └── Succeeds → record COMPLETED, notify both parties
```

### Pattern 3 — Soft Delete
Nothing is hard-deleted. Status fields track lifecycle:
- User: `ACTIVE → SUSPENDED → DELETED`
- Account: `ACTIVE → SUSPENDED → CLOSED`
- Notification: `UNREAD → READ → DELETED`

### Pattern 4 — Optimistic Locking (account-service)
The `version` column on `accounts` prevents concurrent balance corruption. Two simultaneous deposits on the same account: the first wins, the second gets a `409 Conflict` and must retry.

### Pattern 5 — Standard Error Envelope
Every service returns the same error shape:
```json
{
  "timestamp": "2026-04-09T10:00:00",
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Insufficient funds in account ACC-20260409-001",
  "path":      "/accounts/1/withdraw"
}
```

### Pattern 6 — Interface + Impl
Every service class has an interface (`AccountService`) and implementation (`AccountServiceImpl`). This enforces the dependency inversion principle and makes future testing/mocking straightforward.

### Pattern 7 — Records as DTOs
All Request/Response types are Java 21 records (immutable, compact). Lombok is used only on JPA entities.

---

## 8. Transaction Flow Diagrams

### Debit Flow
```
Client ──POST /v1/transactions/debit──► API Gateway
                                              │
                                              ▼
                                    transaction-service
                                              │
                                    1. resolveAccount(accountId)
                                       ──GET /accounts/{id}──► account-service
                                       Check status == ACTIVE
                                              │
                                    2. Save Transaction(PENDING)
                                              │
                                    3. ──PUT /accounts/{id}/withdraw──► account-service
                                       ┌─ Success ──► update COMPLETED + notify user
                                       └─ Failure ──► update FAILED + notify user
                                              │
                                    4. Return TransactionResponse
```

### Transfer Flow
```
Client ──POST /v1/transactions/transfer──► API Gateway
                                                  │
                                                  ▼
                                        transaction-service
                                                  │
                             1. Validate source != target (else 400)
                                                  │
                             2. resolveAccount(sourceAccountId) → check ACTIVE
                             3. resolveAccount(targetAccountId) → check ACTIVE
                                                  │
                             4. Save Transaction(PENDING)
                                                  │
                             5. STEP 1: withdraw from source
                                ──PUT /accounts/{source}/withdraw──► account-service
                                ├── Fails → mark FAILED, return
                                └── Succeeds ↓
                                                  │
                             6. STEP 2: deposit to target
                                ──PUT /accounts/{target}/deposit──► account-service
                                ├── Succeeds → mark COMPLETED
                                │    Notify source user: "Transfer Sent"
                                │    Notify target user: "Transfer Received"
                                └── Fails → COMPENSATE
                                     ──PUT /accounts/{source}/deposit──► account-service
                                     ├── Compensation succeeds → FAILED (source refunded)
                                     └── Compensation fails  → FAILED (CRITICAL — manual intervention)
```

---

## 9. Docker Infrastructure

```
docker-compose.yml — All containers
─────────────────────────────────────────────────────────────────────

Container              Image / Build                  Port Mapping   Depends On
─────────────────      ─────────────────              ────────────   ───────────────────────
service-discovery      ./service-discovery            8761:8761      —
api-gateway            ./api-gateway                  8080:8080      service-discovery (healthy)
postgres-users         postgres:16-alpine             5433:5432      —
user-service           ./user-service                 8081:8081      postgres-users, service-discovery
postgres-accounts      postgres:16-alpine             5434:5432      —
account-service        ./account-service              8082:8082      postgres-accounts, service-discovery, user-service
postgres-transactions  postgres:16-alpine             5435:5432      —
transaction-service    ./transaction-service          8083:8083      postgres-transactions, service-discovery, account-service
postgres-notifications postgres:16-alpine             5436:5432      —
notification-service   ./notification-service         8084:8084      postgres-notifications, service-discovery

Named volumes (persist across restarts):
  postgres_users_data, postgres_accounts_data,
  postgres_transactions_data, postgres_notifications_data

Credentials (all DBs):
  POSTGRES_USER: banking_user
  POSTGRES_PASSWORD: banking_pass

Health checks on every container — downstream services wait for upstream to be healthy before starting.
```

---

## 10. How to Run

### Start everything
```bash
docker compose up --build
```

### Check health
```
http://localhost:8761           Eureka dashboard (all services should appear)
http://localhost:8080/actuator/health
http://localhost:8081/actuator/health
http://localhost:8082/actuator/health
http://localhost:8083/actuator/health
http://localhost:8084/actuator/health
```

### Swagger UIs (direct service access)
```
http://localhost:8081/swagger-ui.html   user-service
http://localhost:8082/swagger-ui.html   account-service
http://localhost:8083/swagger-ui.html   transaction-service
http://localhost:8084/swagger-ui.html   notification-service
```

### Typical test flow
```
1. POST /v1/users              → create user, get {id}
2. POST /v1/accounts           → open account (userId from step 1)
3. POST /v1/accounts           → open second account (same or different user)
4. PUT  /v1/accounts/{id}/deposit   → fund account 1
5. POST /v1/transactions/debit      → debit account 1
6. POST /v1/transactions/credit     → credit account 2
7. POST /v1/transactions/transfer   → transfer from account 1 to 2
8. GET  /v1/notifications/user/{id} → see auto-generated notifications
```

### Wipe data (delete volumes)
```bash
docker compose down -v
```

---

## Swagger URLs via Gateway

```
http://localhost:8080/user-service/swagger-ui.html
http://localhost:8080/account-service/swagger-ui.html
http://localhost:8080/transaction-service/swagger-ui.html
http://localhost:8080/notification-service/swagger-ui.html
```
