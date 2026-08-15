# RentFlow Backend — API Flow (API_FLOW.md)

Diagrams only. Shapes, status codes and validation rules live in [API_DOCS.md](docs/API_DOCS.md).

> Rendered by GitHub natively. In VS Code install the **Markdown Preview Mermaid Support** extension.

---

## 1. The full pipeline

Every request travels this path.

```mermaid
flowchart TD
    C["CLIENT<br/>React / Postman / curl<br/>fetch + Authorization: Bearer token"]
    T["TOMCAT<br/>embedded server, port 8080"]

    C -->|HTTP| T --> F1

    subgraph SEC["SECURITY FILTER CHAIN"]
        direction TB
        F1["JwtAuthFilter.java<br/>read Bearer token"]
        F2["JwtService.java<br/>verify signature + expiry"]
        F3["SecurityContext<br/>AuthenticatedUser uid, email, role"]
        F4["SecurityConfig.java<br/>route permission table"]
        F1 --> F2
        F2 -->|valid| F3
        F2 -->|"invalid / missing"| F3x["stay anonymous<br/>never throws"]
        F3 --> F4
        F3x --> F4
    end

    F4 -->|"denied - RestAuthEntryPoint"| E403(["401 / 403 END"])
    F4 -->|"allowed"| D

    D["DISPATCHER SERVLET<br/>matches METHOD + PATH to a controller method<br/>/auth/** · /items/** · /bookings/** · /api/version"]
    B["ARGUMENT BINDING<br/>@RequestBody JSON to DTO<br/>@Valid constraints<br/>@PathVariable<br/>@AuthenticationPrincipal"]
    D --> B
    B -->|"validation fails"| E400(["400 END"])
    B --> CTRL

    CTRL["CONTROLLER - thin<br/>Auth / Item / Booking / Availability / Version"]
    SVC["SERVICE - fat<br/>UserService / ItemService / BookingService<br/>@Transactional + business rules"]
    OG["OwnershipGuard.java<br/>BookingStateMachine.java<br/>RedisLockManager.java"]
    REPO["REPOSITORY - dumb<br/>User / Item / Booking repositories<br/>method name becomes SQL"]
    DB[("POSTGRESQL<br/>users · items · bookings")]
    DTO["Entity to DTO<br/>UserResponse / ItemResponse / BookingResponse"]
    R(["200 / 201 JSON to CLIENT"])

    CTRL --> SVC
    SVC -.->|"not your row"| OG
    OG -->|ForbiddenException| E403b(["403 END"])
    SVC --> REPO -->|Hibernate to JDBC| DB
    DB -->|"tx commits on return"| DTO --> R

    classDef err fill:#ffe0e0,stroke:#c00,color:#900
    classDef ok fill:#e0f5e0,stroke:#0a0,color:#060
    class E403,E400,E403b err
    class R ok
```

---

## 2. File-to-file chain

```mermaid
flowchart TD
    REQ(["REQUEST"]) --> JAF

    JAF["JwtAuthFilter.java"]
    JWT["JwtService.java"]
    AU["AuthenticatedUser.java"]
    SC["SecurityConfig.java"]

    JAF --- JWT
    JAF --- AU
    JAF --> SC

    SC --> AC["AuthController.java"]
    SC --> IC["ItemController.java"]
    SC --> VC["VersionController.java"]
    SC --> BC["BookingController.java"]
    SC --> AVC["ItemBookingController.java"]

    AC -->|"RegisterRequest<br/>LoginRequest"| US["UserService.java"]
    IC -->|"CreateItemRequest<br/>UpdateItemRequest"| IS["ItemService.java"]
    BC -->|"CreateBookingRequest"| BS["BookingService.java"]
    AVC --> BS

    US --- PE["PasswordEncoder<br/>BCrypt"]
    AC --- JWT
    IS --> OG["OwnershipGuard.java"]
    BS --> OG
    BS -->|"needs rate + owner<br/>SELECT FOR UPDATE"| IS
    BS --- BSM["BookingStateMachine.java"]
    BS --- LMG["RedisLockManager.java<br/>lock:item:id"]

    US --> UR["UserRepository.java"]
    IS --> IR["ItemRepository.java"]
    BS --> BR["BookingRepository.java<br/>findOverlapping"]

    UR --> UE["User.java<br/>users table"]
    IR --> IE["Item.java<br/>items table"]
    BR --> BE["Booking.java<br/>bookings table<br/>EXCLUDE constraint"]

    UE --> UDTO["UserResponse<br/>AuthResponse"]
    IE --> IDTO["ItemResponse"]
    BE --> BDTO["BookingResponse<br/>AvailabilityResponse"]
    VC --> VDTO["Map name/version/status"]

    UDTO --> OUT
    IDTO --> OUT
    BDTO --> OUT
    VDTO --> OUT
    OUT(["JSON to CLIENT"])

    classDef sec fill:#fff3d6,stroke:#d89a00
    classDef ctrl fill:#e3f0ff,stroke:#3178c6
    classDef svc fill:#efe3ff,stroke:#7a4fbf
    classDef repo fill:#e0f5e0,stroke:#0a0
    class JAF,JWT,AU,SC,OG,PE,BSM,LMG sec
    class AC,IC,VC,BC,AVC ctrl
    class US,IS,BS svc
    class UR,IR,BR,UE,IE,BE repo
```

