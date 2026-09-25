package com.bablsoft.accessflow.core.api;

/**
 * A bytes-scanned cap (#941) — on a datasource or on a grant — was configured for an engine whose
 * dry-run does not report a bytes estimate. See {@link BytesScannedCapSupport}.
 */
public final class BytesScannedCapNotSupportedException extends DatasourceAdminException {

    private final DbType dbType;

    public BytesScannedCapNotSupportedException(DbType dbType) {
        super("bytes-scanned caps are not supported for db_type " + dbType);
        this.dbType = dbType;
    }

    public DbType dbType() {
        return dbType;
    }
}
