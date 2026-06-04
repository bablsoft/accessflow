package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when a custom {@code ai_config} system-prompt template is set but does not contain the
 * mandatory {@code {{sql}}} placeholder — without it the analyzer would never see the query under
 * review. Resolved by the global handler to HTTP 400.
 */
public class AiConfigInvalidPromptException extends RuntimeException {

    public AiConfigInvalidPromptException() {
        super("AI config system prompt must contain the {{sql}} placeholder");
    }
}
