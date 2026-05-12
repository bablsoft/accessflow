package com.bablsoft.accessflow.core.api;

/**
 * Thrown when an admin tries to create or update a datasource with {@code ai_analysis_enabled =
 * true} but no {@code ai_config_id} is supplied (or the existing binding is being cleared without
 * disabling AI). Resolved to HTTP 422.
 */
public final class MissingAiConfigForDatasourceException extends DatasourceAdminException {

    public MissingAiConfigForDatasourceException() {
        super("AI analysis is enabled but no AI config is bound to this datasource");
    }
}
