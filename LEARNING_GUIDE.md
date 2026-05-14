# Banking System — Learning Guide

> **Who this is for:** You built this system and want to understand *why* every decision was made — not just *what* exists. Read it like a book, top to bottom.
>
> **Companion doc:** `SYSTEM_DOCUMENTATION.md` is the technical reference. This guide is the explanation.

---

## Chapter 1: Why Microservices?

### Start with the alternative

Imagine building a bank as one big application (a **monolith**):

```
BankApp.jar
├── UserController
├── AccountController  
├── TransactionController
├── NotificationController
├── AuthController
└── Shared Database
```

This works when the app is small. Then it grows:

- **Deployment problem:** Fix a notification bug → deploy the *entire bank* → risk breaking unrelated features
- **Team problem:** 5 teams editing the same codebase → constant merge conflicts, blocked deployments
- **Scale problem:** Transaction processing is under load → you must scale everything, even the notification code sitting idle
- **Crash problem:** A bug in notification code → the whole bank goes down

### What microservices solve

Split the system into **small, independent services**. Each service:
- Does one thing well
- Has its own database
- Runs as its own process
- Communicates over the network
- Can be deployed, scaled, and crashed independently

```
user-service     account-service     transaction-service
      ↓                 ↓                     ↓
  postgres-users  postgres-accounts   postgres-transactions
```

**The tradeoff:** Distributed systems are harder to build and debug. Network calls fail. Data is eventually consistent. But for a bank, this is the right trade.

---

## Chapter 2: The Entry Point — API Gateway

### Why one front door?

Without a gateway, clients need to know every service's location:
- Auth: port 8085
- Users: port 8081
- Accounts: port 8082
- Transactions: port 8083
- ...

Every mobile app and web client embeds your internal architecture. When you change a port, every client breaks. When you want to add authentication, you add it to every service separately.

**The gateway is a single entry point.** All traffic enters through `http://localhost:8080`. Nothing else is exposed.

### What the gateway does

```
Client → :8080/v1/accounts/1
              ↓
    [1] JWT GlobalFilter — is this token valid?
              ↓ (yes)
    [2] Route match: /v1/accounts/** → lb://account-service
              ↓
    [3] RewritePath: strip /v1 → /accounts/1
              ↓
    [4] Forward to account-service:8082/accounts/1
```

**Step 1 — JWT validation:** Every request (except `/v1/auth/**`) must have a valid Bearer token. If missing, expired, or tampered with → 401 immediately. The service never sees the request.

**Step 2 — Route match:** The gateway knows which paths go to which service, defined in `application.yml`.

**Step 3 — RewritePath:** Services don't know they're behind a gateway. They receive `/accounts/1`, not `/v1/accounts/1`. The `/v1` prefix is a gateway concern.

**Step 4 — Forward:** Uses `lb://account-service` — the `lb://` prefix means "load-balanced via Eureka." If account-service runs 3 instances, traffic is spread across all three automatically.

### Why WebFlux (not regular Spring MVC)?

The gateway is a **proxy** — it receives requests and forwards them. Under load, it handles thousands of concurrent requests. 

With regular Spring MVC (blocking I/O), each request holds a thread while waiting for the downstream service to respond. Under load: thousands of requests × thread-per-request = you run out of threads.

WebFlux is event-driven and non-blocking. One thread handles many concurrent connections because it never blocks. It just registers callbacks: "when the downstream responds, do this."

**Rule:** Never add `spring-boot-starter-web` to the gateway. It will conflict with WebFlux and break routing.

---

## Chapter 3: Service Discovery — How Services Find Each Other

### The naive approach (wrong)

```java
// BAD — hardcoded URL
RestTemplate rt = new RestTemplate();
Account acc = rt.getForObject("http://192.168.1.45:8082/accounts/1", Account.class);
```

Problems:
- What if account-service restarts and gets a different IP?
- What if you run 3 instances of account-service for load?
- What if you change the port?

### Eureka to the rescue

Eureka is a **service registry** — a phone book.

```
account-service starts
    → "Hi Eureka, I am 'account-service' at 172.20.0.5:8082"
    → Eureka stores this

transaction-service wants to call account-service
    → "Eureka, where is 'account-service'?"
    → Eureka: "172.20.0.5:8082 (or 172.20.0.6:8082, 172.20.0.7:8082 if you run 3)"
    → transaction-service calls the right address
```

