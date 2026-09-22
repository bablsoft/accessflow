package com.bablsoft.accessflow.schemachange.api;

/** More statements than {@code accessflow.schemachange.max-statements} allows. Mapped to HTTP 400. */
public final class SchemaChangeSetStatementLimitException extends SchemaChangeException {

    private final int limit;
    private final int actual;

    public SchemaChangeSetStatementLimitException(int limit, int actual) {
        super("Schema change set holds " + actual + " statements; the limit is " + limit);
        this.limit = limit;
        this.actual = actual;
    }

    public int limit() {
        return limit;
    }

    public int actual() {
        return actual;
    }
}
