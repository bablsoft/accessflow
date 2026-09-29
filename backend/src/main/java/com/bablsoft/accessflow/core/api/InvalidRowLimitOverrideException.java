package com.bablsoft.accessflow.core.api;

/** A grant's {@code row_limit_override} was set below 1 (#1085); {@code null} means "no override". */
public final class InvalidRowLimitOverrideException extends DatasourceAdminException {

    private final int value;

    public InvalidRowLimitOverrideException(int value) {
        super("row_limit_override must be at least 1, got " + value);
        this.value = value;
    }

    public int value() {
        return value;
    }
}
