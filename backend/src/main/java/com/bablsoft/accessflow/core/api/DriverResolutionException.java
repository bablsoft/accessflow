package com.bablsoft.accessflow.core.api;

public final class DriverResolutionException extends RuntimeException {

    public enum Reason {
        OFFLINE_CACHE_MISS,
        DOWNLOAD_FAILED,
        CHECKSUM_MISMATCH,
        CACHE_NOT_WRITABLE,
        UNAVAILABLE
    }

    private final DbType dbType;
    private final Reason reason;

    public DriverResolutionException(DbType dbType, Reason reason, String message) {
        super(message);
        this.dbType = dbType;
        this.reason = reason;
    }

    public DriverResolutionException(DbType dbType, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.dbType = dbType;
        this.reason = reason;
    }

    public DbType dbType() {
        return dbType;
    }

    public Reason reason() {
        return reason;
    }
}
