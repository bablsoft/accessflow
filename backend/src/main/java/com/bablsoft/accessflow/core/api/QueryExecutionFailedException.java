package com.bablsoft.accessflow.core.api;

public final class QueryExecutionFailedException extends QueryExecutionException {

    private final String detail;
    private final String sqlState;
    private final int vendorCode;

    public QueryExecutionFailedException(String message, String detail, String sqlState,
                                         int vendorCode, Throwable cause) {
        super(message, cause);
        this.detail = detail;
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public String detail() {
        return detail;
    }

    public String sqlState() {
        return sqlState;
    }

    public int vendorCode() {
        return vendorCode;
    }
}
