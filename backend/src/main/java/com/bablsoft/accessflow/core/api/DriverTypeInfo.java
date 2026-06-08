package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Catalog row surfaced via {@code GET /datasources/types} (datasource wizard) and
 * {@code GET /datasources/connectors} (connector marketplace).
 *
 * <p>{@code source} discriminates how the driver enters the catalog:
 * <ul>
 *   <li>{@code "bundled"} — one of the five first-class dialects from the connector catalog.
 *       {@code customDriverId} and {@code connectorId} may both be set when surfaced via the
 *       marketplace; on the wizard feed the dialect rows carry a null {@code connectorId}.</li>
 *   <li>{@code "connector"} — a catalog connector. {@code connectorId} is the manifest id;
 *       {@code code} is the connector's {@link DbType} ({@link DbType#CUSTOM} for engines beyond
 *       the built-in five).</li>
 *   <li>{@code "uploaded"} — an admin-uploaded driver for the caller's organization.
 *       {@code customDriverId} is the {@code custom_jdbc_driver.id}.</li>
 * </ul>
 */
public record DriverTypeInfo(
        DbType code,
        String displayName,
        String iconUrl,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        DriverStatus driverStatus,
        boolean bundled,
        String source,
        UUID customDriverId,
        String vendorName,
        String driverClass,
        String connectorId,
        String description,
        String documentationUrl) {

    public static DriverTypeInfo bundled(DbType code, String displayName, String iconUrl,
                                         int defaultPort, SslMode defaultSslMode,
                                         String jdbcUrlTemplate, DriverStatus driverStatus,
                                         boolean nativeBundle) {
        return new DriverTypeInfo(code, displayName, iconUrl, defaultPort, defaultSslMode,
                jdbcUrlTemplate, driverStatus, nativeBundle, "bundled", null, null, null,
                null, null, null);
    }

    public static DriverTypeInfo uploaded(DbType code, String displayName, String iconUrl,
                                          int defaultPort, SslMode defaultSslMode,
                                          String jdbcUrlTemplate, UUID customDriverId,
                                          String vendorName, String driverClass) {
        return new DriverTypeInfo(code, displayName, iconUrl, defaultPort, defaultSslMode,
                jdbcUrlTemplate, DriverStatus.READY, false, "uploaded", customDriverId,
                vendorName, driverClass, null, null, null);
    }

    public static DriverTypeInfo connector(DbType code, String connectorId, String displayName,
                                           String iconUrl, int defaultPort, SslMode defaultSslMode,
                                           String jdbcUrlTemplate, DriverStatus driverStatus,
                                           boolean bundled, String vendorName, String driverClass,
                                           String description, String documentationUrl) {
        return new DriverTypeInfo(code, displayName, iconUrl, defaultPort, defaultSslMode,
                jdbcUrlTemplate, driverStatus, bundled, "connector", null, vendorName, driverClass,
                connectorId, description, documentationUrl);
    }
}
