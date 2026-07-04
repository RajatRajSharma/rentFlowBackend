# RentFlow Backend — Structure, Setup & Design Guide

This is the backend engineering guide for RentFlow. It covers three things a junior/mid developer
(and their interviewer) needs: **how the codebase is organised**, **how to set it up**, and **how to
explain the design** (LLD and HLD) under questioning.

> Java 17 · Spring Boot 3 · PostgreSQL · Redis · RabbitMQ · GraphQL (analytics only) · Websocket (payment status only)

---

## Table of Contents
1. [Project Setup — Commands](#1-project-setup--commands)
2. [Folder Structure & Why Each Folder Exists](#2-folder-structure--why-each-folder-exists)
3. [The `/docs` Folder](#3-the-docs-folder)
4. [Clean-Code Principles Baked Into the Structure](#4-clean-code-principles-baked-into-the-structure)
5. [Low-Level Design (LLD)](#5-low-level-design-lld)
6. [High-Level Design (HLD)](#6-high-level-design-hld)
7. [Cross-Question Defense Playbook](#7-cross-question-defense-playbook)

---

## 1. Project Setup — Commands

### Prerequisites
- Java 17 (`java -version` → 17.x)
- Maven 3.9+ (or use the bundled `./mvnw` wrapper)
- Docker + docker-compose

### Generate the skeleton
Use Spring Initializr (web UI at start.spring.io, or the CLI below):

```bash
# Create the project with the dependencies we need
curl https://start.spring.io/starter.zip \
  -d dependencies=web,data-jpa,security,validation,postgresql,data-redis,amqp,flyway,actuator,graphql,websocket \
  -d type=maven-project \
  -d javaVersion=17 \
  -d groupId=com.rentflow \
  -d artifactId=backend \
  -d packageName=com.rentflow \
  -d name=rentflow-backend \
  -o rentflow-backend.zip

unzip rentflow-backend.zip -d rentflow-backend
cd rentflow-backend
```

### Add the extra libraries (edit `pom.xml`)
- `redisson` — clean distributed-lock API over Redis.
- `stripe-java` (or `razorpay-java`) — payment gateway SDK.
- `testcontainers` (junit-jupiter, postgresql) — real DB in integration tests.
- `wiremock` — fake the payment gateway in tests.
- `mapstruct` + `lombok` — DTO mapping and boilerplate reduction.

### Day-to-day commands
```bash
# Bring up Postgres + Redis + RabbitMQ (no app yet) for local dev
docker-compose up -d postgres redis rabbitmq

# Run the app (Flyway auto-migrates on startup)
./mvnw spring-boot:run

# Run all tests (spins up Testcontainers)
./mvnw test

# Run only the concurrency proof test
./mvnw test -Dtest=BookingConcurrencyIT

# Build a jar
./mvnw clean package

# Full stack (app + db + redis + rabbit + frontend) in one command
docker-compose up --build
```

### First-run checklist
1. `docker-compose up -d postgres redis rabbitmq`
2. Copy `.env.example` → `.env`, fill JWT secret + Stripe test keys.
3. `./mvnw spring-boot:run` → Flyway creates tables.
4. Hit `http://localhost:8080/actuator/health` → should be `UP`.
5. Register a user, log in, get a JWT. You're live.

---

## 2. Folder Structure & Why Each Folder Exists

We use a **feature-first (package-by-feature) layout**, not package-by-layer. Everything about
*bookings* lives under `booking/`, everything about *payments* under `payment/`. This scales far
better than one giant `controllers/`, `services/`, `repositories/` split, because when you work on
a feature you touch one folder, not five.

```
backend/
├── pom.xml
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── docs/                          ← all design docs & README plans (see §3)
│   ├── README.md
│   ├── BACKEND.md                 ← this file
│   ├── hld.md
│   ├── lld.md
│   └── adr/                       ← architecture decision records
│       ├── 0001-postgres-for-ledger.md
│       ├── 0002-no-kubernetes.md
│       ├── 0003-graphql-only-for-analytics.md
│       └── 0004-websocket-scope.md
│
└── src/
    ├── main/
    │   ├── java/com/rentflow/
    │   │   │
    │   │   ├── RentflowApplication.java     ← Spring Boot entry point
    │   │   │
    │   │   ├── common/                       ← shared, cross-cutting code
    │   │   │   ├── exception/                  custom exceptions + @ControllerAdvice
    │   │   │   ├── config/                     Spring @Configuration beans
    │   │   │   ├── dto/                         shared response wrappers (ApiError, Page)
    │   │   │   ├── util/                        helpers (date ranges, money)
    │   │   │   └── audit/                       created/updated timestamp base entity
    │   │   │
    │   │   ├── security/                      ← authentication & authorization
    │   │   │   ├── JwtService.java             mint/verify tokens
    │   │   │   ├── JwtAuthFilter.java          reads token → sets SecurityContext
    │   │   │   ├── SecurityConfig.java         filter chain, route rules
    │   │   │   └── OwnershipGuard.java         "is this resource yours?" checks
    │   │   │
    │   │   ├── user/                          ← FEATURE: accounts
    │   │   │   ├── User.java                    entity
    │   │   │   ├── Role.java                    enum: USER, ADMIN
    │   │   │   ├── UserRepository.java          data access (interface)
    │   │   │   ├── UserService.java             business logic
    │   │   │   ├── AuthController.java          /auth/register, /auth/login
    │   │   │   └── dto/                          request/response records
    │   │   │
    │   │   ├── item/                          ← FEATURE: listings
    │   │   │   ├── Item.java
    │   │   │   ├── ItemRepository.java
    │   │   │   ├── ItemService.java
    │   │   │   ├── ItemController.java
    │   │   │   └── dto/
    │   │   │
    │   │   ├── booking/                       ← FEATURE: the heart
    │   │   │   ├── Booking.java
    │   │   │   ├── BookingStatus.java           enum (state machine states)
    │   │   │   ├── BookingRepository.java       incl. the overlap query
    │   │   │   ├── BookingService.java          locking + lifecycle
    │   │   │   ├── BookingStateMachine.java     legal-transition rules
    │   │   │   ├── LockManager.java             Redis distributed lock (interface)
    │   │   │   ├── BookingController.java
    │   │   │   └── dto/
    │   │   │
    │   │   ├── payment/                       ← FEATURE: money
    │   │   │   ├── Payment.java
    │   │   │   ├── PaymentRepository.java
    │   │   │   ├── PaymentService.java
    │   │   │   ├── gateway/                      PaymentGateway interface + Stripe impl
    │   │   │   │   ├── PaymentGateway.java        ← abstraction (swap gateways)
    │   │   │   │   └── StripeGateway.java
    │   │   │   ├── WebhookController.java         idempotent callback handler
    │   │   │   ├── IdempotencyService.java
    │   │   │   └── dto/
    │   │   │
    │   │   ├── ledger/                        ← FEATURE: double-entry accounting
    │   │   │   ├── LedgerEntry.java
    │   │   │   ├── LedgerRepository.java
    │   │   │   └── LedgerService.java            posts balanced debit/credit pairs
    │   │   │
    │   │   ├── settlement/                    ← FEATURE: returns & deposit release
    │   │   │   ├── Return.java
    │   │   │   ├── ReturnController.java
    │   │   │   ├── SettlementService.java
    │   │   │   └── DepositReleaseWorker.java     @Scheduled job
    │   │   │
    │   │   ├── reconciliation/                ← FEATURE: fix stuck payments
    │   │   │   └── ReconciliationWorker.java     @Scheduled poller
    │   │   │
    │   │   ├── notification/                  ← FEATURE: async emails
    │   │   │   ├── NotificationConsumer.java     listens to RabbitMQ
    │   │   │   └── EmailService.java
    │   │   │
    │   │   ├── realtime/                      ← FEATURE: websocket push
    │   │   │   ├── WebSocketConfig.java          STOMP endpoint /ws
    │   │   │   ├── RealtimeService.java          publishes to user's channel
    │   │   │   └── RedisPubSubBridge.java        fan-out across instances
    │   │   │
    │   │   ├── analytics/                     ← FEATURE: GraphQL admin dashboard
    │   │   │   ├── AnalyticsDataFetcher.java     GraphQL resolver (admin-only)
    │   │   │   └── AnalyticsRepository.java      GROUP BY aggregations
    │   │   │
    │   │   └── messaging/                     ← shared event/queue plumbing
    │   │       ├── EventPublisher.java           publish to RabbitMQ
    │   │       ├── RabbitConfig.java             exchanges, queues, bindings
    │   │       └── events/                        BookingConfirmed, PaymentSucceeded…
    │   │
    │   └── resources/
    │       ├── application.yml                  config (profiles: dev, test, prod)
    │       ├── graphql/
    │       │   └── schema.graphqls               GraphQL schema (analytics)
    │       └── db/migration/                     Flyway migrations
    │           ├── V1__users.sql
    │           ├── V2__items.sql
    │           ├── V3__bookings_and_exclusion.sql
    │           ├── V4__payments_ledger.sql
    │           └── V5__returns_webhooks.sql
    │
    └── test/
        └── java/com/rentflow/
            ├── booking/
            │   └── BookingConcurrencyIT.java     ← the 500-request proof
            ├── payment/
            │   └── WebhookIdempotencyIT.java
            └── support/                           Testcontainers base classes
```

### What each top-level piece is for
- **`common/`** — cross-cutting code that every feature uses: global exception handling, config,
  shared DTOs, utilities. Keeps features from re-implementing the same plumbing.
- **`security/`** — one place for auth. Anyone reviewing "how does login work?" goes here and nowhere else.
- **feature packages** (`user`, `item`, `booking`, `payment`, `ledger`, `settlement`,
  `reconciliation`, `notification`, `realtime`, `analytics`) — each is a self-contained vertical
  slice with its own entity, repository, service, controller, and DTOs.
- **`messaging/`** — shared queue plumbing so features publish events without knowing RabbitMQ details.
- **`resources/db/migration/`** — versioned schema. The `V3` file holds the exclusion constraint.
- **`test/`** — mirrors the main package layout; the two IT (integration test) files are your stars.

### The layering rule inside every feature
`Controller → Service → Repository`. Controllers never touch repositories directly; services never
touch HTTP. This is the single most important clean-code rule in the codebase and interviewers look
for it.

```
Controller  = HTTP in/out, validation, auth annotations. Thin.
Service     = business logic, transactions, orchestration. Fat.
Repository  = data access only. No logic.
```

---

## 3. The `/docs` Folder

`docs/` holds everything an interviewer or new teammate reads *before* touching code:

- **`README.md`** — the product/plan overview (your existing plan).
- **`BACKEND.md`** — this file: structure, setup, LLD/HLD.
- **`hld.md`** — high-level design write-up + the architecture diagram.
- **`lld.md`** — low-level design: entities, state machine, class relationships.
- **`adr/`** — **Architecture Decision Records.** One short markdown file per significant decision,
  each stating the context, the decision, and the consequences. These are the single highest-signal
  documents you can write — they prove you decide deliberately.

Why a docs folder at all? Because "documented decisions read as seniority." When an interviewer opens
your repo, `docs/` is where they learn you can *think*, not just type. Keep each file short and
honest — an ADR is often 15 lines.

---

## 4. Clean-Code Principles Baked Into the Structure

These aren't abstract ideals — each maps to a concrete choice above, and each is something you can
point at in an interview.

- **Single Responsibility** — each class does one thing. `JwtService` mints tokens; it doesn't also
  handle login flow. `LedgerService` posts entries; it doesn't talk to Stripe.
- **Package by feature, not layer** — change a feature, touch one folder. Reduces the "shotgun
  surgery" smell where one change ripples across `controllers/`, `services/`, `repositories/`.
- **Depend on abstractions** — `PaymentGateway` is an interface; `StripeGateway` is one
  implementation. Swapping to Razorpay means one new class, zero changes elsewhere. Same for
  `LockManager`. This is Dependency Inversion in practice.
- **Thin controllers, fat services** — HTTP concerns stay at the edge; logic stays testable in the
  middle; data access stays dumb at the bottom.
- **DTOs at the boundary** — never expose JPA entities directly over HTTP. Request/response `record`s
  in each feature's `dto/` package decouple your API shape from your DB shape.
- **Explicit state machine** — booking transitions live in one class with a legal-transition table,
  so illegal jumps are rejected in one place, not scattered across services.
- **Fail loudly, handle centrally** — custom exceptions bubble up to one `@ControllerAdvice` that
  turns them into clean HTTP responses. No `try/catch` soup in controllers.

---

## 5. Low-Level Design (LLD)

The LLD is about **classes, interfaces, entities, and how they relate**. The diagram below is the
one to sketch on a whiteboard. Explanation and interview talking points follow it.

*(See `docs/lld.md` for the rendered class diagram.)*

### The abstractions (interfaces) and why they exist

| Interface | Implementation(s) | Why it's an interface |
|-----------|-------------------|-----------------------|
| `PaymentGateway` | `StripeGateway` (+ `RazorpayGateway` later) | Swap payment providers without touching booking/payment logic. Inject a fake in tests. **Dependency Inversion.** |
| `LockManager` | `RedisLockManager` (+ `DbRowLockManager` alt) | The booking flow doesn't care *how* mutual exclusion is achieved. Lets you demo both locking strategies and A/B them. |
| `EventPublisher` | `RabbitEventPublisher` (+ `InMemoryPublisher` for `@Async` start) | Start simple with in-process async, upgrade to RabbitMQ later — callers never change. |
| `NotificationChannel` | `EmailChannel` (+ future SMS/push) | Add channels without editing the consumer. **Open/Closed principle.** |

### Core entities & relationships (what to say)
- **User** — one account type (`USER`) plus `ADMIN`. Owner and renter are *capabilities*, not roles.
- **Item** — has an `ownerId`; ownership is checked at the resource level, not via a role.
- **Booking** — the central entity. Carries `status` (the state machine), the date range, and a
  `@Version` column for optimistic locking.
- **Payment** — has a `UNIQUE idempotencyKey`; that constraint is what makes retries/duplicate
  webhooks safe at the database level.
- **LedgerEntry** — double-entry rows; every money movement is two balanced entries.
- **Return** — created on item return; drives deposit settlement.

### The design patterns to name out loud
- **State pattern / explicit state machine** — `BookingStateMachine` centralises legal transitions.
- **Strategy** — pricing (daily / weekly / promotional) behind a `PricingStrategy` interface.
- **Repository + Service layering** — Spring-idiomatic separation.
- **Dependency Inversion** — services depend on `PaymentGateway` / `LockManager` interfaces.
- **Idempotency key** — on every payment-mutating endpoint.
- **Optimistic locking** (`@Version`) with a pessimistic (`SELECT … FOR UPDATE`) fallback on the hot path.

### The interview script for the LLD (say this)
> "Every feature is a vertical slice: entity, repository, service, controller, DTOs. Controllers are
> thin — just HTTP and validation. Services hold the logic and own the transaction. Repositories only
> do data access. The two hard parts — locking and payments — sit behind interfaces, so they're
> swappable and I can inject fakes in tests. Booking transitions are governed by an explicit state
> machine, so illegal states are unreachable. And the ledger only writes balanced pairs, so the books
> always balance."

That paragraph, delivered calmly, *is* a senior LLD answer.

---

## 6. High-Level Design (HLD)

The HLD is about **components, data stores, and how requests flow between them at runtime** — the
box-and-arrow view of the whole system. The diagram (in `docs/hld.md`) shows the pieces; the flows
and talking points are below.

### The components (one line each)
- **Client (React SPA)** — talks REST for everything, GraphQL for the admin dashboard, and holds one
  websocket for live payment status.
- **API (Spring Boot)** — controllers, services, security filter. The monolith. Can run as N instances.
- **PostgreSQL** — source of truth. ACID transactions, row locks, the exclusion constraint, the ledger.
- **Redis** — three jobs: distributed **locks**, **cache** for hot listings, and **pub/sub** for
  websocket fan-out across instances.
- **RabbitMQ** — the async event bus (BookingConfirmed, PaymentSucceeded, ReturnRecorded).
- **Payment Gateway (Stripe)** — external; sends **webhooks** back to the API.
- **Workers** — Notification consumer (email), Deposit-release (scheduled), Reconciliation (scheduled).

### The two flows that matter
**Booking + payment (the happy path):**
1. Client → `POST /bookings` → API acquires **Redis lock** on the item.
2. API runs the **overlap query**; if free, writes `PENDING_PAYMENT` to Postgres, releases the lock.
3. Client → `POST /bookings/{id}/pay` → API asks the **gateway** for a payment intent (idempotency key).
4. Gateway → **webhook** `succeeded` → API flips booking to `CONFIRMED`, writes **ledger** entries.
5. API publishes `PaymentSucceeded` to **RabbitMQ** *and* to **Redis pub/sub**.
6. Notification worker emails both parties; the instance holding the client's **websocket** pushes
   the live "confirmed" update.

**Why each store is where it is** (the sentence per component that wins the interview):
- *Postgres for money* — "Bookings and the ledger need ACID and row locking. A document store would
  fight this."
- *Redis for locks* — "An in-process lock breaks the moment I run two API instances. A Redis lock is
  shared, so it's correct under horizontal scaling."
- *RabbitMQ for async* — "Email and settlement are slow and retryable. They don't belong on the
  request thread, so they go on a queue with retry and dead-letter semantics."
- *Redis pub/sub for websockets* — "The webhook can land on any instance, but the user's socket is on
  one specific instance. Pub/sub fans the event to all instances so the right one delivers it."

*(See `docs/hld.md` for the rendered architecture diagram.)*

### How to explain an HLD without rambling
Don't narrate every box. **Trace one request through the whole system** and let each component
introduce itself as the request touches it:
> "Client hits the API, which grabs a Redis lock, checks Postgres for an overlap, writes the booking,
> then calls Stripe. Stripe's webhook comes back, we write the ledger, and we fire one event that fans
> out two ways — RabbitMQ for the email, Redis pub/sub for the live update."

One sentence per hop. That demonstrates you understand *flow*, which is what HLD questions are really testing.

---

## 7. Cross-Question Defense Playbook

Interviewers probe by pushing on your choices. For each likely challenge, here's the crisp answer.
The pattern for all of them: **acknowledge the trade-off, give your reason, name the condition under
which you'd choose differently.** That structure is what reads as senior.

| They ask | Short answer | The trade-off sentence |
|----------|--------------|------------------------|
| "Why a monolith, not microservices?" | The domain is cohesive; one deployable is simpler to run and reason about. | "I'd split along a scaling or ownership boundary once a piece genuinely diverged — not before, or I'd pay distributed-systems tax for no benefit." |
| "Optimistic or pessimistic locking?" | Pessimistic (`SELECT … FOR UPDATE`) on the booking hot path where contention is real; optimistic (`@Version`) elsewhere. | "Pessimistic when conflicts are likely and short; optimistic when they're rare and I'd rather not hold a lock." |
| "Redis lock — what if it expires mid-work?" | Use a lease long enough for the critical section, and the DB is still the final authority. | "The exclusion constraint in Postgres is my real guarantee; the Redis lock is an optimisation to avoid wasted work. Even if the lock fails, the DB rejects the overlap." |
| "Why not just DB locks then, skip Redis?" | You can — and I have a `DbRowLockManager` behind the same interface. | "Redis lock scales the *coordination* out of the DB under high contention; for this scale either works, which is why it's an interface." |
| "Webhook arrives twice — what happens?" | The `processed_webhooks` table + unique idempotency key make the second one a no-op. | "Gateways guarantee at-least-once, not exactly-once, so the handler must be idempotent by design." |
| "Gateway times out — did the charge happen?" | Don't assume. The reconciliation job polls the gateway and reconciles state. | "Unknown ≠ failed. I converge to the truth via a poll rather than guessing and corrupting state." |
| "How do the books always balance?" | Every movement is two balanced ledger entries; sum(debit) == sum(credit) invariant. | "I can prove correctness by querying that the ledger nets to zero per booking." |
| "Your webhook hit instance A but the socket is on B?" | Redis pub/sub fans the event to all instances; the one holding the socket delivers. | "An in-memory socket registry breaks under scale-out; pub/sub is what makes real-time multi-instance-safe." |
| "Why GraphQL only for analytics?" | Analytics = varied nested reads (GraphQL's strength). Transactions = cacheable, resource-oriented (REST's strength). | "I pick the tool by fit. GraphQL everywhere would cost me HTTP caching and clean webhook semantics for no gain." |
| "Would you use Kubernetes?" | Not for one service. | "I'd reach for K8s with multiple services needing auto-scaling and self-healing. This doesn't, so adding it is over-engineering." |
| "Why Postgres, not MongoDB?" | Money + bookings need ACID, row locks, relational integrity (the ledger). | "A document store would fight the problem; the relational model *is* the ledger." |
| "How do you avoid a dual-write bug (DB says booked, queue never got the event)?" | The outbox pattern: write the event to an `outbox` table in the same transaction, relay publishes it. | "The event fires if and only if the DB commit succeeded — no lost or phantom events." |
| "One USER role — how is an owner blocked from editing another's item?" | Resource-level authz: allowed iff `item.ownerId == currentUser.id`, checked in `OwnershipGuard`. | "Role tells you *what kind* of user; ownership tells you *whose* resource. You need both." |
| "How would you scale reads?" | Cache hot listings in Redis, invalidate on write; index the availability hot path. | "Reads dominate browsing; the write path is already protected by locks + the constraint." |

### The meta-answer that impresses most
When cornered on any "why did you build it this way?" — the winning move is to show you know where
the design *bends*:
> "This is deliberately a well-factored monolith with the two genuinely hard problems — concurrency
> and payment correctness — solved properly and proven with tests. I kept optional tech (GraphQL,
> websockets) scoped to the one place each fits, and I can tell you exactly when I'd add the things I
> left out. The design isn't maximal; it's *appropriate*, and I can defend every piece."

That answer works because it's true — the whole project was built to make it true.

---

## Quick reference: what to read before an interview
1. This file's **§5 interview script** (LLD) and **§6 flow narration** (HLD).
2. The **§7 playbook** — read it until each trade-off sentence is automatic.
3. The `adr/` files — each is a decision you can defend in 30 seconds.
4. The concurrency test result — your single highest-impact talking point.
