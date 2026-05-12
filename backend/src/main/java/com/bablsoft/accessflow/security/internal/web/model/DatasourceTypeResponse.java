package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.SslMode;

public record DatasourceTypeResponse(
        DbType code,
        String displayName,
        String iconUrl,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        DriverStatus driverStatus,
        boolean bundled) {

    public static DatasourceTypeResponse from(DriverTypeInfo info) {
        return new DatasourceTypeResponse(
                info.code(),
                info.displayName(),
                info.iconUrl(),
                info.defaultPort(),
                info.defaultSslMode(),
                info.jdbcUrlTemplate(),
                info.driverStatus(),
                info.bundled());
    }
}