Note the dependency direction: `BookingService` uses `ItemService`, never the reverse. That's why
the availability endpoint lives in the booking package even though its URL starts with `/items` —
putting it in `ItemController` would create a cycle.

---

## 3. POST /auth/register

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant SC as SecurityConfig
    participant AC as AuthController
    participant US as UserService
    participant PE as PasswordEncoder
    participant UR as UserRepository
    participant DB as PostgreSQL

    CL->>SC: POST /auth/register {name, email, password}
    Note over SC: /auth/** is public
    SC->>AC: register(@Valid RegisterRequest)

    alt validation fails
        AC-->>CL: 400 + fieldErrors
    else valid
        AC->>US: register(request)
        Note over US: @Transactional
        US->>UR: existsByEmail(email)
        UR->>DB: SELECT count(*) FROM users WHERE email = ?
        alt email taken
            US-->>CL: 409 DuplicateEmailException
        else free
            US->>PE: encode(password)
            PE-->>US: BCrypt hash
            US->>UR: save(new User(..., Role.USER))
            UR->>DB: INSERT INTO users
            DB-->>US: generated id
            US-->>AC: User
            AC-->>CL: 201 UserResponse.from(user) - no passwordHash
        end
    end
```

---

## 4. POST /auth/login

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant AC as AuthController
    participant US as UserService
    participant UR as UserRepository
    participant PE as PasswordEncoder
    participant JS as JwtService

    CL->>AC: POST /auth/login {email, password}
    AC->>US: authenticate(email, password)
    Note over US: @Transactional readOnly
    US->>UR: findByEmail(email)

    alt user not found
        US-->>CL: 401 Invalid email or password
    else found
        US->>PE: matches(raw, passwordHash)
        alt mismatch
            US-->>CL: 401 Invalid email or password
        else match
            US-->>AC: User
            AC->>JS: generateToken(user)
            Note over JS: sub=email · uid=id · role · exp=+24h<br/>signed with app.jwt.secret
            JS-->>AC: JWT
            AC-->>CL: 200 {accessToken, tokenType Bearer, user}
        end
    end

    Note over CL: stores token, sends it as<br/>Authorization: Bearer on every later call
```

---

## 5. GET /items and GET /items/{id} — public

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant SC as SecurityConfig
    participant IC as ItemController
    participant IS as ItemService
    participant IR as ItemRepository
    participant DB as PostgreSQL

    CL->>SC: GET /items or GET /items/7
    Note over SC: GET /items and /items/* are public<br/>a token, if sent, is parsed but not required
    SC->>IC: list() or get(id)
    IC->>IS: findAll() or get(id)
    Note over IS: @Transactional readOnly
    IS->>IR: findAll() / findById(id)
    IR->>DB: SELECT * FROM items

    alt id not found
        IS-->>CL: 404 Item not found
    else ok
        IR-->>IS: Item(s)
        IS-->>IC: Item(s)
        IC-->>CL: 200 ItemResponse list or single
    end
```

---

## 6. POST /items and PUT /items/{id} — protected

Two gates: **authentication** in the filter chain, then **ownership** in the service.

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant JF as JwtAuthFilter
    participant SC as SecurityConfig
    participant IC as ItemController
    participant IS as ItemService
    participant OG as OwnershipGuard
    participant IR as ItemRepository
    participant DB as PostgreSQL

    CL->>JF: POST /items or PUT /items/7 + Bearer token
    JF->>JF: JwtService.parse(token)

    alt token missing, invalid or expired
        JF->>SC: request stays anonymous
        SC-->>CL: 403 END
    else token valid
        JF->>SC: SecurityContext = AuthenticatedUser(uid=3)
        SC->>IC: create(...) or update(id, ...)
        Note over IC: @Valid body · @AuthenticationPrincipal<br/>ownerId comes from the TOKEN, never the body

        alt validation fails
            IC-->>CL: 400 + fieldErrors
        else valid
            IC->>IS: create(request, 3) / update(7, request, 3)
            Note over IS: @Transactional

            opt PUT only
                IS->>IR: findById(7)
                alt not found
                    IS-->>CL: 404
                end
                IS->>OG: requireOwner(item.ownerId, 3)
                alt owner mismatch
                    OG-->>CL: 403 You do not own this resource
                end
            end

            IS->>IR: save(item)
            IR->>DB: INSERT INTO items / UPDATE items WHERE id=? AND version=?
            Note over DB: @Version optimistic lock<br/>racing update is rejected
            DB-->>IS: saved row
            IS-->>IC: Item
            IC-->>CL: 201 (POST) or 200 (PUT) ItemResponse
        end
    end
```

---

## 6b. GET /items/{id}/availability — public

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant SC as SecurityConfig
    participant AV as ItemAvailabilityController
    participant BS as BookingService
    participant IS as ItemService
    participant BR as BookingRepository

    CL->>SC: GET /items/2/availability?from=&to=
    Note over SC: /items/*/availability is public
    SC->>AV: availability(id, from, to)
    Note over AV: @DateTimeFormat parses the ISO dates<br/>unparseable or missing param leads to 400
    AV->>BS: findBlocking(id, from, to)

    alt to before from, or over 90 days
        BS-->>CL: 400 InvalidDateRangeException
    else valid range
        BS->>IS: get(itemId)
        alt item missing
            IS-->>CL: 404
        else exists
            BS->>BR: findOverlapping(id, BLOCKING, from, to)
            Note over BR: start <= :to AND end >= :from<br/>status in PENDING_PAYMENT, CONFIRMED, ACTIVE
            BR-->>BS: blocking bookings
            BS-->>AV: list
            AV-->>CL: 200 available + bookedRanges
        end
    end
