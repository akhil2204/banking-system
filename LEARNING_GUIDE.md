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
    [1] Rate Limiter — is this IP within limit? (Redis token bucket)
              ↓ (yes)
    [2] JWT GlobalFilter — is this token valid?
              ↓ (yes)
    [3] Route match: /v1/accounts/** → lb://account-service
              ↓
    [4] RewritePath: strip /v1 → /accounts/1
              ↓
    [5] Forward to account-service:8082/accounts/1
```

**Step 1 — Rate limiting:** Each IP gets 10 requests/second steady-state, burst up to 20. Tracked in Redis. If the bucket is empty → 429 Too Many Requests immediately. This protects all services from brute force, DoS, and badly-behaved clients without writing a single line in any downstream service.

**Step 2 — JWT validation:** Every request (except `/v1/auth/**`) must have a valid Bearer token. If missing, expired, or tampered with → 401 immediately. The service never sees the request.

**Step 3 — Route match:** The gateway knows which paths go to which service, defined in `application.yml`.

**Step 4 — RewritePath:** Services don't know they're behind a gateway. They receive `/accounts/1`, not `/v1/accounts/1`. The `/v1` prefix is a gateway concern.

**Step 5 — Forward:** Uses `lb://account-service` — the `lb://` prefix means "load-balanced via Eureka." If account-service runs 3 instances, traffic is spread across all three automatically.

### Rate Limiting — Redis Token Bucket Algorithm

The token bucket algorithm works like this:

```
Bucket capacity: 20 tokens  (burstCapacity)
Refill rate:     10 tokens/second  (replenishRate)

Request arrives:
  → Is there ≥1 token in the bucket? YES → consume 1 token → allow request
  → Is there ≥1 token in the bucket? NO  → return 429 Too Many Requests

Every second: add 10 tokens (up to max 20)
```

**Why Redis?** The gateway may run as multiple instances. Each instance must see the same token count for the same IP. Redis is a shared, in-memory store — all gateway instances read and write the same bucket counters. Without Redis, each instance would have its own bucket and the limit would effectively be 10 × (number of gateway instances).

**Why not simple request counting?** Token buckets allow bursts. A user who hasn't made any requests can burst 20 requests instantly (bucket was full). A user who's been sending 10 req/s can't burst at all (bucket stays empty). This matches real usage patterns — normal users occasionally burst, attackers sustain high rates.

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

## Chapter 4: Authentication — How JWT Works, Step by Step

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

**Breaking down the BCrypt output:**
```
$2a$10$N9qo8uLOickgx2ZMR...
 │   │  └── 53-char hash (salt + hash combined)
 │   └── cost factor (10 = 2^10 = 1024 rounds)
 └── BCrypt version identifier
```

Key properties:
- **One-way:** You cannot reverse the hash to get the password. The only way to "check" is to run BCrypt again on the input and compare.
- **Salted:** The salt is a random 16-byte value embedded in the output. Two identical passwords produce completely different hashes. This defeats rainbow tables (precomputed hash lookup tables).
- **Slow by design:** Cost factor 10 means 1024 hashing rounds. On modern hardware, BCrypt takes ~100ms per hash. This is fine for login (one hash per request) but makes brute force infeasible (only 10 guesses/second per machine vs millions/second for unsalted SHA).

**Why not SHA-256?**
SHA-256 is designed for speed — can compute billions per second. BCrypt is designed for slowness — that's its security feature.

**Verification — how it works:**
```java
// Stored in DB:  "$2a$10$N9qo8uLOickgx2ZMRandSaltAndHash"
// User inputs:   "mypassword"

boolean matches = passwordEncoder.matches("mypassword", storedHash);

// What BCrypt does internally:
// 1. Extract salt from storedHash (embedded in the string)
// 2. Run BCrypt("mypassword", extractedSalt, cost=10)
// 3. Compare result to storedHash
// No "decryption" involved. The hash is never reversed.
```

### JWT Structure — Anatomy of a Token

After login, the user gets a JWT. Every subsequent request sends it:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI0MiIsImVtYWlsIjoiam9obkBleGFtcGxlLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzE2MDAwMDAwLCJleHAiOjE3MTYwMDA5MDB9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

That string is three Base64URL-encoded parts separated by dots:

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9          ← HEADER (Base64URL)
.
eyJzdWIiOiI0MiIsImVtYWlsIjoiam9obkBleGFtcGxlLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzE2MDAwMDAwLCJleHAiOjE3MTYwMDA5MDB9   ← PAYLOAD (Base64URL)
.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c   ← SIGNATURE (Base64URL)
```

**Decoded HEADER:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```
`alg: HS256` means HMAC-SHA256 is used for signing.

**Decoded PAYLOAD:**
```json
{
  "sub": "42",
  "email": "john@example.com",
  "role": "USER",
  "iat": 1716000000,
  "exp": 1716000900
}
```
- `sub` (subject) = the user's ID in our system
- `iat` (issued at) = Unix timestamp when token was created
- `exp` (expires at) = Unix timestamp after which token is invalid (iat + 15 minutes)
- `email`, `role` = custom claims — arbitrary data we added

**SIGNATURE:**
```
HMAC-SHA256(
    base64url(header) + "." + base64url(payload),
    SECRET_KEY
)
```
The signature is computed over the header AND payload combined. If anyone changes any byte in the header or payload, the signature won't match when recomputed → tamper detected.

### How auth-service Generates the Token

```java
// In auth-service JwtService:

private Key getSigningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
    // jwtSecret is the base64-encoded 32-byte secret from application.yml:
    // JWT_SECRET env var → "bXliYW5raW5nc2VjcmV0a2V5..." (base64)
    // Decoded: 32 raw bytes → exactly 256 bits → valid for HS256
    return Keys.hmacShaKeyFor(keyBytes);
}

public String generateAccessToken(Long userId, String email, String role) {
    return Jwts.builder()
        .subject(userId.toString())        // "sub" claim
        .claim("email", email)             // custom claim
        .claim("role", role)               // custom claim
        .issuedAt(new Date())              // "iat" claim = now
        .expiration(new Date(             // "exp" claim = now + 15 min
            System.currentTimeMillis() + 15 * 60 * 1000
        ))
        .signWith(getSigningKey())         // signs with HS256
        .compact();                        // assembles and encodes to string
}
```

**What `signWith` does internally:**
1. Serializes header to JSON → Base64URL encodes it → `eyJhbGci...`
2. Serializes payload to JSON → Base64URL encodes it → `eyJzdWIi...`
3. Concatenates: `header.payload` → the "signing input"
4. Computes `HMAC-SHA256(signingInput, secretKey)` → raw bytes
5. Base64URL encodes the signature bytes → `SflKxwRJ...`
6. Concatenates everything: `header.payload.signature`

### How api-gateway Validates the Token

The `JwtAuthenticationFilter` runs on every request (except `/v1/auth/**`):

```java
// Step 1: Extract token from Authorization header
String authHeader = request.getHeaders().getFirst("Authorization");
if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    return unauthorized(exchange);  // 401 — no token
}
String token = authHeader.substring(7);  // strip "Bearer "

// Step 2: Parse and validate
try {
    Claims claims = Jwts.parser()
        .verifyWith((SecretKey) getSigningKey())  // same key as auth-service
        .build()
        .parseSignedClaims(token)   // throws if invalid
        .getPayload();

    // parseSignedClaims internally does:
    // 1. Split token by "."
    // 2. Decode header → confirm alg=HS256
    // 3. Decode payload → extract claims
    // 4. Recompute HMAC-SHA256(header + "." + payload, secretKey)
    // 5. Compare recomputed signature to token's signature
    //    → if different: SignatureException
    // 6. Check exp claim → if in the past: ExpiredJwtException

    // Step 3: Extract user info from claims
    String userId = claims.getSubject();       // "42"
    String email  = claims.get("email", String.class);
    String role   = claims.get("role", String.class);

    // Step 4: Add downstream headers
    request.mutate()
        .header("X-User-Id",    userId)
        .header("X-User-Email", email)
        .header("X-User-Role",  role)
        .build();

    return chain.filter(exchange);  // proceed to route

} catch (ExpiredJwtException e) {
    return unauthorized(exchange, "Token expired");
} catch (JwtException e) {
    return unauthorized(exchange, "Invalid token");
}
```

**Why no database lookup?** JWT is stateless. The signature proves the token was issued by our server (only the server knows the secret). The `exp` claim proves it hasn't expired. No DB query needed. This is the primary advantage of JWT over session tokens.

**Security implication:** If the secret key is compromised, attackers can forge tokens. Rotate the `JWT_SECRET` env var periodically. In production, store it in a secrets manager (AWS Secrets Manager, HashiCorp Vault), never in code.

### Access Token + Refresh Token

We issue two tokens:

| | Access Token | Refresh Token |
|---|---|---|
| Lifetime | 15 minutes | 7 days |
| Format | JWT (stateless) | UUID (stored in DB) |
| Where used | Every API request | Only at `/auth/refresh` |
| Stored client-side | Memory (lost on tab close) | localStorage or secure cookie |
| Stored server-side | Nowhere (stateless) | auth DB with expiry + status |
| Revokable? | No — stateless | Yes — set status=REVOKED in DB |

**Why two tokens?**

If we only had one long-lived JWT:
- Stolen JWT → attacker has access for 7 full days
- No way to invalidate it (JWT is stateless — no DB record to delete)
- Logout is fake — client deletes token locally, but attacker still has their copy

With access + refresh:
- Stolen access token → valid for at most 15 minutes (low blast radius)
- Logout: invalidate refresh token in DB → attacker can't get new access tokens after the current one expires in 15 min
- The refresh token hits the DB only once every 15 minutes (not on every API call)

### Registration Flow — Step by Step

```
POST /v1/auth/register
{
  "firstName": "Alice",
  "lastName": "Smith",
  "email": "alice@bank.com",
  "password": "securepass123"
}

Gateway:
→ /v1/auth/** is whitelisted → no JWT check
→ Rate limiter still applies → 10 req/s per IP
→ Routes to auth-service

auth-service:
1. Validate input (@Valid): password ≥ 8 chars, email format
2. Check: does alice@bank.com already exist in auth DB? → if yes: 409 Conflict
3. BCrypt hash the password:
   "securepass123" → "$2a$10$RandomSalt...HashedValue"
4. Feign call: POST /users to user-service
   { firstName:"Alice", lastName:"Smith", email:"alice@bank.com" }
   → user-service creates user profile, returns { id: 1, status: "ACTIVE" }
5. Save to auth DB:
   { email:"alice@bank.com", passwordHash:"$2a$10$...", userId:1, status:ACTIVE }
6. Generate access token (JWT, 15 min expiry)
7. Generate refresh token (UUID) → save to auth DB with 7-day expiry
8. Return 201:
   { token: "eyJ...", tokenType: "Bearer", email: "alice@bank.com" }
```

Why call user-service from auth-service? Because auth-service owns "can this person log in?" and user-service owns "who is this person?" They have separate concerns. If you want to add profile fields (phone, address), you change user-service, not auth-service.

### Login Flow — Step by Step

```
POST /v1/auth/login
{ "email": "alice@bank.com", "password": "securepass123" }

auth-service:
1. Find credential by email in auth DB
   → if not found: return 401 (same response as wrong password — prevents email enumeration)
2. BCrypt.matches("securepass123", "$2a$10$...storedHash...")
   → if false: return 401
3. Generate new access token (JWT, 15 min)
4. Generate new refresh token UUID → upsert in DB (replace old one)
5. Return 200: { token: "eyJ...", tokenType: "Bearer" }
```

**Why return the same 401 for both wrong email and wrong password?**

If you returned "email not found" vs "wrong password" separately, an attacker could enumerate valid emails: try thousands of emails until they get "wrong password" instead of "email not found". Same 401 for both prevents this.

---

## Chapter 5: Authorization — How Every Service Uses Authentication

### Authentication vs Authorization

- **Authentication** = "Who are you?" → verified by checking the JWT signature + expiry
- **Authorization** = "Are you allowed to do this?" → business logic check against the authenticated identity

The gateway handles authentication. Individual services handle authorization.

### The Trust Model

```
Internet
    │
    ▼
[api-gateway :8080]  ← ONLY port exposed publicly
    │                   JWT validated here
    │                   X-User-Id / X-User-Email / X-User-Role headers added
    │
    ├── [user-service :8081]      ← internal only, trusts gateway headers
    ├── [account-service :8082]   ← internal only, trusts gateway headers
    ├── [transaction-service :8083] ← internal only, trusts gateway headers
    ├── [notification-service :8084]
    ├── [auth-service :8085]
    └── [audit-service :8086]
```

**Services never re-validate the JWT.** They trust that if a request reached them, the gateway already validated it. This is correct because:
1. Services are internal — not directly accessible from the internet (in production: inside a VPC/private network)
2. Re-validating JWT on every service call would double the validation work with no security gain

### How Each Service Uses the Auth Headers

Every service that needs the user's identity reads from the forwarded headers. In Spring MVC, this comes from the controller:

```java
// In any controller — get the authenticated user's ID from the gateway header
@GetMapping("/accounts/mine")
public ResponseEntity<Page<AccountResponse>> getMyAccounts(
        @RequestHeader("X-User-Id") Long userId,
        Pageable pageable) {
    return ResponseEntity.ok(accountService.getByUserId(userId, pageable));
}
```

The `@RequestHeader` annotation binds the HTTP header to the method parameter. If the gateway didn't add the header (should never happen for authenticated routes), Spring throws `MissingRequestHeaderException` → 400. In practice this means the request bypassed the gateway — which should be impossible in a properly configured network.

### Service-to-Service Calls (Feign Clients)

When transaction-service calls account-service via Feign, there is **no JWT**:

```java
@FeignClient(name = "account-service")
public interface AccountClient {
    @PutMapping("/accounts/{id}/withdraw")
    AccountResponse withdraw(@PathVariable Long id, @RequestBody WithdrawRequest request);
    // No Authorization header — these are internal calls
}
```

Why? Because transaction-service is already inside the trust boundary. The user was authenticated at the gateway. By the time transaction-service is running, we already know the user is valid. Inter-service calls are internal machine-to-machine communications — they don't need user-level auth.

In production, you'd add mutual TLS (mTLS) between services so that only authorized service certificates can call each other. But that's infrastructure-level security, not application-level JWT.

### What Happens if Someone Bypasses the Gateway

If someone discovers account-service runs on port 8082 and calls it directly:
1. **In development (Docker Compose):** All ports are exposed on localhost → direct access works, but there's no X-User-Id header → services that require it return 400
2. **In production (Kubernetes/cloud):** Services run in a private network. Only the gateway has a public IP. Direct access to :8082 is impossible.

**Rule for production:** Never expose service ports to the internet. Use network policies or security groups to restrict inbound traffic on all ports except the gateway's 8080.

### The Role Field — Foundation for Future Authorization

The JWT carries `"role": "USER"`. Currently all users get role USER. The infrastructure for role-based access control (RBAC) is in place:

```java
// Future: restrict /admin/** to ROLE_ADMIN
// In JwtAuthenticationFilter:
if (path.startsWith("/admin") && !"ADMIN".equals(claims.get("role"))) {
    return forbidden(exchange);  // 403
}
```

Adding admin endpoints is a config change in the gateway filter — no changes needed in downstream services.

---

## Chapter 6: User Service

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

---

## Chapter 7: Account Service

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
-- If version is already 1 (changed by Request A), 0 rows updated → exception
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

---

## Chapter 8: Transaction Service — The Most Complex Service

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

---

## Chapter 9: Async Notifications — RabbitMQ + notification-service

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
  routing key: transaction.completed  OR  transaction.failed

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

---

## Chapter 10: Immutable Audit Log — Kafka + audit-service

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

**Consumer Group** = multiple consumers sharing the work. Each partition is assigned to exactly one consumer in the group.

**Offset** = position in the partition log. Like a bookmark. Kafka tracks where each consumer group is up to.

**auto-offset-reset: earliest** = when starting fresh (no committed offset), read from the very beginning of the log. Enables full rebuild.

**AckMode.RECORD** = commit the offset after each message is successfully processed. If audit-service crashes mid-batch, it restarts from the last committed offset, not the beginning.

### Why String deserialization (not typed objects)?

```java
@KafkaListener(topics = {"banking.audit.transactions", "banking.audit.accounts"}, groupId = "audit-service")
public void consume(String rawJson, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    JsonNode node = objectMapper.readTree(rawJson);
    // ... extract fields, save to DB
}
```

With raw JSON + manual parsing, audit-service is **completely decoupled**. account-service can evolve freely. audit-service just reads whatever JSON it receives. No shared library needed.

### Rebuilding from scratch

```bash
# Step 1: Reset consumer group offset to beginning
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group audit-service \
  --reset-offsets --to-earliest --execute \
  --topic banking.audit.transactions

# Step 2: Clear audit DB
docker exec -it postgres-audit psql -U banking_user -d banking_audit -c "TRUNCATE audit_events;"

# Step 3: Restart audit-service — it reads from offset 0 and rebuilds
docker compose restart audit-service
```

audit-service reads every event ever published (within retention window) and rebuilds the entire audit table. The Kafka log is the source of truth. The database is just a queryable cache of it.

---

## Chapter 11: Kafka Partition Selection — The Full Mechanics

### The Problem It Solves

Kafka is designed for high throughput. A single machine can only write so fast to disk. The solution: split a topic across multiple machines (partitions), so writes to partition 0 go to machine 1, writes to partition 1 go to machine 2, etc.

But if events are spread randomly, you lose ordering. If account #42's "WITHDRAWN" event lands in partition 0 and "DEPOSITED" lands in partition 1, and two consumers process them in parallel, you might read DEPOSITED before WITHDRAWN — wrong order.

**Solution:** deterministically route all events for the same entity to the same partition.

### The Murmur2 Hash Function

Kafka's default partitioner uses **Murmur2** (not Java's standard `hashCode()`). Why?

- Java's `hashCode()` is not guaranteed to be stable across JVM restarts (and actually changed behavior between Java versions)
- Murmur2 is faster than cryptographic hashes (SHA, MD5) — speed matters when hashing millions of keys per second
- Murmur2 has excellent distribution — keys spread evenly across partitions

### The Exact Partition Calculation

```
Given: entityId = "42", numPartitions = 3

Step 1: Convert key to bytes
  "42" → byte[] {52, 50}  (UTF-8 encoding)

Step 2: Compute Murmur2 hash
  murmur2({52, 50}) → some 32-bit integer, e.g., -1867828150

Step 3: Make positive and mod by partition count
  Math.abs(-1867828150) % 3 = 1867828150 % 3 = 1

Result: event goes to partition 1
```

Every time entityId="42" is used as the key, it hashes to the same partition. Deterministic, always.

### Our Partition Key Strategy

```java
// In account-service AuditEventPublisher:
kafkaTemplate.send(
    "banking.audit.accounts",        // topic
    account.getId().toString(),       // KEY = entity ID (string)
    objectMapper.writeValueAsString(event)  // value
);

// In transaction-service AuditEventPublisher:
kafkaTemplate.send(
    "banking.audit.transactions",
    transaction.getId().toString(),   // KEY = entity ID
    objectMapper.writeValueAsString(event)
);
```

With this approach:
- All events for Account #42 → same partition → consumed in order by audit-service
- All events for Account #99 → same partition → consumed in order
- Different accounts may be on different partitions → processed in parallel → high throughput

### Consumer Group Assignment

With 3 partitions and 1 audit-service instance:
```
Partition 0 ─┐
Partition 1 ─┤── audit-service instance 1 (processes all 3)
Partition 2 ─┘
```

With 3 partitions and 3 audit-service instances:
```
Partition 0 ──── audit-service instance 1
Partition 1 ──── audit-service instance 2
Partition 2 ──── audit-service instance 3
```

**Rule:** Maximum useful parallelism = number of partitions. Adding a 4th consumer instance when you have 3 partitions leaves the 4th idle (no partition to assign it). To scale consumer throughput, increase partition count first.

### What Happens When You Scale Consumers (Rebalancing)

When a new consumer joins the group (e.g., a second audit-service starts):

1. Kafka detects the new consumer joining the group
2. **Rebalancing** starts — all consumers pause briefly
3. Kafka reassigns partitions:
   - Before: instance 1 handles partitions 0, 1, 2
   - After: instance 1 handles 0, 1; instance 2 handles 2
4. Consumers resume from their last committed offsets

This rebalancing is automatic. No configuration changes needed to scale consumers.

### What Happens With No Key (Null Key)

If you don't provide a partition key:
```java
kafkaTemplate.send("banking.audit.accounts", event);  // no key
```

Kafka uses **round-robin** distribution across partitions. Event 1 → partition 0, Event 2 → partition 1, Event 3 → partition 2, Event 4 → partition 0, ...

This maximizes throughput but loses ordering. For audit, this would mean account #42's WITHDRAWN and DEPOSITED events could go to different partitions, read by different consumers, stored out of order. **Always use entity ID as key for ordered event streams.**

### Monitoring Kafka Lag

**Consumer lag** = how many messages a consumer group is behind the latest message in the partition. High lag = audit-service is slow or down.

```bash
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group audit-service \
  --describe

# Output:
# GROUP         TOPIC                      PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# audit-service banking.audit.transactions 0          1042            1042            0    ← caught up
# audit-service banking.audit.transactions 1          987             989             2    ← 2 behind
# audit-service banking.audit.accounts     0          500             500             0    ← caught up
```

Zero lag = consumer is real-time. Non-zero lag = processing is behind. Persistent high lag = add more consumer instances or increase partition count.

---

## Chapter 12: Observability — Zipkin, Prometheus, and Grafana

### The Three Pillars of Observability

**Logs** = what happened (text records, always had these)
**Metrics** = how the system is performing over time (numbers, counters, gauges)
**Traces** = how a single request traveled through all services

Our system implements all three.

### Distributed Tracing with Zipkin

Without tracing:
```
User reports: "My transfer took 5 seconds!"
You look at logs:
  - transaction-service: "Processing transfer 123... done" (no timing)
  - account-service: nothing useful
  - RabbitMQ: no logs
  - notification-service: "Received event" (no timing)
```
You have no idea which step was slow.

With Zipkin:
```
Trace ID: abc123 (same ID spans entire request)
  ├── Span: api-gateway        [0ms → 2ms]   2ms   Route + JWT validate
  ├── Span: transaction-service [2ms → 4800ms] 4798ms  Process transfer
  │     ├── Span: account-service (withdraw)  [2ms → 50ms]    48ms   ← FAST
  │     ├── Span: account-service (deposit)   [50ms → 4750ms] 4700ms ← SLOW! bottleneck here
  │     └── Span: rabbitmq publish            [4750ms → 4800ms] 50ms
  └── Span: (response returned at 4800ms)
```

The trace shows account-service's deposit call was the bottleneck. Without tracing, you'd be guessing.

### How Trace Propagation Works

Every service has `micrometer-tracing-bridge-brave` on the classpath. This auto-instruments:
- Incoming HTTP requests (creates/continues a trace)
- Outgoing HTTP requests via RestTemplate/Feign (propagates trace headers)
- Kafka messages (attaches trace context to message headers)

```
Request arrives at gateway:
  → micrometer creates Trace ID: "abc123", Span ID: "span1"
  → adds headers: traceparent: "00-abc123-span1-01"

Gateway forwards to account-service:
  → account-service reads traceparent header
  → continues trace with Trace ID: "abc123", new Span ID: "span2"
  → when done, reports span to Zipkin: "span2 started at 2ms, ended at 50ms, parent=span1"

Zipkin UI:
  → collects all spans with Trace ID "abc123"
  → renders the timeline
```

**sampling.probability: 1.0** = trace every single request. In production, use 0.1 (10%) to avoid flooding Zipkin with traces from high-traffic services. Use 1.0 in development.

### Metrics with Prometheus and Grafana

**Prometheus** is a pull-based metrics system. Every 15 seconds, Prometheus sends an HTTP GET to `/actuator/prometheus` on each service. The response is a text file:

```
# HELP http_server_requests_seconds_count Total number of requests
# TYPE http_server_requests_seconds_count counter
http_server_requests_seconds_count{method="POST",status="201",uri="/transactions/transfer"} 1042.0
http_server_requests_seconds_count{method="POST",status="503",uri="/transactions/transfer"} 3.0

# HELP jvm_memory_used_bytes JVM memory used
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 5.3477376E7
```

Prometheus stores this time-series data. You can query it: "show me the P99 latency of POST /transactions/transfer over the last hour."

**Grafana** is the visualization layer. It queries Prometheus and renders charts. Examples of useful queries:

```promql
# Request rate per service (requests per second)
rate(http_server_requests_seconds_count[1m])

# Error rate (5xx responses)
rate(http_server_requests_seconds_count{status=~"5.."}[1m])

# P99 latency (99th percentile response time)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# JVM heap usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Kafka consumer lag (requires JMX metrics)
kafka_consumer_records_lag
```

### What Micrometer Does

`micrometer-registry-prometheus` is the bridge between Spring Boot's metrics and Prometheus's format. Spring Boot already collects metrics automatically:
- HTTP request counts, durations, status codes
- JVM heap, GC pause times, thread counts
- HikariCP connection pool stats (active connections, pending, timeout)
- Spring Data repository query times
- Kafka producer/consumer stats

Micrometer formats these in the text format Prometheus expects. You add the dependency and expose the endpoint — everything else is automatic.

**Import these Grafana dashboard IDs:**
- **4701** — JVM Micrometer (heap, GC, threads, CPU per service)
- **11378** — Spring Boot 3 Statistics (request rates, error rates, slow endpoints)
- **6417** — Spring Boot Statistics (overview per service)

---

## Chapter 13: Scaling to 500,000 – 1,000,000 Transactions Per Day

### The Math First

Let's understand what these numbers actually mean in terms of load:

```
500,000 transactions/day:
  Average: 500,000 / 86,400 seconds = 5.8 transactions/second

1,000,000 transactions/day:
  Average: 1,000,000 / 86,400 = 11.6 transactions/second

But banks don't have uniform load. Peaks happen:
  - Morning: 8–10 AM → 3x average
  - Lunch:  12–2 PM → 2x average
  - Evening: 6–9 PM → 2x average

Peak at 1M/day: 11.6 × 3 = ~35 transactions/second
Peak at 5M/day: 58 × 3 = ~175 transactions/second
```

**Current single-instance system can handle:** ~50-100 TPS comfortably. So for 1M/day (35 TPS peak), the current architecture works **as-is**. Scaling becomes necessary at 5M+ transactions/day.

### Where the Bottlenecks Are (In Order)

```
1. Database (always the bottleneck — disk is slow)
2. Inter-service HTTP calls (account-service called 2-3 times per transfer)
3. Kafka (nearly unlimited — designed for millions of messages/second)
4. Application memory (JVM heap — rarely the issue)
5. CPU (almost never the issue for I/O-bound banking workloads)
```

### Database Connection Pooling (HikariCP)

Every service uses HikariCP (Spring Boot's default pool). The default pool size is 10 connections.

**The Hikari formula for pool sizing:**
```
connections = (core_count × 2) + effective_spindle_count

For a 4-core machine with 1 SSD (spindle_count ≈ 1):
connections = (4 × 2) + 1 = 9 ≈ 10  ← matches HikariCP default!

For an 8-core machine:
connections = (8 × 2) + 1 = 17 → set to 20
```

**Why not more connections?** More connections doesn't mean more throughput. PostgreSQL has to context-switch between connections. Beyond the optimal count, adding connections hurts throughput. This is counterintuitive but proven empirically.

**Current pool behavior at 35 TPS (1M/day peak):**
```
Each transaction: ~3 DB operations (create PENDING, update COMPLETED, select account)
Total DB ops: 35 × 3 = 105 ops/second

Each op takes ~5ms on average
Pool can service: 10 connections × (1000ms / 5ms) = 2000 ops/second

Result: 2000 >> 105 → no bottleneck. Current config is fine.
```

**When to tune (at 1000 TPS):**
```yaml
# In application.yml (per service)
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 30000   # wait 30s for a connection
      idle-timeout: 600000         # remove idle connections after 10m
      max-lifetime: 1800000        # recycle connections every 30m
```

### Horizontal Scaling — Running Multiple Service Instances

Eureka + Spring Cloud LoadBalancer make this trivially easy:

```bash
# Scale transaction-service to 3 instances
docker compose up --scale transaction-service=3

# All 3 register with Eureka as "transaction-service"
# api-gateway load-balances across all 3 automatically
# No config changes needed anywhere
```

**What each additional instance adds:**
- More threads to handle concurrent requests
- More connection pool slots to the database
- More Kafka consumer threads (each instance can consume from different partitions)

**Caveat:** Stateless services scale horizontally. **Databases do not.** All 3 transaction-service instances share the same `postgres-transactions`. The DB is still the ceiling.

### Database Scaling Strategies

**Read replicas — separate reads from writes**

```
                   ┌── postgres-transactions (PRIMARY) ← writes only
transaction-service─┤
                   └── postgres-transactions-replica   ← reads only
                        (stream replication from primary, ~10ms lag)
```

For every transfer, we do:
- 1 write: INSERT PENDING record → primary
- 1 write: UPDATE to COMPLETED/FAILED → primary
- 2+ reads: GET /transactions/{id}, GET /transactions/account/{id} → replica

**Routing in Spring Data:**
```java
@Transactional(readOnly = true)  // Spring routes to read replica automatically
public Page<TransactionResponse> findByAccountId(Long accountId, Pageable pageable) {
    return transactionRepository.findByAccountId(accountId, pageable);
}
```

This is already done in the codebase. Adding a read replica requires only a configuration change, not a code change.

**Table Partitioning — when the table gets huge**

```sql
-- After 6 months: 180M transaction rows (1M/day × 180 days)
-- PostgreSQL query on unpartitioned table starts slowing down

-- Solution: partition by month
CREATE TABLE transactions (
    id BIGSERIAL,
    created_at TIMESTAMP NOT NULL,
    ...
) PARTITION BY RANGE (created_at);

CREATE TABLE transactions_2026_01 PARTITION OF transactions
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE transactions_2026_02 PARTITION OF transactions
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
```

Queries with a date filter only scan the relevant partition (e.g., "show transactions from January 2026" only reads the January partition — 100x faster than scanning all 180M rows).

**Archiving old data**

Transactions older than 7 years legally must be retained but are rarely queried. Move them to cheap cold storage (S3/GCS) and drop the old partitions from the live database.

```
Current: 7 billion rows → slow queries, expensive storage
After archiving 7+ year old data: 365 million rows → fast queries, normal storage
```

### Kafka Scaling

At 1M transactions/day, Kafka is not a bottleneck at all. Context:

```
1M transactions/day → audit publishes 1M messages/day to each topic
= 11.6 messages/second

Kafka capacity: 1,000,000+ messages/second (production clusters)

Current 3 partitions × 1 audit-service instance = fine.
```

**When to scale Kafka:**
- **10M+ messages/day:** Increase partition count to 10 to allow 10 parallel consumers
- **Cross-datacenter:** Add replicas (replication.factor=3) for durability
- **Long retention:** Keep 30 days instead of 7 → full month of audit replay
  ```yaml
  KAFKA_LOG_RETENTION_HOURS: 720  # 30 days
  KAFKA_LOG_RETENTION_BYTES: -1   # unlimited size
  ```

**Scaling consumer throughput:**
```
Current: 3 partitions, 1 consumer → 1 consumer processes all 3 partitions
Scale:   3 partitions, 3 consumers → 1 consumer per partition (3x throughput)
         beyond 3 consumers → no improvement (more consumers than partitions)

Scale to 6M messages/day:
  Increase partitions to 6, run 6 audit-service instances
  Each instance processes 1M messages/day
```

### Caching with Redis — Account Balance Reads

The most common read in account-service: `GET /accounts/{id}` and `GET /accounts/{id}/balance`.

At high load, these hits are mostly repeat reads of the same accounts (popular accounts get queried many times per second). Redis cache can eliminate 90%+ of these DB reads.

```java
// In account-service AccountService:
@Cacheable(value = "accounts", key = "#id")
@Transactional(readOnly = true)
public AccountResponse findById(Long id) {
    return accountRepository.findById(id)
        .map(this::toResponse)
        .orElseThrow(() -> new AccountNotFoundException(id));
}

@CacheEvict(value = "accounts", key = "#id")
@Transactional
public AccountResponse deposit(Long id, DepositRequest request) {
    // After deposit, evict the cache entry — balance changed
    Account account = ...deposit logic...
    return toResponse(account);
}
```

**Cache hit flow:**
```
GET /accounts/42
  → Redis: "account:42" exists? YES → return cached value (~0.5ms)
  → DB never queried
```

**Cache miss flow:**
```
GET /accounts/42
  → Redis: "account:42" exists? NO
  → DB query → return result
  → Redis: store "account:42" with TTL=60 seconds
```

After deposit/withdraw: evict cache → next read goes to DB → caches fresh value.

**Configuration:**
```yaml
spring:
  cache:
    type: redis
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
  cache:
    redis:
      time-to-live: 60000  # 60 seconds TTL
```

### CQRS — Command Query Responsibility Segregation

audit-service already implements a natural CQRS pattern:

```
WRITE side:  Kafka consumer → writes to DB (high volume, simple inserts)
READ  side:  REST API → reads from DB (low volume, complex queries with filters)
```

By separating writes (Kafka consumer) from reads (REST controller), each can be optimized independently:
- Write side: optimized for throughput (batch inserts, minimal validation)
- Read side: optimized for query performance (indexes, read replicas, caching)

For account-service and transaction-service, you could introduce CQRS when queries get complex:
```
command-service: handles writes (deposit, withdraw, transfer)
   → publishes events to Kafka

query-service: handles reads (balance queries, transaction history)
   → subscribes to events, maintains denormalized read models
   → fast reads because data is already in query-friendly format
```

### Sizing Example — 1M Transactions/Day Production Setup

```
Component                 Single Instance    High-Availability Setup
─────────────────────────────────────────────────────────────────────
api-gateway               2 instances        3 instances + load balancer
user-service              1 instance         2 instances
account-service           2 instances        4 instances (hotspot: balance writes)
transaction-service       2 instances        4 instances (core processing)
notification-service      1 instance         2 instances
auth-service              1 instance         2 instances
audit-service             1 instance         3 instances (3 Kafka partitions)
postgres (each)           1 primary          1 primary + 1 replica
Redis                     1 instance         Redis Sentinel (1 primary + 2 replicas)
Kafka                     1 broker (KRaft)   3 brokers + 3 partitions
RabbitMQ                  1 instance         RabbitMQ cluster (3 nodes)
```

**Estimated cost (AWS, ap-south-1):**
- Managed RDS PostgreSQL (small): ~$50/month × 6 = $300
- EC2 t3.medium for services: ~$30/month × 16 = $480
- ElastiCache Redis: ~$20/month
- MSK (Managed Kafka): ~$150/month
Total: ~$950/month to handle 1M transactions/day with HA.

### Summary: Scale-Up Decision Tree

```
Current load:
  <35 TPS (1M/day)  → current single-instance architecture is fine
  35-100 TPS        → horizontal scale services, add read replica
  100-500 TPS       → add caching (Redis for balances), increase connection pools
  500+ TPS          → partition transactions table, consider CQRS for hot paths
  1000+ TPS         → database sharding, dedicated connection pooler (PgBouncer)
```

The most important insight: **optimize the database first, not the services.** Services are stateless and scale horizontally for free. Databases hold state and require careful, planned scaling.

---

## Chapter 14: End-to-End Flows

### Flow 1: New User, Full Onboarding

```
1. POST /v1/auth/register
   { email, password, firstName, lastName }
   
   Gateway:
   → Rate limiter: check Redis bucket for this IP
   → /v1/auth/** is whitelisted — no JWT check
   → Routes to auth-service

   auth-service:
   → BCrypt hash the password (100ms — intentionally slow)
   → Feign: POST /users → user-service creates profile, returns userId=1
   → Save: { email, bcryptHash, userId=1 }
   → Generate access token JWT (exp: now+15min)
   → Generate refresh token UUID → save in DB (exp: now+7days)
   → Response 201: { token: "eyJ...", tokenType: "Bearer", email }

2. POST /v1/accounts
   Authorization: Bearer eyJ...
   { userId: 1, accountType: SAVINGS }
   
   Gateway:
   → Rate limiter check
   → JWT GlobalFilter: parse "eyJ..." → verify HMAC-SHA256 → check exp → valid
   → Extract: X-User-Id=1, X-User-Email=alice@bank.com, X-User-Role=USER
   → Routes to account-service

   account-service:
   → Feign: GET /users/1 → user-service confirms ACTIVE
   → Create account: { balance:0, status:ACTIVE, accountNumber:"ACC-00000001" }
   → Kafka publish to banking.audit.accounts: { action:CREATED, accountId:1 }
   → Response 201: { id:1, accountNumber:"ACC-00000001", balance:0.00 }

   audit-service (async, milliseconds later):
   → Kafka consumer reads event from banking.audit.accounts
   → Stores: { eventType:ACCOUNT, action:CREATED, entityId:1, userId:1 }
```

### Flow 2: Transfer (Happy Path)

```
POST /v1/transactions/transfer
Authorization: Bearer eyJ...
{ sourceAccountId: 1, destinationAccountId: 2, amount: 300.00 }

transaction-service:
→ Validate: amount > 0, source ≠ destination
→ Save PENDING transaction record to DB
→ Feign: GET /accounts/1 → ACTIVE ✓
→ Feign: GET /accounts/2 → ACTIVE ✓
→ Feign: PUT /accounts/1/withdraw { amount: 300.00 }
    account-service: balance 1000 → 700
    Kafka: AccountAuditEvent { action:WITHDRAWN, entityId:1, newBalance:700 }
→ Feign: PUT /accounts/2/deposit { amount: 300.00 }
    account-service: balance 500 → 800
    Kafka: AccountAuditEvent { action:DEPOSITED, entityId:2, newBalance:800 }
→ Update transaction: PENDING → COMPLETED
→ RabbitMQ publish: exchange=banking.transactions, routingKey=transaction.completed
→ Kafka publish: banking.audit.transactions (audit event)
→ Response 201: { status: "COMPLETED", amount: 300.00 }

notification-service (async):
→ RabbitMQ consumer: receives TransactionEvent
→ Creates notification: { userId:1, type:TRANSACTION_TRANSFER, status:UNREAD }

audit-service (async):
→ Kafka consumer: reads TransactionAuditEvent → stores transaction audit record
→ Kafka consumer: reads 2 AccountAuditEvents → stores account audit records
```

### Flow 3: Transfer Failure (Saga Compensation)

```
POST /v1/transactions/transfer
{ sourceAccountId: 1, destinationAccountId: 2, amount: 300.00 }
Account 2 is SUSPENDED

transaction-service:
→ Save PENDING record
→ Feign: GET /accounts/1 → ACTIVE ✓
→ Feign: GET /accounts/2 → SUSPENDED → throws AccountNotActiveException
→ CATCH: start compensation
→ Feign: PUT /accounts/1/deposit { amount: 300.00 }  ← undo the debit (never happened — no debit was made)
   (Actually: no debit happened yet — exception thrown before withdraw — so no compensation needed)
   Wait — in this scenario, the destination check happens BEFORE the withdraw.
   Let's use the real failing scenario: destination check after withdraw.

Actual saga scenario (destination account closed after withdraw starts):
→ Feign: PUT /accounts/1/withdraw → SUCCESS (balance: 700)
→ Feign: PUT /accounts/2/deposit → 404 Not Found → catch exception
→ COMPENSATE: Feign: PUT /accounts/1/deposit { amount: 300.00 } → balance restored to 1000
→ Update transaction: PENDING → FAILED, failureReason="Deposit to destination failed"
→ RabbitMQ: routingKey=transaction.failed
→ Response 201: { status: "FAILED", failureReason: "Deposit to destination failed" }

Net result: Account 1 balance unchanged (1000). Account 2 unchanged. No money lost.
```

### Flow 4: Token Expiry + Refresh

```
Client sends expired access token:
→ Gateway JWT filter: parseSignedClaims → ExpiredJwtException → 401

Client sends refresh request:
POST /v1/auth/refresh
{ "refreshToken": "550e8400-e29b-41d4-a716-446655440000" }

auth-service:
→ Find refresh token in DB (by UUID)
→ Check: status = ACTIVE (not revoked)
→ Check: expiresAt > now (not expired — 7 day window)
→ Generate new access token (JWT, fresh 15 min expiry)
→ Response 200: { token: "eyJ...(new token)..." }

Client retries original request with new token → success
```

### Flow 5: Rate Limit Exceeded

```
Client sends 25 requests in 1 second (exceeds burst of 20):

First 20 requests:
→ Redis token bucket: 20 tokens → consume 1 each → 0 tokens left
→ All 20 pass to downstream services

Requests 21-25:
→ Redis token bucket: 0 tokens → bucket empty
→ Gateway returns 429 Too Many Requests immediately
→ Response header: X-RateLimit-Remaining: 0
→ Downstream services never see these requests

After 1 second: bucket refills 10 tokens → next 10 requests pass
```

---

## Chapter 15: Design Patterns Summary

### All Patterns and Where They Appear

| Pattern | Service | What It Solves |
|---|---|---|
| **API Gateway** | api-gateway | Single entry point, cross-cutting concerns |
| **Token Bucket Rate Limiting** | api-gateway + Redis | Protect all services from DoS/brute-force |
| **Service Registry** | Eureka | Dynamic discovery, no hardcoded URLs |
| **Database Per Service** | All | Isolation, independent schema evolution |
| **JWT Authentication** | auth-service + gateway | Stateless identity without DB on every request |
| **HMAC-SHA256 Signing** | auth-service | Token tampering detection |
| **Refresh Token** | auth-service | Revocable sessions with short-lived access tokens |
| **BCrypt Password Hashing** | auth-service | Brute-force resistant credential storage |
| **Soft Delete** | All | Audit compliance, data retention |
| **Saga (Compensation)** | transaction-service | Distributed transactions without 2PC |
| **Optimistic Locking** | account-service | Concurrent updates without DB-level locks |
| **Circuit Breaker** | account-service, transaction-service | Prevent cascading failures |
| **Topic Exchange** | RabbitMQ setup | Route events to multiple consumers without producer changes |
| **Dead Letter Queue** | notification-service | Handle bad messages without infinite retry |
| **Kafka Event Log** | audit-service | Immutable, replayable audit trail |
| **Murmur2 Partition Key** | Kafka publishing | Per-entity ordering guarantee |
| **Raw JSON Consumer** | audit-service | Decouple consumer from producer class structure |
| **Fire-and-forget Publish** | All Kafka/RabbitMQ publishers | Messaging failures never roll back DB transactions |
| **Local Mirror DTOs** | All Feign consumers | No shared libraries, independent deployment |
| **Standard Error Envelope** | All | Consistent error format for all clients |
| **Flyway Migrations** | All DB services | Safe, versioned schema evolution |
| **Distributed Tracing** | All (Zipkin) | Find which service is slow in cross-service requests |
| **Metrics Scraping** | All (Prometheus/Grafana) | Monitor health and performance over time |
| **PENDING-first State Machine** | transaction-service | Crash-safe transaction processing |

### Key Design Decisions Explained

**Why no shared library between services?**
If we had a `common.jar` with shared DTOs, every service imports it. When `common.jar` changes (e.g., an enum value is added), every service must recompile and redeploy simultaneously. With local mirror DTOs (each service owns a copy), services deploy independently.

**Why does transaction-service return 201 even on transfer failure?**
The HTTP response indicates whether the *request was processed*, not whether the *business operation succeeded*. The transfer was processed — a transaction record was created, saga compensation ran, a final status was determined. That's a successful request. The `status` field in the body tells you if the money moved.

**Why store full raw JSON in audit payload?**
If you only store IDs and the action, you lose the historical snapshot. If you later change how a balance is calculated, old audit records don't show what the balance actually was at that moment. The raw JSON snapshot is immutable — it shows exactly what existed at the moment the event was published, regardless of future schema changes.

**Why KRaft Kafka (no Zookeeper)?**
Kafka traditionally required Zookeeper for cluster coordination. KRaft mode (Kafka 3.3+) embeds the coordination logic into Kafka itself — one less container, one less thing to manage, one less failure point.

**Why is the gateway written in WebFlux when all other services use Spring MVC?**
The gateway's job is to proxy traffic — receive a request, wait for the downstream response, return it. With blocking Spring MVC, each waiting request holds a thread. Under load: 1000 concurrent requests = 1000 threads blocked, waiting. With WebFlux (non-blocking), 1000 concurrent requests might use 10 threads that switch between connections as I/O becomes available. WebFlux is the right tool for a proxy. For services with real business logic, Spring MVC is simpler and more appropriate.

---

## Chapter 16: Running and Testing the System

### Start the System

```bash
# From the banking-system root directory
mvn clean package -DskipTests  # Build all JARs first

docker compose up --build      # Build images and start all containers
```

**Startup order (automatic via Docker Compose depends_on):**
1. Infrastructure: Redis, RabbitMQ, Kafka, all PostgreSQL instances, Zipkin (~15 seconds)
2. service-discovery (Eureka) starts
3. Business services start, register with Eureka (~30 seconds total)
4. Prometheus and Grafana start (after services are healthy)

**Verify everything is running:**
```
http://localhost:8761  — Eureka: should show 7 services registered
http://localhost:9411  — Zipkin: ready to show traces
http://localhost:9090  — Prometheus: check Status → Targets (all should be UP)
http://localhost:3000  — Grafana: login admin/admin → import dashboard IDs 4701, 11378
http://localhost:15672 — RabbitMQ: banking_user/banking_pass → check queues
```

### Test with Postman

Import `banking-system.postman_collection.json`. The collection has:

- **Auth** folder: Register, Login, Refresh, Logout (no token needed)
- **E2E Flow** folder: Run in order — register → login → open 2 accounts → deposit → transfer → check notifications → check audit. Each step captures IDs and tokens for the next.
- **Error Cases** folder: 401 (missing token), 422 (insufficient funds), 400 (bad input)

### Useful Commands

```bash
# All service logs
docker compose logs -f

# Specific service
docker compose logs -f transaction-service

# Rebuild and restart one service
docker compose up -d --build transaction-service

# Full reset (deletes all data)
docker compose down -v && docker compose up --build

# Connect to PostgreSQL
docker exec -it postgres-transactions psql -U banking_user -d banking_transactions

# Check Kafka consumer lag
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group audit-service --describe

# List Kafka topics
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Reset audit-service offset (rebuild from scratch)
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group audit-service \
  --reset-offsets --to-earliest --execute \
  --topic banking.audit.transactions

# Check Redis rate limit keys (see current token bucket state)
docker exec redis redis-cli keys "request_rate_limiter.*"

# Check Zipkin health
curl http://localhost:9411/health

# Check specific service metrics
curl http://localhost:8083/actuator/prometheus | grep http_server_requests
```

### Common Issues

**401 on every request:**
- Run `POST /v1/auth/login` first → captures `access_token` in collection variable
- Access tokens expire in 15 minutes → re-run login or use refresh endpoint

**429 Too Many Requests:**
- Rate limiter triggered (10 req/s per IP, burst 20)
- Wait 1-2 seconds and retry

**503 from account-service:**
- user-service might not be up yet — check Eureka at http://localhost:8761
- Circuit breaker might be OPEN — wait 10 seconds and retry

**No notifications appearing:**
- RabbitMQ may not be healthy — check http://localhost:15672
- Queue `notification.transaction.queue` message count should be 0 or low
- Check logs: `docker compose logs -f notification-service`

**Zipkin shows no traces:**
- Services need to receive at least one request before traces appear
- Check `management.tracing.sampling.probability: 1.0` is in each service's application.yml

**Prometheus targets showing DOWN:**
- Service may not be started yet — wait 60 seconds after startup
- Check that `prometheus` is in `management.endpoints.web.exposure.include`

---

*Read `SYSTEM_DOCUMENTATION.md` for the technical reference: exact configuration, database schemas, and API contracts.*
