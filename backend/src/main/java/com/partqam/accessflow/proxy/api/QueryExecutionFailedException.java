package com.partqam.accessflow.proxy.api;

public final class QueryExecutionFailedException extends QueryExecutionException {

    private final String sqlState;
    private final int vendorCode;

    public QueryExecutionFailedException(String message, String sqlState, int vendorCode,
                                         Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public String sqlState() {
        return sqlState;
    }

    public int vendorCode() {
        return vendorCode;
    }
}
