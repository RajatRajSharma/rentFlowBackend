# RentFlow Backend — Project Setup Guide (SETUP.md)

A step-by-step, reproducible guide to setting up this Java / Spring Boot project **from an empty
folder to a running server**. Every command has a 1–2 line explanation, and the choices we made
(dependencies, versions, naming) are all recorded here.

> Stack: **Java 17 target · Spring Boot 3 · Maven · PostgreSQL · Redis · RabbitMQ · GraphQL · WebSocket**
> Host machine used: **Windows 11 · PowerShell · JDK 25 installed** (we compile targeting 17 — see §9).

---

## 0. How Spring Boot setup actually works (the mental model)

You do **not** hand-write a Spring Boot project. You **generate a skeleton** from
[Spring Initializr](https://start.spring.io), then open it and start filling in code.

There are three equivalent ways to generate that skeleton — pick one:
1. **Website** — https://start.spring.io → tick dependencies → *Generate* → download zip. (Easiest.)
2. **IDE** — IntelliJ IDEA / VS Code "New Spring Boot Project". Same form, inside the editor.
3. **`curl` (CLI)** — scriptable version of the website. This is what this guide uses so every
   choice is written down explicitly.

The long `curl` command below is **not** something anyone memorizes — each `-d` flag is simply one
checkbox on the website turned into text.

---

## 1. Prerequisites

Install these first, then verify each one:

```powershell
java -version        # need a JDK (we have 25; project targets 17)
docker --version     # need Docker (runs Postgres/Redis/RabbitMQ)
curl.exe --version   # NOTE: use curl.exe, not curl — see the PowerShell warning below
```

| Tool | Why we need it | Note |
|------|----------------|------|
| **JDK** | compiles and runs the app | 17 is the target; a newer JDK (like 25) can compile *to* 17 |
| **Docker** | runs Postgres + Redis + RabbitMQ as containers | avoids installing three databases by hand |
| **Maven** | build tool | **not required** — the project ships a wrapper (`mvnw`) that downloads the right Maven for you |

> ⚠️ **PowerShell / VS Code terminal gotcha:** In PowerShell, `curl` is **not** the real curl — it's
> an alias for `Invoke-WebRequest`, which does not understand curl's flags (so `curl --version`
> errors out). Always type **`curl.exe`** (with the `.exe`) to get the real tool. **Simplest of all:
> skip curl entirely and use the website** https://start.spring.io — click the checkboxes, hit
> *Generate*, download the zip. The `curl` command in §2 is just the same thing written as text.

---

## 2. Generate the Spring Boot skeleton

This asks Spring's server to build a starter project with exactly the libraries we want and saves it
as a zip:

```bash
curl -sS https://start.spring.io/starter.zip \
  -d dependencies=web,data-jpa,security,validation,postgresql,data-redis,amqp,flyway,actuator,graphql,websocket \
  -d type=maven-project \
  -d javaVersion=17 \
  -d groupId=com.rentflow \
  -d artifactId=backend \
  -d packageName=com.rentflow \
  -d name=rentflow-backend \
  -o rentflow-backend.zip
```

- `-sS` → silent, but still show errors.
- `-d ...` → each one is a form field (a checkbox/textbox on the website).
- `-o rentflow-backend.zip` → save the server's reply to this file.

### What each dependency gives us

| Dependency | What it provides | Used in RentFlow for |
|------------|------------------|----------------------|
| `web` | Spring MVC + embedded Tomcat — write REST controllers | every `*Controller` |
| `data-jpa` | JPA + **Hibernate** — map Java objects ↔ DB tables so you don't write SQL by hand (JPA = the standard, Hibernate = the engine that implements it) | every entity + `*Repository` |
| `security` | Spring Security — login, JWT filter, route protection | `security/` package |
| `validation` | Bean Validation — `@NotNull`, `@Valid` on inputs | request DTOs |
| `postgresql` | PostgreSQL JDBC driver | talking to Postgres |
| `data-redis` | Spring Data Redis client | distributed locks, cache, pub/sub |
| `amqp` | Spring AMQP — RabbitMQ | `messaging/`, `notification/` |
| `flyway` | Versioned DB migrations, auto-run on startup | `db/migration/V*.sql` |
| `actuator` | Ops endpoints, e.g. `/actuator/health` | first-run health check |
| `graphql` | Spring for GraphQL | `analytics/` dashboard |
| `websocket` | WebSocket / STOMP | live payment status |

