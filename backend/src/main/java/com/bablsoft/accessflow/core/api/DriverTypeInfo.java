package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Catalog row surfaced via {@code GET /datasources/types}.
 *
 * <p>{@code source} discriminates how the driver enters the catalog:
 * <ul>
 *   <li>{@code "bundled"} — entry from the static {@code DriverRegistry}. {@code customDriverId}
 *       is {@code null}; {@code code} is the bundled {@link DbType}.</li>
 *   <li>{@code "uploaded"} — admin-uploaded driver for the caller's organization.
 *       {@code customDriverId} is the {@code custom_jdbc_driver.id}; {@code code} mirrors the
 *       upload's {@code target_db_type} (may be {@link DbType#CUSTOM} for a fully dynamic
 *       datasource).</li>
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
        String driverClass) {

    public static DriverTypeInfo bundled(DbType code, String displayName, String iconUrl,
                                         int defaultPort, SslMode defaultSslMode,
                                         String jdbcUrlTemplate, DriverStatus driverStatus,
                                         boolean nativeBundle) {
        return new DriverTypeInfo(code, displayName, iconUrl, defaultPort, defaultSslMode,
                jdbcUrlTemplate, driverStatus, nativeBundle, "bundled", null, null, null);
    }

    public static DriverTypeInfo uploaded(DbType code, String displayName, String iconUrl,
                                          int defaultPort, SslMode defaultSslMode,
                                          String jdbcUrlTemplate, UUID customDriverId,
                                          String vendorName, String driverClass) {
        return new DriverTypeInfo(code, displayName, iconUrl, defaultPort, defaultSslMode,
                jdbcUrlTemplate, DriverStatus.READY, false, "uploaded", customDriverId,
                vendorName, driverClass);
    }
}
