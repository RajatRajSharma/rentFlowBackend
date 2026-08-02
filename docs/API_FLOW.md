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

    F4 -->|"denied"| E403(["403 END"])
    F4 -->|"allowed"| D

    D["DISPATCHER SERVLET<br/>matches METHOD + PATH to a controller method"]
    B["ARGUMENT BINDING<br/>@RequestBody JSON to DTO<br/>@Valid constraints<br/>@PathVariable<br/>@AuthenticationPrincipal"]
    D --> B
    B -->|"validation fails"| E400(["400 END"])
    B --> CTRL

    CTRL["CONTROLLER - thin<br/>AuthController / ItemController / VersionController"]
    SVC["SERVICE - fat<br/>UserService / ItemService<br/>@Transactional + business rules"]
    OG["OwnershipGuard.java"]
    REPO["REPOSITORY - dumb<br/>UserRepository / ItemRepository<br/>method name becomes SQL"]
    DB[("POSTGRESQL<br/>users · items")]
    DTO["Entity to DTO<br/>UserResponse.from / ItemResponse.from"]
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

    AC -->|"RegisterRequest<br/>LoginRequest"| US["UserService.java"]
    IC -->|"CreateItemRequest<br/>UpdateItemRequest"| IS["ItemService.java"]

    US --- PE["PasswordEncoder<br/>BCrypt"]
    AC --- JWT
    IS --> OG["OwnershipGuard.java"]

    US --> UR["UserRepository.java"]
    IS --> IR["ItemRepository.java"]

    UR --> UE["User.java<br/>users table"]
    IR --> IE["Item.java<br/>items table"]

    UE --> UDTO["UserResponse<br/>AuthResponse"]
    IE --> IDTO["ItemResponse"]
    VC --> VDTO["Map name/version/status"]

    UDTO --> OUT
    IDTO --> OUT
    VDTO --> OUT
    OUT(["JSON to CLIENT"])

    classDef sec fill:#fff3d6,stroke:#d89a00
    classDef ctrl fill:#e3f0ff,stroke:#3178c6
    classDef svc fill:#efe3ff,stroke:#7a4fbf
    classDef repo fill:#e0f5e0,stroke:#0a0
    class JAF,JWT,AU,SC,OG,PE sec
    class AC,IC,VC ctrl
    class US,IS svc
    class UR,IR,UE,IE repo
```

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
    FC -->|"happens BEFORE DispatcherServlet"| BYP["never reaches GlobalExceptionHandler<br/>Spring default 403 body, not ApiError"]

    classDef gap fill:#ffe0e0,stroke:#c00,color:#900
    class FC,BYP gap
```

---

## 8. Startup — before any request

```mermaid
flowchart TD
    A["./mvnw spring-boot:run"] --> B["RentflowBackendApplication.main()"]
    B --> C["load config<br/>application.yml then .env then env vars"]
    C --> D["component scan of com.rentflow.**<br/>@RestController @Service @Component @Configuration @Entity"]
    D --> E["beans built + constructor injected<br/>ItemController takes ItemService takes ItemRepository + OwnershipGuard"]
    E --> F["DataSource connects to PostgreSQL<br/>localhost:5433 via docker-compose"]
    F --> G["Flyway runs V1__users.sql then V2__items.sql<br/>recorded in flyway_schema_history"]
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
