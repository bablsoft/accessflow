package com.bablsoft.accessflow.ai.internal;

/**
 * Thrown when the admin AI analyses stats request is malformed — e.g. {@code from} is after
 * {@code to}. Mapped to HTTP 400 by {@code AiAnalysisExceptionHandler}.
 */
public class BadAiAnalysisStatsQueryException extends RuntimeException {

    public BadAiAnalysisStatsQueryException(String messageKey) {
        super(messageKey);
    }
}
