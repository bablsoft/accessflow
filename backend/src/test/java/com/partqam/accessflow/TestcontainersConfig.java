package com.partqam.accessflow;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Testcontainers configuration for integration tests.
 * Use with {@code @ImportTestcontainers(TestcontainersConfig.class)}.
 */
public final class TestcontainersConfig {

    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withCommand("postgres", "-c", "max_connections=300");

    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
}
