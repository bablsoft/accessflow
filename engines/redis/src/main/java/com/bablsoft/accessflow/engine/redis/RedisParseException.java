package com.bablsoft.accessflow.engine.redis;

/**
 * Internal signal that a Redis command could not be parsed/validated. Carries an i18n message key
 * and arguments; {@link RedisCommandParser} resolves it against the host {@code MessageSource} and
 * rethrows as a {@link com.bablsoft.accessflow.core.api.InvalidSqlException} (HTTP 422), matching
 * the SQL engine's behaviour for unparseable input.
 */
final class RedisParseException extends RuntimeException {

    private final transient Object[] args;

    RedisParseException(String messageKey, Object... args) {
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
