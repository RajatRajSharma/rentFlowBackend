# RentFlow Backend — 5-Week Implementation Plan (PLAN.md)

A day-by-day checklist from **setup → production**. Grounded in `README.md` (§15 Build Order,
§17 Backend Weekly Plan) and `BACKEND.md`.

## How to use this
- Each task starts as **⏳** (to-do). When it's done, change it to **✅**.
- Optional middle state: **🚧** (in progress) while you're actively on it.
- Days are a guide, not a cage — slide tasks between days as reality demands.
- **Ship Weeks 1–3 first** (auth + booking + payments) — that alone is a strong project.
  Weeks 4–5 make it stand out.

## Progress at a glance
| Week | Theme | Status |
|------|-------|--------|
| 1 | Foundations — setup, auth, item CRUD | ✅ done |
| 2 | Booking engine — concurrency (the heart) | ⏳ |
| 3 | Payments — correctness (the fintech story) | ⏳ |
| 4 | Async & realtime — off-thread + live updates | ⏳ |
| 5 | Analytics, docs & production | ⏳ |

---

## Week 1 — Foundations (setup, auth, item CRUD)
**Goal:** log in, list an item, see it — end to end.

### Day 1 — Project setup
- ✅ Generate Spring Boot skeleton from Initializr (web, jpa, security, redis, amqp, flyway, graphql, websocket…)
- ✅ Move design docs into `docs/` (BACKEND.md, README.md)
- ✅ Write `docs/SETUP.md` (reproducible setup guide)
- ⏳ Create the feature-package folder structure (`booking/`, `payment/`, … per SETUP.md §5)
- ✅ Add `docker-compose.yml` (postgres, redis, rabbitmq)
- ✅ Add `.env` + `.env.example`; convert `application.properties` → `application.yml` (native `.env` import)
- ✅ `docker-compose up -d` and `./mvnw spring-boot:run` → `/actuator/health` is `UP` (db/redis/rabbit all UP)
- ✅ Bonus: custom `/api/version` endpoint working (proves controller layer)
- ✅ Add pgAdmin to docker-compose (web GUI for Postgres at http://localhost:5050)

### Day 2 — Database foundation & users table
- ✅ Write Flyway migration `V1__users.sql` (users table with role check)
- ✅ Confirm Flyway runs on startup and creates the table (history shows V1 success)
- ✅ Create `User` JPA entity + `Role` enum (USER, ADMIN)
- ✅ Create `UserRepository` (Spring Data JPA — `existsByEmail`, `findByEmail`)
- ✅ Add `common/audit` base entity (created/updated timestamps)

### Day 3 — Registration & password hashing
- ✅ `AuthController` with `POST /auth/register`
- ✅ `UserService` — hash password (BCrypt), save user, reject duplicate email
- ✅ Request/response DTOs in `user/dto/` (records)
- ✅ Global exception handling (`@RestControllerAdvice` + `ApiError` DTO)
- ✅ Manual test: register → 201, duplicate → 409, invalid → 400, row in Postgres (BCrypt hash)

### Day 4 — JWT login & security filter
- ✅ Add `jjwt` dependency to `pom.xml` (0.12.6)
- ✅ `JwtService` — mint + verify tokens (HS384, uid/role claims)
- ✅ `JwtAuthFilter` — read token → set `SecurityContext` (`AuthenticatedUser` principal)
- ✅ `SecurityConfig` — stateless, public vs protected routes, JWT filter wired in
- ✅ `POST /auth/login` → returns a JWT; protected endpoints require it (401/403 without)

### Day 5 — Item CRUD + ownership guard
- ✅ `V2__items.sql` migration (items table, owner_id FK, version, index)
- ✅ `Item` entity (`@Version` optimistic lock) + `ItemRepository`
- ✅ `ItemService` + `ItemController`: `GET /items`, `GET /items/{id}`, `POST /items`, `PUT /items/{id}`
- ✅ `OwnershipGuard` — edit allowed iff `item.ownerId == currentUser.id`
- ✅ Manual end-to-end test: register → login → create item → edit own (200) → B edits A's item (403)

---

## Week 2 — Booking engine (concurrency, the heart)
**Goal:** no double-booking under concurrent load, proven by a test.

### Day 6 — Booking entity & state machine
- ⏳ `V3__bookings_and_exclusion.sql` (bookings table + availability index)
- ⏳ `Booking` entity + `BookingStatus` enum + `@Version` column
- ⏳ `BookingStateMachine` — legal-transition table, reject illegal jumps
- ⏳ Unit tests for the state machine transitions

### Day 7 — Availability & overlap query
- ⏳ `BookingRepository` with the overlap query (`start <= newEnd AND end >= newStart`)
- ⏳ `GET /items/{id}/availability?from=&to=`
- ⏳ `BookingService.create()` happy path → writes `PENDING_PAYMENT`
- ⏳ `POST /bookings` + `GET /bookings/me`

### Day 8 — Locking layer
- ⏳ `LockManager` interface + `RedisLockManager` (Redisson `RLock`)
- ⏳ Wrap booking creation in the Redis lock on `item:{id}`
- ⏳ Add `SELECT ... FOR UPDATE` (pessimistic) inside the transaction
- ⏳ Add the Postgres **exclusion constraint** (btree_gist) to the migration

### Day 9 — The concurrency proof
- ⏳ Testcontainers base class in `test/support/`
- ⏳ `BookingConcurrencyIT` — fire N (e.g. 500) concurrent requests for one slot
- ⏳ Assert **exactly 1 succeeds**, the rest rejected, 0 double-bookings
- ⏳ Capture the result line for the README proof section

### Day 10 — Cancel + buffer/catch-up
- ⏳ `POST /bookings/{id}/cancel` (renter, per rules)
- ⏳ `GET /items/mine/bookings` (owner view)
- ⏳ Harden error responses + validation
- ⏳ Catch up / refactor; re-run all tests green

---

## Week 3 — Payments (correctness, the fintech story)
**Goal:** money is never lost or double-charged, even on failure/retry.

### Day 11 — Payment model & gateway abstraction
- ⏳ `V4__payments_ledger.sql` (payments + ledger_entries tables)
- ⏳ `Payment` entity (UNIQUE `idempotency_key`) + `PaymentRepository`
- ⏳ `PaymentGateway` interface + `StripeGateway` impl (test mode)
- ⏳ Stripe test keys wired via `.env`

### Day 12 — Payment intent + idempotency
- ⏳ `PaymentService` — create intent (fee + deposit) with idempotency key
- ⏳ `POST /bookings/{id}/pay` (renter only, idempotency key required)
- ⏳ `IdempotencyService` — dedupe retried pay requests

### Day 13 — Webhook handler
- ⏳ `V5__returns_webhooks.sql` (returns + processed_webhooks tables)
- ⏳ `WebhookController` `POST /webhooks/payments` — signature verify
- ⏳ Idempotent via `processed_webhooks` (duplicate delivery = no-op)
- ⏳ On success → booking `CONFIRMED`; on fail → `PAYMENT_FAILED`, item released

### Day 14 — Double-entry ledger
- ⏳ `LedgerService` — post balanced debit/credit pairs
- ⏳ Write ledger entries on payment success
- ⏳ Test: sum(debit) == sum(credit) per booking (books balance)
- ⏳ Worked example (fee + deposit) verified

### Day 15 — Failure scenarios + WireMock
- ⏳ Card declined → clean rollback
- ⏳ Gateway timeout → don't assume failure
- ⏳ `WebhookIdempotencyIT` (duplicate webhook)
- ⏳ WireMock fake-gateway tests (declines, timeouts, duplicates)

---

## Week 4 — Async & realtime (off-thread + live updates)
**Goal:** slow work is off the request path; status updates are live.

### Day 16 — Event publishing
- ⏳ `EventPublisher` interface (+ `InMemoryPublisher` to start)
- ⏳ Define events (`BookingConfirmed`, `PaymentSucceeded`, `ReturnRecorded`)
- ⏳ Publish `PaymentSucceeded` on webhook success

### Day 17 — RabbitMQ + notifications
- ⏳ `RabbitConfig` (exchanges, queues, bindings) + `RabbitEventPublisher`
- ⏳ `NotificationConsumer` listens on the queue
- ⏳ `EmailService` (log/console email to start) — email both parties async

### Day 18 — Scheduled workers
- ⏳ `settlement/` — `Return` + `POST /bookings/{id}/return` + `SettlementService`
- ⏳ `DepositReleaseWorker` (`@Scheduled`) — release deposits past return window
- ⏳ `ReconciliationWorker` (`@Scheduled`) — poll PENDING payments, fix "webhook never arrived"

### Day 19 — Websocket + Redis pub/sub
- ⏳ `WebSocketConfig` — STOMP endpoint `/ws`
- ⏳ `RealtimeService` — push to `/user/queue/payments`
- ⏳ `RedisPubSubBridge` — fan-out across instances (`payments:user:{id}`)
- ⏳ On payment success: publish → any instance delivers to the right socket

### Day 20 — Async catch-up + outbox (optional)
- ⏳ End-to-end: pay → webhook → live UI flip (no polling)
- ⏳ (Optional) outbox pattern for dual-write safety
- ⏳ Re-run full test suite green

---

## Week 5 — Analytics, docs & production
**Goal:** an interviewer is impressed in 5 minutes — and it's deployable.

### Day 21 — GraphQL analytics
- ⏳ `schema.graphqls` (`platformOverview` + types)
- ⏳ `AnalyticsDataFetcher` resolver + `AnalyticsRepository` (`GROUP BY` aggregations)
- ⏳ Admin-only security (`@PreAuthorize("hasRole('ADMIN')")`)
- ⏳ Admin refund override `POST /admin/bookings/{id}/refund`

### Day 22 — Caching & read scaling
- ⏳ Cache hot `GET /items` in Redis; invalidate on write
- ⏳ Verify availability index is used (explain analyze)
- ⏳ GraphQL test — correct aggregates + admin-only access enforced

### Day 23 — Docs & ADRs
- ⏳ `docs/hld.md` — architecture diagram + flows
- ⏳ `docs/lld.md` — entities, state machine, class relationships
- ⏳ `docs/adr/` — 0001 postgres-for-ledger, 0002 no-k8s, 0003 graphql-only-analytics, 0004 websocket-scope
- ⏳ README proof section (the "500 → 1 confirmed" concurrency result)

### Day 24 — Production hardening
- ⏳ `Dockerfile` for the app (multi-stage build)
- ⏳ Full `docker-compose up --build` (api + db + redis + rabbit) works
- ⏳ Prod `application-prod.yml` profile; secrets via env, not committed
- ⏳ Actuator health/readiness; sensible logging; connection-pool tuning

### Day 25 — Deploy & final proof
- ⏳ Deploy (Render / Railway / Fly.io / a VM) — Postgres + Redis provisioned
- ⏳ Smoke test the deployed API (`/actuator/health`, register→login→book→pay)
- ⏳ Live demo link + short walkthrough recorded
- ⏳ Final pass over the interview scripts (BACKEND.md §5/§6/§7)

---

## Definition of done (per phase)
- **Week 1:** register → login → list item → edit-own works end to end.
- **Week 2:** concurrency test proves exactly 1 of N bookings wins.
- **Week 3:** payment failure/retry/duplicate-webhook all leave state consistent; ledger balances.
- **Week 4:** email + live websocket push both fire off one event; scheduled jobs run.
- **Week 5:** GraphQL dashboard returns aggregates; app is deployed and demoable.
