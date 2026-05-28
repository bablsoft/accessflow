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
 *
 * <p>The {@code readReplica*} fields are optional. When {@link #hasReadReplica()} is {@code true},
 * the proxy engine routes {@link QueryType#SELECT} queries to a sibling HikariCP pool built from
 * the replica JDBC URL and credentials; non-SELECT queries always hit the primary regardless.
 * Reuses the same driver class as the primary.
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
        String readReplicaJdbcUrl,
        String readReplicaUsername,
        String readReplicaPasswordEncrypted,
        boolean active) {

    public boolean hasReadReplica() {
        return readReplicaJdbcUrl != null && !readReplicaJdbcUrl.isBlank();
    }
}
