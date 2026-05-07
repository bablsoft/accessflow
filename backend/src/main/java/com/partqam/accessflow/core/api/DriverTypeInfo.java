package com.partqam.accessflow.core.api;

public record DriverTypeInfo(
        DbType code,
        String displayName,
        String iconUrl,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        DriverStatus driverStatus) {
}
