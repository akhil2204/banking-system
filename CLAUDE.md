# Banking System — Claude Context

## Project structure
Multi-module Maven project. Each service is a separate Spring Boot app.
Always work one service at a time. Never modify multiple services in one session.

---

## Session scope rules — prevent token bloat

These rules exist because large sessions exhaust context, produce errors, and slow everything down.

### Hard limits per session
- **One service per session.** docker-compose.yml and CLAUDE.md/memory are the only allowed side-effects.
- **One phase step per session.** Never combine "add Kafka to transaction-service" and "add Kafka to account-service" in one prompt.
- **Documentation is a separate session.** Never write or rewrite SYSTEM_DOCUMENTATION.md at the end of an implementation session.
- **No back-to-back phases without a new session.** Phase 5a and Phase 5b must be separate sessions.

### Editing rules (prevent edit-call explosion)
- If a file needs more than 2 edits in a session → Read it once, then Write the full replacement. Never chain 5+ Edit calls on the same file.
- Read only files that are directly needed. Do not read the whole service upfront — read one file, decide if more are needed.

### How to prompt for low token usage
Instead of:                          → Say instead:
"implement phase 5b"                 → "add Kafka producer to transaction-service only"
"go with 5a then 5b"                 → do 5a, start a new session, then do 5b
"create a detailed document"         → start a fresh session: "write SYSTEM_DOCUMENTATION.md"
"update all services with X"         → "update account-service with X" (one service per session)

### Phase decomposition (how to break up future phases)
Each line below = one session:
- Phase 6a: add Zipkin to one service, validate it works, then repeat per service
- Phase 6b: add Prometheus to one service at a time
- Phase 7: write integration tests for one service at a time
- Documentation rewrites: always a standalone session with no implementation

---

## Tech stack
- Java 21, Spring Boot 3.4.3, Spring Cloud 2024.0.1, Maven multi-module
- PostgreSQL (user-service, account-service, transaction-service, notification-service, auth-service, audit-service)
- Docker Compose for all local infrastructure
- RabbitMQ 3.x — async notification delivery (transaction-service → notification-service)
- Kafka 3.7 KRaft — audit event streaming (transaction-service + account-service → audit-service)

## Architecture rules — never break these
- Each service owns its own database schema. Never cross-query between service DBs.
- transaction-service never writes balances directly — always calls account-service
- All inter-service calls use Feign clients with Eureka name resolution
- No hardcoded IPs or ports in any service code
- Every API returns standard error envelope: { timestamp, status, error, message, path }
- Never hard delete — always soft delete via status field
- All external paths go through api-gateway with /v1/ prefix

## Naming conventions
- Entities: singular noun (User, Account, Transaction)
- DTOs: suffix Request/Response (CreateUserRequest, UserResponse)
- Services: interface + Impl (UserService + UserServiceImpl)
- Repositories: suffix Repository (UserRepository)
- Controllers: suffix Controller (UserController)
- Feign clients: suffix Client (AccountClient, UserClient)

## Port assignments
| Service | Port |
|---|---|
| api-gateway | 8080 |
| user-service | 8081 |
| account-service | 8082 |
| transaction-service | 8083 |
| notification-service | 8084 |
| auth-service | 8085 |
| audit-service | 8086 |
| service-discovery | 8761 |
| postgres-users | 5433 |
| postgres-accounts | 5434 |
| postgres-transactions | 5435 |
| postgres-notifications | 5436 |
| postgres-auth | 5437 |
| postgres-audit | 5438 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ Management UI | 15672 |
| Kafka | 9092 |

---

## Build phases

### Phase 1 — Foundation ✓
- Parent POM (multi-module, Spring Boot 3.4.3, Spring Cloud 2024.0.1, Java 21)
- service-discovery (Eureka server)
- api-gateway (WebFlux, load-balanced routes, graceful shutdown)
- user-service (first CRUD service, establishes all base patterns)

### Phase 2 — Core banking ✓
- account-service (Feign → user-service, BigDecimal balance, optimistic locking)
- transaction-service (Feign → account-service, PENDING→COMPLETED/FAILED state machine, Saga compensation)