**Heartbeat:** Every 30 seconds, account-service sends "I'm still alive" to Eureka. If heartbeats stop, Eureka removes it. The next caller won't get a dead address.

### How Feign uses Eureka

```java
@FeignClient(name = "account-service")  // "account-service" = spring.application.name
public interface AccountClient {
    @GetMapping("/accounts/{id}")
    AccountResponse getAccount(@PathVariable Long id);
}
```

When transaction-service calls `accountClient.getAccount(1)`:
1. Spring intercepts the call
2. Asks Eureka: "where is account-service?"
3. Gets the real IP:port
4. Makes the HTTP GET
5. Returns the deserialized response

No URLs. No ports. No hardcoding. Services just use names.

**Dashboard:** http://localhost:8761 — see all registered services and their health status.

---

## Chapter 4: Authentication — auth-service

### The problem

Every API call needs to know: "who is making this request?" and "are they allowed to?"

Option 1: Send username+password on every request → validates against DB every time → slow, DB under constant load

Option 2: Issue a **token** at login → send token on subsequent requests → validate token without hitting DB → fast

We use Option 2 with **JWT (JSON Web Tokens)**.

### BCrypt — storing passwords safely

**Never store plaintext passwords.** If the database is breached, every user's password is exposed.

BCrypt is a one-way hash function designed specifically for passwords:

```
"mypassword" → BCrypt (with random salt + cost=10) → "$2a$10$N9qo8uLOickgx2ZMR..."
```

Key properties:
- **One-way:** You cannot reverse the hash to get the password
- **Salted:** Random data added before hashing → two identical passwords produce different hashes → can't use rainbow tables
- **Slow by design:** Cost factor controls how many hashing rounds → slow enough to be brute-force resistant

**Verification:**
```java
passwordEncoder.matches("mypassword", "$2a$10$N9qo8uLOickgx2ZMR...")  // true
```

BCrypt re-runs the same process on the input and compares. The hash is never "decrypted."

### JWT — tokens that carry their own data

After login, users get a JWT. Every subsequent request includes it in the header:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI0MiIsImVtYWlsIjoiam9obkBleGFtcGxlLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzE2MDAwMDAwLCJleHAiOjE3MTYwMDA5MDB9.abc123...
```

That big string has three parts separated by dots:

```
HEADER.PAYLOAD.SIGNATURE

Header:    { "alg": "HS256", "typ": "JWT" }          — which algorithm
Payload:   { "sub": "42", "email": "john@example.com", "role": "USER", "exp": 1716000900 }
Signature: HMAC-SHA256(header + "." + payload, SECRET_KEY)
```

**The signature is the security.** It is computed using a secret key that only the server knows. The gateway verifies the signature on every request. If anyone tampers with the payload (e.g., changes `userId` from 42 to 1 to impersonate another user), the signature check fails → 401.

### Access Token + Refresh Token

We issue two tokens:

| | Access Token | Refresh Token |
|---|---|---|
| Lifetime | 15 minutes | 7 days |
| Where used | Every API request | Only at `/auth/refresh` |
| Stored | Client memory | Client storage + server DB |
| Revokable | No (stateless) | Yes (delete from DB) |

**Why two tokens?**

If we only had one long-lived token:
- Stolen token → attacker has access for 7 days
- No way to logout (token is stateless, not in DB)

With the pair:
- Stolen access token expires in 15 min
- Logout invalidates the refresh token in DB → new access tokens can't be obtained
- Only the refresh token hits the DB (at most once every 15 min) → low DB load

### Registration flow

```
POST /v1/auth/register
{ "email": "alice@bank.com", "password": "secret", "firstName": "Alice", "lastName": "Smith" }

auth-service:
1. BCrypt hash the password
2. Feign call to user-service: POST /users { firstName, lastName, email }
   → user-service creates user profile, returns { id: 1, ... }
3. Store in auth DB: { email, bcryptHash, userId: 1, status: ACTIVE }
4. Return 201 with access + refresh tokens (user is logged in immediately)

Why call user-service?
  auth-service owns "can you log in?" (credentials)
  user-service owns "who are you?" (profile data)
  Separation of concerns. Changing the user profile schema doesn't touch auth logic.
```

### Login flow

```
POST /v1/auth/login
{ "email": "alice@bank.com", "password": "secret" }

