package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.ai.api.TextToSqlDisabledException;
import com.bablsoft.accessflow.ai.api.TextToSqlNotConfiguredException;
import com.bablsoft.accessflow.ai.api.TextToSqlService;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.SupportedLanguage;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Synchronous text-to-SQL generation for the editor preview. Mirrors
 * {@code DefaultAiAnalyzerService.analyzePreview}: resolve the datasource, gate on
 * {@code text_to_sql_enabled}, require a bound {@code ai_config}, introspect the schema (honouring
 * the caller's restricted columns), then ask the per-config AI delegate to generate SQL. No
 * persistence and no query request — the returned draft goes back to the editor.
 */
@Service
@RequiredArgsConstructor
class DefaultTextToSqlService implements TextToSqlService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTextToSqlService.class);

    private final AiAnalyzerStrategy strategy;
    private final AiConfigRepository aiConfigRepository;
    private final SystemPromptRenderer promptRenderer;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final LocalizationConfigService localizationConfigService;
    private final QueryParser queryParser;
    private final AiRateLimiter aiRateLimiter;

    @Override
    public GeneratedSqlResult generateSql(UUID datasourceId, String prompt, UUID userId,
                                          UUID organizationId, boolean isAdmin) {
        var descriptor = datasourceLookupService.findById(datasourceId)
                .orElseThrow(() -> new AiAnalysisException("Datasource not found: " + datasourceId));
        if (!descriptor.textToSqlEnabled()) {
            throw new TextToSqlDisabledException();
        }
        var aiConfigId = descriptor.aiConfigId();
        if (aiConfigId == null) {
            throw new TextToSqlNotConfiguredException();
        }
        verifySameOrg(aiConfigId, descriptor.organizationId());
        var schemaView = datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, isAdmin);
        var restrictedColumns = permissionLookupService.findFor(userId, datasourceId)
                .map(p -> p.restrictedColumns())
                .orElse(List.of());
        var schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns);
        var dbType = descriptor.dbType();
        aiRateLimiter.enforce(organizationId);
        var generated = strategy.generateSql(prompt, dbType, schemaContext,
                resolveLanguage(organizationId), aiConfigId);
        var result = generated.withSyntax(promptRenderer.syntaxFor(dbType, generated.sql()));
        validateDraft(result.sql(), dbType);
        return result;
    }

    /**
     * Parse the generated draft through the engine-aware {@link QueryParser} so an unparseable draft
     * fails closed as {@code AI_RESPONSE_INVALID} (HTTP 422) before it reaches the editor — the draft
     * is still re-validated on submit through {@code POST /api/v1/queries}. No execution, no
     * persistence. Engine-resolution / provider errors are not parse failures and propagate as-is.
     */
    private void validateDraft(String sql, DbType dbType) {
        try {
            queryParser.parse(sql, dbType);
        } catch (InvalidSqlException e) {
            throw new AiAnalysisParseException(
                    "Generated query did not parse for " + dbType + ": " + e.getMessage(), e);
        }
    }

    private void verifySameOrg(UUID aiConfigId, UUID datasourceOrgId) {
        var orgMatches = aiConfigRepository.findById(aiConfigId)
                .map(e -> e.getOrganizationId().equals(datasourceOrgId))
                .orElse(false);
        if (!orgMatches) {
            throw new AiAnalysisException("AI configuration does not belong to this organization");
        }
    }

    private String resolveLanguage(UUID organizationId) {
        if (organizationId == null) {
            return SupportedLanguage.EN.code();
        }
        try {
            return localizationConfigService.getOrDefault(organizationId).aiReviewLanguage();
        } catch (RuntimeException e) {
            log.warn("Failed to resolve AI review language for org {}: {}", organizationId, e.getMessage());
            return SupportedLanguage.EN.code();
        }
    }
}