```

---

## 6c. POST /bookings — the concurrency path

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant JF as JwtAuthFilter
    participant BC as BookingController
    participant BS as BookingService
    participant LM as RedisLockManager
    participant RD as Redis
    participant IS as ItemService
    participant BR as BookingRepository
    participant DB as PostgreSQL

    CL->>JF: POST /bookings + Bearer token
    alt no or invalid token
        JF-->>CL: 401 END
    else authenticated as uid=4
        JF->>BC: create(@Valid CreateBookingRequest, principal)
        Note over BC: renter = token uid, never the body
        alt validation fails
            BC-->>CL: 400
        else valid
            BC->>BS: create(request, renterId=4)
            BS->>BS: requireValidRange — cheap checks before taking a lock

            BS->>LM: withLock("item:7")
            LM->>RD: tryLock wait=3s lease=10s
            alt lock not acquired in time
                RD-->>CL: 409 too many concurrent requests, retry
            else acquired
                Note over BS,DB: TRANSACTION OPENS INSIDE THE LOCK

                BS->>IS: getForUpdate(itemId)
                IS->>DB: SELECT ... FOR UPDATE — row lock, defence 2
                Note over BS: item missing leads to 404<br/>item not ACTIVE leads to 409<br/>you own it leads to 403

                BS->>BR: findOverlapping(itemId, BLOCKING, start, end)
                alt dates taken
                    BR-->>CL: 409 already booked
                else free
                    BS->>BS: total = dailyRate x days<br/>deposit copied from item
                    BS->>BR: saveAndFlush(booking PENDING_PAYMENT)
                    BR->>DB: INSERT INTO bookings
                    alt EXCLUDE constraint rejects the row — defence 3
                        DB-->>BS: DataIntegrityViolationException
                        BS-->>CL: 409 just booked by someone else
                    else accepted
                        DB-->>BS: saved
                    end
                end

                Note over BS,DB: TRANSACTION COMMITS
                BS->>LM: unlock — only AFTER the commit
                LM->>RD: release item:7
                BS-->>BC: Booking
                BC-->>CL: 201 BookingResponse
            end
        end
    end
```

