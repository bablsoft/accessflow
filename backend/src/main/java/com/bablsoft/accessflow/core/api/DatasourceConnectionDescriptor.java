package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Cross-module DTO carrying the connection-relevant fields of a datasource. Used by the proxy
 * module to read state without touching {@code core/internal} JPA entities. Carries only the
 * encrypted password; never serialized to a public API response.
 *
 * <p>For bundled-driver datasources {@code host}, {@code port}, {@code databaseName} are set and
 * {@code customDriverId} / {@code jdbcUrlOverride} are {@code null}. For uploaded-driver
 * datasources at least {@code customDriverId} is set; {@code jdbcUrlOverride} is set when the
 * datasource uses {@link DbType#CUSTOM} (free-form URL).
 */
public record DatasourceConnectionDescriptor(
        UUID id,
        UUID organizationId,
        DbType dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String passwordEncrypted,
        SslMode sslMode,
        int connectionPoolSize,
        int maxRowsPerQuery,
        boolean aiAnalysisEnabled,
        UUID aiConfigId,
        UUID customDriverId,
        String jdbcUrlOverride,
        boolean active) {
}
