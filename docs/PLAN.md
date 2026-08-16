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
| 2 | Booking engine — concurrency (the heart) | ✅ done |
| 3 | Payments — correctness (the fintech story) | ✅ done |
| 4 | Async & realtime — off-thread + live updates | 🚧 Days 16–18 done |
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
- ✅ `V3__bookings_and_exclusion.sql` (bookings table + availability index)
- ✅ `Booking` entity + `BookingStatus` enum + `@Version` column
- ✅ `BookingStateMachine` — legal-transition table, reject illegal jumps
- ✅ Unit tests for the state machine transitions (45 tests, all green)
- ✅ Bonus: pulled the **exclusion constraint** forward into V3 (see Day 8 note)

### Day 7 — Availability & overlap query
- ✅ `BookingRepository` with the overlap query (`start <= newEnd AND end >= newStart`)
- ✅ `GET /items/{id}/availability?from=&to=` (public, returns booked ranges)
- ✅ `BookingService.create()` happy path → writes `PENDING_PAYMENT`
- ✅ `POST /bookings` + `GET /bookings/me`
- ✅ Rules enforced: item must be ACTIVE, can't book your own item, max 90 days,
  price snapshot at booking time

### Day 8 — Locking layer
- ✅ `LockManager` interface + `RedisLockManager` (Redisson `RLock`, wait + lease timeouts)
- ✅ Wrap booking creation in the Redis lock on `item:{id}` — lock acquired **outside** the
  transaction and released after commit, via `TransactionTemplate`
- ✅ Add `SELECT ... FOR UPDATE` (pessimistic) inside the transaction — `ItemRepository
  .findByIdForUpdate`, `MANDATORY` propagation so it can't be called without one
- ✅ ~~Add the Postgres **exclusion constraint** (btree_gist) to the migration~~ — done on
  Day 6 instead. Flyway forbids editing an applied migration, and the constraint belongs
  with the table it protects. Verified by raw SQL: an overlapping insert that bypasses all
  application code is still rejected.