**The ordering is the whole point.** The lock is taken *outside* the transaction and released
*after* it commits. `BookingService.create` uses a `TransactionTemplate` instead of
`@Transactional` precisely so this nesting is visible in the code rather than hidden in an
annotation. Get it backwards — lock inside the transaction — and the lock is released while the
commit is still in flight, letting the next writer read stale data and book the same dates.

**Why `saveAndFlush` and not `save`:** `save` only queues the INSERT, so the constraint would fire
at commit time — outside the method, where the exception can no longer be translated into a clean
409. Flushing forces the database to check *here*.

---

## 6d. Booking state machine

Legal transitions live in one table in
[BookingStateMachine.java](src/main/java/com/rentflow/booking/BookingStateMachine.java).
Anything not drawn below is rejected with a 409.

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : POST /bookings
    PENDING_PAYMENT --> CONFIRMED : payment success
    PENDING_PAYMENT --> PAYMENT_FAILED : fail or timeout
    PENDING_PAYMENT --> CANCELLED : cancel
    CONFIRMED --> ACTIVE : start date reached
    CONFIRMED --> CANCELLED : cancel per rules
    ACTIVE --> RETURNED : item returned
    RETURNED --> CLOSED : deposit settled
    RETURNED --> DISPUTED : damage claimed
    DISPUTED --> CLOSED : dispute resolved
    PAYMENT_FAILED --> [*]
    CANCELLED --> [*]
    CLOSED --> [*]

    note right of ACTIVE
        Not cancellable —
        money and possession
        have already moved
    end note
    note left of PENDING_PAYMENT
        These three block the calendar:
        PENDING_PAYMENT, CONFIRMED, ACTIVE.
        Same list as the DB exclusion constraint.
    end note
```

Two transitions are wired to endpoints today: **create** (`POST /bookings` → `PENDING_PAYMENT`)
and **cancel** (`POST /bookings/{id}/cancel` → `CANCELLED`). The rest are defined and tested but
unreachable until payments (Week 3) and the return flow exist.

---

## 6f. POST /bookings/{id}/cancel

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant BC as BookingController
    participant BS as BookingService
    participant BR as BookingRepository
    participant OG as OwnershipGuard
    participant SM as BookingStateMachine

    CL->>BC: POST /bookings/7/cancel + Bearer token
    BC->>BS: cancel(7, currentUserId)
    Note over BS: @Transactional
    BS->>BR: findById(7)
    alt not found
        BR-->>CL: 404
    else found
        BS->>OG: requireOwner(booking.renterId, currentUserId)
        alt not the renter
            OG-->>CL: 403 You do not own this resource
        else is the renter
            BS->>SM: requireTransition(status, CANCELLED)
            alt illegal from this state
                SM-->>CL: 409 Cannot move a booking from X to CANCELLED
            else legal
                BS->>BR: save(status = CANCELLED)
                Note over BR: CANCELLED is not in BLOCKING,<br/>so the dates are free again immediately
                BC-->>CL: 200 BookingResponse
            end
        end
    end
```

---

## 6e. The three defences against double-booking

