package com.bablsoft.accessflow.engine.snowflake;

/**
 * Internal signal that a datasource's connection configuration cannot produce a Snowflake
 * connection — an encrypted (passphrase-protected) private key, a malformed private-key PEM, or a
 * {@code jdbc_url_override} that is not a {@code jdbc:snowflake://} URL. Carries an i18n message
 * key and arguments; the catch sites resolve it against the host-provided {@code EngineMessages}
 * and rethrow as the exception type appropriate to the path — a
 * {@link com.bablsoft.accessflow.core.api.QueryExecutionFailedException} from the executor, a
 * {@link com.bablsoft.accessflow.core.api.DatasourceConnectionTestException} from the connection
 * probe and schema introspector.
 */
final class SnowflakeConfigException extends RuntimeException {

    private final transient Object[] args;

    SnowflakeConfigException(String messageKey, Object... args) {
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
