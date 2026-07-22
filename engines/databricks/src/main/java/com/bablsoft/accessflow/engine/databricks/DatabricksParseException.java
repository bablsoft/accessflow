package com.bablsoft.accessflow.engine.databricks;

/**
 * Internal signal that a Databricks SQL statement could not be parsed/validated. Carries an i18n
 * message key and arguments; {@link DatabricksQueryParser} resolves it against the host-provided
 * {@code EngineMessages} and rethrows as a
 * {@link com.bablsoft.accessflow.core.api.InvalidSqlException} (HTTP 422), matching the SQL
 * engine's behaviour for unparseable input.
 */
final class DatabricksParseException extends RuntimeException {

    private final transient Object[] args;

    DatabricksParseException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args == null ? new Object[0] : args.clone();
    }

    String messageKey() {
        return getMessage();
    }

    Object[] args() {
        return args.clone();
    }
}
