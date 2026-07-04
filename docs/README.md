# RentFlow — Equipment Rental Marketplace (Backend-Heavy)

A production-flavoured rental platform built to demonstrate senior-level backend engineering:
concurrency control, payment handling with failure recovery, role-based auth, async processing,
real-time updates, and clean LLD/HLD. Built in **Java + Spring Boot** with a **React** frontend.

Every "heavy" technology below is included because the project *needs* it — not for decoration.
An interviewer should be able to ask "why is this here?" and get a real answer.

> **The one-line hook:**
> "A rental marketplace that prevents double-booking under concurrent requests and never loses
> or double-charges money, even when payments fail or webhooks arrive twice."

That sentence names the two hard problems — **concurrency** and **payment correctness** —
which are exactly what senior backend interviews probe.

---

## Table of Contents
1. [What It Is](#1-what-it-is)
2. [Core Hard Problems](#2-core-hard-problems)
3. [Tech Stack](#3-tech-stack)
4. [High-Level Design](#4-high-level-design-hld)
5. [Data Model & Schema](#5-data-model--schema)
6. [API Surface (REST)](#6-api-surface-rest)
7. [GraphQL — the Admin Analytics Dashboard](#7-graphql--the-admin-analytics-dashboard)
8. [Websockets — Live Payment-Status Push](#8-websockets--live-payment-status-push)
9. [Low-Level Design](#9-low-level-design-lld)
10. [The Concurrency Problem, Solved](#10-the-concurrency-problem-solved)
11. [Payments & Failure Scenarios](#11-payments--failure-scenarios)
12. [Async Processing](#12-async-processing)
13. [Roles & Authorization](#13-roles--authorization)
14. [Considered but Scoped Out](#14-considered-but-scoped-out)
15. [Build Order](#15-build-order)
16. [Frontend Weekly Plan](#16-frontend-weekly-plan)
17. [Backend Weekly Plan](#17-backend-weekly-plan)
18. [Testing Strategy](#18-testing-strategy)
19. [Local Setup](#19-local-setup)
20. [What You Need to Learn](#20-what-you-need-to-learn-per-topic)
21. [Interview Questions This Prepares You For](#21-interview-questions-this-prepares-you-for)
22. [How to Present It](#22-how-to-present-it)

---

## 1. What It Is

RentFlow is a marketplace where users list rentable equipment (cameras, drones, tools, etc.)
and other users book items for a date range, pay a rental fee plus a refundable security deposit,
and get the deposit back (fully or partially) on return based on item condition.

**One account type does everything.** Like YouTube — every account *can* upload, most just watch —
every RentFlow **USER** can both list items and book items. Whether you can edit a given item is
decided by whether you own it, not by a separate role. A single **ADMIN** oversees the platform
via an analytics dashboard.

---

## 2. Core Hard Problems

| # | Problem | Why it's a senior signal |
|---|---------|--------------------------|
| 1 | **Date-range overlap prevention** | Two renters must not book the same item for overlapping dates, even if they submit in the same millisecond. Needs locking + a correct overlap query. |
| 2 | **Concurrent booking safety** | The classic race condition. Solved with pessimistic/optimistic locking or a Redis distributed lock. |
| 3 | **Payment failure handling** | Card declines, gateway timeouts, and "success but no callback" must all leave the system consistent. |
| 4 | **Idempotency** | A retried "confirm booking" or a duplicate webhook must not double-charge or double-book. |
| 5 | **Deposit hold + partial refund** | Money is held, then released or partially claimed on return. A real ledger/settlement flow. |
| 6 | **Async processing** | Notifications, webhook processing, and deposit-release jobs run off the request thread. |
| 7 | **Resource-level authorization** | A user may only edit items they own. An authz check beyond simple roles. |
| 8 | **Real-time delivery across instances** | Pushing a live status update to the right user when any backend instance might hold the webhook. |

---

## 3. Tech Stack

| Layer | Choice | Why this, defensibly |
|-------|--------|----------------------|
| Language/Framework | **Java 17 + Spring Boot 3** | Target-language artifact; industry-standard backend. |
| Frontend | **React + TypeScript** | Reuses existing strength; clean SPA. |
| Primary DB | **PostgreSQL** | ACID transactions + row locking are essential for money and bookings. |
| Cache + Locks + Pub/Sub | **Redis** | Distributed locks (booking), caching (listings), and pub/sub fan-out for websockets. |
| Message Queue | **RabbitMQ** *(or Spring @Async to start)* | Async: notifications, webhook processing, deposit-release. |
| Auth | **Spring Security + JWT** | Stateless role-based auth (USER / ADMIN). |
| Payments | **Stripe or Razorpay (test mode)** | Real gateway with webhooks, holds, and refunds. |
| Analytics API | **GraphQL (Spring for GraphQL)** | Admin dashboard: varied, nested, aggregated reads — GraphQL's best-fit use case. Scoped to this one place. |
| Real-time | **STOMP over Websocket (spring-boot-starter-websocket)** | One live feature: payment-status push. Fanned out via Redis pub/sub for multi-instance safety. |
| Migrations | **Flyway** | Versioned schema — reviewers see disciplined DB evolution. |
| Containerisation | **Docker + docker-compose** | One command runs the whole stack. |
| Testing | **JUnit 5 + Spring Boot Test + Testcontainers** | Real integration tests, incl. the concurrency proof. |
| Docs | **README + HLD diagram + ADRs** | Documented decisions read as seniority. |

Each of GraphQL and Websockets appears in **exactly one place** where it is the right tool. See
sections 7 and 8. Everything else is REST.

---

## 4. High-Level Design (HLD)

### Components
- **Controller layer** — REST endpoints, request validation, JWT auth filter.
- **Booking Service** — availability check, locking, booking lifecycle. The heart.
- **Payment Service** — talks to the gateway, records ledger entries, handles webhooks.
- **Notification Service** — consumes queue events, sends emails (async).
- **Realtime Service** — pushes payment-status updates to the user's browser (websocket + Redis pub/sub).
- **Analytics/GraphQL layer** — read-only aggregated queries for the admin dashboard.
- **Settlement/Deposit Worker** — scheduled job that releases deposits after return.
- **Reconciliation Worker** — scheduled job that polls stuck/pending payments.
- **PostgreSQL** — source of truth for users, items, bookings, payments, ledger.
- **Redis** — distributed locks, cached listings, pub/sub for realtime.
- **RabbitMQ** — event bus for async work.

### Booking request flow
1. Renter requests booking for item X, dates D1–D2.
2. Booking Service acquires a **Redis lock on item X** (or a DB row lock via `SELECT ... FOR UPDATE`).
3. It runs the **overlap query**: is X already booked for any date in D1–D2?
4. If free → create booking in `PENDING_PAYMENT`, release the availability lock.
5. Payment Service creates a payment intent (fee + deposit hold) with an **idempotency key**.
6. On gateway **webhook "succeeded"** → booking → `CONFIRMED`, ledger written,
   `BookingConfirmed` event published to RabbitMQ **and** pushed live to the renter via websocket.
7. Notification Service consumes the event, emails both parties (off the request thread).
8. If payment fails/times out → booking → `PAYMENT_FAILED`, item stays bookable.

### Scaling notes (for the interview)
- Cache item listings in Redis; invalidate on update.
- Availability is the hot path → index `(item_id, start_date, end_date)`.
- Horizontal scaling → the **Redis** lock (not an in-process lock) is what makes multi-instance safe,
  and Redis **pub/sub** is what lets any instance reach a user's websocket on any other instance.

---

## 5. Data Model & Schema

### ER overview
```
User ──1:N──▶ Item          (a user lists items)
User ──1:N──▶ Booking        (a user books items)
Item ──1:N──▶ Booking
Booking ──1:N──▶ Payment     (fee payment + deposit hold)
Booking ──1:N──▶ LedgerEntry (double-entry, always balanced)
Booking ──1:1──▶ Return      (created on item return)
```

### DDL sketch (Postgres)
```sql
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('USER','ADMIN')) DEFAULT 'USER',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE items (
    id             BIGSERIAL PRIMARY KEY,
    owner_id       BIGINT NOT NULL REFERENCES users(id),  -- ownership lives here, not in a role
    title          TEXT NOT NULL,
    description    TEXT,
    daily_rate     NUMERIC(12,2) NOT NULL,
    deposit_amount NUMERIC(12,2) NOT NULL,
    status         TEXT NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT NOT NULL DEFAULT 0,   -- optimistic lock
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT NOT NULL REFERENCES items(id),
    renter_id      BIGINT NOT NULL REFERENCES users(id),
    start_date     DATE NOT NULL,
    end_date       DATE NOT NULL,
    status         TEXT NOT NULL,               -- see state machine
    total_amount   NUMERIC(12,2) NOT NULL,
    deposit_amount NUMERIC(12,2) NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_booking_availability ON bookings (item_id, start_date, end_date);

-- Postgres exclusion constraint: the DATABASE itself refuses overlapping
-- active bookings for the same item — the last line of defence even if
-- application locking has a bug.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE bookings ADD CONSTRAINT no_overlap
    EXCLUDE USING gist (
        item_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT','CONFIRMED','ACTIVE'));

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES bookings(id),
    gateway_ref     TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,        -- dedupe retries/webhooks
    amount          NUMERIC(12,2) NOT NULL,
    type            TEXT NOT NULL CHECK (type IN ('FEE','DEPOSIT','REFUND')),
    status          TEXT NOT NULL,               -- PENDING/SUCCEEDED/FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id),
    account    TEXT NOT NULL,                    -- e.g. RENTER_CASH, OWNER_PAYABLE, DEPOSIT_HELD
    debit      NUMERIC(12,2) NOT NULL DEFAULT 0,
    credit     NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE returns (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT NOT NULL UNIQUE REFERENCES bookings(id),
    condition        TEXT NOT NULL,              -- OK / DAMAGED
    deposit_deducted NUMERIC(12,2) NOT NULL DEFAULT 0,
    refund_amount    NUMERIC(12,2) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Store processed webhook IDs so a duplicate delivery is a no-op.
CREATE TABLE processed_webhooks (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The **exclusion constraint** is the single most impressive line in the schema: the *database*
guarantees no overlapping bookings, so even a race that slips past your app locking cannot corrupt
data.

### The overlap query
Two ranges overlap iff `existing.start <= new.end AND existing.end >= new.start`:
```sql
SELECT EXISTS (
    SELECT 1 FROM bookings
    WHERE item_id = :itemId
      AND status IN ('PENDING_PAYMENT','CONFIRMED','ACTIVE')
      AND start_date <= :newEnd
      AND end_date   >= :newStart
);
```

---

## 6. API Surface (REST)

Everything except admin analytics is REST. Ownership actions are gated by `item.owner_id == you`,
not by a role.

| Method | Path | Who | Purpose |
|--------|------|-----|---------|
| POST | `/auth/register` | public | Create account (role defaults to USER). |
| POST | `/auth/login` | public | Returns JWT. |
| GET | `/items` | any | Browse/search listings (cached). |
| POST | `/items` | USER | List an item (you become its owner). |
| PUT | `/items/{id}` | owner only | Edit an item you own. |
| GET | `/items/{id}/availability?from=&to=` | any | Check date availability. |
| POST | `/bookings` | USER | Request a booking (triggers lock + overlap check). |
| GET | `/bookings/me` | USER | My bookings (as renter). |
| GET | `/items/mine/bookings` | owner only | Bookings on items I own. |
| POST | `/bookings/{id}/pay` | renter only | Create payment intent (idempotency key required). |
| POST | `/bookings/{id}/cancel` | renter only | Cancel per rules. |
| POST | `/bookings/{id}/return` | owner only | Mark returned, set condition → triggers settlement. |
| GET | `/me/earnings` | USER | My earnings as an owner. |
| POST | `/webhooks/payments` | gateway | Payment status callbacks (idempotent). |
| ws | `/ws` (STOMP) | USER | Subscribe to `/user/queue/payments` for live status. |
| POST | `/admin/bookings/{id}/refund` | ADMIN | Manual refund override. |
| POST | `/graphql` | ADMIN | Analytics dashboard queries (see §7). |

**Idempotency:** `POST /bookings/{id}/pay` and `/webhooks/payments` carry an idempotency key. The
`payments.idempotency_key UNIQUE` constraint plus the `processed_webhooks` table make retries safe.

---

## 7. GraphQL — the Admin Analytics Dashboard

**Used in exactly one place, because this is where GraphQL is genuinely the right tool.**

The admin dashboard shows an overview of the platform over a date range: bookings over time,
revenue by category, top items, deposit/refund stats, active vs cancelled ratios. Different
widgets want different slices of aggregated, nested data. Building a dozen bespoke REST reporting
endpoints (`/admin/stats/revenue`, `/admin/stats/top-items`, …) is exactly the over/under-fetching
pain GraphQL solves: the frontend sends **one query** asking for precisely the fields each view
needs, and the server returns exactly that shape.

### Why GraphQL here and REST everywhere else (the interview answer)
- **Analytics = varied, nested, read-only queries** → GraphQL's sweet spot. One endpoint, client
  picks fields, no endpoint sprawl.
- **Bookings/payments = resource-oriented, cacheable, money-mutating** → REST's sweet spot. Clean
  HTTP status codes, easy CDN caching of `GET /items`, simple idempotent webhook contract.

Saying *"I used GraphQL only for the analytics dashboard, where clients need flexible aggregated
reads, and kept transactional flows on REST for caching and clean HTTP semantics"* is a strong,
honest, senior answer — it shows you choose tools by fit, not by hype.

### Schema sketch
```graphql
type Query {
  platformOverview(from: Date!, to: Date!): Overview!
}

type Overview {
  totalBookings: Int!
  totalRevenue: Float!
  totalDepositsHeld: Float!
  bookingsByDay: [DailyPoint!]!
  revenueByCategory: [CategoryStat!]!
  topItems(limit: Int = 5): [ItemStat!]!
  statusBreakdown: [StatusCount!]!
}

type DailyPoint    { date: Date!  bookings: Int!  revenue: Float! }
type CategoryStat  { category: String!  revenue: Float!  bookings: Int! }
type ItemStat      { itemId: ID!  title: String!  bookings: Int!  revenue: Float! }
type StatusCount   { status: String!  count: Int! }
```

A single dashboard load fires one query and gets every widget's data in one round trip, each widget
requesting only the fields it renders. Secured with `@PreAuthorize("hasRole('ADMIN')")` on the
GraphQL data-fetcher — the endpoint is admin-only.

**Implementation note:** resolvers run SQL `GROUP BY` aggregations against Postgres (or read from a
lightweight materialised/summary view if you want to show you'd optimise the hot path).

---

## 8. Websockets — Live Payment-Status Push

**Used in exactly one place: pushing payment confirmation to the renter's browser in real time.**

After a renter clicks **Pay**, the booking sits in `PENDING_PAYMENT` and only flips to `CONFIRMED`
when the gateway webhook lands on the backend — seconds later, out of band. Without a push, the
frontend must poll `GET /bookings/{id}`. The websocket lets the backend push
"your booking is confirmed" the instant the webhook arrives.

### The hard part (and the senior answer)
With multiple backend instances, the webhook may hit **instance A** while the renter's websocket is
held by **instance B**. An in-memory socket registry would fail here. The fix:

```
Gateway webhook ─▶ instance A ─▶ publish to Redis channel  payments:user:{id}
                                            │
              all instances subscribe ──────┤
                                            ▼
                    instance B (holds the socket) ─▶ STOMP push to /user/{id}/queue/payments
```

Redis **pub/sub** fans the event out to every instance; whichever one holds that user's socket
delivers it. That's why Redis is in the stack for more than just locks.

> The one sentence that makes this impressive: *"Any instance can receive the webhook and still
> reach the user, because the notification goes through Redis pub/sub, not an in-memory socket map."*

### Flow
1. Renter's browser opens a STOMP websocket at `/ws`, subscribes to `/user/queue/payments`.
2. Renter pays; booking is `PENDING_PAYMENT`.
3. Webhook `succeeded` hits some instance → booking → `CONFIRMED`.
4. That instance publishes to Redis `payments:user:{renterId}`.
5. The instance holding the socket receives the pub/sub message and pushes to the renter.
6. UI flips `PENDING_PAYMENT → CONFIRMED` with no polling.

Polling is a perfectly valid fallback and worth mentioning — the websocket is a UX optimisation,
and knowing *when it's worth the added complexity* is the point.

---

## 9. Low-Level Design (LLD)

### Booking state machine (the LLD centrepiece)
```
PENDING_PAYMENT ──payment success──▶ CONFIRMED ──start date──▶ ACTIVE
      │                                   │                       │
      └─payment fail/timeout─▶ PAYMENT_FAILED                     │ return item
                                          │ cancel (rules)        ▼
                                          ▼                    RETURNED ─deposit settled─▶ CLOSED
                                      CANCELLED                    │
                                                            damage │
                                                                   ▼
                                                               DISPUTED
```

### Design patterns to use (and name in interviews)
- **State pattern / explicit state machine** for booking transitions (reject illegal transitions centrally).
- **Strategy** for pricing (daily vs weekly vs promotional).
- **Repository + Service layering** (Spring-idiomatic).
- **Idempotency key** on all payment-mutating endpoints.
- **Optimistic locking** (`@Version`) on Item/Booking, or **pessimistic** (`SELECT ... FOR UPDATE`).
- **Outbox pattern** (optional, advanced): write the event to an `outbox` table in the same
  transaction as the state change, publish to RabbitMQ from a relay — guarantees the event fires
  iff the DB commit succeeded. A great "how do you avoid dual-write bugs?" answer.

---

## 10. The Concurrency Problem, Solved

Build one thing that *proves* your concurrency handling works, and put the result in the README.

**Three layers of defence (say all three):**
1. **Redis distributed lock** on `item:{id}` — serialises booking attempts across instances.
2. **`SELECT ... FOR UPDATE`** inside the transaction — DB-level serialisation.
3. **Postgres exclusion constraint** — the database itself rejects any overlap that slips through.

**The proof:** a Testcontainers integration test that fires **N concurrent booking requests for the
same item and same dates** and asserts **exactly one succeeds**.

> README headline: *"Firing 500 concurrent requests for one slot → 1 confirmed, 499 rejected,
> 0 double-bookings."*

Almost no portfolio project does this. It's your single highest-impact differentiator.

---

## 11. Payments & Failure Scenarios

1. **Card declined** → booking `PAYMENT_FAILED`, item released, renter notified. Clean rollback.
2. **Gateway timeout (no response)** → don't assume failure; reconcile via webhook or status poll.
3. **"Success but webhook never arrived"** → the **reconciliation job** polls pending payments and
   fixes state. (Most candidates never think of this — huge signal.)
4. **Duplicate webhook** → `processed_webhooks` table makes the second delivery a no-op.
5. **Concurrent deposit release + damage claim** → lock the booking row so you don't both refund and deduct.
6. **Partial refund** → deposit ₹5000, ₹1500 damage → refund ₹3500, ledger balances to zero.

### Double-entry ledger — worked example
Rental fee ₹2000, deposit ₹5000 paid; on return ₹1500 damage claimed:

| Event | Account | Debit | Credit |
|-------|---------|------:|-------:|
| Fee paid | RENTER_CASH | 2000 | |
| | OWNER_PAYABLE | | 2000 |
| Deposit held | RENTER_CASH | 5000 | |
| | DEPOSIT_HELD | | 5000 |
| Return (damage) | DEPOSIT_HELD | 5000 | |
| | OWNER_PAYABLE | | 1500 |
| | RENTER_REFUND | | 3500 |

Every money movement is two balanced entries — sum(debit) always equals sum(credit), so you can
*prove* the books balance.

---

## 12. Async Processing

| Event | Producer | Consumer | Work done |
|-------|----------|----------|-----------|
| `BookingConfirmed` | Booking Service | Notification Service | Email both parties. |
| `PaymentSucceeded` | Payment Service | Ledger + Notification + Realtime | Write ledger, notify, push to websocket. |
| `ReturnRecorded` | Return endpoint | Settlement Worker | Compute refund, issue via gateway. |

**Scheduled jobs (`@Scheduled`):**
- **Deposit-release worker** — releases deposits for bookings past their return window.
- **Reconciliation worker** — polls `PENDING` payments older than N minutes, queries the gateway,
  and corrects state (fixes the "webhook never arrived" case).

Start with **Spring `@Async` + `@Scheduled`**, then upgrade the event bus to **RabbitMQ** for real
at-least-once delivery + retry/dead-letter semantics.

---

## 13. Roles & Authorization

**Two roles only:**
- **USER** — the default for everyone. Can browse, book, pay, return, **and** list/edit their own
  items and view their own earnings. Owner and renter are *capabilities of one account*, not roles.
- **ADMIN** — oversight: view the analytics dashboard (GraphQL), resolve disputes, override refunds.

**Ownership is a resource-level authz check, not a role.** An action like "edit this item" is
allowed iff `item.owner_id == currentUser.id`. This is a *stronger* interview topic than role checks
because it's the thing candidates most often get wrong (checking the role but forgetting to check
that the resource actually belongs to the caller).

Enforced via Spring Security: `@PreAuthorize("hasRole('ADMIN')")` for admin endpoints, and an
explicit ownership guard (a `@PreAuthorize` SpEL expression or a service-layer check) for
owner-only actions.

---

## 14. Considered but Scoped Out

Naming what you *didn't* use, and why, reads as senior. Each of these is a deliberate decision.

| Technology | Verdict | The reasoning (say this in an interview) |
|-----------|---------|------------------------------------------|
| **Kubernetes** | Not used | A single service doesn't warrant orchestration. "I'd reach for K8s with multiple services needing auto-scaling and self-healing — this doesn't, so adding it would be over-engineering." |
| **GraphQL beyond analytics** | Scoped to the admin dashboard only | GraphQL shines for varied nested reads (analytics). For money-mutating, cacheable, resource-oriented flows, REST gives cleaner HTTP semantics, easier CDN caching, and a simpler idempotent webhook contract. Using GraphQL everywhere would trade those away for no benefit. |
| **Websockets beyond payment status** | Scoped to one live feature | Real-time is a UX optimisation, not a core requirement. Polling is a valid fallback. I added exactly one websocket feature (payment-status push) to demonstrate the pattern — including the multi-instance Redis pub/sub fan-out — without over-building. |
| **Microservices** | Not used (modular monolith) | The domain is cohesive; a well-layered monolith is simpler to reason about and deploy. I'd split services along scaling/ownership boundaries only when they actually diverge. |
| **NoSQL / MongoDB** | Not used | Money and bookings need ACID transactions, row locking, and relational integrity (the ledger). Postgres is the correct fit; a document store would fight the problem. |

---

## 15. Build Order

**Phase 1 — Skeleton.** Spring Boot, Postgres, Docker compose, User + JWT auth (USER/ADMIN), Item
CRUD with ownership checks, React shell.
*Goal:* log in, list an item, see it — end to end.

**Phase 2 — Booking core (the heart).** Booking entity + state machine, overlap query, **locking**,
exclusion constraint, the concurrency test.
*Goal:* no double-booking under concurrent load, proven by a test.

**Phase 3 — Payments (the fintech story).** Stripe/Razorpay test integration, payment intents,
idempotency keys, webhook handler, double-entry ledger, the failure scenarios.
*Goal:* money is never lost or double-charged, even on failure/retry.

**Phase 4 — Async + realtime + polish.** RabbitMQ (or start with @Async), notification consumer,
deposit-release job, reconciliation job, **websocket payment-status push with Redis pub/sub**.
*Goal:* slow work is off the request path; status updates are live.

**Phase 5 — Analytics + docs + proof.** **GraphQL admin dashboard**, README with HLD diagram, ADRs,
concurrency-test result, live demo link, short walkthrough.
*Goal:* an interviewer is impressed in 5 minutes.

**Ship Phases 1–3 first — that alone is a strong project.** Phases 4–5 make it stand out.

---

## 16. Frontend Weekly Plan

Assumes ~1 focused week per phase; compress or stretch to taste. React + TypeScript throughout.

| Week | Focus | Deliverables |
|------|-------|--------------|
| **1 — Auth & shell** | Project setup | Vite + React + TS + router; login/register forms; JWT stored + attached to requests; protected routes; app layout/nav; USER vs ADMIN route guards. |
| **2 — Items** | Browse & list | Item list/search page (calls `GET /items`); item detail page; "List an item" form; "My items" management view; availability calendar on the detail page. |
| **3 — Booking & pay** | The core UX | Booking form (date-range picker → `POST /bookings`); booking summary showing fee + deposit; "Pay" button hitting the payment intent; **payment-pending screen** that will later go live. |
| **4 — Realtime & my bookings** | Live status | Open the STOMP websocket, subscribe to `/user/queue/payments`; flip the pending screen to CONFIRMED on push (fallback: poll); "My bookings" list with statuses; return-request UI for owners. |
| **5 — Admin dashboard** | GraphQL | Admin-only dashboard route; **one GraphQL query** for the whole overview; charts (Recharts): bookings-over-time line, revenue-by-category bar, top-items table, status breakdown; date-range picker driving the query. |

Frontend notes:
- Use a typed API client; generate types from the GraphQL schema for the dashboard.
- Keep the payment-pending → confirmed transition visibly *live* — it's your demo money-shot.
- Charts should read from the single `platformOverview` query, proving the GraphQL benefit.

---

## 17. Backend Weekly Plan

| Week | Focus | Deliverables |
|------|-------|--------------|
| **1 — Foundations** | Auth & CRUD | Spring Boot project; Postgres + Flyway; `users` table; register/login + JWT filter; Spring Security with USER/ADMIN; Item entity + CRUD; **ownership guard** on edit; Docker compose (api, web, postgres, redis). |
| **2 — Booking engine** | Concurrency | Booking entity + state machine; overlap query; Redis distributed lock **+** `SELECT FOR UPDATE`; **exclusion constraint** migration; the Testcontainers concurrency test (N requests → 1 wins). |
| **3 — Payments** | Correctness | Gateway (Stripe/Razorpay test) integration; payment intents (fee + deposit); idempotency keys; webhook endpoint with signature check + `processed_webhooks` dedupe; double-entry ledger; failure-scenario handling. |
| **4 — Async & realtime** | Off-thread + live | RabbitMQ (or @Async) + event publishing; notification consumer; deposit-release `@Scheduled` job; reconciliation job; **websocket endpoint + Redis pub/sub fan-out** for payment-status push. |
| **5 — Analytics & docs** | GraphQL + polish | Spring for GraphQL; `platformOverview` resolver with `GROUP BY` aggregations; admin-only security on the GraphQL layer; HLD diagram; ADRs (Postgres, no-K8s, GraphQL-only-for-analytics, websocket-scope); WireMock payment tests; README proof section. |

Backend notes:
- Write the concurrency test in Week 2 — it gates the whole "no double-booking" claim.
- Keep GraphQL strictly read-only and admin-only; all writes stay on REST.
- Reuse the `PaymentSucceeded` event to drive email *and* the websocket push — one event, two consumers.

---

## 18. Testing Strategy

| Layer | Tool | What it proves |
|-------|------|----------------|
| Unit | JUnit 5 + Mockito | State-machine transitions, pricing strategy, ledger balancing. |
| Slice | `@WebMvcTest` | Controller validation + auth rules (role + ownership). |
| Integration | Spring Boot Test + **Testcontainers (Postgres, Redis)** | Real DB behaviour, overlap query, locking. |
| **Concurrency** | Testcontainers + `ExecutorService` | **N concurrent bookings → exactly 1 succeeds.** |
| Payment | WireMock (fake gateway) | Declines, timeouts, duplicate webhooks, reconciliation. |
| GraphQL | Spring GraphQL test | `platformOverview` returns correct aggregates; admin-only access enforced. |

The concurrency test and the payment-failure tests are the two that matter most for the story.

---

## 19. Local Setup

```bash
# One command brings up backend + frontend + Postgres + Redis + RabbitMQ
docker-compose up --build

# Backend:      http://localhost:8080
# GraphQL:      http://localhost:8080/graphql  (admin)
# Frontend:     http://localhost:3000
# RabbitMQ UI:  http://localhost:15672
```

`docker-compose.yml` services: `api`, `web`, `postgres`, `redis`, `rabbitmq`.
Flyway runs migrations on `api` startup. Seed data via a Flyway `V...__seed.sql` or a dev profile.

Env vars (`.env`): DB creds, JWT secret, `STRIPE_SECRET_KEY` / webhook secret (test mode),
Redis host (used for locks **and** pub/sub).

---

## 20. What You Need to Learn (per topic)

Coming from Node/TypeScript — learn these *as you hit each phase*, not all up front.

**Phase 1 — Java + Spring foundations**
- Spring Boot: `@RestController`, `@Service`, `@Repository`, dependency injection.
- Spring Data JPA: entities, repositories, derived queries, `@Query`.
- Spring Security + JWT: filter chain, `UserDetailsService`, method security, SpEL ownership checks.
- Flyway migrations.

**Phase 2 — Concurrency & DB**
- Transactions & isolation levels (`READ COMMITTED` vs `SERIALIZABLE`).
- Pessimistic locking: `SELECT ... FOR UPDATE`, JPA `@Lock(PESSIMISTIC_WRITE)`.
- Optimistic locking: JPA `@Version`, handling `OptimisticLockException`.
- Redis distributed locks: Redisson `RLock`, or `SET NX PX` + fencing token; know the failure modes.
- Postgres range types + `btree_gist` exclusion constraints.

**Phase 3 — Payments**
- Payment intents / holds / captures (Stripe or Razorpay test mode).
- Webhooks: signature verification, at-least-once delivery, idempotent handlers.
- Idempotency keys: what they protect, where to store them.
- Double-entry bookkeeping: debits/credits, why the ledger always balances.

**Phase 4 — Async & realtime**
- Spring `@Async`, `@Scheduled`, thread pools (`TaskExecutor`).
- RabbitMQ: exchanges, queues, bindings, acks, dead-letter queues, retry.
- Dual-write problem + the **outbox pattern**.
- Websockets: STOMP, `spring-boot-starter-websocket`, `@SendToUser`.
- **Redis pub/sub** and why it's needed for multi-instance websocket fan-out.

**Phase 5 — GraphQL & docs**
- GraphQL basics: schema, types, queries, resolvers/data-fetchers.
- **Spring for GraphQL**: mapping resolvers, securing fields, batching (DataLoader) if you go deep.
- When GraphQL beats REST and vice-versa (you have a great real example now).
- ADR writing, HLD/LLD vocabulary.

**Cross-cutting**
- Testcontainers, WireMock.
- Docker Compose multi-service setups.

---

## 21. Interview Questions This Prepares You For

- "How do you prevent two users booking the same item at the same time?"
- "What happens if the payment gateway times out?"
- "How do you make a webhook handler idempotent?"
- "Optimistic vs pessimistic locking — which did you use and why?"
- "Walk me through your booking state machine."
- "Why Postgres and not MongoDB here?"
- "Where did you use a queue, and why async?"
- "Would you use Kubernetes here? Why or why not?"
- "How do you guarantee the books always balance?"
- "How do you avoid a dual-write bug between your DB and your message queue?" *(outbox)*
- "You have one USER role — how does someone get blocked from editing another user's item?" *(resource-level authz)*
- "Why did you use GraphQL for the dashboard but REST everywhere else?"
- "Your webhook hits instance A but the user's websocket is on instance B — how does the update reach them?" *(Redis pub/sub)*

Every one has a real answer *because you built it*. That's the point.

---

## 22. How to Present It

- **Resume:** *"RentFlow — Equipment Rental Marketplace · Java, Spring Boot, PostgreSQL, Redis,
  RabbitMQ, GraphQL, Websockets, Docker"* with 2–3 bullets on the concurrency and payment work.
- **Interview framing:** *"~2 years backend in Node/TypeScript in fintech; I built this in
  Java/Spring Boot to move into Java backend roles and go deep on concurrency and payment
  correctness. Every technology in it earns its place — I can tell you why each is there and where
  I deliberately chose *not* to use one."* Confident, true, defensible.
