package com.bablsoft.accessflow.engine.mongodb;

/**
 * Internal signal that a MongoDB query could not be parsed/validated. Carries an i18n message key
 * and arguments; {@link MongoQueryParser} resolves it against the {@code MessageSource} and rethrows
 * as a {@link com.bablsoft.accessflow.core.api.InvalidSqlException} (HTTP 422), matching the SQL
 * engine's behaviour for unparseable input.
 */
final class MongoParseException extends RuntimeException {

    private final transient Object[] args;

    MongoParseException(String messageKey, Object... args) {
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
