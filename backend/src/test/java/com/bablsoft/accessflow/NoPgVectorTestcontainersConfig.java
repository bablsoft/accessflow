package com.bablsoft.accessflow;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers configuration for {@code PgVectorGracefulDegradationIntegrationTest} — a
 * <b>plain</b> (non-pgvector) Postgres image so the {@code vector} extension is genuinely
 * unavailable. The init script provisions only the audit roles (no {@code CREATE EXTENSION vector}),
 * proving the application still starts: V69 is skipped and {@code knowledge_document} is created by
 * V73. Distinct from {@link TestcontainersConfig}, which uses a pgvector-enabled image.
 */
public final class NoPgVectorTestcontainersConfig {

    @ServiceConnection
    @SuppressWarnings("resource")
    public static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withCommand("postgres", "-c", "max_connections=500")
            .withInitScript("db/test-init-audit-roles-no-pgvector.sql");

    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    public static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
}
