package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when text-to-SQL generation is requested for a datasource that has no {@code ai_config}
 * bound (text-to-SQL reuses the same AI configuration as risk analysis). Maps to HTTP 400.
 */
public class TextToSqlNotConfiguredException extends RuntimeException {

    public TextToSqlNotConfiguredException() {
        super("No AI configuration is bound to this datasource");
    }
}
