package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when the AI provider call fails (HTTP error, timeout, IO problem, missing API key).
 * Maps to HTTP 503.
 */
public class AiAnalysisException extends RuntimeException {

    public AiAnalysisException(String message) {
        super(message);
    }

    public AiAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
