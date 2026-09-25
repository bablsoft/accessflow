package com.bablsoft.accessflow.core.api;

import java.util.Set;

/**
 * The engines whose pre-flight dry-run reports a bytes-scanned estimate (AF-634), and therefore
 * the only ones a bytes-scanned cap (#941) may be configured on. On any other engine every query
 * would have no estimate, so a cap there would either reject everything or be a no-op — both
 * surprising — and configuring one is refused instead.
 */
public final class BytesScannedCapSupport {

    private static final Set<DbType> SUPPORTED = Set.of(DbType.BIGQUERY, DbType.SNOWFLAKE,
            DbType.DATABRICKS);

    private BytesScannedCapSupport() {
    }

    public static boolean supports(DbType dbType) {
        return dbType != null && SUPPORTED.contains(dbType);
    }

    public static Set<DbType> supportedTypes() {
        return SUPPORTED;
    }
}