### Phase 3 — Reliability ✓
- notification-service (originally called synchronously by transaction-service; replaced by RabbitMQ in Phase 5a)
- Flyway migrations — V1__init.sql in all 5 DB-backed services; ddl-auto: validate
- Circuit breaker (Resilience4j) — account-service (UserClient), transaction-service (AccountClient)

### Phase 4 — Security ✓
- auth-service (BCrypt, access token + refresh token, HMAC-SHA256 JWT)
- api-gateway JWT GlobalFilter (validates Bearer token; whitelists /v1/auth/**)

### Phase 5a — Async Notifications via RabbitMQ ✓
- RabbitMQ container added to Docker Compose (port 5672 AMQP, 15672 Management UI)
- transaction-service: publishes `TransactionEvent` to topic exchange `banking.transactions`
  - routing key `transaction.completed` / `transaction.failed`
  - Removed `NotificationClient` Feign call + circuit breaker for notification-service
- notification-service: `@RabbitListener` consumes queue `notification.transaction.queue`
  - Dead letter queue `notification.transaction.dlq` for failed/bad messages
  - `defaultRequeueRejected=false` — bad messages go to DLQ, not infinite retry

Key patterns introduced:
- Topic exchange with wildcard binding (`transaction.#`) — new event types need zero config change
- DLQ pattern — failed messages held for inspection instead of silently dropped
- Each service owns its own `TransactionEvent` record copy — no shared library, no enum coupling
- Producer catches publish failures silently; consumer rethrows so broker handles retry/DLQ

### Phase 5b — Audit Event Streaming via Kafka ✓
- Kafka (KRaft, bitnami/kafka:3.7) added to Docker Compose — no Zookeeper needed
- transaction-service: publishes `TransactionAuditEvent` to topic `banking.audit.transactions`
- account-service: publishes `AccountAuditEvent` to topic `banking.audit.accounts`
- New **audit-service** (port 8086, DB: banking_audit, postgres-audit:5438)
  - `@KafkaListener` consumers on both topics, consumer group `audit-service`
  - Receives raw JSON strings, parses with ObjectMapper — no class coupling to producers
  - Read-only REST API: GET /audit, GET /audit/{id}, GET /audit/entity/{type}/{id}, GET /audit/user/{userId}
  - No write REST endpoint — all records created exclusively via Kafka

Key patterns introduced:
- Partition key = entityId → per-entity ordering guaranteed
- `ADD_TYPE_INFO_HEADERS=false` on producer → clean JSON payload, no class name embedded
- String deserialization + ObjectMapper in consumer → explicit, type-safe, no magic headers
- `AckMode.RECORD` → offset committed per message; restart replays unprocessed messages
- `auto-offset-reset=earliest` → audit-service can rebuild from zero by resetting offset
- Payload column stores full raw JSON → complete, immutable audit snapshot per event
- `occurredAt` (business time) vs `receivedAt` (processing time) — gap = end-to-end latency

### Current build status
All 5 phases (5a + 5b) complete. 8 services running. Full system documentation at SYSTEM_DOCUMENTATION.md.

### TODO
- (all core services built — see next steps below)

---

## Patterns in use — apply to every service without exception

### Code patterns
- Records as DTOs (Java 21) — all Request/Response types are records
- Lombok (@Builder, @Getter, @Setter, @RequiredArgsConstructor) on JPA entities only
- Constructor injection via @RequiredArgsConstructor — never @Autowired on fields
- Interface + Impl for every service class
- @Transactional(readOnly=true) on all reads; open-in-view=false in application.yml
- Typed unchecked exceptions thrown in service, caught once in GlobalExceptionHandler
- Standard error envelope: { timestamp, status, error, message, path }
- Soft delete via status field — never hard delete
- EnumType.STRING — never ORDINAL
- ddl-auto: validate — Flyway owns schema creation; Hibernate only validates
- Flyway migration files at src/main/resources/db/migration/V{n}__{desc}.sql
- Env var substitution in YAML: ${VAR:default}
- springdoc-openapi-starter-webmvc-ui 2.8.6 for Swagger UI on each service

### Standard application.yml blocks (copy into every new service)

**Eureka client:**
```yaml
eureka:
  client:
    service-url:
      defaultZone: http://${EUREKA_HOST:localhost}:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
```

**Actuator (minimum):**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always
```

**Graceful shutdown:**
```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s
server:
  shutdown: graceful
```

### Standard POM dependencies for a DB-backed service
```xml
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-validation
spring-boot-starter-actuator
spring-cloud-starter-netflix-eureka-client
postgresql (runtime)
lombok (optional)
springdoc-openapi-starter-webmvc-ui 2.8.6
spring-cloud-starter-openfeign       ← for services that call other services
spring-boot-starter-test (test)
```

### Feign client pattern (account-service needs this to call user-service)
1. Add `spring-cloud-starter-openfeign` to pom.xml
2. Add `@EnableFeignClients` to the main application class
3. Create interface in `client/` package:
```java
@FeignClient(name = "user-service")   // "user-service" must match spring.application.name
public interface UserClient {
    @GetMapping("/users/{id}")
    UserResponse getUserById(@PathVariable Long id);
}
```
4. Feign resolves `user-service` via Eureka — no URL, no port, no hardcoding.
5. If the target service returns 404, Feign throws `FeignException.NotFound`.
   Catch it in the calling service's GlobalExceptionHandler or wrap it in a domain exception.

### Gateway route pattern (add to api-gateway application.yml for each new service)
```yaml
- id: account-service-route
  uri: lb://account-service
  predicates:
    - Path=/v1/accounts/**
  filters:
    - RewritePath=/v1/(?<remaining>.*), /${remaining}
```

---

## Service details

### user-service ✓
**Port:** 8081 | **DB:** banking_users (postgres-users:5433)

| Method | Path | Description |
|--------|------|-------------|
| POST | /users | Create user |
| GET | /users | List all (paginated, ?status= filter) |
| GET | /users/{id} | Get by ID |
| GET | /users/email/{email} | Get by email — used by other services |
| PUT | /users/{id} | Update profile |
| PUT | /users/{id}/status | Update status (ACTIVE/SUSPENDED/DELETED) |
| DELETE | /users/{id} | Soft delete → 204 |

External (via gateway): `/v1/users/**`
Swagger: http://localhost:8081/swagger-ui.html

### service-discovery ✓
**Port:** 8761 | No database
Dashboard: http://localhost:8761
`register-with-eureka: false`, `fetch-registry: false`, `enable-self-preservation: false`

### api-gateway ✓
**Port:** 8080 | No database | Stack: WebFlux — NEVER add spring-boot-starter-web here

- Routes: `/v1/users/**`, `/v1/accounts/**`, `/v1/transactions/**`, `/v1/notifications/**`, `/v1/auth/**`, `/v1/audit/**`
- All use `lb://` prefix — resolved via Eureka
- RewritePath strips `/v1` before forwarding
- Graceful shutdown (25s), response compression, liveness/readiness probes
- Live routes: http://localhost:8080/actuator/gateway/routes

**JWT GlobalFilter (Phase 4 — done):**
- Validates Bearer token on every incoming request before routing
- Public paths (`/v1/auth/**`) are whitelisted and bypass the filter
- Returns 401 if token is missing, expired, or invalid
- On success, forwards `X-User-Id`, `X-User-Email`, `X-User-Role` headers downstream

**Deferred (infrastructure not yet added):**
- Rate limiting → needs Redis
- Distributed tracing → needs Zipkin
- Prometheus metrics → needs micrometer-registry-prometheus + Prometheus container

### account-service ✓
**Port:** 8082 | **DB:** banking_accounts (postgres-accounts:5434)
Calls user-service via Feign: `@FeignClient(name = "user-service")`
Publishes `AccountAuditEvent` to Kafka topic `banking.audit.accounts`

| Method | Path | Description |
|--------|------|-------------|
| POST | /accounts | Open account (validates user is ACTIVE via Feign) |
| GET | /accounts | List all (paginated, ?status= filter) |
| GET | /accounts/{id} | Get by ID |
| GET | /accounts/number/{accountNumber} | Get by account number |
| GET | /accounts/user/{userId} | All accounts for a user |
| GET | /accounts/{id}/balance | Current balance only |
| PUT | /accounts/{id}/deposit | Deposit funds |
| PUT | /accounts/{id}/withdraw | Withdraw (422 if insufficient funds) |
| PUT | /accounts/{id}/status | Update status |
| DELETE | /accounts/{id} | Soft close → CLOSED, 204 |

External (via gateway): `/v1/accounts/**`
Swagger: http://localhost:8082/swagger-ui.html

Key patterns:
- `BigDecimal(precision=19, scale=4)` for balance — never double/float
- `@Version` on Account entity — optimistic locking, 409 on concurrent update
- `FeignException` handling — 404 from upstream → 404, other → 503
- `UserResponse` as local mirror DTO with `String status` (no enum coupling)
- `InsufficientFundsException` → 422 Unprocessable Entity
- `AccountNotActiveException` → 422 Unprocessable Entity
- `BigDecimal.compareTo()` for value comparison (not equals — scale-aware)
- Kafka publish after every state change (CREATED, DEPOSITED, WITHDRAWN, STATUS_CHANGED, CLOSED)

### transaction-service ✓
**Port:** 8083 | **DB:** banking_transactions (postgres-transactions:5435)
Calls account-service via Feign: `@FeignClient(name = "account-service")`
Publishes `TransactionEvent` to RabbitMQ exchange `banking.transactions`
Publishes `TransactionAuditEvent` to Kafka topic `banking.audit.transactions`

| Method | Path | Description |
|--------|------|-------------|
| POST | /transactions/debit | Debit an account (money out) |
| POST | /transactions/credit | Credit an account (money in) |
| POST | /transactions/transfer | Transfer between two accounts (with compensation) |
| GET | /transactions | List all (?status=, ?type= filters, paginated) |
| GET | /transactions/{id} | Get by ID |
| GET | /transactions/account/{accountId} | All transactions for an account |

External (via gateway): `/v1/transactions/**`
Swagger: http://localhost:8083/swagger-ui.html

Key patterns:
- PENDING→COMPLETED/FAILED state machine — record persisted before any Feign call
- Manual Saga compensating transaction on transfer deposit failure
- `@Slf4j` structured logging of all transaction outcomes
- `resolveAccount()` helper — consolidates Feign call + active-status check
- Transfer always returns 201; caller must check `status` field in response
- No Feign call to notification-service — notification delivery is async via RabbitMQ only

### notification-service ✓
**Port:** 8084 | **DB:** banking_notifications (postgres-notifications:5436)
Consumes from RabbitMQ queue `notification.transaction.queue` — **no inbound Feign calls**

| Method | Path | Description |
|--------|------|-------------|
| POST | /notifications | Create notification (internal — called by RabbitMQ consumer) |
| GET | /notifications/{id} | Get by ID |
| GET | /notifications/user/{userId} | All for user (?unreadOnly=true) |
| GET | /notifications/user/{userId}/unread-count | Count of unread notifications |
| PUT | /notifications/{id}/read | Mark single as read |
| PUT | /notifications/user/{userId}/read-all | Mark all as read |
| DELETE | /notifications/{id} | Soft delete |

External (via gateway): `/v1/notifications/**`
Swagger: http://localhost:8084/swagger-ui.html

Key patterns:
- Notification types: TRANSACTION_DEBIT, TRANSACTION_CREDIT, TRANSACTION_TRANSFER
- Notification status: UNREAD → READ (soft delete → DELETED)
- Notification channel: IN_APP (EMAIL/SMS fields exist for future use)
- Created exclusively by RabbitMQ consumer — REST POST is internal
- DLQ: `notification.transaction.dlq` — bad messages land here, not requeued

### auth-service ✓
**Port:** 8085 | **DB:** banking_auth (postgres-auth:5437)
No Feign calls outbound — standalone token issuer

| Method | Path | Description |
|--------|------|-------------|
| POST | /auth/register | Register credential (BCrypt hash stored) + creates user via Feign |
| POST | /auth/login | Validate credentials → return signed JWT |
| POST | /auth/refresh | Exchange refresh token for new access token |
| POST | /auth/logout | Soft-invalidate refresh token |

External (via gateway): `/v1/auth/**` — whitelisted (no JWT check on these paths)
Swagger: http://localhost:8085/swagger-ui.html

Key patterns:
- BCrypt password hashing — never store plaintext
- Access token (short-lived, 15 min) + refresh token (long-lived 7 days, stored in DB)
- JWT signed with HMAC-SHA256; secret injected via env var (`JWT_SECRET`)
- api-gateway GlobalFilter reads and validates the same secret to verify tokens
- Refresh tokens soft-invalidated on logout (status field)
- `/auth/register` calls user-service via Feign to create the user profile first

### audit-service ✓
**Port:** 8086 | **DB:** banking_audit (postgres-audit:5438)
Consumes from Kafka topics `banking.audit.transactions` and `banking.audit.accounts`
**No write REST endpoint** — all records created exclusively via Kafka consumers

| Method | Path | Description |
|--------|------|-------------|
| GET | /audit | List all audit events (paginated, ?eventType=TRANSACTION\|ACCOUNT) |
| GET | /audit/{id} | Get single event |
| GET | /audit/entity/{eventType}/{entityId} | All events for one entity |
| GET | /audit/user/{userId} | All events linked to a user |

External (via gateway): `/v1/audit/**`
Swagger: http://localhost:8086/swagger-ui.html

Key patterns:
- `occurredAt` (business time from source) vs `receivedAt` (Kafka processing time)
- `payload` column = full raw JSON snapshot — immutable, complete point-in-time record
- `auto-offset-reset=earliest` + `AckMode.RECORD` — restart replays from last committed offset
- Consumer group `audit-service` — reset offset to 0 to rebuild entire audit table from Kafka log
- Indexes on event_type, entity_id, user_id, occurred_at for efficient filtering

---

## Docker Compose — current services
| Container | Image/Build | Port |
|---|---|---|
| service-discovery | ./service-discovery | 8761 |
| api-gateway | ./api-gateway | 8080 |
| postgres-users | postgres:16-alpine | 5433→5432 |
| user-service | ./user-service | 8081 |
| postgres-accounts | postgres:16-alpine | 5434→5432 |
| account-service | ./account-service | 8082 |
| postgres-transactions | postgres:16-alpine | 5435→5432 |
| transaction-service | ./transaction-service | 8083 |
| postgres-notifications | postgres:16-alpine | 5436→5432 |
| notification-service | ./notification-service | 8084 |
| postgres-auth | postgres:16-alpine | 5437→5432 |
| auth-service | ./auth-service | 8085 |
| postgres-audit | postgres:16-alpine | 5438→5432 |
| audit-service | ./audit-service | 8086 |
| rabbitmq | rabbitmq:3-management | 5672, 15672 |
| kafka | bitnami/kafka:3.7 | 9092 |

---

## Suggested next steps (choose based on goals)

### Production hardening
- **Flyway migrations** ✓ — all 6 DB services have V1__init.sql; ddl-auto: validate
- **Circuit breaker (Resilience4j)** ✓ — account-service (UserClient), transaction-service (AccountClient)
- **JWT auth** ✓ — auth-service issues tokens; api-gateway GlobalFilter validates every request
- **Integration tests** — `@SpringBootTest` + Testcontainers (spins up real PostgreSQL in Docker for tests)

### Observability
- **Distributed tracing** — add Zipkin container + `micrometer-tracing-bridge-brave` to each service
- **Prometheus + Grafana** — add `micrometer-registry-prometheus`; dashboards for request rates, error rates, latency

### Reliability
- **Async notifications via RabbitMQ** ✓ — transaction-service publishes events; notification-service consumes; fully decoupled
- **Async audit via Kafka** ✓ — audit-service (port 8086) consumes Kafka topics from transaction-service and account-service; immutable audit log with replay capability
