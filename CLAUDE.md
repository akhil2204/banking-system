# Banking System — Claude Context

## Project structure
Multi-module Maven project. Each service is a separate Spring Boot app.
Always work one service at a time. Never modify multiple services in one session.

## Tech stack
- Java 21, Spring Boot 3.4.3, Spring Cloud 2024.0.1, Maven multi-module
- PostgreSQL (user-service, account-service, transaction-service)
- Docker Compose for all local infrastructure

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
| service-discovery | 8761 |
| postgres-users | 5433 |
| postgres-accounts | 5434 |
| postgres-transactions | 5435 |

---

## Current build status

### DONE — all services complete
- Parent POM (multi-module, Spring Boot 3.4.3, Spring Cloud 2024.0.1, Java 21)
- service-discovery ✓
- api-gateway ✓
- user-service ✓
- account-service ✓
- transaction-service ✓
- notification-service ✓

### TODO
- (all core services built — see next steps below)

---

## Recommended build order and why

### 1. account-service (next)
Manages bank accounts (SAVINGS / CURRENT), balances, and account lifecycle.
Calls user-service via Feign to validate a user exists before opening an account.
transaction-service depends on this — must exist first.

### 2. transaction-service
Manages DEBIT / CREDIT / TRANSFER operations.
Calls account-service via Feign to check balances and trigger updates.
Never touches the accounts DB directly.

### 3. notification-service
Notifies users of account and transaction events.
Start synchronous (called by Feign from transaction-service).
Later upgrade to async with RabbitMQ/Kafka — add that infrastructure then.

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

- `/v1/users/**` → `lb://user-service` (RewritePath strips /v1)
- Graceful shutdown (25s), response compression, liveness/readiness probes
- Live routes: http://localhost:8080/actuator/gateway/routes

**Deferred (infrastructure not yet added):**
- Rate limiting → needs Redis
- Distributed tracing → needs Zipkin
- Prometheus metrics → needs micrometer-registry-prometheus + Prometheus container
- JWT validation filter → needs auth design

### account-service ✓
**Port:** 8082 | **DB:** banking_accounts (postgres-accounts:5434)
Calls user-service via Feign: `@FeignClient(name = "user-service")`

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

Key patterns introduced:
- `BigDecimal(precision=19, scale=4)` for balance — never double/float
- `@Version` on Account entity — optimistic locking, 409 on concurrent update
- `FeignException` handling — 404 from upstream → 404, other → 503
- `UserResponse` as local mirror DTO with `String status` (no enum coupling)
- `InsufficientFundsException` → 422 Unprocessable Entity
- `AccountNotActiveException` → 422 Unprocessable Entity
- `BigDecimal.compareTo()` for value comparison (not equals — scale-aware)

### transaction-service ✓
**Port:** 8083 | **DB:** banking_transactions (postgres-transactions:5435)
Calls account-service via Feign: `@FeignClient(name = "account-service")`

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

Key patterns introduced:
- PENDING→COMPLETED/FAILED state machine — record persisted before any Feign call
- Manual Saga compensating transaction on transfer deposit failure
- `@Slf4j` structured logging of all transaction outcomes
- `resolveAccount()` helper — consolidates Feign call + active-status check
- Transfer always returns 201; caller must check `status` field in response

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

---

## Suggested next steps (choose based on goals)

### Production hardening
- **Flyway migrations** ✓ — all 4 DB services have V1__init.sql; ddl-auto: validate
- **Circuit breaker (Resilience4j)** ✓ — account-service (UserClient), transaction-service (AccountClient + NotificationClient)
- **JWT auth** — add a Spring Security filter in api-gateway; each service validates the token
- **Integration tests** — `@SpringBootTest` + Testcontainers (spins up real PostgreSQL in Docker for tests)

### Observability
- **Distributed tracing** — add Zipkin container + `micrometer-tracing-bridge-brave` to each service
- **Prometheus + Grafana** — add `micrometer-registry-prometheus`; dashboards for request rates, error rates, latency

### Reliability
- **Async notifications** — replace synchronous Feign call from transaction-service with RabbitMQ/Kafka event; notification-service becomes a consumer; decouples the two services completely
- **Circuit breaker** — add Resilience4j to account-service and transaction-service Feign clients; fail fast when downstream is unhealthy instead of waiting for timeouts
