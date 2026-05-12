package com.bablsoft.accessflow.core.api;

public final class CustomDriverChecksumMismatchException extends RuntimeException {

    private final String expectedSha256;
    private final String actualSha256;

    public CustomDriverChecksumMismatchException(String expectedSha256, String actualSha256) {
        super("Uploaded JDBC driver SHA-256 mismatch: expected " + expectedSha256
                + " but got " + actualSha256);
        this.expectedSha256 = expectedSha256;
        this.actualSha256 = actualSha256;
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    public String actualSha256() {
        return actualSha256;
    }
}
