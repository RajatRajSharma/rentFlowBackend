# RentFlow — Backend

An equipment-rental marketplace backend. Java · Spring Boot · PostgreSQL.

> **The product in one line:** a rental marketplace that never double-books an item under
> concurrent requests, and never loses or double-charges money when payments fail or webhooks
> arrive twice.

| | |
|---|---|
| **Runs at** | `http://localhost:8080` |
| **Status** | Weeks 1–2 done, Week 3 nearly. **500 concurrent requests for one slot → exactly 1 booking** ([proof](#31-the-concurrency-proof)); **100 concurrent pay requests → exactly 1 set of charges** ([proof](#32-the-idempotency-proof)); **every payment posts a balanced double-entry movement** ([proof](#33-the-books-balance)). Failure scenarios next. |
| **API reference** | [docs/API_DOCS.md](docs/API_DOCS.md) |
| **Request flow diagrams** | [docs/API_FLOW.md](docs/API_FLOW.md) |
| **Full product/system design** | [docs/README.md](docs/README.md) |

---

## Table of Contents
1. [What this product is](#1-what-this-product-is)
2. [What we are trying to achieve](#2-what-we-are-trying-to-achieve)
3. [What works today](#3-what-works-today)
4. [Run it on localhost](#4-run-it-on-localhost)
5. [Verify the install](#5-verify-the-install)
6. [The stack — what, why, justified](#6-the-stack--what-why-justified)
7. [Architecture](#7-architecture)
8. [Project layout](#8-project-layout)
9. [Command cheat sheet](#9-command-cheat-sheet)
10. [Troubleshooting](#10-troubleshooting)
11. [Documentation index](#11-documentation-index)

---

## 1. What this product is

RentFlow is a **peer-to-peer marketplace for renting physical equipment** — cameras, drones, power
tools, camping gear.

A user lists an item with a **daily rate** and a **refundable security deposit**. Another user books
it for a date range, pays rent + deposit up front, collects the item, returns it, and gets the
deposit back — in full, or partially if the item comes back damaged.

**One account type does everything.** Like YouTube — every account *can* upload, most just watch —
every RentFlow user can both list and rent. Whether you may edit a given item is decided by
**ownership of that row**, not by a separate "owner" role. A single **ADMIN** oversees the platform.

The three actors:

| Actor | Does |
|-------|------|
| **Owner** (any user) | Lists an item, sets rate + deposit, approves returns, receives payout |
| **Renter** (any user) | Browses, books a date range, pays, returns, gets deposit back |
| **Admin** | Platform oversight and analytics |

---

## 2. What we are trying to achieve

This is a portfolio project built to demonstrate **senior backend engineering**, so the domain was
chosen deliberately: rentals force you to solve two genuinely hard problems that most CRUD projects
never touch.

### Problem 1 — Concurrency: never double-book
Two people click "Book" on the same camera for the same weekend at the same millisecond. A naive
`if (available) { book() }` check-then-act **will** sell the same dates twice, because both requests
read "available" before either writes.

**Solution:** a Redis distributed lock per item, plus a Postgres exclusion constraint on the date
range as the last line of defence, plus optimistic locking (`@Version`) on the row. The database is
made *incapable* of storing overlapping bookings — application logic is the fast path, the constraint
is the guarantee.

### Problem 2 — Payment correctness: never lose or double-charge money
Payment gateways fail mid-flight, time out after actually succeeding, and deliver the same webhook
three times. Money must survive all of it.

**Solution:** every money movement is an **append-only ledger entry** (nothing is ever mutated),
every gateway call carries an **idempotency key**, webhooks are deduplicated by event id, and a
reconciliation job compares our ledger against the gateway's records on a schedule.

### Why these two
They are exactly what senior backend interviews probe: *"how do you prevent a race condition across
instances?"* and *"how do you keep money correct when the network lies to you?"* Everything else in
this project — auth, CRUD, async, realtime — is scaffolding that lets those two problems exist.

### Non-goals
Not building: chat, reviews/ratings, search relevance ranking, mobile apps, multi-currency,
or a real payment processor integration in production mode. See [docs/README.md](docs/README.md)
§14 for what was considered and deliberately scoped out.

---

## 3. What works today

✅ **Live**

| Feature | Endpoints |
|---------|-----------|
| Health & version | `GET /actuator/health`, `/actuator/info`, `/api/version` |
| Registration | `POST /auth/register` — BCrypt hashing, duplicate-email guard |
| Login | `POST /auth/login` — returns a 24 h JWT |
| Browse items | `GET /items`, `GET /items/{id}` — public |
| List an item | `POST /items` — authenticated, owner taken from the token |
| Edit an item | `PUT /items/{id}` — authenticated **and** owner-only |
| Check availability | `GET /items/{id}/availability?from=&to=` — public |
| Book an item | `POST /bookings` — Redis lock + row lock + DB constraint, created in `PENDING_PAYMENT` |
| Cancel a booking | `POST /bookings/{id}/cancel` — renter only, frees the dates immediately |
| My bookings / my items' bookings | `GET /bookings/me`, `GET /items/mine/bookings` |
| **No double-booking** | Three layers, proven by a 500-thread test — see below |
| Booking lifecycle | `BookingStateMachine` — one transition table, illegal jumps rejected centrally |
| Pay for a booking | `POST /bookings/{id}/pay` — fee + deposit intents, `Idempotency-Key` required |
| **No double-charging** | Replay check + distributed lock + `UNIQUE` key, proven by a 100-thread test — see below |
| Gateway abstraction | `PaymentGateway` with a Stripe (test-mode) and a keyless `FakeGateway` impl |
| Payment webhooks | `POST /webhooks/payments` — HMAC signature verify, replay window, `processed_webhooks` dedupe |
| **Duplicate delivery = no-op** | `INSERT … ON CONFLICT DO NOTHING` claims the event id *before* the work, in the same transaction |
| **Double-entry ledger** | Every movement is two balanced halves; nothing unbalanced can be written |
| Failure scenarios | Declines, gateway outages and timeouts covered by WireMock tests against the real `StripeGateway` |
| Domain events | `EventPublisher` + `PaymentSucceeded` / `BookingConfirmed`, delivered only after the transaction commits |
| Uniform errors | one `ApiError` JSON shape for every failure, including security rejections |
| Schema migrations | Flyway `V1__users.sql` … `V5__returns_webhooks.sql` |
| Tests | 66 unit + 29 integration (real Postgres + Redis), all green |

### 3.1 The concurrency proof

The headline claim of this project, measured rather than asserted.
[BookingConcurrencyIT](src/test/java/com/rentflow/booking/BookingConcurrencyIT.java) releases 500
threads at once, all requesting the same item for the same dates:

```
==================== CONCURRENCY PROOF ====================
 requests fired      : 500
 succeeded           : 1
 rejected (conflict) : 499
 rejected (lock busy): 0
 unexpected errors   : 0
 bookings in database: 1
===========================================================
```

Three independent layers produce that result:

| # | Layer | What it stops |
|---|-------|---------------|
| 1 | **Redis distributed lock** on `item:{id}` (Redisson `RLock`) | Two requests entering the check at once — across *all* instances, which `synchronized` cannot do |
| 2 | **`SELECT … FOR UPDATE`** on the item row | The same, at the database, if Redis is down or an instance skips it |
| 3 | **Postgres `EXCLUDE USING gist`** constraint | Storing an overlapping row *at all*, whatever the application does |

A second test repeats the run with layer 1 bypassed entirely and still gets exactly one booking; a
third confirms locking doesn't reject *legitimate* non-overlapping bookings. The lock is acquired
**outside** the transaction and released only after commit — the reverse ordering is the classic bug
that reintroduces the race.

### 3.2 The idempotency proof

The same problem shape as above — two writers, one permitted outcome — but with money instead of
dates. [PaymentIdempotencyIT](src/test/java/com/rentflow/payment/PaymentIdempotencyIT.java) fires
100 simultaneous pay requests carrying **one** `Idempotency-Key`:

```
================= PAYMENT IDEMPOTENCY PROOF =================
 pay requests fired  : 100
 responses returned  : 100
 failures            : 0
 payment rows in db  : 2
 distinct payment ids: 2
 total charged       : 7000.00
=============================================================
```

Two rows — one ₹2000 fee, one ₹5000 deposit — and all 100 callers were handed *the same two
charges*. Again three layers, each covering the others' hole:

| # | Layer | What it stops | Hole when alone |
|---|-------|---------------|-----------------|
| 1 | **Replay check** | The ordinary retry, with no lock and no work | Two requests arrive at once and both find nothing |
| 2 | **Distributed lock** on `pay:{bookingId}` | Concurrent attempts racing each other | Redis is down |
| 3 | **`UNIQUE(idempotency_key)`** | A duplicate row, whatever else broke | Returns an error, not the caller's original result |

Plus one rule that no idempotency key can express: **a booking has at most one live set of
charges.** Without it, a renter retrying from a fresh browser tab — new key, same booking — gets a
second fee and a second deposit, and both can be paid. The keys differ, so nothing collides.

The ordering matters as much as the layers: payment rows are written **before** the gateway is
called, so the key is claimed in our database before any money can move. Charge-then-write means a
crash in between takes the customer's money with no record of it.

### 3.3 The books balance

A `payments` table tells you what you **charged**. It cannot tell you what you **owe** — and after
a rental, part of that ₹5000 deposit may be the owner's (damage) and part the renter's (refund).
A single amount column has nowhere to put that. Double-entry does, and it comes with a property no
single-column design has: correctness is **provable** by summing both sides.

Every payment success posts two balanced halves. `PaymentWebhookIT` asserts this exact output:

```
==================== LEDGER (booking 156) ====================
 RENTER_CASH    debit  2000.00  credit     0.00     fee paid
 OWNER_PAYABLE  debit     0.00  credit  2000.00
 RENTER_CASH    debit  5000.00  credit     0.00     deposit held
 DEPOSIT_HELD   debit     0.00  credit  5000.00
 total debit : 7000.00
 total credit: 7000.00
 balanced    : true
============================================================
```

The two credits land in **different accounts**, and that's the whole design: both are cash sitting
with us, but only `OWNER_PAYABLE` is ever ours to pay out. `LedgerService` validates before it
saves, so an unbalanced ledger is never a bug you find later — it's a transaction that never
committed.

### 3.4 Duplicate webhooks change nothing

Every gateway delivers at-least-once — Stripe retries for up to three days without a 2xx — so "we
got this event twice" is not an edge case, it's Tuesday. Without a guard, the second delivery posts
a second ledger movement and the books silently stop balancing.

The handler **claims the event id before it acts**, with `INSERT … ON CONFLICT DO NOTHING`. The
insert *is* the check — a check-then-insert would be the same check-then-act race the booking
engine exists to avoid. The claim and the work share one transaction, so a failure rolls back both
and the gateway's retry gets a real second chance instead of hitting a marker for work that never
happened.

Signatures are the real Stripe scheme: HMAC-SHA256 over `{timestamp}.{raw body}`, constant-time
compare, ±300s replay window checked in both directions. The `FakeGateway` signs and verifies
**identically** — a fake that waved signatures through would leave the one security-critical path
here untested, on a public endpoint where a hole lets anyone confirm their own bookings for free.

⏳ **Not built yet** — reconciliation for webhooks that never arrive (a payment sits `PENDING`
forever today), refunds/settlement and the return flow, scheduled jobs to expire abandoned
`PENDING_PAYMENT` bookings and start `CONFIRMED` ones, async notifications, WebSocket live status,
GraphQL admin analytics. Roadmap in [docs/PLAN.md](docs/PLAN.md).

---

## 4. Run it on localhost

### 4.1 Prerequisites

| Tool | Why | Check |
|------|-----|-------|
| **JDK 17+** | compiles and runs the app | `java -version` |
| **Docker Desktop** | runs Postgres, Redis, RabbitMQ, pgAdmin | `docker --version` |
| Maven | **not needed** — the repo ships the `mvnw` wrapper | — |

### 4.2 Clone and enter

```bash
git clone <repo-url> rentFlowBackend
cd rentFlowBackend
```

### 4.3 Create your `.env` (optional in dev)

`application.yml` already defaults to working dev values, so the app runs without a `.env`. Create
one when you want to override anything:

```bash
cp .env.example .env
```

```bash
DB_URL=jdbc:postgresql://localhost:5433/rentflow    # host port 5433, not 5432 — see note below
DB_USER=rentflow
DB_PASSWORD=rentflow
REDIS_HOST=localhost
RABBIT_HOST=localhost
JWT_SECRET=change-me-to-a-long-random-string-at-least-32-chars
```

> **Why port 5433?** The Docker Postgres is mapped `5433:5432` so it doesn't collide with a native
> PostgreSQL already using 5432 on the dev machine. Inside Docker it's still 5432.

> `.env` is git-ignored. `JWT_SECRET` must be **at least 32 characters** — HMAC-SHA256 needs a
> 256-bit key and the app will refuse to start otherwise.

### 4.4 Start the infrastructure

```bash
docker compose up -d
```

Brings up four containers:

| Container | Port | What it is |
|-----------|------|------------|
| `rentflow-postgres` | `5433` | the database |
| `rentflow-redis` | `6379` | distributed locks / cache |
| `rentflow-rabbitmq` | `5672`, UI `15672` | async message broker (`guest`/`guest`) |
| `rentflow-pgadmin` | `5050` | web GUI for Postgres — no login, server pre-registered |

Wait for Postgres to be healthy: `docker compose ps` should show `healthy`.

### 4.5 Run the app

```bash
# macOS / Linux / Git Bash
./mvnw spring-boot:run

# Windows PowerShell
.\mvnw.cmd spring-boot:run
```

On startup Flyway applies `V1__users.sql` and `V2__items.sql` automatically, then Hibernate
validates the entities against the real schema. Server is up when you see
`Tomcat started on port 8080`.

---

## 5. Verify the install

```bash
# 1. Is it alive? Expect "status":"UP" with db/redis/rabbit all UP
curl http://localhost:8080/actuator/health

# 2. Our own controller layer
curl http://localhost:8080/api/version

# 3. Register
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Rajat","email":"rajat@example.com","password":"supersecret123"}'

# 4. Log in and keep the token
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"rajat@example.com","password":"supersecret123"}'

# 5. Create a listing with that token
curl -X POST http://localhost:8080/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <paste accessToken>" \
  -d '{"title":"Sony A7 III","description":"2 batteries","dailyRate":1500.00,"depositAmount":20000.00}'

# 6. Browse — no auth needed
curl http://localhost:8080/items
```

On PowerShell use `curl.exe` (plain `curl` is an alias for `Invoke-WebRequest` and won't accept
these flags).

Full endpoint reference with every field and error case: [docs/API_DOCS.md](docs/API_DOCS.md).

Inspect the database at **http://localhost:5050** (pgAdmin, no login — the RentFlow server is
pre-registered; enter password `rentflow` on first connect).

---

## 6. The stack — what, why, justified

Every technology here is present because the product **needs** it. If an interviewer asks
"why is this here?", these are the answers.

### Core

| Tech | Used for | Why this and not the alternative |
|------|----------|----------------------------------|
| **Java 17 + Spring Boot 4** | the whole application | Mature ecosystem for transactional, money-handling systems. `@Transactional` gives declarative transaction boundaries — the single most important tool when correctness of money matters. |
| **Spring Web MVC** | REST controllers | Blocking/servlet model, which pairs naturally with JPA. WebFlux was rejected: it needs reactive DB drivers (R2DBC) and would fight JPA for no benefit at our concurrency level. |
| **Maven + `mvnw` wrapper** | build & dependencies | Wrapper means nobody needs Maven installed and everyone builds with the identical version. |
| **PostgreSQL 16** | source of truth | Chosen specifically for two features we depend on: **exclusion constraints with `tstzrange`** (the database itself refuses overlapping bookings) and true ACID transactions. MongoDB cannot express that constraint — the double-booking guarantee would have to live in application code, which is exactly the thing we don't trust. |
| **Spring Data JPA + Hibernate** | persistence | Removes boilerplate SQL; derived queries (`findByEmail` → `WHERE email = ?`). Also gives `@Version` **optimistic locking**, which is our defence against lost updates on concurrent edits. Raw SQL stays available for the few hot paths that need it. |
| **Flyway** | schema migrations | Schema is versioned, ordered, and applied identically on every machine and every environment. Hibernate runs `ddl-auto: validate` — Flyway owns the schema, Hibernate only checks it matches. Auto-generated DDL is never trustworthy in production. |

### Security

| Tech | Used for | Why |
|------|----------|-----|
| **Spring Security** | the filter chain, route rules | One declarative table of who can call what, instead of guards scattered per route. |
| **JWT (`jjwt` 0.12)** | stateless auth | The token carries `uid`/`email`/`role` signed with HMAC-SHA256, so any instance can authenticate a request **without a session store or DB lookup** — a prerequisite for running more than one instance. Cost: no instant revocation; a token is valid until it expires (24 h). Accepted trade-off at this stage. |
| **BCrypt** | password hashing | Per-password salt plus a deliberately slow work factor, so a leaked table can't be brute-forced cheaply. Never store or log the raw password — the DB only ever sees the hash. |
| **`OwnershipGuard`** | resource-level authorization | Roles answer *what kind of user*; ownership answers *whose row*. Every write path needs both. Centralising the check means one place to change the rule and one place to test it. |
| **Bean Validation (`@Valid`)** | input validation | Constraints declared on the DTO record, enforced before any business logic runs, and translated into a uniform 400 with a `field → message` map. |

### Infrastructure (installed and running, wired in upcoming weeks)

| Tech | Used for | Why it's non-negotiable for this product |
|------|----------|------------------------------------------|
| **Redis** + **Redisson** | distributed lock on booking (`RLock`); caching; pub/sub fan-out | A JVM `synchronized` block only locks **one instance**. The moment you run two, it guarantees nothing. Redis gives a lock that all instances respect — the difference between a toy and a system that survives horizontal scaling. Redisson's `RLock` adds the two timings that matter: a **wait** so callers fail fast under contention, and a **lease** so a crashed instance can't hold an item locked forever. |
| **RabbitMQ** | async work: emails, invoices, deposit-refund jobs | Sending an email inside the booking transaction makes the user wait on SMTP and lets a mail failure roll back a valid booking. Publishing an event instead keeps the request fast and makes the side-effect retryable, with a dead-letter queue for what still fails. |
| **WebSocket / STOMP** | live payment-status push | Payment confirmation is asynchronous — it arrives by webhook seconds later. Polling from the browser is wasteful and laggy; a push tells the user "payment confirmed" the instant the webhook lands. |
| **GraphQL** | admin analytics dashboard only | An analytics dashboard asks wildly varying, deeply nested questions ("revenue by category by month, with top owners"). REST would need a new endpoint per widget or return massively over-fetched payloads. GraphQL lets one endpoint serve them all. **Deliberately not used for the transactional API** — REST's explicit contract and cacheability are worth more there. Using the right tool in the right place is the point. |
| **Testcontainers** | integration tests against real Postgres + Redis | H2 has no `EXCLUDE USING gist`. The double-booking test would pass against a schema that silently dropped the single most important constraint in the system — a test that can't fail the way production fails is worse than no test. |
| **Docker Compose** | local infrastructure | One command brings up four services identically on any machine, instead of four manual installs. |
| **pgAdmin** | DB inspection | Browser GUI for the database during development. Dev-only convenience. |
| **Spring Actuator** | `/actuator/health`, `/info` | Real liveness/readiness reporting per dependency (db, redis, rabbit) — what a load balancer or Kubernetes probe would consume. |

### Deliberate design decisions

| Decision | Reason |
|----------|--------|
| **DTOs at every boundary, never expose entities** | The API contract and the DB schema evolve independently, and a field like `passwordHash` cannot leak by accident. `UserResponse.from()` / `ItemResponse.from()` are the only conversion points. |
| **Thin controller, fat service, dumb repository** | Business rules live in exactly one layer. A controller that knows business rules, or a service that knows HTTP status codes, means the code is in the wrong place. |
| **Package by feature, not by layer** | Everything about items lives in `item/`. A change to items touches one folder instead of three. |
| **`BigDecimal` for all money, `NUMERIC(12,2)` in the DB** | `double`/`float` accumulate binary rounding error. Money is never floating point. |
| **Owner id read from the token, never from the request body** | There is no field an attacker could tamper with to create or edit something as someone else. |
| **Central `@RestControllerAdvice`** | Every error returns the same JSON shape, and controllers stay free of `try/catch`. Security rejections are formatted into the same shape by `RestAuthEntryPoint`, so the filter chain isn't an exception to the rule. |
| **Distributed lock acquired outside the transaction** | Releasing it inside — while the commit is still in flight — lets the next writer read stale data. `BookingService` uses an explicit `TransactionTemplate` so the nesting is visible rather than hidden in an annotation. |
| **Explicit state machine for booking status** | Illegal states become unreachable, the rules read as one table, and there's exactly one thing to test and one place to change when the lifecycle grows. |
| **Prices snapshot onto the booking at creation** | An owner editing their daily rate must never change what an existing renter already agreed to pay. |
| **Append-only ledger for money** *(upcoming)* | A mutable `balance` column loses history and hides bugs. Append-only entries mean every rupee is traceable and any state is re-derivable. |

---

## 7. Architecture

```mermaid
flowchart LR
    CL["Client<br/>React / Postman"] -->|"HTTP + JWT"| API

    subgraph APP["Spring Boot app · :8080"]
        direction TB
        API["Security filter chain<br/>JwtAuthFilter + SecurityConfig"]
        CT["Controllers<br/>thin"]
        SV["Services<br/>business logic + @Transactional"]
        RP["Repositories<br/>Spring Data JPA"]
        API --> CT --> SV --> RP
    end

    RP --> PG[("PostgreSQL :5433<br/>source of truth")]
    SV -->|"distributed lock<br/>lock:item:id"| RD[("Redis :6379")]
    SV -.->|"publish events"| MQ[["RabbitMQ :5672"]]
    MQ -.->|"consume"| W["Async workers<br/>email · invoice · refund"]
    APP -.->|"live status push"| CL

    classDef soon stroke-dasharray: 4 4
    class MQ,W soon
```

Dashed = installed and running, wired in upcoming weeks.

Layer contract and per-endpoint request traces: [docs/API_FLOW.md](docs/API_FLOW.md).

---

## 8. Project layout

```
rentFlowBackend/
├── docker-compose.yml          postgres · redis · rabbitmq · pgadmin
├── pom.xml                     dependencies + build
├── mvnw / mvnw.cmd             Maven wrapper (no Maven install needed)
├── .env.example                template — copy to .env
│
├── docs/
│   ├── README.md               full product + system design
│   ├── API_DOCS.md             endpoint reference
│   ├── API_FLOW.md             request-flow diagrams
│   ├── PLAN.md                 5-week build plan
│   ├── SETUP.md                how the project was scaffolded
│   ├── BACKEND.md              backend conventions
│   └── Initial_hld.md / Initial_lld.md
│
└── src/main/
    ├── java/com/rentflow/
    │   ├── RentflowBackendApplication.java     entry point
    │   ├── common/                             VersionController · audit · config · exceptions
    │   │   └── lock/                           LockManager · RedisLockManager (booking + payment)
    │   ├── security/                           SecurityConfig · JwtAuthFilter · JwtService
    │   │                                       AuthenticatedUser · OwnershipGuard
    │   ├── user/                               AuthController · UserService · User · dto/
    │   ├── item/                               ItemController · ItemService · Item · dto/
    │   ├── booking/                            the heart — BookingController · BookingService
    │   │                                       BookingStateMachine · Booking · dto/
    │   ├── payment/                            the money — PaymentService · IdempotencyService
    │   │   │                                   WebhookService · WebhookController
    │   │   └── gateway/                        PaymentGateway · StripeGateway · FakeGateway
    │   │                                       StripeStyleWebhooks (HMAC verify)
    │   └── ledger/                             LedgerService · LedgerEntry · LedgerAccount
    └── resources/
        ├── application.yml                     config: db · redis · rabbit · jwt · lock · payment
        └── db/migration/                       V1__users.sql · V2__items.sql
                                                V3__bookings_and_exclusion.sql
                                                V4__payments_ledger.sql
                                                V5__returns_webhooks.sql
```

---

## 9. Command cheat sheet

```bash
docker compose up -d              # start postgres, redis, rabbitmq, pgadmin
docker compose ps                 # check container health
docker compose logs -f postgres   # tail one service
docker compose down               # stop everything (data survives in the volume)
docker compose down -v            # stop AND wipe the database volume

./mvnw spring-boot:run            # run the app (Flyway migrates on startup)
./mvnw test                       # fast unit tests only (seconds, no Docker)
./mvnw verify                     # everything, including the concurrency + idempotency proofs
./mvnw clean package              # build a runnable jar in target/
```

Unit tests (`*Test`) run under surefire; integration tests (`*IT`) run under failsafe in the
`verify` phase, so day-to-day `mvnw test` stays fast and doesn't need Docker.

On PowerShell substitute `.\mvnw.cmd` for `./mvnw`.

Useful URLs:

| URL | What |
|-----|------|
| http://localhost:8080/actuator/health | app + dependency health |
| http://localhost:8080/api/version | app version |
| http://localhost:5050 | pgAdmin — inspect the database |
| http://localhost:15672 | RabbitMQ management UI (`guest`/`guest`) |

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| App exits with a connection refused on startup | Containers aren't up yet | `docker compose ps` — wait for postgres `healthy`, then rerun |
| `Port 5433 already allocated` | Something else holds the port | Change the host side of `5433:5432` in `docker-compose.yml` **and** `DB_URL` |
| Startup fails with a key-length error | `JWT_SECRET` shorter than 32 chars | Use a longer secret — HMAC-SHA256 needs 256 bits |
| `Schema validation: missing table` | Flyway didn't run, or the volume holds an old schema | `docker compose down -v` then `docker compose up -d` to start clean |
| `mvnw verify` says "Could not find a valid Docker environment" | Some Docker Desktop builds expose an engine proxy that docker-java can't negotiate with, so Testcontainers can't start | Nothing to do — the test base falls back to the running docker-compose services and its own `rentflow_test` database. Just make sure `docker compose up -d` is running. |
| Integration tests fail to connect at all | Neither Testcontainers nor compose is up | `docker compose up -d`, wait for postgres `healthy` |
| `curl --version` errors in PowerShell | `curl` is an alias for `Invoke-WebRequest` | Use `curl.exe` |
| `mvn: command not found` | Maven isn't installed globally | Use the wrapper: `./mvnw` or `.\mvnw.cmd` |
| Timezone error from PostgreSQL | Windows reports the deprecated `Asia/Calcutta` alias | Already handled — `pom.xml` forces `-Duser.timezone=Asia/Kolkata` |

---

## 11. Documentation index

| Doc | Read it for |
|-----|-------------|
| [docs/README.md](docs/README.md) | Full product spec, HLD, data model, concurrency and payment design |
| [docs/API_DOCS.md](docs/API_DOCS.md) | Every endpoint: request/response shapes, validation, status codes |
| [docs/API_FLOW.md](docs/API_FLOW.md) | Mermaid diagrams of how a request travels through the code |
| [docs/PLAN.md](docs/PLAN.md) | Day-by-day 5-week build plan with progress |
| [docs/SETUP.md](docs/SETUP.md) | How the project was scaffolded from scratch, and why each choice |
| [docs/BACKEND.md](docs/BACKEND.md) | Backend conventions and package structure |
| [docs/Initial_hld.md](docs/Initial_hld.md) · [docs/Initial_lld.md](docs/Initial_lld.md) | Original high- and low-level design notes |
