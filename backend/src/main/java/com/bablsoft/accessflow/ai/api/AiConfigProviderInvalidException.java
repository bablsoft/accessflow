package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an {@code ai_config} row names a provider that cannot do the job asked of it — today,
 * an embedding-only provider (Voyage) selected as the row's chat provider (AF-918). The
 * {@code messageKey} selects the localized {@code messages.properties} detail; resolved by the
 * module handler to HTTP 400 {@code AI_CONFIG_PROVIDER_INVALID}.
 */
public class AiConfigProviderInvalidException extends RuntimeException {

    private final String messageKey;

    public AiConfigProviderInvalidException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
