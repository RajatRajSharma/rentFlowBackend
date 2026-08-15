# RentFlow Backend — API Reference (API_DOCS.md)

Every HTTP endpoint the backend currently exposes, with request/response shapes, auth rules,
validation rules, and error cases. Generated from the code as of **Week 2 complete (booking engine)**.

- **Base URL (local):** `http://localhost:8080`
- **Content type:** `application/json` for all request and response bodies
- **Auth scheme:** stateless JWT — `Authorization: Bearer <accessToken>`
- **Source of truth:** [SecurityConfig.java](src/main/java/com/rentflow/security/SecurityConfig.java) for
  who can call what; the controllers for shapes.

> For *how* a request travels through the layers, see [API_FLOW.md](docs/API_FLOW.md).

---

## Table of Contents
1. [Endpoint summary](#1-endpoint-summary)
2. [Authentication](#2-authentication)
3. [Common error format](#3-common-error-format)
4. [System endpoints](#4-system-endpoints)
5. [Auth endpoints](#5-auth-endpoints)
6. [Item endpoints](#6-item-endpoints)
7. [Booking endpoints](#7-booking-endpoints)
8. [Payment endpoints](#8-payment-endpoints)
9. [Data models](#9-data-models)
10. [Status code reference](#10-status-code-reference)
11. [Quick cURL walkthrough](#11-quick-curl-walkthrough)
12. [Not implemented yet](#12-not-implemented-yet)

---

## 1. Endpoint summary

| # | Method | Path | Auth | Purpose |
|---|--------|------|------|---------|
| 1 | GET | `/actuator/health` | Public | Liveness + db/redis/rabbit component health |
| 2 | GET | `/actuator/info` | Public | Build/app info from `info.app.*` |
| 3 | GET | `/api/version` | Public | App name/version/description (our own controller) |
| 4 | POST | `/auth/register` | Public | Create an account |
| 5 | POST | `/auth/login` | Public | Exchange credentials for a JWT |
| 6 | GET | `/items` | Public | Browse all listings |
| 7 | GET | `/items/{id}` | Public | Fetch one listing |
| 8 | POST | `/items` | **Bearer** | Create a listing (owner = caller) |
| 9 | PUT | `/items/{id}` | **Bearer + owner** | Update a listing you own |
| 10 | GET | `/items/{id}/availability` | Public | Are these dates free? |
| 11 | POST | `/bookings` | **Bearer** | Book an item for a date range |
| 12 | GET | `/bookings/me` | **Bearer** | My bookings as a renter |
| 13 | POST | `/bookings/{id}/cancel` | **Bearer + renter** | Cancel a booking you made |
| 14 | GET | `/items/mine/bookings` | **Bearer** | Bookings on items I own |
| 15 | POST | `/bookings/{id}/pay` | **Bearer + renter** | Create the payment intents (fee + deposit) |
| 16 | GET | `/bookings/{id}/payments` | **Bearer + renter** | What has been charged for this booking |
| 17 | POST | `/webhooks/payments` | **HMAC signature** | Gateway payment callbacks (idempotent) |

Anything not listed above falls through to `.anyRequest().authenticated()` and returns **401**
when unauthenticated.

---

## 2. Authentication

RentFlow is **stateless**: there is no server-side session. Identity is carried by a signed JWT
on every request.

**Get a token:** `POST /auth/login` → `accessToken`.

**Use it:**
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9....
```

**Token contents** ([JwtService.java](src/main/java/com/rentflow/security/JwtService.java)):

| Claim | Meaning |
|-------|---------|
| `sub` | user's email |
| `uid` | user's numeric id — this is what ownership checks compare |
| `role` | `USER` or `ADMIN` |
| `iat` / `exp` | issued-at / expiry — **24 h** (`app.jwt.expiration-ms: 86400000`) |

Signed with HMAC-SHA using `app.jwt.secret` (env `JWT_SECRET`, must be ≥ 32 chars).

**Behaviour on a bad token:** [JwtAuthFilter](src/main/java/com/rentflow/security/JwtAuthFilter.java)
never throws. A missing, malformed, or expired token simply leaves the request *unauthenticated*;
[RestAuthEntryPoint](src/main/java/com/rentflow/security/RestAuthEntryPoint.java) then answers
**401** with the standard `ApiError` body.

The split is the standard one:

| Code | Means |
|------|-------|
| **401** Unauthorized | "I don't know who you are" — no token, malformed, or expired |
| **403** Forbidden | "I know who you are, and you still may not" — ownership violations |

### Two layers of authorization
| Layer | Question | Where |
|-------|----------|-------|
| Authentication | Do you have a valid token? | `SecurityConfig` + `JwtAuthFilter` |
| Ownership | Is *this row* yours? | [OwnershipGuard](src/main/java/com/rentflow/security/OwnershipGuard.java), called from the service |

Owner vs renter is **not** a role — every `USER` can both list and rent. Whether you may edit a
given item is decided per-resource by comparing `item.owner_id` with the token's `uid`.

---

## 3. Common error format

Every handled exception returns the same body, produced by
[GlobalExceptionHandler](src/main/java/com/rentflow/common/exception/GlobalExceptionHandler.java):

```json
{
  "timestamp": "2026-08-02T10:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fieldErrors": {
    "email": "must be a valid email",
    "password": "password must be at least 8 characters"
  }
}
```

`fieldErrors` is `null` for everything except validation failures.

| Exception | HTTP | Thrown when |
|-----------|------|-------------|
| `MethodArgumentNotValidException` | 400 | `@Valid` body constraints fail |
| `MissingServletRequestParameterException` | 400 | A required query param is absent |
| `MethodArgumentTypeMismatchException` | 400 | A query param can't be parsed, e.g. `?from=xyz` |
| `HttpMessageNotReadableException` | 400 | Malformed or missing JSON body |
| `InvalidDateRangeException` | 400 | End before start, or a range longer than 90 days |
| `InvalidCredentialsException` | 401 | Login email not found **or** password mismatch |
| *(security filter chain)* | 401 | No, malformed or expired token on a protected route |
| `ForbiddenException` | 403 | Acting on a resource you don't own, or booking your own item |
| `NotFoundException` | 404 | Item or booking id doesn't exist |
| `DuplicateEmailException` | 409 | Registering an already-used email |
| `BookingConflictException` | 409 | Dates already taken, or the item isn't `ACTIVE` |
| `IllegalTransitionException` | 409 | A booking status change the state machine forbids |
| `LockAcquisitionException` | 409 | Item under heavy contention; the lock timed out — retry |
| `ObjectOptimisticLockingFailureException` | 409 | Someone else modified the row first (`@Version`) |
| `DataIntegrityViolationException` | 409 | Any constraint a service didn't already translate |
| `Exception` (catch-all) | 500 | Anything unexpected — logged server-side, bland message out |

Security rejections are produced by the filter chain *before* the handler runs, so they're
formatted separately by `RestAuthEntryPoint` — but into the **same** `ApiError` shape, so every
error the API returns looks identical regardless of where it came from.

---

## 4. System endpoints

### 4.1 `GET /actuator/health`
Public. Reports app liveness plus per-component status (`show-details: always`).

**200**
```json
{
  "status": "UP",
  "components": {
    "db":      { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis":   { "status": "UP" },
    "rabbit":  { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```
Returns **503** with `"status": "DOWN"` if a dependency is unreachable.

### 4.2 `GET /actuator/info`
Public. Echoes the `info.app.*` keys from `application.yml`.

### 4.3 `GET /api/version`
Public. Our own controller ([VersionController](src/main/java/com/rentflow/common/VersionController.java)) —
exists to prove the controller layer works end to end, independent of Actuator.

**200**
```json
{
  "name": "RentFlow Backend",
  "version": "0.0.1-SNAPSHOT",
  "description": "Equipment rental marketplace — backend",
  "status": "OK"
}
```

---

## 5. Auth endpoints

Controller: [AuthController.java](src/main/java/com/rentflow/user/AuthController.java) — base path `/auth`.

### 5.1 `POST /auth/register`
Create an account. Public. Role is always `USER` — it is **not** accepted from the request.

**Request** (`RegisterRequest`)
```json
{
  "name": "Rajat Sharma",
  "email": "rajat@example.com",
  "password": "supersecret123"
}
```

| Field | Type | Rules |
|-------|------|-------|
| `name` | string | `@NotBlank` — "name is required" |
| `email` | string | `@NotBlank`, `@Email` — "must be a valid email" |
| `password` | string | `@NotBlank`, `@Size(min = 8)` — "password must be at least 8 characters" |

**201 Created** (`UserResponse` — note there is no `passwordHash`; the DTO exposes only what's safe)
```json
{
  "id": 1,
  "name": "Rajat Sharma",
  "email": "rajat@example.com",
  "role": "USER"
}
```

**Errors**
| Code | Cause |
|------|-------|
| 400 | Validation failed → `fieldErrors` map |
| 409 | `Email already registered: rajat@example.com` |

The password is BCrypt-hashed in `UserService.register` before it ever reaches the DB.

---

### 5.2 `POST /auth/login`
Exchange credentials for a JWT. Public.

**Request** (`LoginRequest`)
```json
{
  "email": "rajat@example.com",
  "password": "supersecret123"
}
```

| Field | Type | Rules |
|-------|------|-------|
| `email` | string | `@NotBlank`, `@Email` |
| `password` | string | `@NotBlank` |

**200 OK** (`AuthResponse`)
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyYWphdEBleGFtcGxlLmNvbSIsInVpZCI6MSwicm9sZSI6IlVTRVIifQ.xxx",
  "tokenType": "Bearer",
  "user": { "id": 1, "name": "Rajat Sharma", "email": "rajat@example.com", "role": "USER" }
}
```

**Errors**
| Code | Cause |
|------|-------|
| 400 | Validation failed |
| 401 | `Invalid email or password` |

The 401 message is deliberately vague for **both** the unknown-email and wrong-password cases, so
the endpoint can't be used to enumerate registered emails.

---

## 6. Item endpoints

Controller: [ItemController.java](src/main/java/com/rentflow/item/ItemController.java) — base path `/items`.
`GET` routes are public (browsing shouldn't need an account); writes need a token.

### 6.1 `GET /items`
Public. Returns every listing. No pagination or filtering yet.

**200** — array of `ItemResponse`
```json
[
  {
    "id": 1,
    "ownerId": 1,
    "title": "Sony A7 III Camera",
    "description": "Full-frame mirrorless, 2 batteries included",
    "dailyRate": 1500.00,
    "depositAmount": 20000.00,
    "status": "ACTIVE"
  }
]
```

### 6.2 `GET /items/{id}`
Public. Fetch one listing.

| Param | In | Type |
|-------|----|------|
| `id` | path | `Long` |

**200** — a single `ItemResponse` (shape above).

**Errors**
| Code | Cause |
|------|-------|
| 404 | `Item not found: 42` |

---

### 6.3 `POST /items` 🔒
Create a listing. **Requires a Bearer token.** The owner is taken from the token's `uid` — it is
never read from the request body, so you cannot create a listing on someone else's behalf.

**Request** (`CreateItemRequest`)
```json
{
  "title": "Sony A7 III Camera",
  "description": "Full-frame mirrorless, 2 batteries included",
  "dailyRate": 1500.00,
  "depositAmount": 20000.00
}
```

| Field | Type | Rules |
|-------|------|-------|
| `title` | string | `@NotBlank` — "title is required" |
| `description` | string | optional, no constraints |
| `dailyRate` | BigDecimal | `@NotNull`, `@DecimalMin(0.0, exclusive)` — "dailyRate must be positive" |
| `depositAmount` | BigDecimal | `@NotNull`, `@DecimalMin(0.0)` — "depositAmount cannot be negative" |

Money is `BigDecimal` end to end (`NUMERIC(12,2)` in Postgres) — never `double`/`float`.

**201 Created** — the saved `ItemResponse`, with server-assigned `id`, `ownerId`, and
`status: "ACTIVE"`.

**Errors**
| Code | Cause |
|------|-------|
| 400 | Validation failed |
| 401 | No token / invalid or expired token |

---

### 6.4 `PUT /items/{id}` 🔒 owner-only
Full replace of the mutable fields of a listing you own.

| Param | In | Type |
|-------|----|------|
| `id` | path | `Long` |

**Request** (`UpdateItemRequest`) — same four fields and same rules as `CreateItemRequest`. All
four are sent every time; this is a PUT (replace), not a PATCH (merge).

**200 OK** — the updated `ItemResponse`.

**Errors**
| Code | Cause |
|------|-------|
| 400 | Validation failed |
| 401 | No or invalid token |
| 403 | Valid token, but you aren't the owner → `You do not own this resource` |
| 404 | `Item not found: 42` |

Order of checks in `ItemService.update`: **load → 404 if missing → ownership → 403 if not yours → apply**.
`status` and `ownerId` are not editable through this endpoint.

**Concurrency:** `Item` carries a JPA `@Version` column. If two updates race, the second save fails
with an optimistic-lock error rather than silently overwriting the first (lost update).

---

## 7. Booking endpoints

Controllers: [BookingController.java](src/main/java/com/rentflow/booking/BookingController.java) and
[ItemAvailabilityController.java](src/main/java/com/rentflow/booking/ItemAvailabilityController.java).

**Dates are inclusive on both ends.** `2026-09-10` → `2026-09-12` is **three** billable days.
All dates are ISO `YYYY-MM-DD`.

### 7.1 `GET /items/{id}/availability?from=&to=`
Public. Are these dates free, and if not, which windows are taken?

| Param | In | Type | Required |
|-------|----|------|----------|
| `id` | path | `Long` | yes |
| `from` | query | ISO date | yes |
| `to` | query | ISO date | yes |

**200 OK** — free
```json
{ "itemId": 2, "from": "2026-09-10", "to": "2026-09-12", "available": true, "bookedRanges": [] }
```

**200 OK** — partly taken
```json
{
  "itemId": 2,
  "from": "2026-09-01",
  "to": "2026-09-30",
  "available": false,
  "bookedRanges": [ { "startDate": "2026-09-10", "endDate": "2026-09-12" } ]
}
```

`bookedRanges` deliberately omits who booked the dates — this endpoint is public, so a calendar
can grey out unavailable days without leaking other users' activity.

**Errors**
| Code | Cause |
|------|-------|
| 400 | Missing `from`/`to`, unparseable date, `to` before `from`, or a window over 90 days |
| 404 | Unknown item id |

Only `PENDING_PAYMENT`, `CONFIRMED` and `ACTIVE` bookings block dates. A `CANCELLED`,
`PAYMENT_FAILED`, `RETURNED` or `CLOSED` booking releases its dates.

---

### 7.2 `POST /bookings` 🔒
Create a booking. **Requires a Bearer token.** The renter is taken from the token, and the price is
computed server-side — a client cannot name its own price.

**Request** (`CreateBookingRequest`)
```json
{ "itemId": 2, "startDate": "2026-09-10", "endDate": "2026-09-12" }
```

| Field | Type | Rules |
|-------|------|-------|
| `itemId` | Long | `@NotNull` — "itemId is required" |
| `startDate` | ISO date | `@NotNull`, `@FutureOrPresent` — "startDate cannot be in the past" |
| `endDate` | ISO date | `@NotNull`, must not be before `startDate`, max 90 days total |

**201 Created** (`BookingResponse`)
```json
{
  "id": 1,
  "itemId": 2,
  "renterId": 4,
  "startDate": "2026-09-10",
  "endDate": "2026-09-12",
  "days": 3,
  "status": "PENDING_PAYMENT",
  "totalAmount": 4500.00,
  "depositAmount": 20000.00
}
```

`totalAmount` = `item.dailyRate` × billable days. `depositAmount` is copied from the item. **Both are
snapshots** — if the owner later edits the item's price, this booking keeps the figures the renter
agreed to.

Every booking is created in `PENDING_PAYMENT`, which already holds the dates against everyone else.
Payment (Week 3) is what flips it to `CONFIRMED`.

**Errors**
| Code | Cause |
|------|-------|
| 400 | Validation failed, end before start, or over 90 days |
| 401 | No or invalid token |
| 403 | You are the item's owner → `You cannot book your own item` |
| 404 | `Item not found: 999` |
| 409 | `Item 3 is already booked between 2026-10-12 and 2026-10-18`, or the item isn't `ACTIVE` |

### The no-double-booking guarantee
Two ranges overlap iff `existing.start <= new.end AND existing.end >= new.start`. That one condition
covers overlap at the front, at the back, fully inside, and fully surrounding.

Three layers enforce it:

| # | Layer | What it stops | Status |
|---|-------|---------------|--------|
| 1 | Redis distributed lock on `item:{id}` (Redisson `RLock`) | Two requests entering the check at once, **across all instances** | ✅ live |
| 2 | `SELECT … FOR UPDATE` on the item row | The same, at the database, if Redis is down or skipped | ✅ live |
| 3 | Postgres `EXCLUDE USING gist` constraint | Storing an overlapping row **at all**, whatever the app does | ✅ live |

Each layer alone has a hole — layer 1 fails if Redis is down, layer 2 fails if someone forgets the
transaction, layer 3 gives a correct but unfriendly error. Together they leave none.

**Lock ordering matters.** The Redis lock is acquired *outside* the transaction and released only
after it commits (`BookingService.create` uses a `TransactionTemplate` rather than `@Transactional`
to make this visible). The classic bug is the reverse: releasing the lock while the transaction is
still committing lets a second writer read stale data and book the same dates.

`saveAndFlush` — not `save` — forces the constraint to fire inside the service method, where it can
be translated into a clean 409. A plain `save` defers the INSERT to commit time, outside reach,
where it surfaces as an opaque 500.

**Proven, not asserted** — see [BookingConcurrencyIT](src/test/java/com/rentflow/booking/BookingConcurrencyIT.java):

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

A second test repeats it with the Redis lock bypassed entirely and still gets exactly one booking,
proving layers 2 and 3 hold on their own. A third confirms locking doesn't reject *legitimate*
non-overlapping bookings — 20 concurrent requests for 20 distinct days all succeed.

Adjacent bookings are fine: a booking ending the 15th and another starting the 16th both succeed.

---

### 7.3 `GET /bookings/me` 🔒
Your own bookings as a renter, newest start date first. Returns `[]` for a user who has never
booked — it never shows bookings made by anyone else.

**200 OK** — array of `BookingResponse` (shape above).

**Errors**
| Code | Cause |
|------|-------|
| 401 | No or invalid token |

---

### 7.4 `POST /bookings/{id}/cancel` 🔒 renter-only
Cancel a booking you made. No request body.

**POST, not DELETE** — cancelling is a state change, not a deletion. The row survives for history,
refunds and analytics; only its `status` changes.

| Param | In | Type |
|-------|----|------|
| `id` | path | `Long` |

**200 OK** — the updated `BookingResponse` with `"status": "CANCELLED"`.

**Errors**
| Code | Cause |
|------|-------|
| 401 | No or invalid token |
| 403 | You aren't the renter → `You do not own this resource` |
| 404 | `Booking not found: 999` |
| 409 | The state machine forbids it, e.g. `Cannot move a booking from CANCELLED to CANCELLED` |

Only `PENDING_PAYMENT` and `CONFIRMED` bookings can be cancelled. Once a booking is `ACTIVE` the
renter physically has the item — it has to be returned, not cancelled.

**Cancelling frees the dates immediately.** `CANCELLED` isn't in the blocking set, so it drops out
of both the overlap query and the DB exclusion constraint, and the same range can be rebooked at
once — including by someone else.

---

### 7.5 `GET /items/mine/bookings` 🔒
The owner's side of the marketplace: every booking placed on items **you** own, newest start date
first. The owner id comes from the token, so you can only ever see your own.

**200 OK** — array of `BookingResponse`. `[]` if you own no items, or nobody has booked them.

**Errors**
| Code | Cause |
|------|-------|
| 401 | No or invalid token |

> Note the pairing: `/bookings/me` is your history **as a renter**; `/items/mine/bookings` is your
> inbound demand **as an owner**. Same account, two roles — which is the whole point of the
> one-account-type model.

---

## 8. Payment endpoints

Money endpoints differ from every other endpoint in this API in one way: **a repeated request is
not a new request.** Everything below is built around that.

### 8.1 `POST /bookings/{id}/pay` 🔒 renter-only

Create (or replay) the payment intents for a booking you rented. **No request body** — the amounts
come from the booking, so a client can never name its own price, and the renter comes from the JWT.

| Param | In | Type | Notes |
|-------|----|------|-------|
| `id` | path | `Long` | Booking id |
| `Idempotency-Key` | **header** | `String` | **Required.** Non-blank, ≤ 200 chars |

**Why the header is required.** Generating a key server-side would defeat the purpose: a retry
would generate a different one and charge twice. On a money endpoint, silently accepting that risk
is not a default worth having — so a missing header is a **400**, not a guess.

**200 OK, not 201** — a retry with the same key is the *same* request and must give the *same*
answer, so this can't honestly claim to have created something each time.

```json
{
  "bookingId": 12,
  "bookingStatus": "PENDING_PAYMENT",
  "currency": "INR",
  "totalCharged": 7000.00,
  "payments": [
    {
      "id": 31, "bookingId": 12, "type": "FEE",
      "amount": 2000.00, "status": "PENDING",
      "gatewayRef": "pi_fake_37634a5f4fe098ee",
      "clientSecret": "pi_fake_37634a5f4fe098ee_secret_9c1d...",
      "failureReason": null
    },
    {
      "id": 32, "bookingId": 12, "type": "DEPOSIT",
      "amount": 5000.00, "status": "PENDING",
      "gatewayRef": "pi_fake_2403a90f30b57c28",
      "clientSecret": "pi_fake_2403a90f30b57c28_secret_4a70...",
      "failureReason": null
    }
  ]
}
```

**Fee and deposit are separate charges, on purpose.** They behave completely differently after this
point: the fee is *earned* and owed to the owner; the deposit is only *held* and, unless damage is
claimed, given back. Charging them as one lump sum would make the return settlement impossible to
reason about — and the renter is entitled to see which is which before paying.

**`status` is always `PENDING` here.** Creating an intent means the gateway agreed to *try*, not
that money moved. Only the webhook (Day 13) or the reconciliation sweep (Day 18) sets `SUCCEEDED`.
Nothing in the request path decides its own outcome.

**Errors**
| Code | Cause |
|------|-------|
| 400 | `Missing required header: Idempotency-Key`, or a blank / over-long key |
| 401 | No or invalid token |
| 403 | You aren't the renter → `You do not own this resource` |
| 404 | `Booking not found: 999` |
| 409 | The booking can no longer be confirmed (cancelled, failed, already returned) |
| 409 | The booking has no amount to charge |
| 502 | The payment provider is unavailable — *your booking is unchanged, please retry* |

> **Why 502 and not 500.** Your request was fine; our downstream wasn't. A 500 would blame us and
> send you off to fix a request with nothing wrong with it; a 4xx would imply there's something for
> you to correct. 502 says "retry later", which is the only useful thing we can tell you.

### The idempotency guarantee

Three layers, because each alone has a hole — the same shape as the booking engine's
[no-double-booking guarantee](#the-no-double-booking-guarantee):

| # | Layer | Stops | Fails alone when |
|---|-------|-------|------------------|
| 1 | **Replay check** | The ordinary retry, with no lock and no work | Two requests arrive at once and both find nothing |
| 2 | **Distributed lock** on `pay:{bookingId}` | Concurrent attempts racing each other | Redis is down |
| 3 | **`payments.idempotency_key` UNIQUE** | A duplicate row, whatever else broke | Never — but it returns an error, not the caller's original result |

Plus a fourth rule that none of the three can express: **a booking has at most one live set of
charges, whatever key asks for them.** Without it, a renter retrying from a fresh browser tab —
new key, same booking — would get a second fee and a second deposit, and both could then be paid.
Key-level idempotency can't catch that: the keys differ, so nothing collides. `FAILED` payments are
excluded from "live", because a declined card is exactly when a genuinely new attempt *should* be
allowed.

The key you send is also forwarded to the gateway as its own `Idempotency-Key`, so even a retry
that gets all the way to Stripe replays the original intent instead of creating a second one.

Proven by `PaymentIdempotencyIT`: **100 simultaneous pay requests → 2 payment rows, ₹7000 charged,
every caller handed the same two charges.**

### 8.2 `GET /bookings/{id}/payments` 🔒 renter-only

Every payment recorded against a booking, oldest first.

**200 OK** — array of `PaymentResponse`. `clientSecret` is always `null` here: it's a bearer
credential for one intent, issued by the gateway and never stored, so it is only ever returned by
the endpoint that just obtained it.

**Errors**
| Code | Cause |
|------|-------|
| 401 | No or invalid token |
| 403 | You aren't the renter |
| 404 | `Booking not found: 999` |

### 8.3 `POST /webhooks/payments` 🔏 signature-authenticated

Where a payment actually resolves. The pay endpoint only creates intents; **this is the only
thing that sets a payment to `SUCCEEDED` or `FAILED`**, writes the ledger, and moves a booking to
`CONFIRMED` or `PAYMENT_FAILED`.

| Param | In | Notes |
|-------|----|-------|
| body | raw | The gateway's JSON, byte-for-byte |
| `Stripe-Signature` | header | `t={unix},v1={hex hmac}` |

**No JWT.** Stripe has no account here — the signature *is* the authentication, and a stronger
one than a bearer token. `permitAll` on this route means "authenticated by other means", not "open":
an unsigned request is still rejected.

**The body is a raw `String`, not a DTO.** The signature is computed over the exact bytes sent.
Binding to an object would mean verifying a re-serialised copy — different key order, different
whitespace, different bytes — and every signature would fail.

**200 OK**
```json
{ "result": "PROCESSED" }
```
| `result` | Meaning |
|----------|---------|
| `PROCESSED` | First delivery of an event we act on. State changed. |
| `DUPLICATE` | Already handled this event id. Nothing happened, and nothing should have. |
| `IGNORED` | Correctly signed, but not a type we act on (`charge.updated` and friends). |

### Status codes here are instructions, not decoration

A 2xx tells the gateway to stop redelivering; a 5xx tells it to retry for up to three days.
So the codes are chosen for what they make the gateway *do*:

| Code | Cause | Why |
|------|-------|-----|
| 200 | Processed, duplicate, or ignored | All three need no retry |
| 400 | Bad/missing signature, stale timestamp, unparseable body | Stops redelivery of a payload that will never become valid |
| 404 | No payment matches the gateway ref | Usually an event from another environment sharing the same test account. Day 18's reconciliation is the safety net if it was ours |
| 500 | Anything genuinely transient | Retried, which is what we want |

The 400 message is deliberately vague (`Webhook could not be verified`). Telling a prober whether
they got the signature or the timestamp wrong hands them half the answer; the detail goes to our logs.

### Duplicate delivery is a no-op

Every gateway delivers at-least-once, so "we got this event twice" is not an edge case — it's
Tuesday. The handler **claims the event id before it acts**, using
`INSERT ... ON CONFLICT DO NOTHING`. The insert *is* the check: a check-then-insert would be the
same check-then-act race the booking engine exists to avoid, and two simultaneous copies would
both write ledger entries.

The claim and the work share **one transaction**:

- Both commit → the event is recorded as done, and it is done.
- Anything throws → the claim rolls back too, so the gateway's retry gets a genuine second
  chance rather than being turned away by a marker for work that never happened.

### Replay protection

The timestamp is *inside* the signed payload, so it can't be edited without breaking the
signature. A ±300s tolerance (`WEBHOOK_TOLERANCE_SECONDS`) then bounds how long a captured
request stays usable — checked in **both** directions, since only rejecting old timestamps
leaves a request replayable forever by anyone whose clock runs fast.

The comparison is constant-time. A normal `equals` returns faster the earlier it finds a mismatched
byte, which leaks enough to forge a signature one byte at a time.

> The `fake` gateway signs and verifies **identically**, with its own secret. A fake that waved
> signatures through would leave the one security-critical path in this feature untested — on a
> public endpoint where a hole lets anyone confirm their own bookings for free.

### What a success actually does

1. Payment → `SUCCEEDED`.
2. A balanced ledger movement is posted (see [§9 `LedgerEntry`](#ledgerentry)).
3. A `payment.succeeded` event is published.
4. The booking is confirmed **only once every charge has cleared** — confirming on the fee alone
   would hand over an item whose damage deposit was never taken — and that publishes
   `booking.confirmed`.

A failure sets `FAILED` with the decline code, moves the booking to `PAYMENT_FAILED`, and writes
**no** ledger entries — no money moved. The ledger records what happened, not what was attempted.
`PAYMENT_FAILED` isn't in the blocking set, so the dates are released with no extra step.

### Events are delivered after the commit

Publishing happens through `EventPublisher`, and delivery is deferred to `afterCommit`. An email
saying "your booking is confirmed" for a transaction that then rolled back is worse than a late
email, so a rollback simply never fires it. Day 17 puts RabbitMQ behind the same interface.

---

## 9. Data models

### `UserResponse`
| Field | Type | Notes |
|-------|------|-------|
| `id` | Long | |
| `name` | String | |
| `email` | String | unique across the system |
| `role` | enum | `USER` \| `ADMIN` |

### `AuthResponse`
| Field | Type | Notes |
|-------|------|-------|
| `accessToken` | String | the JWT |
| `tokenType` | String | always `"Bearer"` |
| `user` | UserResponse | the logged-in account |

### `ItemResponse`
| Field | Type | Notes |
|-------|------|-------|
| `id` | Long | |
| `ownerId` | Long | FK → `users.id` |
| `title` | String | |
| `description` | String | nullable |
| `dailyRate` | BigDecimal | `NUMERIC(12,2)` |
| `depositAmount` | BigDecimal | `NUMERIC(12,2)` |
| `status` | String | `ACTIVE` \| `INACTIVE` (DB check constraint) |

### `BookingResponse`
| Field | Type | Notes |
|-------|------|-------|
| `id` | Long | |
| `itemId` | Long | FK → `items.id` |
| `renterId` | Long | FK → `users.id`, taken from the token at creation |
| `startDate` / `endDate` | LocalDate | inclusive both ends |
| `days` | long | billable days, derived |
| `status` | enum | see the state machine below |
| `totalAmount` | BigDecimal | `dailyRate × days`, snapshot |
| `depositAmount` | BigDecimal | copied from the item, snapshot |

### `AvailabilityResponse`
| Field | Type | Notes |
|-------|------|-------|
| `itemId` | Long | |
| `from` / `to` | LocalDate | echoes the query |
| `available` | boolean | true when `bookedRanges` is empty |
| `bookedRanges` | list | `{startDate, endDate}` — no renter identity |

### `BookingStatus`
| Status | Meaning | Blocks dates? |
|--------|---------|---------------|
| `PENDING_PAYMENT` | created, awaiting payment | ✅ |
| `CONFIRMED` | paid and reserved | ✅ |
| `ACTIVE` | renter has the item | ✅ |
| `RETURNED` | handed back, deposit unsettled | ❌ |
| `DISPUTED` | damage claimed | ❌ |
| `CLOSED` | deposit settled — terminal | ❌ |
| `CANCELLED` | cancelled — terminal | ❌ |
| `PAYMENT_FAILED` | payment failed/timed out — terminal | ❌ |

Legal transitions live in one place,
[BookingStateMachine.java](src/main/java/com/rentflow/booking/BookingStateMachine.java); anything
not in its table is rejected with a 409. The "blocks dates" column must stay in sync with the
`WHERE` clause of the exclusion constraint in
[V3__bookings_and_exclusion.sql](src/main/resources/db/migration/V3__bookings_and_exclusion.sql) —
a unit test asserts they match.

### `PaymentResponse`
| Field | Type | Notes |
|-------|------|-------|
| `id` | Long | |
| `bookingId` | Long | |
| `type` | `PaymentType` | `FEE` / `DEPOSIT` / `REFUND` |
| `amount` | BigDecimal | Always > 0 — zero-amount charges are never created |
| `status` | `PaymentStatus` | `PENDING` / `SUCCEEDED` / `FAILED` |
| `gatewayRef` | String | The provider's intent id. `null` until the gateway call succeeds |
| `clientSecret` | String | **Never stored.** Only returned by `POST .../pay`, and only while there's something left to pay |
| `failureReason` | String | Set on `FAILED`, e.g. `card_declined`; else `null` |

### `PaymentIntentResponse`
| Field | Type | Notes |
|-------|------|-------|
| `bookingId` | Long | |
| `bookingStatus` | `BookingStatus` | Echoed so a replaying client can see it was confirmed meanwhile |
| `currency` | String | `INR` by default |
| `totalCharged` | BigDecimal | Sum of the lines below |
| `payments` | PaymentResponse[] | Fee first, then deposit |

### `PaymentType`
| Type | Meaning |
|------|---------|
| `FEE` | The rental charge. Earned by the owner once the booking completes |
| `DEPOSIT` | Refundable damage hold — ours to hold, not ours to keep |
| `REFUND` | Money going back out (deposit release or partial refund) |

### `PaymentStatus`
| Status | Meaning |
|--------|---------|
| `PENDING` | Intent created; the gateway hasn't told us the outcome |
| `SUCCEEDED` | The gateway confirmed the money moved |
| `FAILED` | The gateway declined — see `failureReason` |

> **`PENDING` is not a failure.** A gateway that times out leaves a payment here, and assuming
> "no answer means no charge" is how real systems end up charging a customer whose booking they
> also cancelled. Only the gateway gets to say `SUCCEEDED` or `FAILED`.

### `LedgerEntry`
Not exposed by any endpoint today — documented because it's the record that makes the money
claims checkable.

| Field | Type | Notes |
|-------|------|-------|
| `id` | Long | |
| `bookingId` | Long | |
| `paymentId` | Long | Null for settlement movements with no gateway payment behind them |
| `account` | `LedgerAccount` | |
| `debit` | BigDecimal | Exactly one of debit/credit is non-zero — enforced by a DB CHECK |
| `credit` | BigDecimal | |
| `createdAt` | Instant | |

**Append-only.** No setters, no `updatedAt`, no `version`, and deliberately *not* `Auditable`
like every other entity — an accounting record you can edit is not an accounting record. A
mistake is corrected by posting a new balancing pair, never by rewriting history.

### `LedgerAccount`
| Account | Meaning |
|---------|---------|
| `RENTER_CASH` | Money in from the renter |
| `OWNER_PAYABLE` | What we owe the item's owner — earned fees, plus any damage claim |
| `DEPOSIT_HELD` | The refundable hold. Ours to sit on, not ours to keep |
| `RENTER_REFUND` | Money going back out to the renter |

> The `OWNER_PAYABLE` / `DEPOSIT_HELD` split is the point of the whole design. Both are cash
> sitting with us; only one is ever ours to pay out. Collapse them into a single "cash held"
> column and the only question that matters on return — *how much of this do we give back?* —
> becomes unanswerable.

**Worked example** (from [docs/README.md](docs/README.md) §11), and what
`PaymentWebhookIT` actually asserts:

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

Nothing unbalanced is ever written: `LedgerService.post()` validates before it saves, so an
unbalanced ledger isn't a bug you find later — it's a transaction that never committed. It also
uses `MANDATORY` propagation, so a ledger entry can never commit while the payment update that
caused it rolls back.

### `ApiError`
| Field | Type | Notes |
|-------|------|-------|
| `timestamp` | Instant | ISO-8601 |
| `status` | int | numeric HTTP status |
| `error` | String | reason phrase, e.g. `"Not Found"` |
| `message` | String | human-readable detail |
| `fieldErrors` | Map<String,String> | validation only, else `null` |

### Underlying tables
`users` ([V1__users.sql](src/main/resources/db/migration/V1__users.sql)),
`items` ([V2__items.sql](src/main/resources/db/migration/V2__items.sql)),
`bookings` ([V3__bookings_and_exclusion.sql](src/main/resources/db/migration/V3__bookings_and_exclusion.sql)),
`payments` + `ledger_entries`
([V4__payments_ledger.sql](src/main/resources/db/migration/V4__payments_ledger.sql)),
and `processed_webhooks` + `returns`
([V5__returns_webhooks.sql](src/main/resources/db/migration/V5__returns_webhooks.sql)).

`returns` has no entity yet — created now so Day 18's settlement service lands on an existing
schema. `processed_webhooks` uses the gateway's event id as its **primary key**, not a surrogate
id with an index: uniqueness of that value is the entire feature.

All the rest inherit `created_at` / `updated_at` from
[Auditable](src/main/java/com/rentflow/common/audit/Auditable.java); those columns exist in the DB
but are **not** exposed in any response DTO today.

---

## 10. Status code reference

| Code | Used for |
|------|----------|
| 200 | Successful GET / PUT / login, and `POST /bookings/{id}/pay` |
| 201 | `POST /auth/register`, `POST /items`, `POST /bookings` |
| 400 | Bean-validation failure, bad query param, invalid date range, or a missing/blank `Idempotency-Key` |
| 401 | Failed login only |
| 401 | No, invalid or expired token |
| 403 | Ownership violation, or booking your own item |
| 404 | Unknown item id |
| 409 | Duplicate email, dates already booked, or an illegal status transition |
| 400 | A webhook whose signature, timestamp or body doesn't verify |
| 404 | A webhook naming a payment we don't have |
| 502 | The payment gateway rejected us or never answered |
| 503 | `/actuator/health` when a dependency is DOWN |

---

## 11. Quick cURL walkthrough

```bash
# 0. Is it alive?
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/version

# 1. Register
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Rajat","email":"rajat@example.com","password":"supersecret123"}'

# 2. Login and capture the token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"rajat@example.com","password":"supersecret123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# 3. Create a listing (auth required)
curl -X POST http://localhost:8080/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Sony A7 III","description":"2 batteries","dailyRate":1500.00,"depositAmount":20000.00}'

# 4. Browse (no auth)
curl http://localhost:8080/items
curl http://localhost:8080/items/1

# 5. Update your own listing
curl -X PUT http://localhost:8080/items/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Sony A7 III (updated)","description":"3 batteries","dailyRate":1600.00,"depositAmount":20000.00}'

# 6. Check availability (public)
curl "http://localhost:8080/items/1/availability?from=2026-09-10&to=2026-09-12"

# 7. Book it — as a DIFFERENT user, since you can't book your own item
curl -X POST http://localhost:8080/bookings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RENTER_TOKEN" \
  -d '{"itemId":1,"startDate":"2026-09-10","endDate":"2026-09-12"}'      # → 201, PENDING_PAYMENT

# 8. Same dates again → the overlap guard fires
curl -X POST http://localhost:8080/bookings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RENTER_TOKEN" \
  -d '{"itemId":1,"startDate":"2026-09-11","endDate":"2026-09-11"}'      # → 409

# 9. My bookings, and the owner's inbound view
curl http://localhost:8080/bookings/me        -H "Authorization: Bearer $RENTER_TOKEN"
curl http://localhost:8080/items/mine/bookings -H "Authorization: Bearer $TOKEN"

# 10. Cancel it — then the same dates are bookable again
curl -X POST http://localhost:8080/bookings/1/cancel \
  -H "Authorization: Bearer $RENTER_TOKEN"                              # → 200, CANCELLED
curl -X POST http://localhost:8080/bookings/1/cancel \
  -H "Authorization: Bearer $RENTER_TOKEN"                              # → 409, already cancelled

# 11. Pay for it — fee + deposit, one idempotency key
KEY=$(uuidgen)
curl -X POST http://localhost:8080/bookings/2/pay \
  -H "Authorization: Bearer $RENTER_TOKEN" \
  -H "Idempotency-Key: $KEY"                                            # → 200, two PENDING charges

# 12. Send the SAME key again → identical response, still two rows, nothing charged twice
curl -X POST http://localhost:8080/bookings/2/pay \
  -H "Authorization: Bearer $RENTER_TOKEN" \
  -H "Idempotency-Key: $KEY"

# 13. Forget the header → 400, naming what's missing
curl -X POST http://localhost:8080/bookings/2/pay \
  -H "Authorization: Bearer $RENTER_TOKEN"                              # → 400

# 14. What has been charged so far
curl http://localhost:8080/bookings/2/payments -H "Authorization: Bearer $RENTER_TOKEN"

# 15. Prove the guards work
curl -X POST http://localhost:8080/items -H "Content-Type: application/json" \
  -d '{"title":"x","dailyRate":1,"depositAmount":0}'                    # → 401, no token
curl http://localhost:8080/items/9999                                    # → 404
```

On PowerShell, replace the `TOKEN=$(...)` line with:
```powershell
$TOKEN = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/auth/login `
  -ContentType 'application/json' `
  -Body '{"email":"rajat@example.com","password":"supersecret123"}').accessToken
```

---

## 12. Not implemented yet

Planned in [README.md](docs/README.md) §6 / [PLAN.md](docs/PLAN.md) but **not** in the code today —
don't expect them to respond:

- `DELETE /items/{id}`, `GET /items/mine`, filtering/pagination on `GET /items`
- The only booking transitions wired to an endpoint are *create* (→ `PENDING_PAYMENT`) and
  *cancel* (→ `CANCELLED`). `BookingStateMachine` knows the rest, but nothing calls them yet —
  `ACTIVE`/`RETURNED`/`CLOSED` need the return flow.
- No scheduled job moves `CONFIRMED → ACTIVE` when a start date arrives, and none expires a
  stale `PENDING_PAYMENT`, so an abandoned booking holds its dates until someone cancels it.
- **No reconciliation yet.** If a webhook never arrives, the payment sits `PENDING` forever —
  the sweep that polls the gateway and fixes it is Day 18.
- Refunds and settlement: `POST /bookings/{id}/return`, deposit release, partial refunds. Nothing
  creates a `REFUND` payment, and the ledger deliberately has no entries for one.
- Nothing prunes `processed_webhooks`, so it grows for as long as the app runs.
- The default gateway is `fake` — it takes no money. Set `PAYMENT_GATEWAY=stripe` with a
  `STRIPE_API_KEY` for the real test-mode API.
- GraphQL admin analytics, WebSocket payment-status push
- Refresh tokens / logout — a token is valid until it expires, there is no revocation
- `GET /users/me` — the token already carries id/email/role, but nothing serves the profile

Update this file whenever a controller changes.
