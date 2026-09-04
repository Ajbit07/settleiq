package com.settleiq.api;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test database wiring.
 *
 * Two ways in, in priority order:
 *
 *   1. TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD point at an already-running
 *      Postgres. `make test-db` starts one from docker-compose.test.yml. This is
 *      the local path, and it also covers environments where the Docker socket
 *      is reachable by the CLI but not by Testcontainers -- Docker Desktop on
 *      Windows exposes a named pipe that docker-java cannot negotiate.
 *
 *   2. Otherwise a Testcontainers container, which is what CI uses.
 *
 * Either way the tests run against REAL Postgres 16. There is no H2 fallback:
 * the schema depends on advisory locks, statement triggers, JSONB and
 * SKIP LOCKED, and an in-memory substitute would pass tests that production
 * would fail.
 */
final class PostgresIT {

    private PostgresIT() {}

    static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;

    static synchronized void configure(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            String user = envOr("TEST_DB_USER", "settleiq");
            String pass = envOr("TEST_DB_PASSWORD", "settleiq");
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> user);
            r.add("spring.datasource.password", () -> pass);
        } else {
            if (container == null) {
                container = new PostgreSQLContainer<>(IMAGE)
                        .withDatabaseName("settleiq")
                        .withUsername("settleiq")
                        .withPassword("settleiq");
                container.start();
            }
            r.add("spring.datasource.url", container::getJdbcUrl);
            r.add("spring.datasource.username", container::getUsername);
            r.add("spring.datasource.password", container::getPassword);
        }
        // The worker would race the tests for jobs.
        r.add("settleiq.worker-enabled", () -> "false");
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