1. Find credentials by email
2. passwordEncoder.matches("secret", storedBcryptHash) → true
3. Generate access token: JWT with userId, email, role, exp=now+15min
4. Generate refresh token: random UUID, store in DB with exp=now+7days
5. Return both tokens
```

### JWT GlobalFilter in the Gateway

```
Every incoming request (except /v1/auth/**):
1. Extract "Authorization" header
2. Strip "Bearer " prefix → raw JWT string
3. Parse and verify signature using JWT_SECRET
4. Check expiry (exp claim)
5. If invalid → return 401 immediately (never reaches the service)
6. If valid → add headers to the request:
   X-User-Id: 42
   X-User-Email: alice@bank.com  
   X-User-Role: USER
7. Forward to downstream service
```

Downstream services trust these headers. They never re-validate the JWT — the gateway is the trust boundary.

---

## Chapter 5: User Service

### What it owns

User profiles: name, email, phone, status. It is the **source of truth for who a person is** (not their login credentials — that's auth-service).

### Soft delete — never truly delete

```java
public enum UserStatus { ACTIVE, SUSPENDED, DELETED }
```

When you call `DELETE /v1/users/1`, the user is not removed from the database. Instead:
```sql
UPDATE users SET status = 'DELETED' WHERE id = 1;
```

**Why?**
- **Audit trail:** Banking regulations require knowing who made which transaction, even if they "left"
- **Referential integrity:** Transactions reference user IDs. If you hard-delete the user, those records become orphaned
- **Recovery:** Accidental deletes can be undone (just set status back to ACTIVE)
- **Legal hold:** You may be legally required to retain financial data for 7+ years

The `DELETE` endpoint returns 204 (No Content) — the response is the same as if it were deleted. Callers don't see the difference. But the data lives on.

### Flyway — controlled schema evolution

Instead of letting Hibernate auto-create tables:
```yaml
spring.jpa.hibernate.ddl-auto: create  # BAD — drops and recreates tables on every startup
```

We use Flyway:
```yaml
spring.jpa.hibernate.ddl-auto: validate  # Hibernate only checks, never modifies
```

Flyway migrations live at `src/main/resources/db/migration/V1__init.sql`:
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    ...
);
```

Flyway tracks applied migrations in `flyway_schema_history`. On startup:
1. Flyway checks what's been applied
2. Runs any new migration files in version order
3. Hibernate validates the schema matches the entity model

**Why this matters in production:** You cannot drop and recreate tables in a live database. Flyway gives you safe, versioned, incremental schema changes. `V2__add_phone_column.sql` adds the column without touching existing data.

### Pagination

```
GET /v1/users?page=0&size=20&sort=createdAt,desc
```

Never return all records from a table. A bank with 10 million users — returning them all in one response would crash both the server and the client.

Spring Data's `Pageable` handles this automatically. Pass `page` (0-indexed) and `size` (records per page). The response includes:
```json
{
  "content": [...],
  "totalElements": 10000000,
  "totalPages": 500000,
  "number": 0,
  "size": 20
}
```

---

## Chapter 6: Account Service

### What it owns

Bank accounts: opening them, balances, deposits, withdrawals. **The single source of truth for account balances.** No other service ever writes to a balance column directly.

### BigDecimal — the right type for money

```java
@Column(precision = 19, scale = 4)
private BigDecimal balance;
```

**Never use `double` or `float` for money.** This is a famous bug source:

```java
System.out.println(0.1 + 0.2);  // prints 0.30000000000000004
```

Floating-point numbers store values in binary fractions. Most decimal fractions (like 0.1) cannot be represented exactly in binary. The error is tiny, but it compounds. In a financial system processing millions of transactions, these rounding errors add up to real money.

`BigDecimal` stores exact decimal values. `precision=19` means up to 19 digits total. `scale=4` means 4 decimal places (`1234567890.1234`). For currency, this covers everything from micro-transactions to trillion-dollar transfers.

**Comparison gotcha:**
```java
new BigDecimal("100.00").equals(new BigDecimal("100.0"))    // false — different scale!
new BigDecimal("100.00").compareTo(new BigDecimal("100.0")) // 0 — correct
```

Always use `.compareTo()` for BigDecimal comparisons.

### Optimistic Locking — handling concurrent transactions

```java
@Entity
public class Account {
    @Version
    private Long version;  // Hibernate manages this automatically
}
```

Scenario without locking:
```
Request A reads account: balance=1000, version=0
Request B reads account: balance=1000, version=0
Request A withdraws 500: writes balance=500, version=1
Request B withdraws 700: writes balance=300 — WRONG! Should have failed (only 500 left)
```

With `@Version`, Hibernate's UPDATE includes a WHERE clause:
```sql
UPDATE accounts SET balance=300, version=1 WHERE id=1 AND version=0
```

Request B finds 0 rows updated (version is already 1) → throws `OptimisticLockingFailureException` → we return **409 Conflict** to the client → client retries.

No money lost. No double-spend. No database-level locks held during the request (which would block other operations).

### Circuit Breaker — failing gracefully

account-service calls user-service (via Feign) when opening an account to verify the user is ACTIVE. What if user-service goes down?

Without a circuit breaker:
```
50 requests/sec × 30 sec timeout (waiting for dead service) = 1500 threads blocked
→ account-service runs out of threads
→ account-service also goes down
→ cascading failure throughout the system
```

With Resilience4j circuit breaker:
```
CLOSED: all calls go through
  ↓ (50% of last 10 calls failed)
OPEN: calls fail immediately without trying (fast-fail, no timeout wait)
  ↓ (after 10 seconds)
HALF-OPEN: let 3 test calls through
  ↓ (if they succeed)
CLOSED: back to normal
```

The fallback throws `UserServiceUnavailableException` → `GlobalExceptionHandler` maps it to 503. Callers get a clean error envelope immediately instead of waiting 30 seconds for a timeout.

### Publishing Audit Events to Kafka

After every balance change, account-service publishes an audit event:
```java
kafkaTemplate.send(
    "banking.audit.accounts",           // topic
    account.getId().toString(),          // partition key (entityId)
    objectMapper.writeValueAsString(event)  // JSON payload
);
```

The partition key is the account ID. All events for account #42 go to the same Kafka partition → events for one account are always processed in order by audit-service.

If Kafka is down, the publish fails silently (logged as a warning). **The database transaction is already committed.** Messaging failures are auxiliary — they must never roll back a financial operation.

---

## Chapter 7: Transaction Service — The Most Complex Service

### What it owns

All money movement: debit (money out), credit (money in), transfer (between accounts).

**Critical rule:** transaction-service never touches balances directly. It calls account-service for all balance changes. Why? account-service owns the balance. If two services could modify balances, you'd have race conditions and consistency problems.

### The State Machine

Every transaction starts as PENDING. Always.

```
CREATE PENDING RECORD → save to DB
         ↓
    Make API calls
         ↓
   ┌────────────┐     ┌──────────┐
   │ COMPLETED  │     │  FAILED  │
   └────────────┘     └──────────┘
```

**Why save as PENDING before making any calls?**

Imagine crash-prone scenario without this:
1. Make withdrawal API call → SUCCESS
2. JVM crashes before saving the transaction record
3. Money is gone. No record exists. Nobody knows what happened.

With PENDING-first:
1. Save PENDING record → guaranteed to survive crashes
2. Make withdrawal API call → SUCCESS
3. Update record to COMPLETED

If the JVM crashes at step 2, we have a PENDING record. Operations team can investigate. The record is evidence that something started.

### Saga Pattern — Distributed Transactions

A **transfer** is: debit Account A, credit Account B. These are two separate HTTP calls to two separate databases. There is no way to wrap them in one atomic database transaction.

**The problem:**
```
1. Debit Account A: $500 removed ✓
2. Credit Account B: FAILED (account closed, or service crash)
→ Account A lost $500. Account B never got it.
```

**The solution: Saga with compensating transaction**

```java
try {
    // Step 1: Debit source
    accountClient.withdraw(sourceAccountId, new WithdrawRequest(amount));
    
    // Step 2: Credit destination
    accountClient.deposit(destAccountId, new DepositRequest(amount));
    
    transaction.setStatus(COMPLETED);
    
} catch (Exception creditFailed) {
    // COMPENSATE: undo the debit
    try {
        accountClient.deposit(sourceAccountId, new DepositRequest(amount));
        transaction.setStatus(FAILED);
        transaction.setFailureReason("Credit failed, debit reversed");
        
    } catch (Exception compensationFailed) {
        // Both the credit AND the compensation failed — needs human intervention
        transaction.setStatus(FAILED);
        transaction.setFailureReason("SAGA_COMPENSATION_FAILED: " + compensationFailed.getMessage());
        log.error("CRITICAL: Manual reconciliation needed for transaction {}", id);
    }
}
```

The "compensating transaction" is the "undo." Deposit back to the source account reverses the debit.

**Transfer always returns 201.** The HTTP response indicates the transfer was *processed*, not necessarily that it *succeeded*. The caller must check the `status` field in the response body.

**Why not two-phase commit (2PC)?**
2PC is a distributed protocol that coordinates commits across systems. It works but requires all participants to support it, can leave systems locked if the coordinator crashes, and is complex to implement correctly. Sagas are simpler and more resilient.

### Publishing to RabbitMQ

After every transaction outcome, publish a notification event:
```java
rabbitTemplate.convertAndSend(
    "banking.transactions",      // exchange name
    "transaction.completed",     // routing key
    new TransactionEvent(...)
);
```

This is **fire-and-forget.** transaction-service does not wait for notification-service to process it. The response to the client goes out immediately. notification-service processes the event asynchronously.

---

## Chapter 8: Async Notifications — RabbitMQ + notification-service

### Why async?

Before Phase 5a, transaction-service called notification-service synchronously via Feign:
```
Transaction processing:
  → account-service (debit)     ~10ms
  → notification-service (HTTP) ~200ms (slow? network issue? service down?)
Total: ~210ms+
```

If notification-service is slow, transactions are slow. If it's down, transactions fail. But sending a notification is not part of the transaction — it's a side effect. The user should not wait for it.

After Phase 5a:
```
Transaction processing:
  → account-service (debit)  ~10ms
  → RabbitMQ publish         ~2ms
Total: ~12ms

Meanwhile, asynchronously:
  RabbitMQ → notification-service (processes when ready)
```

### RabbitMQ Concepts

Think of RabbitMQ as a post office:

**Exchange** = the sorting office. Receives messages and routes them to mailboxes.

**Queue** = the mailbox. Stores messages until a consumer picks them up.

**Binding** = the routing rule. "Mail with label `transaction.#` goes to mailbox `notification.transaction.queue`."

**Topic Exchange** = smart sorting by label pattern:
- `*` matches exactly one word
- `#` matches zero or more words

Our setup:
```
Producer sends to exchange: banking.transactions
  routing key: transaction.completed
       OR
  routing key: transaction.failed

Exchange routes by pattern: transaction.#  (matches both!)
       ↓
Queue: notification.transaction.queue
       ↓
notification-service consumer
```

Adding a new event type like `transaction.reversed`? Nothing changes — the `transaction.#` pattern already covers it.

### Dead Letter Queue (DLQ) — handling bad messages

What if a message has malformed JSON and the consumer always throws an exception?

Without DLQ:
```
Consumer receives message → throws exception → message requeued → consumer receives it again → throws exception → requeued → infinite loop
```

The queue is stuck. No new messages can be processed.

With DLQ (`defaultRequeueRejected=false`):
```
Consumer throws exception → message rejected (not requeued) → goes to DLQ
New messages continue processing normally
DLQ holds bad message for inspection
```

The DLQ (`notification.transaction.dlq`) is visible in the RabbitMQ Management UI at http://localhost:15672. Operations can inspect the bad message, fix the bug, and replay it manually.

### notification-service Consumer

```java
@RabbitListener(queues = "notification.transaction.queue")
public void handleTransactionEvent(TransactionEvent event) {
    // event is automatically deserialized from JSON by Jackson
    notificationService.createFromEvent(event);
    // If this throws, the message goes to DLQ (not requeued)
}
```

Spring automatically:
- Deserializes the JSON message body to `TransactionEvent`
- Calls the method
- Acknowledges the message on success (message deleted from queue)
- On unhandled exception: rejects (goes to DLQ, because `defaultRequeueRejected=false`)

---

## Chapter 9: Immutable Audit Log — Kafka + audit-service

### Why Kafka instead of RabbitMQ?

| | RabbitMQ | Kafka |
|---|---|---|
| After consumer reads | Message deleted | **Message stays** (retained for 7 days by default) |
| Replay | Not possible | Reset offset to 0, replay entire history |
| Ordering | Per-queue FIFO | **Per partition**, guaranteed by key |
| Use case | Task queues, notifications | **Event logs, audit trails** |

For audit, we need:
- **Immutability:** Nobody can delete audit events
- **Replay:** If audit-service crashes, it should be able to rebuild from scratch
- **Multiple consumers:** Future analytics service could read the same audit events

RabbitMQ deletes processed messages. Kafka keeps them. That's the fundamental difference.

### Kafka Concepts

**Topic** = an ordered, append-only log of events. Like a database table, but you can only add rows, never update or delete.

**Partition** = a topic is split into shards for parallelism. Events within a partition are ordered. Events across partitions are NOT.

**Partition key** = determines which partition an event lands in (by hashing the key). We use entity ID as the key → all events for account #42 go to the same partition → ordered history per account.

**Consumer Group** = multiple consumers sharing the work. Each partition is assigned to exactly one consumer in the group. Add consumers (up to partition count) to scale processing.

**Offset** = position in the partition log. Like a bookmark. Kafka tracks where each consumer group is up to.

**auto-offset-reset: earliest** = when starting fresh (no committed offset), read from the very beginning of the log. Enables full rebuild.

**AckMode.RECORD** = commit the offset after each message is successfully processed. If audit-service crashes mid-batch, it restarts from the last committed offset, not the beginning.

### Our Kafka Setup

```
transaction-service publishes → banking.audit.transactions
account-service publishes     → banking.audit.accounts

Both topics consumed by:
  audit-service (consumer group: audit-service)
  → Stores everything in postgres-audit
```

### Why String deserialization (not typed objects)?

```java
@KafkaListener(topics = {"banking.audit.transactions", "banking.audit.accounts"}, groupId = "audit-service")
public void consume(String rawJson, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    JsonNode node = objectMapper.readTree(rawJson);
    // ... extract fields, save to DB
}
```

Alternative approach (type-bound):
```java
@KafkaListener(...)
public void consume(AccountAuditEvent event) { ... }
```

The type-bound approach requires audit-service to have a class `AccountAuditEvent` that matches exactly. If account-service renames a package or changes the class, audit-service must update simultaneously.

With raw JSON + manual parsing, audit-service is **completely decoupled**. account-service can evolve freely. audit-service just reads whatever JSON it receives.

### Rebuilding from scratch

This is Kafka's superpower for audit:

```bash
# Step 1: Reset consumer group offset
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group audit-service --reset-offsets --to-earliest --execute \
  --topic banking.audit.transactions

# Step 2: Clear audit DB
TRUNCATE audit_events;

# Step 3: Restart audit-service
docker compose restart audit-service
```

audit-service reads every event ever published (within retention window) and rebuilds the entire audit table. The Kafka log is the source of truth. The database is just a queryable cache of it.

### occurredAt vs receivedAt

```java
event.setOccurredAt(Instant.parse(node.get("occurredAt").asText()));  // from the source event
event.setReceivedAt(Instant.now());                                     // when audit-service processed it
```

`occurredAt` = when the business event happened (e.g., "John transferred $500 at 14:23:01")
`receivedAt` = when audit-service stored it (e.g., 14:23:01.050)

The gap is end-to-end latency through Kafka. Under normal conditions: milliseconds. A large gap indicates Kafka lag.

Business analysts use `occurredAt` for reports ("show all transactions from Monday").
Operations teams use the gap for alerting ("audit-service is 5 minutes behind").

---

## Chapter 10: End-to-End Flows

### Flow 1: New User, Full Onboarding

```
1. POST /v1/auth/register
   { email, password, firstName, lastName }
   
   auth-service receives:
   → BCrypt hash the password
   → Feign: POST /users (to user-service) → creates user profile, returns userId=1
   → Save credentials: { email, bcryptHash, userId=1 }
   → Generate access token (JWT, 15 min) + refresh token (UUID, 7 days)
   → Response: { accessToken, refreshToken, userId: 1 }

2. POST /v1/accounts
   Authorization: Bearer eyJ...
   { userId: 1, accountType: SAVINGS, initialDeposit: 1000.00 }
   
   api-gateway:
   → JWT filter validates token → extracts userId=1 → adds X-User-Id: 1
   → Routes to account-service
   
   account-service:
   → Feign: GET /users/1 (to user-service) → confirms user is ACTIVE
   → Create account: { userId:1, balance:1000.00, status:ACTIVE, accountNumber:"ACC-00000001" }
   → Kafka publish to banking.audit.accounts: { action:CREATED, accountId:1, newBalance:1000.00 }
   → Response: { id: 1, accountNumber: ACC-00000001, balance: 1000.00 }
   
   audit-service (async):
   → Kafka consumer reads the event
   → Stores audit record: { eventType:ACCOUNT, action:CREATED, entityId:1, payload:{...} }
```

### Flow 2: Transfer Between Accounts (Happy Path)

```
Setup: User has two accounts — accountId=1 (balance $1000), accountId=2 (balance $500)

POST /v1/transactions/transfer
Authorization: Bearer eyJ...
{ sourceAccountId: 1, destinationAccountId: 2, amount: 300.00 }

transaction-service:
→ Validate amount > 0
→ Create PENDING transaction record (saved to DB)
→ Feign: GET /accounts/1 → confirm ACTIVE
→ Feign: GET /accounts/2 → confirm ACTIVE
→ Feign: PUT /accounts/1/withdraw { amount: 300.00 }
    account-service: balance $1000 - $300 = $700, version=1
    Kafka: AccountAuditEvent { action:WITHDRAWN, entityId:1, previousBalance:1000, newBalance:700 }
→ Feign: PUT /accounts/2/deposit { amount: 300.00 }
    account-service: balance $500 + $300 = $800, version=1
    Kafka: AccountAuditEvent { action:DEPOSITED, entityId:2, previousBalance:500, newBalance:800 }
→ Update transaction: status=COMPLETED
→ RabbitMQ publish to banking.transactions: { routingKey: transaction.completed, event: {...} }
→ Kafka publish to banking.audit.transactions: { transactionId:1, status:COMPLETED, ... }
→ Response: { id: 1, status: COMPLETED, amount: 300.00 }

notification-service (async, milliseconds later):
→ RabbitMQ consumer reads TransactionEvent
→ Create notification: { userId:1, type:TRANSACTION_TRANSFER, status:UNREAD, message:"Transfer of $300.00..." }

audit-service (async, milliseconds later):
→ Kafka consumer reads TransactionAuditEvent (from banking.audit.transactions)
→ Stores audit record for the transaction
→ Kafka consumer reads AccountAuditEvents (from banking.audit.accounts, 2 events)
→ Stores two more audit records (one per account state change)
```

### Flow 3: Transfer Failure (Saga Compensation)

```
Same transfer, but account 2 has status=SUSPENDED

POST /v1/transactions/transfer
{ sourceAccountId: 1, destinationAccountId: 2, amount: 300.00 }

transaction-service:
→ Create PENDING transaction
→ Feign: PUT /accounts/1/withdraw → SUCCESS (source now has $700)
→ Feign: GET /accounts/2 → SUSPENDED → throws AccountNotActiveException
→ CATCH exception — must compensate!
→ Feign: PUT /accounts/1/deposit { amount: 300.00 } → SUCCESS (source restored to $1000)
→ Update transaction: status=FAILED, failureReason="Destination account not active"
→ RabbitMQ publish: routing key = transaction.failed
→ Response: { id: 1, status: FAILED, failureReason: "Destination account not active" }

notification-service:
→ Receives transaction.failed event
→ Creates notification: "Your transfer of $300.00 failed: Destination account not active"

Note: Account 1's balance is restored to $1000. Net effect: zero. Saga compensation worked.
```

### Flow 4: Token Expiry + Refresh

```
Access token expires after 15 minutes.

Client sends request with expired token:
→ api-gateway JWT filter: token is expired → 401 Unauthorized

Client uses refresh token:
POST /v1/auth/refresh
{ refreshToken: "550e8400-e29b-41d4-a716-446655440000" }

auth-service:
→ Find refresh token in DB → check not expired (7 days), not revoked
→ Generate new access token (JWT, fresh 15 min)
→ Response: { accessToken: "eyJ...(new)...", refreshToken: "550e..." (unchanged) }

Client retries the original request with the new access token → success
```

---

## Chapter 11: Design Patterns Summary

### Patterns and Where They Appear

| Pattern | Service | What It Solves |
|---|---|---|
| **API Gateway** | api-gateway | Single entry point, cross-cutting concerns |
| **Service Registry** | Eureka | Dynamic discovery, no hardcoded URLs |
| **Database Per Service** | All | Isolation, independent schema evolution |
| **JWT Authentication** | auth-service + gateway | Stateless identity without DB on every request |
| **Refresh Token** | auth-service | Revocable sessions with short-lived access tokens |
| **Soft Delete** | All | Audit compliance, data retention |
| **Saga (Compensation)** | transaction-service | Distributed transactions without 2PC |
| **Optimistic Locking** | account-service | Concurrent updates without DB-level locks |
| **Circuit Breaker** | account-service, transaction-service | Prevent cascading failures |
| **Topic Exchange** | RabbitMQ setup | Route events to multiple consumers without producer changes |
| **Dead Letter Queue** | notification-service | Handle bad messages without infinite retry |
| **Kafka Event Log** | audit-service | Immutable, replayable audit trail |
| **Partition by Entity ID** | Kafka publishing | Per-entity ordering guarantee |
| **Raw JSON Consumer** | audit-service | Decouple consumer from producer class structure |
| **Fire-and-forget Publish** | All Kafka/RabbitMQ publishers | Messaging failures never roll back DB transactions |
| **Local Mirror DTOs** | All Feign consumers | No shared libraries, independent deployment |
| **Standard Error Envelope** | All | Consistent error format for all clients |
| **Flyway Migrations** | All DB services | Safe, versioned schema evolution |

### Key Design Decisions Explained

**Why no shared library between services?**
If we had a `common.jar` with shared DTOs, every service imports it. When `common.jar` changes (e.g., an enum value is added), every service must recompile and redeploy simultaneously. With local mirror DTOs (each service owns a copy), services deploy independently. The small code duplication is worth the deployment independence.

**Why does transaction-service return 201 even on transfer failure?**
The HTTP response indicates whether the *request was processed*, not whether the *business operation succeeded*. The transfer was processed — a transaction record was created, saga compensation ran, a final status was determined. That's a successful request. The `status` field in the body tells you if the money moved.

**Why store full raw JSON in audit payload?**
If you only store IDs and the action, you lose the historical snapshot. If you later change how a balance is calculated, old audit records don't show what the balance actually was at that moment. The raw JSON snapshot is immutable — it shows exactly what existed at the moment the event was published, regardless of future schema changes.

**Why KRaft Kafka (no Zookeeper)?**
Kafka traditionally required Zookeeper for cluster coordination (separate process, separate port, separate management). KRaft mode (Kafka 3.3+) embeds the coordination logic into Kafka itself — one less container, one less thing to manage, one less failure point. Fine for development and increasingly common in production.

---

## Chapter 12: Running and Testing the System

### Start the System

```bash
# From the banking-system root directory
mvn clean package -DskipTests  # Build all JARs first

docker compose up --build      # Build images and start all containers
```

**Startup order (automatic via Docker Compose depends_on):**
1. All postgres instances + RabbitMQ + Kafka (~10 seconds)
2. service-discovery (Eureka) starts
3. All other services start, register with Eureka (~30 seconds total)

**Verify:** http://localhost:8761 — should show 7 services registered (api-gateway, user-service, account-service, transaction-service, notification-service, auth-service, audit-service).

### Test with Postman

Import `banking-system.postman_collection.json` into Postman. The collection has:

- **Auth** folder: Register, Login, Refresh, Logout (no token needed)
- **Users** folder: All CRUD operations (token required)
- **Accounts** folder: Open account, deposit, withdraw, transfer (token required)
- **Transactions** folder: Debit, credit, transfer, queries (token required)
- **Notifications** folder: Read notifications (token required)
- **Audit** folder: Query audit events (token required)
- **E2E Flow** folder: Run in order — register → login → open 2 accounts → deposit → transfer → check notifications → check audit

**Run the E2E Flow folder in order.** Test scripts automatically capture tokens and IDs into collection variables, so each request uses the values from previous responses.

### Common Issues

**401 on every request:**
- Run `POST /v1/auth/login` first
- The `access_token` variable must be set in the collection
- Access tokens expire after 15 minutes — run login again

**503 from account-service:**
- user-service might not be up yet — check http://localhost:8761
- Circuit breaker might be OPEN — wait 10 seconds and retry

**No notifications appearing:**
- RabbitMQ might not be healthy — check http://localhost:15672
- Queue `notification.transaction.queue` should have 0 or low message count
- Check notification-service logs: `docker compose logs -f notification-service`

**No audit events:**
- Kafka might be starting — wait 30 seconds after startup
- Check audit-service logs: `docker compose logs -f audit-service`
- The consumer group needs to initialize before events are stored

### Useful Commands

```bash
# Check all service logs at once
docker compose logs -f

# Check specific service
docker compose logs -f transaction-service

# Restart a service (pick up code changes)
docker compose up -d --build transaction-service

# Full reset (delete all data)
docker compose down -v
docker compose up --build

# Connect to a PostgreSQL instance
docker exec -it postgres-accounts psql -U banking_user -d banking_accounts

# List Kafka topics
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# Check Kafka consumer group lag
docker exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group audit-service --describe
```

---

*Read `SYSTEM_DOCUMENTATION.md` next for the technical reference: exact config, data models, and phase-by-phase implementation details.*
