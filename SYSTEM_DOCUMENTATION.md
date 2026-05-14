# Banking System — Complete Technical Documentation

> **Stack:** Java 21 · Spring Boot 3.4.3 · Spring Cloud 2024.0.1 · Maven multi-module
> **Infrastructure:** PostgreSQL 16 · Docker Compose · RabbitMQ 3.13 · Kafka 3.7 (KRaft)
> **Last updated:** 2026-05-08 — Phase 5b complete (5 phases / 8 services done)

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Phase 1 — Foundation](#2-phase-1--foundation)
3. [Phase 2 — Core Banking](#3-phase-2--core-banking)
4. [Phase 3 — Reliability](#4-phase-3--reliability)
5. [Phase 4 — Security](#5-phase-4--security)
6. [Phase 5a — Async Notifications via RabbitMQ](#6-phase-5a--async-notifications-via-rabbitmq)
7. [Phase 5b — Audit Event Streaming via Kafka](#7-phase-5b--audit-event-streaming-via-kafka)
8. [Cross-Cutting Patterns](#8-cross-cutting-patterns)
9. [Infrastructure Reference](#9-infrastructure-reference)
10. [API Reference](#10-api-reference)
11. [Data Models](#11-data-models)
12. [Testing Guide](#12-testing-guide)
13. [Error Reference](#13-error-reference)
14. [What's Next](#14-whats-next)

---

## 1. Architecture Overview

```
                    +-------------------------------+
                    |        Client (HTTP)          |
                    +---------------+---------------+
                                    |
                    +---------------v---------------+
                    |       api-gateway :8080       |
                    |  JWT GlobalFilter             |
                    |  /v1/** -> lb://service-name  |
                    +--+--+--+--+--+--+--+----------+
                       |  |  |  |  |  |  |
       +---------------+  |  |  |  |  |  +------------------+
       v                  v  |  v  |  v                     v
 +----------+  +----------+  | +--------+  +----------+  +----------+
 |  user    |  | account  |  | transact|  |notificat |  |  auth    |
 | service  |  | service  |  | service |  |  service |  | service  |
 | :8081    |  | :8082    |  | :8083   |  | :8084    |  | :8085    |
 | pg:5433  |  | pg:5434  |  | pg:5435 |  | pg:5436  |  | pg:5437  |
 +----------+  +----------+  +--+--+---+  +----^-----+  +----------+
                                  |  |          |
                                  |  +--RabbitMQ-+ (async notifications)
                                  |
                                  +--Kafka---> audit-service :8086
                                  |                          pg:5438
                             account-service --Kafka---------^

   +------------------------------------------------+
   |    service-discovery (Eureka) :8761            |
   |  All services register; lb:// resolves here    |
   +------------------------------------------------+
```

### Architecture rules (never break these)
- Each service owns its own database — no cross-DB queries, no shared schemas
- All inter-service HTTP calls use Feign clients resolved via Eureka — no hardcoded URLs or ports
- All external traffic enters through api-gateway with `/v1/` prefix
- transaction-service never writes balances directly — always calls account-service
- Notifications are async via RabbitMQ — transaction response never waits for notification-service
- Audit logging is async via Kafka — producers never wait for audit-service
- Never hard delete — always soft delete via a status field

---

## 2. Phase 1 — Foundation

**Goal:** Multi-module Maven structure, service discovery, gateway routing, and the first CRUD service. Every pattern here is reused in every subsequent service without exception.

### Parent POM
- Inherits `spring-boot-starter-parent` 3.4.3 — provides managed versions for all Spring Boot dependencies, so child modules never specify versions
- Imports `spring-cloud-dependencies` 2024.0.1 BOM — locks Spring Cloud artifact versions without adding anything to the classpath
- `<packaging>pom</packaging>` — aggregator, not a deployable artifact
- Lombok excluded from fat JARs in `pluginManagement` — it is a compile-time annotation processor only

### service-discovery (Eureka Server)
Port 8761 · No database · Dashboard: http://localhost:8761

- `register-with-eureka: false` — the registry does not register itself with itself
- `fetch-registry: false` — no need to cache registrations locally
- `enable-self-preservation: false` — in dev, do not lock stale registrations when instances go down

**Why Eureka?** Replaces hardcoded `http://account-service:8082` URLs in every service. Services register by name; `lb://account-service` resolves to any healthy instance at call time. Adding a second instance of any service requires zero code changes — Eureka distributes the load automatically.

### api-gateway (Spring Cloud Gateway / WebFlux)
Port 8080 · No database · **WebFlux only — never add spring-boot-starter-web here**

- Explicit routes defined in `application.yml` — auto-discovery is disabled (would expose internal service names as public paths)
- `RewritePath` filter strips `/v1/` prefix before forwarding to downstream services
- Response compression enabled (min 1 KB threshold)
- Graceful shutdown: 25 s drain window for in-flight requests
- Liveness/readiness probes enabled for Kubernetes-readiness

**Route pattern:**
```yaml
- id: account-service-route
  uri: lb://account-service        # lb:// = load-balanced via Eureka
  predicates:
    - Path=/v1/accounts/**
  filters:
    - RewritePath=/v1/(?<remaining>.*), /${remaining}  # strip /v1 prefix
```

**Why RewritePath?** Downstream services never know they are behind a gateway. When we add v2, a new gateway route points to a v2 service — existing services are untouched.

### user-service
Port 8081 · DB: `banking_users` (postgres-users:5433)

Establishes every pattern used in all subsequent services. Soft delete: `DELETE /users/{id}` sets `status = DELETED` and returns 204. `GET /users/email/{email}` is used by auth-service via Feign for login validation.

### Patterns established in Phase 1 (applied to every service)

| Pattern | Rule |
|---|---|
| Java records as DTOs | All Request/Response types are records — immutable, no boilerplate, no getters/setters |
| Lombok on entities only | `@Builder @Getter @Setter @RequiredArgsConstructor` on JPA entities only; records handle DTOs |
| Constructor injection | `@RequiredArgsConstructor` on every service class; never `@Autowired` on fields |
| Transactional reads | `@Transactional(readOnly=true)` on all read methods; `open-in-view: false` everywhere |
| GlobalExceptionHandler | Single `@RestControllerAdvice` per service; typed exceptions → standard error envelope |
| Flyway + validate | Flyway owns schema creation; Hibernate `ddl-auto: validate` checks but never modifies |
| EnumType.STRING | Never ORDINAL — reordering enum values would silently corrupt all existing data |
| Soft delete | Status field; never hard delete any record |
| springdoc-openapi 2.8.6 | Swagger UI on every service at `/swagger-ui.html` |
| Env var substitution | `${VAR:default}` in all YAML — override in Docker Compose, use defaults locally |

---

## 3. Phase 2 — Core Banking

**Goal:** Financial operations with correct money arithmetic, inter-service HTTP calls, and distributed transaction patterns.

### account-service
Port 8082 · DB: `banking_accounts` (postgres-accounts:5434)
Calls user-service via Feign to validate the user exists and is ACTIVE before opening an account.

**Key patterns introduced:**

**BigDecimal for money — never double/float**
`double` and `float` cannot represent most decimal fractions exactly. `0.1 + 0.2 == 0.30000000000000004` in double arithmetic. In a financial system this error compounds across millions of transactions. `NUMERIC(19,4)` in PostgreSQL maps to exact arithmetic with precision=19 (up to 999 trillion units) and scale=4 (four decimal places for micro-currencies and interest).

**BigDecimal.compareTo() not .equals()**
`equals()` also checks scale: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`. `compareTo()` compares only numeric value: `1.0.compareTo(1.00) == 0`. All balance comparisons use `compareTo`.

**@Version optimistic locking**
Two concurrent withdrawals both read `balance=1000`. Without locking, both subtract their amount and the second write silently overwrites the first. With `@Version`, Hibernate adds `WHERE version=N` to every UPDATE. The second writer finds no matching row (version already incremented) and gets `ObjectOptimisticLockingFailureException` which the handler maps to 409 Conflict. Zero performance cost when there is no contention — unlike pessimistic `SELECT FOR UPDATE` which locks even on reads.

**Local mirror DTOs across service boundaries**
```java
// account-service owns this locally — String status, NOT an enum from user-service
public record UserResponse(Long id, String email, String status) {}
```
No shared library between services. Renaming `UserStatus.ACTIVE` in user-service never forces account-service to recompile. Each service is deployable independently.

**Feign client pattern:**
```java
@FeignClient(name = "user-service")  // name must match spring.application.name
public interface UserClient {
    @GetMapping("/users/{id}")
    UserResponse getUserById(@PathVariable Long id);
}
```
Add `@EnableFeignClients` to the main application class. Feign resolves `user-service` via Eureka at call time — no URL, no port, no hardcoding. If user-service moves to a different host, nothing in account-service changes.

### transaction-service
Port 8083 · DB: `banking_transactions` (postgres-transactions:5435)
Calls account-service via Feign for all balance operations. Never writes balances directly.

**PENDING -> COMPLETED/FAILED state machine**

The transaction record is written to DB as `PENDING` before any Feign call. If the process crashes mid-execution, the PENDING record survives and is visible to ops. Without this, a crash between "withdraw succeeded" and "deposit succeeded" is completely invisible.

```
1. Save transaction as PENDING
2. Feign: withdraw from source account
     failure -> mark FAILED, save, publish events, return
     success -> continue
3. Feign: deposit to target account
     failure -> COMPENSATE: Feign deposit back to source
           compensation OK     -> mark FAILED, save
           compensation FAILED -> mark FAILED, log CRITICAL (manual intervention required)
     success -> mark COMPLETED, save, publish events, return
```

This is a Manual Saga pattern. Production systems use Temporal or Conductor for guaranteed saga orchestration with automatic retry and compensation.

---

## 4. Phase 3 — Reliability

**Goal:** Graceful degradation when downstream services fail, and schema migration safety.

### notification-service
Port 8084 · DB: `banking_notifications` (postgres-notifications:5436)
After Phase 5a, receives events async via RabbitMQ. REST API remains for reads and status updates.

Notification channels: `IN_APP` (implemented now), `EMAIL`, `SMS` (deferred — the channel field exists now so callers already send the correct intent; adding EMAIL support later requires no API contract changes).

### Flyway migrations (all 5 DB-backed services)
Every DB service has `src/main/resources/db/migration/V1__init.sql`. Flyway runs on startup, applies pending migrations, and Hibernate validates the schema matches the entity model. `ddl-auto: validate` fails fast on startup if schema and entity are out of sync — prevents silent runtime errors from schema drift. The "Hibernate drops and recreates your prod tables" disaster cannot happen.

### Circuit breaker (Resilience4j)
Wraps all Feign calls via `spring.cloud.openfeign.circuitbreaker.enabled=true`.

States:
```
CLOSED --(failure rate >= threshold)--> OPEN --(wait-duration)--> HALF-OPEN
  ^                                                                    |
  +--------------------(test calls succeed)----------------------------+
```

| Config | account-service -> user-service | transaction-service -> account-service |
|---|---|---|
| sliding-window-size | 10 | 10 |
| failure-rate-threshold | 50% | 50% |
| wait-duration-in-open-state | 10 s | 10 s |
| minimum-number-of-calls | 5 | 5 |
| ignore-exceptions | NotFound, BadRequest | NotFound, BadRequest |

4xx responses (404, 400) are excluded from the failure rate. A user not found (404) is a valid response — it means user-service is working correctly, not that it is down.

**Fallback factory pattern:**
```java
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {
    @Override
    public UserClient create(Throwable cause) {
        return id -> { throw new UserServiceUnavailableException(cause.getMessage()); };
    }
}
```
The fallback throws a typed exception which `GlobalExceptionHandler` maps to 503. Callers receive a clean error envelope, not a raw `CallNotPermittedException` stack trace.

---

## 5. Phase 4 — Security

**Goal:** JWT-based authentication at the gateway level. Every external request is validated before routing to any downstream service.

### auth-service
Port 8085 · DB: `banking_auth` (postgres-auth:5437)
Standalone token issuer — no outbound Feign calls except to user-service during registration.

| Method | Path | Description |
|--------|------|-------------|
| POST | /auth/register | BCrypt-hash password, store credential |
| POST | /auth/login | Verify BCrypt hash -> issue access + refresh token |
| POST | /auth/refresh | Validate refresh token -> issue new access token |
| POST | /auth/logout | Soft-revoke refresh token (status = REVOKED) |

**Token strategy:**
- **Access token** — short-lived JWT (15 min). Stateless; gateway validates without any DB call.
- **Refresh token** — long-lived (7 days), stored in DB. Revocable; enables real logout.

**JWT structure:**
```
Header:  { "alg": "HS256", "typ": "JWT" }
Payload: { "sub": "42", "email": "user@example.com", "role": "USER", "iat": ..., "exp": ... }
Signature: HMAC-SHA256(base64(header) + "." + base64(payload), JWT_SECRET)
```

**Shared secret:** Both auth-service and api-gateway read the same `JWT_SECRET` env var. Same base64-encoded key must be configured in both. In production, inject from AWS Secrets Manager or HashiCorp Vault — never commit to source control.

### api-gateway JWT GlobalFilter
- `@Order(Ordered.HIGHEST_PRECEDENCE)` — executes before all other gateway filters
- Public whitelist: `/v1/auth/**`, `/actuator/**` — bypass JWT check (callers do not have a token yet)
- Returns 401 on missing, expired, or invalid token
- On success, forwards `X-User-Id`, `X-User-Email`, `X-User-Role` headers to downstream services
- Downstream services trust these headers without re-validating the token (token is already verified at the gateway boundary)

---

## 6. Phase 5a — Async Notifications via RabbitMQ

**Goal:** Decouple transaction-service from notification-service. A slow or unavailable notification-service must have zero impact on transaction response time.

### Before vs after

**Before (synchronous Feign):**
Every transaction waited for the notification HTTP call to complete:
```
transaction-service -> account-service (balance update)  ~10ms
                    -> notification-service (HTTP call)   ~200ms variable
                    <- response                           total: ~210ms+
```
If notification-service was slow or down, the circuit breaker would eventually open — but until then, every transaction paid the latency cost.

**After (async RabbitMQ):**
Publish is fire-and-forget:
```
transaction-service -> account-service (balance update)  ~10ms
                    -> RabbitMQ publish                   ~2ms
                    <- response                           total: ~12ms
                              |
                    notification-service <- consume       async, independent
```

### RabbitMQ topology

```
Routing key: transaction.completed  -+
Routing key: transaction.failed     -+--> Exchange: banking.transactions (topic)
                                     |              |
                                     |    Binding: transaction.# (wildcard)
                                     |              |
                                     |              v
                                     |    Queue: notification.transaction.queue
                                     |              |
                                     |    [exception / NACK, no requeue]
                                     |              v
                                     +--> DLX: banking.transactions.dlx
                                                    |
                                                    v
                                         DLQ: notification.transaction.dlq
```

**Why a topic exchange over a direct exchange?**
Routing-key patterns allow future consumers to subscribe selectively without any producer code change. An alerting-service could bind only to `transaction.failed`. A reporting-service could bind to `transaction.#` (all outcomes). With a direct exchange, each new consumer type requires an explicit new binding to a specific routing key.

**Why DLQ with defaultRequeueRejected=false?**
Without this, a poison-pill message (malformed JSON, processing exception) is NACKed and immediately requeued. The consumer picks it up again, fails again, requeues again — an infinite loop that blocks the entire queue. Setting `defaultRequeueRejected=false` means a NACKed message goes to the DLX/DLQ instead, leaving the main queue unblocked. The failed message is held for manual inspection and replay.

**Why Jackson2JsonMessageConverter in both services?**
The converter auto-wires into `RabbitTemplate` (producer serialisation) and `SimpleRabbitListenerContainerFactory` (consumer deserialisation). Both sides speak plain JSON — no Java serialisation, no class name embedded in the message, no binary compatibility requirement between the two services.

### What was deleted from transaction-service
- `NotificationClient.java` — Feign interface
- `NotificationClientFallbackFactory.java`
- `NotificationRequest.java` DTO
- Notification-service circuit breaker config in `application.yml`

Dead code is more dangerous than no code. It compiles, it misleads readers, and it may be accidentally revived.

### Event record ownership rule
Both transaction-service and notification-service define their own `TransactionEvent` record. No shared library. Jackson deserialises by field name, not class identity. Adding a field to the producer record is silently ignored by the consumer (Jackson ignores unknown fields by default). Only removing a field that the consumer uses requires coordinated deployment.

---

## 7. Phase 5b — Audit Event Streaming via Kafka

**Goal:** Immutable, replayable audit trail of all significant state changes across the system, required for compliance and post-incident debugging.

### Why Kafka for audit, not RabbitMQ?

| | RabbitMQ | Kafka |
|---|---|---|
| After consumption | Message deleted | Message retained (default 7 days) |
| Replay | Not possible | Consumer resets offset to 0, replays entire history |
| Ordering | Per-queue FIFO | Per-partition, guaranteed by key hash |
| Consumer model | Push, broker delivers | Pull, consumer tracks its own offset |
| Best fit | Task queues, notifications | Event logs, audit trails, analytics pipelines |

RabbitMQ is correct for Phase 5a (deliver once and discard). Kafka is correct for Phase 5b: if audit-service loses its database, it can reset its consumer group offset to 0 and Kafka replays every event that was ever produced — the audit table can be rebuilt completely from the Kafka log.

### Kafka topology (KRaft — no Zookeeper)

Single-broker dev setup using `bitnami/kafka:3.7` with KRaft embedded controller (no separate Zookeeper container needed).

```
transaction-service --publish(key=transactionId)--> banking.audit.transactions (3 partitions)
account-service     --publish(key=accountId)    --> banking.audit.accounts    (3 partitions)
                                                              |
                                               audit-service (group: audit-service)
                                               +-- TransactionEventConsumer
                                               +-- AccountEventConsumer
                                                              |
                                                    postgres-audit:5438
                                                    audit_events table
```

**Why partition key = entityId?**
Kafka partitions messages by hashing the key. All events for `transactionId=42` land in the same partition, guaranteeing that the consumer processes them in the exact order they were produced. Without a key, events for the same entity could spread across partitions and be consumed out of order, producing incorrect audit reconstructions.

**Why ADD_TYPE_INFO_HEADERS=false on producers?**
By default, Spring Kafka's `JsonSerializer` embeds the Java class name in a `__TypeId__` message header. The consumer would need a class with the same fully-qualified name. With the header disabled, both sides exchange plain JSON, mapped by field name via `ObjectMapper`. Renaming a package or class on the producer never breaks the consumer.

**Why String deserialization in the consumer?**
The consumer receives the raw JSON string and calls `objectMapper.readValue(payload, EventClass.class)`. Compared to configuring `JsonDeserializer` with type mapping, this is more explicit (the deserialization step is visible in the listener code), works cleanly with multiple event types on different topics without multiple container factories, and is free of class-name magic.

### audit-service
Port 8086 · DB: `banking_audit` (postgres-audit:5438)

**Read-only REST API** — no write endpoints exist. The only path to creating an audit record is publishing a Kafka event from a source service. This enforces immutability at the architecture level: audit records cannot be created via HTTP, cannot be modified, and are never deleted.

**audit_events table:**
```sql
id           BIGSERIAL PRIMARY KEY
event_type   VARCHAR(50)   NOT NULL  -- TRANSACTION or ACCOUNT
entity_id    BIGINT        NOT NULL  -- transactionId or accountId
user_id      BIGINT                  -- nullable (account owner)
action       VARCHAR(100)  NOT NULL  -- COMPLETED, FAILED, CREATED, DEPOSITED, WITHDRAWN, STATUS_CHANGED, CLOSED
payload      TEXT          NOT NULL  -- full raw JSON of the original Kafka message
occurred_at  TIMESTAMP     NOT NULL  -- business time (from source service)
received_at  TIMESTAMP     NOT NULL  -- processing time (when audit-service persisted it)

Indexes: event_type, entity_id, user_id, occurred_at
```

**Why store the full raw JSON payload?**
The `payload` column holds the complete original event exactly as published. Even if the source service changes its data model months later, old audit records still contain their complete original snapshot. The indexed columns (`event_type`, `entity_id`, `user_id`) enable efficient filtering without parsing the JSON blob.

**occurredAt vs receivedAt**
- `occurredAt` = business time — taken from the Kafka event; represents when the operation actually happened in the source service
- `receivedAt` = processing time — set by `@PrePersist`; represents when audit-service persisted the record
- The gap between the two is end-to-end Kafka pipeline latency, useful for measuring SLA compliance

**Consumer offset and replay**
- `auto-offset-reset: earliest` — on first startup (no committed offset), read from the beginning of all partitions
- `AckMode.RECORD` — commit offset after each message is successfully processed; a restart replays from the last committed position
- To rebuild the entire audit table: reset the consumer group offset to 0 and restart audit-service; Kafka replays every retained message in order

### Events published

**TransactionAuditEvent** (published by transaction-service after every terminal outcome):
```
transactionId, sourceAccountId, targetAccountId, userId,
type (DEBIT/CREDIT/TRANSFER), amount, status (COMPLETED/FAILED),
description, failureReason, occurredAt
```
Published once per transaction after the final `transactionRepository.save()` at every code path.

**AccountAuditEvent** (published by account-service after every state change):
```
accountId, accountNumber, userId, action,
previousStatus, newStatus, previousBalance, newBalance, occurredAt
```
Action values: `CREATED`, `DEPOSITED`, `WITHDRAWN`, `STATUS_CHANGED`, `CLOSED`
Before/after state is captured so the audit log is a complete change log, not just a point-in-time snapshot.

---

## 8. Cross-Cutting Patterns

These patterns appear in every service without exception and are the backbone of the system's consistency.

### Standard error envelope
Every GlobalExceptionHandler maps typed exceptions to this exact shape:
```json
{
  "timestamp": "2026-04-10T14:23:01.123",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient funds in ACC123: requested 500.00, available 100.00",
  "path": "/accounts/7/withdraw"
}
```
Callers never parse raw Java stack traces. Monitoring systems can key on the `error` field.

### Constructor injection
```java
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;  // final = immutable after construction
    private final UserClient userClient;
    private final AuditEventPublisher auditPublisher;
}
```
Makes all dependencies explicit, allows `final` immutability, and simplifies unit testing: construct with mocks directly without Spring context.

### Fire-and-forget messaging publishers
```java
public void publish(String topic, String key, Object event) {
    try {
        kafkaTemplate.send(topic, key, event);
    } catch (Exception e) {
        log.warn("Failed to publish (non-critical): {}", e.getMessage());
        // Never rethrow — the DB transaction is already committed
    }
}
```
The database is the source of truth. Messaging broker outages are auxiliary failures — they must never roll back a committed financial transaction.

### Graceful shutdown
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s
```
On SIGTERM, Spring stops accepting new connections immediately but waits 25 s for in-flight requests to complete before the JVM exits. Without this, a rolling deploy kills requests mid-flight causing random 500 errors for clients.

### No shared library between services
Every cross-service DTO — event records (`TransactionEvent`, `TransactionAuditEvent`, `AccountAuditEvent`) and mirror DTOs (`UserResponse` in account-service) — is owned locally by the consuming service. There is no shared `common` Maven module. This prevents a change in one service from requiring all others to recompile and allows fully independent deployment and versioning.

---

## 9. Infrastructure Reference

### Port assignments
| Service | Application Port | Database Port |
|---|---|---|
| api-gateway | 8080 | — |
| user-service | 8081 | 5433 |
| account-service | 8082 | 5434 |
| transaction-service | 8083 | 5435 |
| notification-service | 8084 | 5436 |
| auth-service | 8085 | 5437 |
| audit-service | 8086 | 5438 |
| service-discovery (Eureka) | 8761 | — |
| RabbitMQ AMQP | 5672 | — |
| RabbitMQ Management UI | 15672 | — |
| Kafka | 9092 | — |

### Docker Compose commands
```bash
# Start everything and rebuild all images
docker compose up -d --build

# Start without rebuilding (use cached images)
docker compose up -d

# Rebuild and restart one service only
docker compose up -d --build transaction-service

# Tail logs for a service
docker compose logs -f audit-service

# Stop containers, keep data volumes
docker compose down

# Stop containers and delete all data volumes (full reset)
docker compose down -v
```

### Useful URLs
| URL | Description |
|---|---|
| http://localhost:8761 | Eureka dashboard — all registered services |
| http://localhost:8080/actuator/gateway/routes | Live gateway routes |
| http://localhost:15672 | RabbitMQ Management UI (banking_user / banking_pass) |
| http://localhost:8081/swagger-ui.html | user-service Swagger |
| http://localhost:8082/swagger-ui.html | account-service Swagger |
| http://localhost:8083/swagger-ui.html | transaction-service Swagger |
| http://localhost:8084/swagger-ui.html | notification-service Swagger |
| http://localhost:8085/swagger-ui.html | auth-service Swagger |
| http://localhost:8086/swagger-ui.html | audit-service Swagger |

### Environment variables
| Variable | Services | Default | Description |
|---|---|---|---|
| `JWT_SECRET` | api-gateway, auth-service | base64 dev key in yml | HMAC-SHA256 signing key — both must share the same value |
| `EUREKA_HOST` | all | localhost | Hostname of service-discovery container |
| `RABBITMQ_HOST` | transaction-service, notification-service | localhost | RabbitMQ hostname |
| `KAFKA_BOOTSTRAP` | transaction-service, account-service, audit-service | localhost:9092 | Kafka bootstrap address |
| `DB_HOST` / `DB_PORT` | all DB services | localhost / service-specific | PostgreSQL connection |

---

## 10. API Reference

All endpoints except `/v1/auth/**` require: `Authorization: Bearer <access_token>`

### user-service `/v1/users`
| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/users | Create user |
| GET | /v1/users | List all (paginated, `?status=`) |
| GET | /v1/users/{id} | Get by ID |
| GET | /v1/users/email/{email} | Get by email |
| PUT | /v1/users/{id} | Update profile |
| PUT | /v1/users/{id}/status | Change status (ACTIVE/SUSPENDED/DELETED) |
| DELETE | /v1/users/{id} | Soft delete -> 204 |

### account-service `/v1/accounts`
| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/accounts | Open account (validates user is ACTIVE) |
| GET | /v1/accounts | List all (paginated, `?status=`) |
| GET | /v1/accounts/{id} | Get by ID |
| GET | /v1/accounts/number/{number} | Get by account number |
| GET | /v1/accounts/user/{userId} | All accounts for a user |
| GET | /v1/accounts/{id}/balance | Balance only |
| PUT | /v1/accounts/{id}/deposit | Deposit funds |
| PUT | /v1/accounts/{id}/withdraw | Withdraw (422 Unprocessable Entity if insufficient funds) |
| PUT | /v1/accounts/{id}/status | Change status |
| DELETE | /v1/accounts/{id} | Soft close -> 204 |

### transaction-service `/v1/transactions`
| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/transactions/debit | Debit an account (money out) |
| POST | /v1/transactions/credit | Credit an account (money in) |
| POST | /v1/transactions/transfer | Transfer between accounts (saga with compensation) |
| GET | /v1/transactions | List all (`?status=`, `?type=`, paginated) |
| GET | /v1/transactions/{id} | Get by ID |
| GET | /v1/transactions/account/{accountId} | All transactions for an account |

### notification-service `/v1/notifications`
| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/notifications | Create notification (internal — called by RabbitMQ consumer) |
| GET | /v1/notifications/{id} | Get by ID |
| GET | /v1/notifications/user/{userId} | All for user (`?unreadOnly=true`) |
| GET | /v1/notifications/user/{userId}/unread-count | Count of unread notifications |
| PUT | /v1/notifications/{id}/read | Mark single as read |
| PUT | /v1/notifications/user/{userId}/read-all | Mark all as read |
| DELETE | /v1/notifications/{id} | Soft delete |

### auth-service `/v1/auth` — public, no JWT required
| Method | Path | Description |
|--------|------|-------------|
| POST | /v1/auth/register | Register credential (BCrypt password hash stored) |
| POST | /v1/auth/login | Validate credentials -> access + refresh token |
| POST | /v1/auth/refresh | Exchange refresh token for new access token |
| POST | /v1/auth/logout | Soft-revoke refresh token |

### audit-service `/v1/audit` — read-only, no write endpoints
| Method | Path | Description |
|--------|------|-------------|
| GET | /v1/audit | List all (paginated, `?eventType=TRANSACTION` or `ACCOUNT`) |
| GET | /v1/audit/{id} | Get single record |
| GET | /v1/audit/entity/{eventType}/{entityId} | All events for one entity (e.g. `/audit/entity/TRANSACTION/42`) |
| GET | /v1/audit/user/{userId} | All events linked to a user |

---

## 11. Data Models

### User

| Field | Type | Constraints |
|---|---|---|
| id | BIGSERIAL | PK |
| first_name | VARCHAR | NOT NULL |
| last_name | VARCHAR | NOT NULL |
| email | VARCHAR | NOT NULL, UNIQUE |
| status | VARCHAR | ACTIVE / SUSPENDED / DELETED |
| created_at | TIMESTAMP | auto-set |

### Account

| Field | Type | Constraints |
|---|---|---|
| id | BIGSERIAL | PK |
| user_id | BIGINT | NOT NULL (no FK join — cross-service) |
| account_number | VARCHAR | UNIQUE, generated (ACC-XXXXXXXX) |
| type | VARCHAR | SAVINGS / CURRENT |
| balance | NUMERIC(19,4) | NOT NULL, default 0 |
| status | VARCHAR | ACTIVE / SUSPENDED / CLOSED |
| version | BIGINT | optimistic lock — auto-incremented by Hibernate |
| created_at | TIMESTAMP | auto-set |

### Transaction

| Field | Type | Constraints |
|---|---|---|
| id | BIGSERIAL | PK |
| type | VARCHAR | DEBIT / CREDIT / TRANSFER |
| status | VARCHAR | PENDING / COMPLETED / FAILED |
| source_account_id | BIGINT | nullable (CREDIT has no source) |
| target_account_id | BIGINT | nullable (DEBIT has no target) |
| amount | NUMERIC(19,4) | NOT NULL |
| description | VARCHAR | nullable |
| failure_reason | VARCHAR | set on FAILED |
| created_at | TIMESTAMP | auto-set |
| completed_at | TIMESTAMP | set when leaving PENDING |

### Notification

| Field | Type | Constraints |
|---|---|---|
| id | BIGSERIAL | PK |
| user_id | BIGINT | NOT NULL |
| type | VARCHAR | TRANSACTION_DEBIT / TRANSACTION_CREDIT / TRANSACTION_TRANSFER |
| channel | VARCHAR | IN_APP |
| title | VARCHAR | NOT NULL |
| message | TEXT | NOT NULL |
| status | VARCHAR | UNREAD / READ / DELETED |
| created_at | TIMESTAMP | auto-set |
| read_at | TIMESTAMP | set when marked READ |

### AuditEvent

| Field | Type | Constraints |
|---|---|---|
| id | BIGSERIAL | PK |
| event_type | VARCHAR(50) | TRANSACTION / ACCOUNT |
| entity_id | BIGINT | transactionId or accountId |
| user_id | BIGINT | nullable |
| action | VARCHAR(100) | COMPLETED / FAILED / CREATED / DEPOSITED / WITHDRAWN / STATUS_CHANGED / CLOSED |
| payload | TEXT | full raw JSON snapshot at time of event |
| occurred_at | TIMESTAMP | business time (from source service) |
| received_at | TIMESTAMP | when audit-service persisted the record |

Indexes on: `event_type`, `entity_id`, `user_id`, `occurred_at`

---

## 12. Testing Guide

### Prerequisites

```bash
docker-compose up --build
```

Wait ~30 s for all services to register. Verify at http://localhost:8761 — all 7 services should show UP (service-discovery, api-gateway, user-service, account-service, transaction-service, notification-service, auth-service, audit-service).

Swagger UIs are on each service's direct port — no JWT needed there. Via api-gateway (port 8080), all endpoints except `/v1/auth/**` require `Authorization: Bearer <token>`.

### Full Happy Path

**Step 1 — Register (creates user + hashed credentials in one call)**

```bash
curl -s -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "password": "password123"
  }' | jq .
```

Response `201`:
```json
{
  "token": "eyJhbGci...",
  "tokenType": "Bearer",
  "userId": 1,
  "email": "john@example.com",
  "role": "USER",
  "issuedAt": "2026-05-08T10:00:00",
  "expiresAt": "2026-05-09T10:00:00"
}
```

Save `token`. All subsequent calls use `-H "Authorization: Bearer <token>"`.

---

**Step 2 — Login (get fresh token any time)**

```bash
curl -s -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "john@example.com", "password": "password123"}' | jq .token
```

---

**Step 3 — Get user ID**

```bash
curl -s http://localhost:8080/v1/users/email/john@example.com \
  -H "Authorization: Bearer <token>" | jq .id
```

---

**Step 4 — Open first account (SAVINGS)**

```bash
curl -s -X POST http://localhost:8080/v1/accounts \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "type": "SAVINGS"}' | jq .
```

Response `201`:
```json
{
  "id": 1,
  "userId": 1,
  "accountNumber": "ACC-00000001",
  "type": "SAVINGS",
  "balance": 0.0000,
  "status": "ACTIVE",
  "createdAt": "2026-05-08T10:00:00"
}
```

---

**Step 5 — Open second account (for transfer)**

```bash
curl -s -X POST http://localhost:8080/v1/accounts \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "type": "CURRENT"}' | jq .id
```

---

**Step 6 — Deposit**

```bash
curl -s -X PUT http://localhost:8080/v1/accounts/1/deposit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 1000.00}' | jq .balance
```

---

**Step 7 — Check balance**

```bash
curl -s http://localhost:8080/v1/accounts/1/balance \
  -H "Authorization: Bearer <token>" | jq .
```

---

**Step 8 — Debit transaction**

```bash
curl -s -X POST http://localhost:8080/v1/transactions/debit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": 1,
    "amount": 100.00,
    "description": "Coffee"
  }' | jq '{status, amount, failureReason}'
```

Always 201. Check `status` field: `COMPLETED` or `FAILED`.

---

**Step 9 — Credit transaction**

```bash
curl -s -X POST http://localhost:8080/v1/transactions/credit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": 1,
    "amount": 50.00,
    "description": "Refund"
  }' | jq '{status, amount}'
```

---

**Step 10 — Transfer between accounts**

```bash
curl -s -X POST http://localhost:8080/v1/transactions/transfer \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": 1,
    "targetAccountId": 2,
    "amount": 200.00,
    "description": "Rent"
  }' | jq '{status, failureReason}'
```

---

**Step 11 — Transaction history**

```bash
# All transactions for account 1
curl -s "http://localhost:8080/v1/transactions/account/1" \
  -H "Authorization: Bearer <token>" | jq '.content[] | {id, type, status, amount}'

# Filter: completed transfers
curl -s "http://localhost:8080/v1/transactions?type=TRANSFER&status=COMPLETED" \
  -H "Authorization: Bearer <token>" | jq .
```

---

**Step 12 — Notifications (async via RabbitMQ — wait ~1 s)**

```bash
# All notifications for user
curl -s "http://localhost:8080/v1/notifications/user/1" \
  -H "Authorization: Bearer <token>" | jq '.content[] | {type, title, status}'

# Unread count
curl -s "http://localhost:8080/v1/notifications/user/1/unread-count" \
  -H "Authorization: Bearer <token>" | jq .

# Mark all read
curl -s -X PUT "http://localhost:8080/v1/notifications/user/1/read-all" \
  -H "Authorization: Bearer <token>"
```

---

**Step 13 — Audit log (async via Kafka — wait ~2 s)**

```bash
# All events
curl -s "http://localhost:8080/v1/audit" \
  -H "Authorization: Bearer <token>" | jq '.content[] | {eventType, action, entityId}'

# Transaction audit trail
curl -s "http://localhost:8080/v1/audit/entity/TRANSACTION/1" \
  -H "Authorization: Bearer <token>" | jq .

# Account audit trail
curl -s "http://localhost:8080/v1/audit/entity/ACCOUNT/1" \
  -H "Authorization: Bearer <token>" | jq .

# All events for a user
curl -s "http://localhost:8080/v1/audit/user/1" \
  -H "Authorization: Bearer <token>" | jq .
```

---

### Edge Case Tests

**Insufficient funds → 422:**
```bash
curl -s -X PUT http://localhost:8080/v1/accounts/1/withdraw \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 999999.00}' | jq '{status, message}'
```

**Transfer to same account → 400:**
```bash
curl -s -X POST http://localhost:8080/v1/transactions/transfer \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccountId": 1, "targetAccountId": 1, "amount": 10.00}' | jq .
```

**No token → 401:**
```bash
curl -s http://localhost:8080/v1/users | jq .
```

**Bad token → 401:**
```bash
curl -s http://localhost:8080/v1/users \
  -H "Authorization: Bearer fake.token.here" | jq .
```

**Transaction on suspended account → 422:**
```bash
# Suspend
curl -s -X PUT http://localhost:8080/v1/accounts/1/status \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"status": "SUSPENDED"}'

# Try debit → expect 422
curl -s -X POST http://localhost:8080/v1/transactions/debit \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "amount": 10.00}' | jq .
```

**Duplicate email → 409:**
```bash
curl -s -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Jane","lastName":"Doe","email":"john@example.com","password":"pass1234"}' | jq .
```

**Optimistic lock conflict → 409 (simulate with parallel requests):**
Two concurrent withdrawals on the same account. One succeeds; the other gets 409 — just retry.

---

## 13. Error Reference

All errors use this envelope:

```json
{
  "timestamp": "2026-05-08T10:00:00.123",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient funds in ACC-00000001: requested 500.00, available 100.00",
  "path": "/accounts/1/withdraw"
}
```

| Code | Meaning | Common causes |
|---|---|---|
| 400 | Validation failed | Blank required field, invalid email, amount < 0.01, same source/target on transfer |
| 401 | Unauthorized | Missing `Authorization` header, expired token, invalid JWT signature |
| 404 | Not found | Entity ID does not exist |
| 409 | Conflict | Duplicate email on register/update; optimistic lock collision on balance (retry) |
| 422 | Business rule violated | Insufficient funds, account not ACTIVE, user not ACTIVE |
| 503 | Service unavailable | Upstream Feign call failed, circuit breaker open, or service down |

---

## 14. What's Next

### Phase 6 — Observability
**Distributed tracing (Zipkin):**
Add `zipkin` container to Docker Compose. Add `micrometer-tracing-bridge-brave` and `zipkin-reporter-brave` to each service. Every request gets a `traceId` that flows across Feign calls and Kafka messages. You can see the full call timeline for one user request across all 8 services at http://localhost:9411.

**Prometheus + Grafana:**
Add `micrometer-registry-prometheus` to each service (exposes `/actuator/prometheus`). Add `prometheus` and `grafana` containers to Docker Compose. Build dashboards for: request rate, error rate, p50/p95/p99 latency, circuit breaker state, Kafka consumer lag, RabbitMQ queue depth.

### Phase 7 — Integration Tests
`@SpringBootTest` + Testcontainers. Testcontainers spins up real PostgreSQL, RabbitMQ, and Kafka containers during the test run — no mocks, no in-memory databases. Tests hit the actual driver stack. The class of bug they catch that mock-based tests miss: Flyway migration fails silently, JSON serialisation incompatibility between services, actual Kafka offset commit behaviour under failure.

### Phase 8 — Production Hardening
**Redis rate limiting:** Add `spring-boot-starter-data-redis` to api-gateway. Configure `RequestRateLimiter` filter per route. Prevents API abuse without custom code.

**Kubernetes:** Write `Deployment`, `Service`, `ConfigMap`, and `Secret` manifests. Liveness and readiness probes are already wired via Spring Boot Actuator and enabled in the gateway. Zero application code changes needed.

**CI/CD:** GitHub Actions pipeline — run tests -> Maven build -> Docker image push -> kubectl rollout. Add `docker-compose.test.yml` for the integration test stage.
