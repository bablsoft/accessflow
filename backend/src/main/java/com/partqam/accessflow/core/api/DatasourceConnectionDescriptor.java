package com.partqam.accessflow.core.api;

import java.util.UUID;

/**
 * Cross-module DTO carrying the connection-relevant fields of a datasource. Used by the proxy
 * module to read state without touching {@code core/internal} JPA entities. Carries only the
 * encrypted password; never serialized to a public API response.
 */
public record DatasourceConnectionDescriptor(
        UUID id,
        DbType dbType,
        String host,
        int port,
        String databaseName,
        String username,
        String passwordEncrypted,
        SslMode sslMode,
        int connectionPoolSize,
        boolean active) {
}
