package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when text-to-SQL generation is requested for a datasource whose
 * {@code text_to_sql_enabled} flag is {@code false}. Maps to HTTP 409.
 */
public class TextToSqlDisabledException extends RuntimeException {

    public TextToSqlDisabledException() {
        super("Text-to-SQL is disabled for this datasource");
    }
}
