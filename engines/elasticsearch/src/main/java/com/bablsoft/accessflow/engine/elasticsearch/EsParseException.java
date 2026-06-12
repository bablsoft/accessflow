package com.bablsoft.accessflow.engine.elasticsearch;

/**
 * Internal, message-key-carrying parse failure raised inside the Elasticsearch query parser /
 * JSON helper and caught at the parser boundary, where it is rethrown as the host-facing
 * {@link com.bablsoft.accessflow.core.api.InvalidSqlException} (HTTP 422). The {@code messageKey}
 * resolves through the host-provided {@code EngineMessages}; the {@code args} are its
 * {@link java.text.MessageFormat} arguments. The analogue of the Mongo engine's
 * {@code MongoParseException}.
 */
class EsParseException extends RuntimeException {

    private final String messageKey;
    private final transient Object[] args;

    EsParseException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    String messageKey() {
        return messageKey;
    }

    Object[] args() {
        return args;
    }
}
