package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Synchronous natural-language → SQL generation for the editor. Resolves the datasource's bound
 * {@code ai_config}, introspects its schema, and asks the AI provider to translate {@code prompt}
 * into a SQL draft. No persistence and no query request is created — the returned SQL flows back to
 * the editor, where the user reviews / edits it and submits it through the regular pipeline (so all
 * governance still applies).
 */
public interface TextToSqlService {

    /**
     * @throws TextToSqlDisabledException       the datasource has {@code text_to_sql_enabled=false}
     * @throws TextToSqlNotConfiguredException  no {@code ai_config} is bound to the datasource
     * @throws AiAnalysisException              datasource not accessible or the provider call failed
     * @throws AiAnalysisParseException         the provider returned no usable SQL
     */
    GeneratedSqlResult generateSql(UUID datasourceId, String prompt, UUID userId,
                                   UUID organizationId, boolean isAdmin);
}
