package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when the AI provider returned a response that does not match the expected JSON schema
 * (missing fields, wrong types, out-of-range values, malformed JSON). Maps to HTTP 422.
 */
public class AiAnalysisParseException extends RuntimeException {

    public AiAnalysisParseException(String message) {
        super(message);
    }

    public AiAnalysisParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
