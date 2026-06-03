package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an {@code ai_config} row uses the {@code OPENAI_COMPATIBLE} provider but no
 * {@code endpoint} (base URL) is set. The custom provider has no built-in default endpoint, so one
 * is mandatory. Resolved by the global handler to HTTP 400.
 */
public class AiConfigEndpointRequiredException extends RuntimeException {

    public AiConfigEndpointRequiredException() {
        super("AI config endpoint is required for an OpenAI-compatible provider");
    }
}
