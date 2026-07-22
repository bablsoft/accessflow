package com.bablsoft.accessflow.engine.bigquery;

/**
 * Internal signal that a GoogleSQL statement could not be parsed/validated. Carries an i18n message
 * key and arguments; {@link BigQueryQueryParser} resolves it against the host-provided
 * {@code EngineMessages} and rethrows as a
 * {@link com.bablsoft.accessflow.core.api.InvalidSqlException} (HTTP 422), matching the SQL engine's
 * behaviour for unparseable input.
 */
final class BigQueryParseException extends RuntimeException {

    private final transient Object[] args;

    BigQueryParseException(String messageKey, Object... args) {
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
