# RentFlow Backend — API Reference (API_DOCS.md)

Every HTTP endpoint the backend currently exposes, with request/response shapes, auth rules,
validation rules, and error cases. Generated from the code as of **Week 1 (auth + item CRUD)**.

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
7. [Data models](#7-data-models)
8. [Status code reference](#8-status-code-reference)
9. [Quick cURL walkthrough](#9-quick-curl-walkthrough)
10. [Not implemented yet](#10-not-implemented-yet)

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

Anything not listed above falls through to `.anyRequest().authenticated()` and returns **403**
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
never throws. A missing, malformed, or expired token simply leaves the request *unauthenticated* —
so a protected route answers **403** (Spring Security's default for an anonymous denial), not 401.
The only 401 in the app today comes from a failed login.

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
| `InvalidCredentialsException` | 401 | Login email not found **or** password mismatch |
| `ForbiddenException` | 403 | Editing a resource you don't own |
| `NotFoundException` | 404 | Item id doesn't exist |
| `DuplicateEmailException` | 409 | Registering an already-used email |

> Note: Spring Security rejections (no/invalid token on a protected route) are produced by the
> filter chain *before* the handler runs, so they return Spring's default body, not `ApiError`.

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
| 403 | No token / invalid or expired token |

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
| 403 | No/invalid token, **or** valid token but you aren't the owner → `You do not own this resource` |
| 404 | `Item not found: 42` |

Order of checks in `ItemService.update`: **load → 404 if missing → ownership → 403 if not yours → apply**.
`status` and `ownerId` are not editable through this endpoint.

**Concurrency:** `Item` carries a JPA `@Version` column. If two updates race, the second save fails
with an optimistic-lock error rather than silently overwriting the first (lost update).

---

## 7. Data models

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

### `ApiError`
| Field | Type | Notes |
|-------|------|-------|
| `timestamp` | Instant | ISO-8601 |
| `status` | int | numeric HTTP status |
| `error` | String | reason phrase, e.g. `"Not Found"` |
| `message` | String | human-readable detail |
| `fieldErrors` | Map<String,String> | validation only, else `null` |

### Underlying tables
`users` ([V1__users.sql](src/main/resources/db/migration/V1__users.sql)) and
`items` ([V2__items.sql](src/main/resources/db/migration/V2__items.sql)). Both inherit
`created_at` / `updated_at` from [Auditable](src/main/java/com/rentflow/common/audit/Auditable.java);
those columns exist in the DB but are **not** exposed in any response DTO today.

---

## 8. Status code reference

| Code | Used for |
|------|----------|
| 200 | Successful GET / PUT / login |
| 201 | `POST /auth/register`, `POST /items` |
| 400 | Bean-validation failure on a `@Valid` body |
| 401 | Failed login only |
| 403 | Missing/invalid token on a protected route, or ownership violation |
| 404 | Unknown item id |
| 409 | Duplicate email at register |
| 503 | `/actuator/health` when a dependency is DOWN |

---

## 9. Quick cURL walkthrough

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

# 6. Prove the guards work
curl -X POST http://localhost:8080/items -H "Content-Type: application/json" \
  -d '{"title":"x","dailyRate":1,"depositAmount":0}'                    # → 403, no token
curl http://localhost:8080/items/9999                                    # → 404
```

On PowerShell, replace the `TOKEN=$(...)` line with:
```powershell
$TOKEN = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/auth/login `
  -ContentType 'application/json' `
  -Body '{"email":"rajat@example.com","password":"supersecret123"}').accessToken
```

---

## 10. Not implemented yet

Planned in [README.md](docs/README.md) §6 / [PLAN.md](docs/PLAN.md) but **not** in the code today —
don't expect them to respond:

- `DELETE /items/{id}`, `GET /items/mine`, filtering/pagination on `GET /items`
- `GET /items/{id}/availability` — already whitelisted as public in `SecurityConfig` in anticipation,
  but no handler exists, so it currently 404s
- The whole booking engine (`/bookings/**`), payments (`/payments/**`, webhooks)
- GraphQL admin analytics, WebSocket payment-status push
- Refresh tokens / logout — a token is valid until it expires, there is no revocation
- `GET /users/me` — the token already carries id/email/role, but nothing serves the profile

Update this file whenever a controller changes.
