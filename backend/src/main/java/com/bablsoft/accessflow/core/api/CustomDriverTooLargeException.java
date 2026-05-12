package com.bablsoft.accessflow.core.api;

public final class CustomDriverTooLargeException extends RuntimeException {

    private final long actualBytes;
    private final long maxBytes;

    public CustomDriverTooLargeException(long actualBytes, long maxBytes) {
        super("Uploaded JDBC driver is " + actualBytes + " bytes; max allowed is " + maxBytes);
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