```mermaid
flowchart TD
    R1["Request A<br/>Oct 10-15"] --> L1
    R2["Request B<br/>Oct 12-18"] --> L1

    L1["1. Redis lock on item:id<br/>one writer across ALL instances"]
    L2["2. SELECT FOR UPDATE on the item row<br/>the database serialises writers too"]
    L3["3. EXCLUDE USING gist<br/>Postgres refuses to store the row"]

    L1 -->|"lock busy, wait expired"| C1(["409 retry"])
    L1 -->|"acquired"| L2
    L2 -->|"overlap query finds a clash"| C2(["409 already booked"])
    L2 -->|"dates free"| L3
    L3 -->|"constraint fires"| C3(["409 backstop"])
    L3 -->|"clean"| OK(["201 booking created"])

    classDef done fill:#e0f5e0,stroke:#0a0
    class L1,L2,L3 done
```

All three are live. Each alone has a hole — 1 fails if Redis is down, 2 fails if someone forgets
the transaction, 3 gives a correct but unfriendly error. Together they leave none.

Measured, not assumed —
[BookingConcurrencyIT](src/test/java/com/rentflow/booking/BookingConcurrencyIT.java) fires 500
simultaneous requests at one slot:

```
 requests fired      : 500
 succeeded           : 1
 rejected (conflict) : 499
 unexpected errors   : 0
 bookings in database: 1
```

A second test repeats it with layer 1 bypassed entirely and still gets exactly one booking. A third
proves the locking doesn't reject legitimate non-overlapping bookings.

---

## 6g. POST /bookings/{id}/pay — the idempotency path

```mermaid
sequenceDiagram
    autonumber
    participant CL as Client
    participant PC as BookingPaymentController
    participant PS as PaymentService
    participant ID as IdempotencyService
    participant LM as LockManager
    participant PR as PaymentRepository
    participant GW as PaymentGateway

    CL->>PC: POST /bookings/7/pay + Bearer + Idempotency-Key: abc
    Note over PC: a missing header is a 400 before<br/>any of this runs
    PC->>PS: pay(7, renterId, "abc")
    PS->>PS: requireUsableKey("abc")
    PS->>PR: findById(7) + requireOwner(renterId)
    Note over PS: validated on EVERY call, replay or not —<br/>a 403 must stay a 403

    PS->>ID: once("pay:7", replay, reserve)
    ID->>PR: findByIdempotencyKeyIn([b7:abc:FEE, b7:abc:DEPOSIT])
    alt already exists (the ordinary retry)
        PR-->>ID: 2 rows
        Note over ID: fast path — no lock, no work
    else nothing yet
        ID->>LM: withLock("idem:pay:7")
        Note over LM: scope is the BOOKING, so a different<br/>key cannot race past this
        ID->>PR: replay AGAIN under the lock
        Note over ID: skipping this re-check is the classic<br/>double-checked-locking bug
        alt someone else just finished
            PR-->>ID: 2 rows
        else still nothing
            ID->>PS: reserve(booking, "abc")
            PS->>PS: canTransition(status, CONFIRMED)?
            Note over PS: gates CREATING charges, not REPLAYING them
            PS->>PR: any live (non-FAILED) payments for booking 7?
            alt yes — a different key already created them
                PR-->>PS: reuse those
            else no
                PS->>PR: saveAll(FEE 2000 PENDING, DEPOSIT 5000 PENDING)
                Note over PR: UNIQUE(idempotency_key) is the<br/>last-resort backstop here
            end
        end
    end

    loop each payment still unsettled
        PS->>GW: createIntent(sameIdempotencyKey, amount)
        Note over GW: called on replays too, on purpose:<br/>the gateway dedupes and returns the<br/>original intent + a usable client secret
        GW-->>PS: pi_xxx + clientSecret
        PS->>PR: UPDATE gateway_ref WHERE id = ? AND gateway_ref IS NULL
        Note over PR: a conditional UPDATE, not save():<br/>concurrent retries all hold version 0, so an<br/>entity save would fail every thread but one
    end

    PC-->>CL: 200 PaymentIntentResponse (fee + deposit, both PENDING)
```

Note the ordering: the rows are written **before** the gateway is called. That is what makes a
retry safe — the key is claimed in our database before any money can move, so a duplicate arriving
mid-flight collides on the UNIQUE constraint instead of starting a second charge. Charge-then-write
means a crash in between takes the customer's money with no record of it.

