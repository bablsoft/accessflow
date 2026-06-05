package com.bablsoft.accessflow;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Testcontainers configuration for integration tests.
 * Use with {@code @ImportTestcontainers(TestcontainersConfig.class)}.
 *
 * <p>The Postgres container ships an init script that provisions the
 * {@code accessflow_audit} and {@code accessflow_app} roles consumed by
 * {@code V38__audit_log_role_separation.sql} and creates the {@code vector} extension required
 * by {@code V69} (RAG knowledge base). Spring's primary DataSource still authenticates as the
 * Testcontainer superuser ({@code test}) so existing tests can freely seed and clean up via
 * JdbcTemplate. The image is pgvector-enabled so {@code CREATE EXTENSION vector} succeeds.
 */
public final class TestcontainersConfig {

    @ServiceConnection
    @SuppressWarnings("resource")
    public static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withCommand("postgres", "-c", "max_connections=500")
            .withInitScript("db/test-init-audit-roles.sql");

    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    public static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
}
