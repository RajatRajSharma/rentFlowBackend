package com.rentflow.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * Base class for integration tests: a REAL PostgreSQL and a REAL Redis.
 *
 * Why not H2 or an embedded Redis? Because the things we most need to prove don't exist there.
 * H2 has no {@code EXCLUDE USING gist}, so the double-booking test would pass against a schema
 * that had silently dropped our single most important constraint. A test that can't fail the
 * way production fails is worse than no test.
 *
 * <h2>Two ways to get those services</h2>
 * <ol>
 *   <li><b>Testcontainers</b> (preferred) — throwaway containers, nothing to set up. Used
 *       whenever Testcontainers can reach the Docker daemon.</li>
 *   <li><b>docker-compose fallback</b> — the Postgres and Redis this project already runs
 *       locally. Some Docker Desktop builds on Windows expose an engine proxy that the
 *       docker-java client can't negotiate with (every probe answers {@code /info} with
 *       HTTP 400), which makes Testcontainers unusable while the docker CLI works fine.
 *       Rather than lose the concurrency proof on those machines, we fall back.</li>
 * </ol>
 *
 * The fallback connects to a SEPARATE database ({@value #FALLBACK_DB}), created on demand.
 * Integration tests truncate tables, and they must never be able to do that to your dev data.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    /**
     * Wipe every application table before each test.
     *
     * Here rather than in each test class on purpose. Tests used to delete the handful of
     * tables they knew about, in an order they worked out themselves — which quietly broke
     * the moment `payments` gained a foreign key to `bookings`, because a leftover payment
     * from one test made another test's `bookings` cleanup fail. Every new table would have
     * meant editing every test that predates it.
     *
     * TRUNCATE ... CASCADE resolves the dependency order itself, so this list needs no
     * ordering and adding a table means adding one word. RESTART IDENTITY resets the
     * sequences too, so ids don't creep upward across a run and tests stay reproducible.
     *
     * `flyway_schema_history` is deliberately absent: the schema is built once per run and
     * must survive.
     */
    @BeforeEach
    void wipeDatabase(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                TRUNCATE TABLE ledger_entries, processed_webhooks, payments, returns,
                               bookings, items, users
                RESTART IDENTITY CASCADE
                """);
    }

    private static final String FALLBACK_HOST = "localhost";
    private static final int FALLBACK_PG_PORT = 5433;      // docker-compose maps 5433:5432
    private static final int FALLBACK_REDIS_PORT = 6379;
    private static final String FALLBACK_DB = "rentflow_test";
    private static final String FALLBACK_USER = "rentflow";
    private static final String FALLBACK_PASSWORD = "rentflow";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("rentflow")
                    .withUsername("rentflow")
                    .withPassword("rentflow");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    /** True when Testcontainers started successfully; false when we fell back to compose. */
    private static final boolean CONTAINERS_STARTED = startContainers();

    private static boolean startContainers() {
        // The PostgreSQL JDBC driver sends the JVM's default timezone as the connection's
        // TimeZone parameter. Java on Windows reports the deprecated alias "Asia/Calcutta",
        // which PostgreSQL 16 rejects, killing the connection before Flyway can run.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));

        try {
            POSTGRES.start();
            REDIS.start();
            System.out.println("[IntegrationTestBase] using Testcontainers");
            return true;
        } catch (Throwable ex) {
            System.out.printf(
                    "[IntegrationTestBase] Testcontainers unavailable (%s: %s)%n"
                    + "[IntegrationTestBase] falling back to docker-compose services, database '%s'%n"
                    + "[IntegrationTestBase] make sure `docker compose up -d` is running%n",
                    ex.getClass().getSimpleName(), ex.getMessage(), FALLBACK_DB);
            createFallbackDatabaseIfMissing();
            return false;
        }
    }

    /** CREATE DATABASE can't run inside a transaction, hence the plain JDBC statement. */
    private static void createFallbackDatabaseIfMissing() {
        String adminUrl = "jdbc:postgresql://" + FALLBACK_HOST + ":" + FALLBACK_PG_PORT + "/postgres";
        try (Connection connection = DriverManager.getConnection(adminUrl, FALLBACK_USER, FALLBACK_PASSWORD);
             Statement statement = connection.createStatement()) {

            try (ResultSet rs = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + FALLBACK_DB + "'")) {
                if (rs.next()) {
                    return;
                }
            }
            statement.execute("CREATE DATABASE " + FALLBACK_DB);
            System.out.println("[IntegrationTestBase] created database " + FALLBACK_DB);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Neither Testcontainers nor the docker-compose Postgres on "
                    + FALLBACK_HOST + ":" + FALLBACK_PG_PORT + " is reachable. "
                    + "Run `docker compose up -d` and try again.", ex);
        }
    }

    /**
     * Point the application at whichever services we ended up with. Container ports are
     * random and only known at runtime — exactly what @DynamicPropertySource is for.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (CONTAINERS_STARTED) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        } else {
            registry.add("spring.datasource.url",
                    () -> "jdbc:postgresql://" + FALLBACK_HOST + ":" + FALLBACK_PG_PORT + "/" + FALLBACK_DB);
            registry.add("spring.datasource.username", () -> FALLBACK_USER);
            registry.add("spring.datasource.password", () -> FALLBACK_PASSWORD);
            registry.add("spring.data.redis.host", () -> FALLBACK_HOST);
            registry.add("spring.data.redis.port", () -> FALLBACK_REDIS_PORT);
        }

        // Flyway builds the schema — exclusion constraint and all — from the same migrations
        // production runs. Hibernate only validates against it.
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Workers off by default: a @Scheduled sweep firing mid-test would rewrite rows the
        // test is asserting on. The worker tests invoke them directly instead.
        registry.add("app.workers.enabled", () -> false);

        // Hundreds of threads queue on one lock in the concurrency test. A production-sized
        // 3s wait would make most of them time out — correct behaviour, but it would hide the
        // more interesting outcome (they get the lock, then lose to the overlap check).
        registry.add("app.lock.wait-ms", () -> 60_000);
    }
}
