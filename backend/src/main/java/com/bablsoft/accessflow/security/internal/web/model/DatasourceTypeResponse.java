package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.SslMode;

import java.util.UUID;

public record DatasourceTypeResponse(
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

    public static DatasourceTypeResponse from(DriverTypeInfo info) {
        return new DatasourceTypeResponse(
                info.code(),
                info.displayName(),
                info.iconUrl(),
                info.defaultPort(),
                info.defaultSslMode(),
                info.jdbcUrlTemplate(),
                info.driverStatus(),
                info.bundled(),
                info.source(),
                info.customDriverId(),
                info.vendorName(),
                info.driverClass());
    }
}