The gateway call sits deliberately **outside** any transaction. A network call inside one pins a
database connection for as long as someone else's outage lasts, which turns a slow gateway into a
dead application.

And nothing here ever sets `SUCCEEDED`. Creating an intent means the gateway agreed to *try*; the
webhook (Day 13) is what resolves it.

Measured, not assumed —
[PaymentIdempotencyIT](src/test/java/com/rentflow/payment/PaymentIdempotencyIT.java) fires 100
simultaneous pay requests with one key:

```
 pay requests fired  : 100
 responses returned  : 100
 failures            : 0
 payment rows in db  : 2
 distinct payment ids: 2
 total charged       : 7000.00
```

---

## 6h. POST /webhooks/payments — where a payment actually resolves

```mermaid
sequenceDiagram
    autonumber
    participant GW as Gateway
    participant WC as WebhookController
    participant WS as WebhookService
    participant PG as PaymentGateway
    participant PW as ProcessedWebhookRepository
    participant PR as PaymentRepository
    participant LS as LedgerService
    participant BR as BookingRepository
    participant EP as EventPublisher

    GW->>WC: POST /webhooks/payments + Stripe-Signature
    Note over WC: body bound as a RAW String —<br/>the signature covers the exact bytes,<br/>so a re-serialised DTO could never verify
    WC->>WS: handle(rawPayload, signature)
    Note over WS: @Transactional — the claim and the<br/>work commit or roll back together

    WS->>PG: parseWebhook(payload, signature)
    Note over PG: HMAC-SHA256 over "{t}.{body}"<br/>constant-time compare<br/>±300s replay window
    alt signature bad / stale / unparseable
        PG-->>GW: 400 — stops redelivery of a payload<br/>that will never become valid
    else verified
        alt event type we don't act on
            WS-->>GW: 200 IGNORED (not even recorded)
        else actionable
            WS->>PW: INSERT event_id ON CONFLICT DO NOTHING
            alt 0 rows — seen before
                WS-->>GW: 200 DUPLICATE, nothing happens
            else 1 row — first delivery
                WS->>PR: findByGatewayRef(pi_xxx)
                alt no such payment
                    PR-->>GW: 404 (event from another environment)
                else found
                    alt succeeded
                        WS->>PR: markSucceeded + flush
                        WS->>LS: post(debit RENTER_CASH, credit OWNER_PAYABLE / DEPOSIT_HELD)
                        Note over LS: validates debits == credits BEFORE saving,<br/>MANDATORY propagation so it can't commit<br/>while the payment update rolls back
                        WS->>EP: publish(PaymentSucceeded)
                        Note over EP: queued for afterCommit — a fact<br/>that can still roll back isn't announced
                        WS->>BR: findByIdForUpdate(bookingId)
                        Note over BR: SELECT FOR UPDATE — fee and deposit<br/>events can arrive simultaneously
                        alt every charge cleared
                            WS->>BR: status = CONFIRMED
                            WS->>EP: publish(BookingConfirmed)
                        else still waiting on one
                            Note over WS: not confirmed — handing over an item<br/>whose deposit never cleared is the bug
                        end
                    else failed
                        WS->>PR: markFailed(reason)
                        Note over LS: no ledger entry — no money moved.<br/>The ledger records what HAPPENED.
                        WS->>BR: status = PAYMENT_FAILED
                        Note over BR: not in BLOCKING, so the dates<br/>are released with no extra step
                    end
                    WS-->>GW: 200 PROCESSED
                end
            end
        end
    end
```

**The status code is an instruction.** A 2xx tells the gateway to stop redelivering; a 5xx tells it
to retry for days. So duplicates and ignored types answer 200, a bad signature answers 400, an
unknown payment answers 404 — and only genuinely transient failures fall through to a 500 and get
retried.

**Claim before you act, in one transaction.** If the work throws, the claim rolls back with it, so
the retry gets a real second chance rather than being turned away by a marker for work that never
happened. Claiming in a separate transaction produces the opposite bug: an event recorded as
processed that wasn't.