### Day 9 — The concurrency proof
- ✅ Integration-test base class in `test/support/` (Testcontainers, with a docker-compose
  fallback for machines where Testcontainers can't reach the Docker daemon)
- ✅ `BookingConcurrencyIT` — 500 concurrent requests for one slot
- ✅ Asserts **exactly 1 succeeds**, the rest rejected, 1 row in the database
- ✅ Second test proves the DB defences hold with the Redis lock bypassed entirely
- ✅ Third test proves locking doesn't reject *legitimate* non-overlapping bookings
- ✅ Result line captured for the README proof section

### Day 10 — Cancel + buffer/catch-up
- ✅ `POST /bookings/{id}/cancel` (renter only, transitions policed by the state machine)
- ✅ `GET /items/mine/bookings` (owner view)
- ✅ Harden error responses + validation:
  - Security rejections now return `ApiError` JSON, and **401** (not 403) when there are no
    usable credentials — closing the gap noted in API_DOCS
  - Handlers for malformed JSON, lock contention, optimistic-lock failure, stray
    integrity violations, and a catch-all so nothing leaks a raw stack trace
- ✅ All tests green: 46 unit + 3 integration

---

## Week 3 — Payments (correctness, the fintech story)
**Goal:** money is never lost or double-charged, even on failure/retry.

### Day 11 — Payment model & gateway abstraction
- ✅ `V4__payments_ledger.sql` (payments + ledger_entries tables). `ledger_entries` is created
  now but has no entity yet — Day 14's service lands on an existing schema. Append-only by
  design: no `updated_at`, no `version`, and a DB check that every row is a debit **or** a
  credit, never both
- ✅ `Payment` entity (UNIQUE `idempotency_key`) + `PaymentRepository` + `PaymentType` /
  `PaymentStatus` enums
- ✅ `PaymentGateway` interface + `StripeGateway` (test mode, raw REST via `RestClient` rather
  than the SDK, so Day 15's WireMock can point at it) + `FakeGateway`
- ✅ Stripe keys wired via `.env`. `PAYMENT_GATEWAY=fake` is the **default**, so a fresh clone
  runs end to end with no Stripe account; `stripe` fails fast at startup without a real key
- ✅ Bonus: moved `LockManager`/`RedisLockManager` to `common/lock/` — payments need it too, and
  a payment→booking dependency for a generic lock utility is the wrong direction

### Day 12 — Payment intent + idempotency
- ✅ `PaymentService` — reserve PENDING rows → call the gateway → record the ref, in that order.
  Rows are written **before** the gateway call so the key is claimed before money can move
- ✅ `POST /bookings/{id}/pay` (renter only, `Idempotency-Key` header required → 400 without).
  Returns **200, not 201** — a retry is the same request and must give the same answer
- ✅ `GET /bookings/{id}/payments` (renter only)
- ✅ `IdempotencyService` — fast replay check → distributed lock → re-check under the lock
- ✅ Plus a rule idempotency keys can't express: a booking has at most **one live set of charges**,
  whatever key asks for them, so a retry from a fresh tab (new key) can't double-charge
- ✅ `PaymentIdempotencyIT` — **100 concurrent pay requests → 2 payment rows, ₹7000, 0 failures**
- ✅ Found and fixed a real race while testing: writing `gateway_ref` via an entity `save()` made
  concurrent retries fail with optimistic-lock errors, for a write they all agreed on. Now a
  conditional `UPDATE ... WHERE gateway_ref IS NULL`
- ✅ 502 (not 500) when the gateway is unreachable; `MissingRequestHeaderException` → 400
- ✅ All tests green: 46 unit + 11 integration

### Day 13 — Webhook handler
- ✅ `V5__returns_webhooks.sql` (processed_webhooks + returns). `returns` has no entity yet —
  Day 18's settlement lands on an existing schema
- ✅ `WebhookController` `POST /webhooks/payments` — public route, because the HMAC signature
  **is** the authentication. Body bound as a raw `String`: signatures cover the exact bytes
  sent, so anything that re-serialises the JSON first can never verify
- ✅ Real Stripe signature scheme in `StripeStyleWebhooks` — HMAC-SHA256 over `{t}.{body}`,
  constant-time compare, and a ±300s tolerance window against replay. **The `FakeGateway`
  signs and verifies identically**, so the security-critical path is exercised locally
  instead of skipped
- ✅ Idempotent via `processed_webhooks`: `INSERT ... ON CONFLICT DO NOTHING` **is** the
  check — a check-then-insert would be the same race the booking engine exists to avoid.
  The claim and the work share one transaction, so a failure rolls back both and the
  gateway's retry gets a real second chance
- ✅ On success → booking `CONFIRMED`; on fail → `PAYMENT_FAILED`, dates released
- ✅ Confirmation waits for **every** charge to clear — confirming on the fee alone would
  hand over an item whose damage deposit was never taken
- ✅ `BookingRepository.findByIdForUpdate` — fee and deposit events can arrive together, so
  the booking row is locked rather than left to optimistic-lock failures
- ✅ Status codes chosen as instructions to the gateway: 400 bad signature and 404 unknown
  payment both stop redelivery; duplicates and ignored types answer 200; only genuinely
  transient failures reach a 5xx and get retried

### Day 14 — Double-entry ledger
- ✅ `LedgerEntry` (append-only — no setters, no `updatedAt`, no `@Version`, and deliberately
  **not** `Auditable`) + `LedgerAccount` + `LedgerRepository` + `LedgerBalance`
- ✅ `LedgerService.post()` — validates before saving, so an unbalanced ledger isn't a bug
  found later but a transaction that never committed. `MANDATORY` propagation: a ledger
  entry must never commit while its cause rolls back
- ✅ Write ledger entries on payment success — `FEE` → RENTER_CASH/OWNER_PAYABLE,
  `DEPOSIT` → RENTER_CASH/DEPOSIT_HELD. Failures write nothing: the ledger records what
  happened, not what was attempted
- ✅ Test: sum(debit) == sum(credit) per booking, using `compareTo` not `equals` so
  `2000.00` and `2000.000` don't read as a false imbalance
- ✅ Worked example verified — **₹2000 fee + ₹5000 deposit → 4 entries, 7000 debit =
  7000 credit**, with the two credits in *different* accounts (only OWNER_PAYABLE is ours
  to pay out)
- ✅ A test asserts `LedgerAccount` and the V4 `CHECK` constraint can't drift apart
- ✅ All tests green: 55 unit + 18 integration

### Day 15 — Failure scenarios + WireMock
- ✅ `StripeGatewayWireMockTest` — a fake Stripe over real HTTP, so the class under test is
  `StripeGateway` itself: decline (402), 5xx, hang, malformed 200, and a retry replaying on
  the same `Idempotency-Key`. Asserts ₹2000.00 leaves as **200000 paise**
- ✅ **Found a real bug doing it**: Boot 4 binds response bodies with Jackson 3, while
  `StripeGateway` asked for a Jackson 2 `JsonNode` — every live Stripe call would have failed.
  Only a test that speaks HTTP could catch it; the fake gateway never does. Now the body is
  taken as text and parsed with the same mapper the webhook path uses
- ✅ Connect/read timeouts on the Stripe client. An unbounded read turns someone else's outage
  into ours: request threads pile up on a socket that will never answer
- ✅ `PaymentFailureIT` with a `ControllableGateway` — an outage leaves the charges reserved and
  ref-less, and a retry recovers *those* rows rather than creating a second set
- ✅ A timeout never marks a payment FAILED. "No answer" means unknown, not declined — only the
  gateway may settle a payment
- ✅ Decline → clean rollback: payment FAILED, booking PAYMENT_FAILED, **no ledger entries**, and
  the dates genuinely free (proven by booking the same item for the same days). A further pay
  attempt is a 409 and never reaches the gateway
- ✅ `WebhookIdempotencyIT` — 8 simultaneous copies of one event → 1 PROCESSED, 7 DUPLICATE, one
  ledger movement; fee and deposit arriving together confirm exactly once
- ✅ The claim-rollback proof: a delivery that fails halfway leaves `processed_webhooks` empty,
  so the gateway's retry is honoured instead of being answered "already done"
- ✅ All tests green: 66 unit + 29 integration

---

## Week 4 — Async & realtime (off-thread + live updates)
**Goal:** slow work is off the request path; status updates are live.

### Day 16 — Event publishing
- ✅ `EventPublisher` + `InMemoryPublisher` (logs and retains a bounded window). Day 17 swaps in
  RabbitMQ behind the same interface, and nothing that publishes has to change
- ✅ `DomainEvent` sealed over `PaymentSucceeded`, `BookingConfirmed`, `ReturnRecorded` — past
  tense facts, carrying amounts so a consumer never has to call back into us and race the
  transaction that produced the event
- ✅ **Delivery waits for the commit.** Publishing inline would announce facts that can still roll
  back — "your booking is confirmed" for a booking the database never kept. Registered as an
  `afterCommit` synchronisation, so a rollback simply never fires it
- ✅ Published from the webhook handler: `PaymentSucceeded` per cleared charge, `BookingConfirmed`
  only once every charge is in. A failed payment announces nothing — nothing happened
- ✅ Tests: rollback publishes nothing (unit), and a duplicate delivery publishes once (IT)
- ✅ All tests green: 66 unit + 29 integration

### Day 17 — RabbitMQ + notifications
- ✅ `RabbitConfig` — one topic exchange, a notifications queue bound with `#`, and a
  dead-letter queue behind it. A message the consumer can never handle would otherwise loop
  forever and starve everything queued behind it
- ✅ A topic exchange rather than a direct send, because the publisher must not know who
  listens — Day 19 binds a second queue to the same events and publishing doesn't change
- ✅ `RabbitEventPublisher` is now the default (`app.events.publisher=rabbit`);
  `InMemoryPublisher` stays for broker-less runs. The after-commit rule moved into a shared
  `AfterCommitPublisher` base so both obey it and neither can forget
- ✅ **A broker outage cannot fail a committed request.** The publish happens after commit, so
  throwing there would 500 a request whose work is already durable. It logs `LOST EVENT`
  instead — and Day 20's outbox is the real fix, because a lost notification should be
  replayable, not just mourned
- ✅ JSON on the wire, with trusted packages pinned — readable in the RabbitMQ console, and
  not a deserialization hole
- ✅ `NotificationConsumer` + `EmailService` (console). Confirmation emails **both** parties;
  a failed payment emails nobody
- ✅ `RabbitNotificationIT` runs the whole async path against the real broker: webhook commits
  → event published → consumer thread → email. All tests green: 66 unit + 32 integration

### Day 18 — Scheduled workers
- ✅ `settlement/` — `Return` entity on V5's table, `SettlementService`,
  `POST /bookings/{id}/return`. **Owner-only**: they took the item back, so they say what state
  it's in; a renter cannot close their own damage claim
- ✅ The deposit split is the movement the ledger was built for — `DEPOSIT_HELD` debited in
  full, credited to `RENTER_REFUND` and `OWNER_PAYABLE`. OK → RETURNED, DAMAGED → DISPUTED
- ✅ Idempotent by replay, not by 409: `returns.booking_id` is UNIQUE, and a retried
  confirmation returns the original record rather than releasing the deposit twice
- ✅ **Extracted `PaymentSettlement`** from `WebhookService` first. Reconciliation must settle a
  payment exactly the way a webhook does, and two copies of that would drift — in accounting
- ✅ `PaymentGateway.fetchStatus` + `GatewayPaymentStatus`, with WireMock tests for Stripe's
  vocabulary. PENDING ("not yet") and UNKNOWN ("we couldn't find out") are kept apart because
  only one of them is ever safe to act on
- ✅ `ReconciliationWorker` — sweeps payments PENDING past a cutoff, asks the gateway, settles
  what it confirms. Each payment in its own transaction and re-read inside it, so one bad row
  can't end the sweep and a webhook landing mid-sweep isn't applied twice
- ✅ A stale payment with **no gateway ref** is flagged for a human, never failed: the Day 15
  timeout may have left a real intent behind, and guessing would strand a real charge
- ✅ `DepositReleaseWorker` — closes bookings whose dispute window passed with no claim.
  DISPUTED is untouched, however long it sits
- ✅ `BookingActivationWorker` — CONFIRMED → ACTIVE on the start date. Not in the original plan,
  but nothing else moved a booking there, so a return was unreachable
- ✅ Workers are plain beans; only `@EnableScheduling` sits behind `app.workers.enabled`. Tests
  invoke a sweep directly rather than waiting for one
- ✅ All tests green: 68 unit + 47 integration

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
