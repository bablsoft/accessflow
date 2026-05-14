package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;

public record DatasourceSpec(
        String name,
        DbType dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        SslMode sslMode,
        Integer connectionPoolSize,
        Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        String reviewPlanName,
        Boolean aiAnalysisEnabled,
        String aiConfigName,
        String jdbcUrlOverride
) {
}