### What each metadata flag means

| Flag | Value | Meaning |
|------|-------|---------|
| `type` | `maven-project` | use **Maven** (not Gradle) |
| `javaVersion` | `17` | compile targeting Java 17 |
| `groupId` | `com.rentflow` | your org ID (reverse-domain convention) |
| `artifactId` | `backend` | the module / build name |
| `packageName` | `com.rentflow` | root Java package all code lives under |
| `name` | `rentflow-backend` | display name (used for the main class) |

`groupId` + `artifactId` together uniquely identify the project in the Maven ecosystem
(like `@scope/name` in npm).

### Choosing between similar-looking options (important!)

On the website you'll see several options with similar names. **Pick one per group — never all.**

**"Web" group — pick `Spring Web` only:**

| Option | What it is | Us? |
|--------|-----------|-----|
| **Spring Web** | Traditional, blocking, runs on Tomcat, classic MVC controllers | ✅ pick this — works naturally with JPA/Hibernate |
| Spring Reactive Web (WebFlux) | Non-blocking/async for very high concurrency; needs special reactive DB drivers | ❌ different model, would fight JPA |

**"Security" group — pick `Spring Security` only:**

| Option | What it's for | Us? |
|--------|---------------|-----|
| **Spring Security** | Core auth framework — login, filters, route protection | ✅ pick this |
| OAuth2 Client | "Sign in with Google/GitHub" — delegate login to someone else | ❌ we mint our own tokens |
| OAuth2 Resource Server | Validate tokens issued by an *external* identity provider | ❌ not our design |
| OAuth2 Authorization Server | Become an identity provider yourself | ❌ overkill |

Our design mints/verifies its **own JWT** in `JwtService`. That needs core **Spring Security** plus a
small JWT library (`jjwt`) — the JWT library is **not** an Initializr checkbox; we add it to `pom.xml`
manually later (see §6-ish, when we wire up security).

> ⚠️ **Gotcha we hit:** we first pinned `-d bootVersion=3.3.5` and the server rejected it with
> `Spring Boot compatibility range is >=3.5.0`. Fix: **omit `bootVersion`** and Initializr uses the
> latest supported release. Only pin a version if you have a specific reason to.

---

## 3. Extract the skeleton into this folder

```bash
unzip -o -q rentflow-backend.zip && rm rentflow-backend.zip
```

- `unzip -o -q` → extract, **o**verwrite silently, **q**uiet.
- `rm` → delete the zip once unpacked.

You now have:

```
mvnw, mvnw.cmd, .mvn/     ← the Maven wrapper (why you don't need Maven installed)
pom.xml                   ← project + dependency + build config
HELP.md, .gitignore
src/main/java/com/rentflow/RentflowBackendApplication.java   ← the entry point
src/main/resources/application.properties                    ← config
src/test/java/com/rentflow/RentflowBackendApplicationTests.java
```

**`pom.xml`** is the heart of a Maven project: it lists every dependency, the Java version, and how
to build. **`mvnw`** (Maven Wrapper) is a small script that downloads the correct Maven version
automatically — so everyone on the team builds identically without installing Maven.

---

## 4. Set up the docs folder

Keep all design/setup docs in one place (see `docs/BACKEND.md` §3):

```bash
mkdir -p docs/adr
mv BACKEND.md docs/BACKEND.md
mv RentFlow_README.md docs/README.md
```

- `mkdir -p docs/adr` → create `docs/` and `docs/adr/` in one go (`-p` = create parents, no error if exists).
- `mv` → move + rename the markdown files into `docs/`.

