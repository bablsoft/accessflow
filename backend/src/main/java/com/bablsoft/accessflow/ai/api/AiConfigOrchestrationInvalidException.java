package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an {@code ai_config}'s multi-model orchestration / guardrail settings are inconsistent
 * (AF-450) — e.g. a guardrail pattern that is not a valid regex, a non-positive voting weight, an
 * orchestration member missing its provider/model, or an OpenAI-compatible member with no endpoint.
 * The {@code messageKey} selects the localized {@code messages.properties} detail; resolved by the
 * global handler to HTTP 400.
 */
public class AiConfigOrchestrationInvalidException extends RuntimeException {

    private final String messageKey;

    public AiConfigOrchestrationInvalidException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
