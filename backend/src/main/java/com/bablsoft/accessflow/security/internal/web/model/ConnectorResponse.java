package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ConnectorCategory;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.SslMode;

/**
 * Catalog row for the connector marketplace ({@code GET /datasources/connectors}). Jackson
 * serializes the camelCase fields to snake_case (see application.yml). {@code category} drives the
 * SQL (RELATIONAL) vs NoSQL (DOCUMENT) grouping in the marketplace UI.
 */
public record ConnectorResponse(
        String id,
        DbType dbType,
        ConnectorCategory category,
        String name,
        String iconUrl,
        String vendor,
        String description,
        String documentationUrl,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        String driverClass,
        DriverStatus driverStatus,
        boolean bundled) {

    public static ConnectorResponse from(DriverTypeInfo info) {
        return new ConnectorResponse(
                info.connectorId(),
                info.code(),
                info.category(),
                info.displayName(),
                info.iconUrl(),
                info.vendorName(),
                info.description(),
                info.documentationUrl(),
                info.defaultPort(),
                info.defaultSslMode(),
                info.jdbcUrlTemplate(),
                info.driverClass(),
                info.driverStatus(),
                info.bundled());
    }
}