---

## 5. Create the feature-package folder structure

We use **package-by-feature** (see `docs/BACKEND.md` §2): everything about bookings lives in
`booking/`, payments in `payment/`, and so on — not one giant `controllers/` + `services/` split.

**PowerShell** (this machine's shell):

```powershell
$base = "src/main/java/com/rentflow"
"common/exception","common/config","common/dto","common/util","common/audit",
"security","user/dto","item/dto","booking/dto","payment/gateway","payment/dto",
"ledger","settlement","reconciliation","notification","realtime","analytics",
"messaging/events" | ForEach-Object { New-Item -ItemType Directory -Force "$base/$_" | Out-Null }

New-Item -ItemType Directory -Force "src/main/resources/db/migration","src/main/resources/graphql" | Out-Null
New-Item -ItemType Directory -Force "src/test/java/com/rentflow/booking","src/test/java/com/rentflow/payment","src/test/java/com/rentflow/support" | Out-Null
```

- `$base = ...` → store the long path once, reuse it.
- the list of `"folder/subfolder"` strings → every feature package from BACKEND.md.
- `ForEach-Object { New-Item -ItemType Directory -Force ... }` → loop and create each folder.
  `-Force` = PowerShell's `mkdir -p` (no error if it already exists).
- `Out-Null` → hide the per-folder output.

> Note: **Git and Java ignore empty folders** — they only "appear" once they contain a file. That's
> fine; real `.java` files land here as we build each feature.

---

## 6. Add the infrastructure files

### `docker-compose.yml` — the three backing services

Create `docker-compose.yml` in the project root:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: rentflow
      POSTGRES_USER: rentflow
      POSTGRES_PASSWORD: rentflow
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"     # AMQP
      - "15672:15672"   # management UI (http://localhost:15672, guest/guest)

volumes:
  pgdata:
```

- `services:` → each block is one container (Postgres, Redis, RabbitMQ).
- `image:` → which prebuilt Docker image to run.
- `ports: "host:container"` → expose the container's port on your machine.
- `volumes:` → persist Postgres data so it survives container restarts.

### `.env.example` — a template for secrets (never commit real secrets)

Create `.env.example` in the root:

```bash
# Copy to .env and fill in real values
DB_URL=jdbc:postgresql://localhost:5432/rentflow
DB_USER=rentflow
DB_PASSWORD=rentflow
REDIS_HOST=localhost
REDIS_PORT=6379
RABBIT_HOST=localhost
JWT_SECRET=change-me-to-a-long-random-string
STRIPE_API_KEY=sk_test_xxx
```

Then, when you start real work:

```bash
cp .env.example .env    # then edit .env with real values
```

---

## 7. Configure the application

Spring generated `application.properties`. We prefer **YAML** (cleaner for nested config). Rename it:

```bash
mv src/main/resources/application.properties src/main/resources/application.yml
```

Then put this in `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/rentflow
    username: rentflow
    password: rentflow
  jpa:
    hibernate:
      ddl-auto: validate      # Flyway owns the schema, not Hibernate
    open-in-view: false
  flyway:
    enabled: true             # runs db/migration/V*.sql on startup
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672

server:
  port: 8080
```

- `datasource` → how to reach Postgres.
- `jpa.hibernate.ddl-auto: validate` → Hibernate checks the schema but doesn't change it — **Flyway**
  is the source of truth for schema.
- `flyway.enabled` → auto-apply migrations on boot.
- `data.redis` / `rabbitmq` → where those services live.

---

## 8. Start everything and run the server

```bash
# 8a. Start the three backing services in the background
docker-compose up -d postgres redis rabbitmq

# 8b. Run the app (Flyway migrates the DB on startup)
./mvnw spring-boot:run
```

- `docker-compose up -d` → start containers **d**etached (in the background).
- `./mvnw spring-boot:run` → the wrapper downloads Maven if needed, compiles, and launches the app.
  On **Windows PowerShell** use `.\mvnw.cmd spring-boot:run` instead of `./mvnw`.

### Verify it's alive

Open http://localhost:8080/actuator/health in a browser, or run (note `curl.exe` on PowerShell):

```bash
curl.exe http://localhost:8080/actuator/health
```

Expected: `{"status":"UP"}`. That means the app started and reached its dependencies. 🎉

---

## 9. Note on Java version (we have 25, target is 17)

We told Initializr `javaVersion=17`, so Maven **compiles to Java 17 bytecode**. A newer JDK (25) can
always target an older version, so:

- **Compile:** JDK 25 produces Java 17 bytecode — fine.
- **Run:** the app runs on JDK 25 — Java is backward compatible — fine.
- **Only risk:** a build-time helper library (Lombok, or the bytecode tools Hibernate/Mockito use)
  occasionally lagging a brand-new JDK. Fix is a one-line library version bump, **not** uninstalling
  anything.

**Decision: keep Java 25, keep the target at 17.** Deal with any specific breakage if and when it
appears.

---

## 10. Day-to-day commands (cheat sheet)

```bash
docker-compose up -d postgres redis rabbitmq   # start backing services
./mvnw spring-boot:run                          # run the app (auto-migrates)
./mvnw test                                     # run all tests
./mvnw test -Dtest=BookingConcurrencyIT         # run one test
./mvnw clean package                            # build a runnable jar
docker-compose up --build                       # full stack in one command
docker-compose down                             # stop all containers
```

(On PowerShell, swap `./mvnw` → `.\mvnw.cmd`.)

---

## 11. First-run checklist

1. `docker-compose up -d postgres redis rabbitmq`
2. `cp .env.example .env` → fill JWT secret + Stripe test keys
3. `./mvnw spring-boot:run` → Flyway creates tables
4. Hit `http://localhost:8080/actuator/health` → should be `UP`
5. Register a user, log in, get a JWT → you're live

---

## 12. Troubleshooting log (things we actually hit)

| Symptom | Cause | Fix |
|---------|-------|-----|
| `curl` returns a tiny (~189 byte) file, `unzip` fails | The "zip" is actually a JSON error from Initializr | `cat` the file to read the error message |
| `Invalid Spring Boot version '3.3.5', range is >=3.5.0` | Pinned an unsupported `bootVersion` | Omit `bootVersion` → uses latest |
| `mvn: command not found` | Maven isn't installed globally | Use the wrapper: `./mvnw` (or `.\mvnw.cmd`) |
| Empty package folders don't show in Git | Git ignores empty directories | Add a file (or `.gitkeep`) when needed |
| `curl --version` errors in VS Code terminal | PowerShell's `curl` is an alias for `Invoke-WebRequest` | Use `curl.exe`, or just use the website |

---

## 13. Mini glossary

| Term | Plain-English meaning |
|------|-----------------------|
| **JDK** | Java Development Kit — compiles + runs Java code |
| **Maven** | Build tool for Java — manages dependencies and builds the app (like npm for Java) |
| **`pom.xml`** | Maven's config file — lists dependencies, Java version, build settings |
| **Maven Wrapper (`mvnw`)** | A script that auto-downloads the right Maven version, so you don't install Maven |
| **Spring Boot** | Framework that wires a Java web app together with sensible defaults |
| **Spring Initializr** | The generator (website/CLI) that creates a Spring Boot skeleton |
| **Dependency** | A library your project uses (each Initializr checkbox = one dependency) |
| **JPA** | The Java standard for mapping objects ↔ database tables |
| **Hibernate** | The engine that implements JPA (writes the SQL for you) |
| **ORM** | Object-Relational Mapping — the general idea JPA/Hibernate implement |
| **Flyway** | Runs versioned SQL migration files to build/evolve your DB schema |
| **Bean** | Any object Spring creates and manages for you (via `@Component`, `@Service`, etc.) |
| **Actuator** | Adds ops endpoints like `/actuator/health` |
