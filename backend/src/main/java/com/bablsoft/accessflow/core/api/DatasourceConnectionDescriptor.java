package com.bablsoft.accessflow.core.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * <p>{@code readReplicas} lists the datasource's read-replica endpoints (AF-457). When
 * {@link #hasReadReplica()} is {@code true}, the proxy engine load-balances
 * {@link QueryType#SELECT} queries round-robin across the healthy replica pools; non-SELECT
 * queries always hit the primary regardless. Replicas reuse the same driver class as the primary.
 *
 * <p>{@code resultCacheEnabled} / {@code resultCacheTtlSeconds} are the opt-in SELECT result-cache
 * settings (AF-457). A {@code null} TTL falls back to the deployment-wide default.
 *
 * <p>{@code localDatacenter} is the Cassandra/ScyllaDB driver's load-balancing datacenter name
 * (the {@code withLocalDatacenter(...)} value). It is {@code null} for every other dialect.
 *
 * <p>{@code apiKeyEncrypted} is the optional encrypted API key for the search engines
 * (Elasticsearch / OpenSearch): when present the engine authenticates with
 * {@code Authorization: ApiKey <decrypted>} instead of HTTP basic. {@code null} for every other
 * dialect and for basic-auth search datasources.
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
        boolean textToSqlEnabled,
        UUID customDriverId,
        String connectorId,
        String jdbcUrlOverride,
        List<ReadReplicaEndpoint> readReplicas,
        boolean active,
        String localDatacenter,
        String apiKeyEncrypted,
        boolean resultCacheEnabled,
        Integer resultCacheTtlSeconds) {

    public DatasourceConnectionDescriptor {
        readReplicas = readReplicas == null ? List.of() : List.copyOf(readReplicas);
    }

    /**
     * Backward-compatible constructor taking the pre-AF-457 single-replica triple. A non-blank
     * {@code readReplicaJdbcUrl} maps to a one-endpoint replica list (deterministic endpoint id
     * derived from the URL); result caching defaults to off. Kept so engine plugins and tests
     * compiled against the old shape keep working unchanged.
     */
    public DatasourceConnectionDescriptor(
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
            boolean textToSqlEnabled,
            UUID customDriverId,
            String connectorId,
            String jdbcUrlOverride,
            String readReplicaJdbcUrl,
            String readReplicaUsername,
            String readReplicaPasswordEncrypted,
            boolean active,
            String localDatacenter,
            String apiKeyEncrypted) {
        this(id, organizationId, dbType, host, port, databaseName, username, passwordEncrypted,
                sslMode, connectionPoolSize, maxRowsPerQuery, aiAnalysisEnabled, aiConfigId,
                textToSqlEnabled, customDriverId, connectorId, jdbcUrlOverride,
                legacyReplicaList(readReplicaJdbcUrl, readReplicaUsername,
                        readReplicaPasswordEncrypted),
                active, localDatacenter, apiKeyEncrypted, false, null);
    }

    /**
     * Backward-compatible constructor for the dialects that have no {@code apiKeyEncrypted} (every
     * engine except Elasticsearch / OpenSearch); delegates to the canonical constructor with
     * {@code null}.
     */
    public DatasourceConnectionDescriptor(
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
            boolean textToSqlEnabled,
            UUID customDriverId,
            String connectorId,
            String jdbcUrlOverride,
            String readReplicaJdbcUrl,
            String readReplicaUsername,
            String readReplicaPasswordEncrypted,
            boolean active,
            String localDatacenter) {
        this(id, organizationId, dbType, host, port, databaseName, username, passwordEncrypted,
                sslMode, connectionPoolSize, maxRowsPerQuery, aiAnalysisEnabled, aiConfigId,
                textToSqlEnabled, customDriverId, connectorId, jdbcUrlOverride, readReplicaJdbcUrl,
                readReplicaUsername, readReplicaPasswordEncrypted, active, localDatacenter, null);
    }

    /**
     * Backward-compatible constructor for the dialects that have neither {@code localDatacenter} nor
     * {@code apiKeyEncrypted}; delegates to the canonical constructor with {@code null} for both.
     */
    public DatasourceConnectionDescriptor(
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
            boolean textToSqlEnabled,
            UUID customDriverId,
            String connectorId,
            String jdbcUrlOverride,
            String readReplicaJdbcUrl,
            String readReplicaUsername,
            String readReplicaPasswordEncrypted,
            boolean active) {
        this(id, organizationId, dbType, host, port, databaseName, username, passwordEncrypted,
                sslMode, connectionPoolSize, maxRowsPerQuery, aiAnalysisEnabled, aiConfigId,
                textToSqlEnabled, customDriverId, connectorId, jdbcUrlOverride, readReplicaJdbcUrl,
                readReplicaUsername, readReplicaPasswordEncrypted, active, null, null);
    }

    public boolean hasReadReplica() {
        return !readReplicas.isEmpty();
    }

    private static List<ReadReplicaEndpoint> legacyReplicaList(String jdbcUrl, String username,
                                                               String passwordEncrypted) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return List.of();
        }
        return List.of(new ReadReplicaEndpoint(
                UUID.nameUUIDFromBytes(jdbcUrl.getBytes(StandardCharsets.UTF_8)),
                jdbcUrl, username, passwordEncrypted));
    }
}