Proven by [PaymentWebhookIT](src/test/java/com/rentflow/payment/PaymentWebhookIT.java) — a
redelivered event posts no second movement, and the books balance at 7000 = 7000 — and by
[WebhookIdempotencyIT](src/test/java/com/rentflow/payment/WebhookIdempotencyIT.java), which fires
8 copies of one event at once (1 processed, 7 duplicates, one ledger movement) and shows that a
delivery failing halfway leaves the event id free for the retry.

**Events are announced after the commit.** `publish` registers an `afterCommit` callback rather
than delivering inline, so a rollback never sends "your booking is confirmed" for a booking the
database didn't keep.

---

## 7. Error path

```mermaid
flowchart TD
    V["@Valid fails"] --> MANV["MethodArgumentNotValidException"]
    L["bad email or password"] --> IC["InvalidCredentialsException"]
    O["not your row"] --> FE["ForbiddenException"]
    M["missing row"] --> NF["NotFoundException"]
    D["email already used"] --> DE["DuplicateEmailException"]

    MANV --> GEH
    IC --> GEH
    FE --> GEH
    NF --> GEH
    DE --> GEH

    GEH["GlobalExceptionHandler.java<br/>@RestControllerAdvice<br/>one @ExceptionHandler per type"]
    GEH --> AE["ApiError<br/>timestamp · status · error · message · fieldErrors"]

    AE --> S400["400 validation"]
    AE --> S401["401 login"]
    AE --> S403["403 ownership"]
    AE --> S404["404 not found"]
    AE --> S409["409 duplicate email"]

    FC["REJECTED IN THE FILTER CHAIN<br/>no token / expired token"]
    FC -->|"happens BEFORE DispatcherServlet"| REP["RestAuthEntryPoint<br/>writes the SAME ApiError shape"]
    REP --> S401b["401 no usable credentials"]
    REP --> S403b["403 authenticated but denied"]

    CATCH["anything unexpected"] --> S500["500 logged server-side,<br/>bland message returned"]

    classDef ok fill:#e0f5e0,stroke:#0a0
    class REP ok
```

Every error the API returns has the identical `ApiError` shape, whether it came from a controller,
a service, or the security filter chain.

---

## 8. Startup — before any request

```mermaid
flowchart TD
    A["./mvnw spring-boot:run"] --> B["RentflowBackendApplication.main()"]
    B --> C["load config<br/>application.yml then .env then env vars"]
    C --> D["component scan of com.rentflow.**<br/>@RestController @Service @Component @Configuration @Entity"]
    D --> E["beans built + constructor injected<br/>ItemController takes ItemService takes ItemRepository + OwnershipGuard"]
    E --> F["DataSource connects to PostgreSQL<br/>localhost:5433 via docker-compose"]
    F --> G["Flyway runs V1__users.sql, V2__items.sql, V3__bookings_and_exclusion.sql<br/>recorded in flyway_schema_history"]
    G --> H{"Hibernate ddl-auto = validate<br/>entities match real tables?"}
    H -->|no| X(["app fails to start"])
    H -->|yes| I["SecurityFilterChain built<br/>JwtAuthFilter before UsernamePasswordAuthenticationFilter"]
    I --> J["route table built from<br/>@GetMapping @PostMapping @PutMapping"]
    J --> K(["Tomcat listening on :8080"])

    classDef err fill:#ffe0e0,stroke:#c00,color:#900
    classDef ok fill:#e0f5e0,stroke:#0a0,color:#060
    class X err
    class K ok
```

---

## 9. Layer contract

```mermaid
flowchart LR
    CT["CONTROLLER<br/>thin"] --> SV["SERVICE<br/>fat"] --> RP["REPOSITORY<br/>dumb"] --> DBX[("DATABASE")]

    CT -.- CTn["bind URL · @Valid · read caller<br/>call one service · map to DTO<br/>NO business rules, NO SQL"]
    SV -.- SVn["business logic · @Transactional<br/>ownership checks<br/>NO HTTP awareness"]
    RP -.- RPn["data access only<br/>method name becomes SQL<br/>NO logic"]

    classDef note fill:#f7f7f7,stroke:#bbb,color:#444
    class CTn,SVn,RPn note
```
