package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerService;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.core.api.AiAnalysisPersistenceService;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.partqam.accessflow.core.api.LocalizationConfigService;
import com.partqam.accessflow.core.api.PersistAiAnalysisCommand;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SupportedLanguage;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
class DefaultAiAnalyzerService implements AiAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiAnalyzerService.class);
    private static final String UNKNOWN_MODEL = "unknown";

    private final AiAnalyzerStrategy strategy;
    private final AiAnalyzerProperties properties;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final AiAnalysisPersistenceService aiAnalysisPersistenceService;
    private final LocalizationConfigService localizationConfigService;
    private final ApplicationEventPublisher eventPublisher;
    private final String configuredModel;

    DefaultAiAnalyzerService(AiAnalyzerStrategy strategy,
                             AiAnalyzerProperties properties,
                             SystemPromptRenderer promptRenderer,
                             AiResponseParser responseParser,
                             DatasourceLookupService datasourceLookupService,
                             DatasourceAdminService datasourceAdminService,
                             QueryRequestLookupService queryRequestLookupService,
                             DatasourceUserPermissionLookupService permissionLookupService,
                             AiAnalysisPersistenceService aiAnalysisPersistenceService,
                             LocalizationConfigService localizationConfigService,
                             ApplicationEventPublisher eventPublisher,
                             @Value("${spring.ai.anthropic.chat.options.model:" + UNKNOWN_MODEL + "}") String configuredModel) {
        this.strategy = strategy;
        this.properties = properties;
        this.promptRenderer = promptRenderer;
        this.responseParser = responseParser;
        this.datasourceLookupService = datasourceLookupService;
        this.datasourceAdminService = datasourceAdminService;
        this.queryRequestLookupService = queryRequestLookupService;
        this.permissionLookupService = permissionLookupService;
        this.aiAnalysisPersistenceService = aiAnalysisPersistenceService;
        this.localizationConfigService = localizationConfigService;
        this.eventPublisher = eventPublisher;
        this.configuredModel = (configuredModel == null || configuredModel.isBlank()) ? UNKNOWN_MODEL : configuredModel;
    }

    @Override
    public AiAnalysisResult analyzePreview(UUID datasourceId, String sql, UUID userId,
                                           UUID organizationId, boolean isAdmin) {
        var descriptor = datasourceLookupService.findById(datasourceId)
                .orElseThrow(() -> new AiAnalysisException("Datasource not found: " + datasourceId));
        var schemaView = datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, isAdmin);
        var restrictedColumns = permissionLookupService.findFor(userId, datasourceId)
                .map(p -> p.restrictedColumns())
                .orElse(List.of());
        var schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns);
        return strategy.analyze(sql, descriptor.dbType(), schemaContext, resolveLanguage(organizationId));
    }

    @Override
    public void analyzeSubmittedQuery(UUID queryRequestId) {
        var snapshot = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (snapshot == null) {
            log.warn("AI analysis skipped: query request {} not found", queryRequestId);
            return;
        }
        var datasourceId = snapshot.datasourceId();
        var dbType = datasourceLookupService.findById(datasourceId)
                .map(d -> d.dbType())
                .orElse(null);
        if (dbType == null) {
            persistFailureAndPublish(queryRequestId, "Datasource not found: " + datasourceId);
            return;
        }
        var restrictedColumns = permissionLookupService
                .findFor(snapshot.submittedByUserId(), datasourceId)
                .map(p -> p.restrictedColumns())
                .orElse(List.of());
        String schemaContext = null;
        try {
            var schemaView = datasourceAdminService.introspectSchemaForSystem(datasourceId, snapshot.organizationId());
            schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns);
        } catch (RuntimeException e) {
            log.warn("Schema introspection failed for query {}: {}", queryRequestId, e.getMessage());
        }
        try {
            var result = strategy.analyze(snapshot.sqlText(), dbType, schemaContext,
                    resolveLanguage(snapshot.organizationId()));
            var issuesJson = responseParser.issuesAsJson(result.issues());
            var command = new PersistAiAnalysisCommand(
                    result.aiProvider(), result.aiModel(), result.riskScore(), result.riskLevel(),
                    result.summary(), issuesJson, result.missingIndexesDetected(),
                    result.affectsRowEstimate(), result.promptTokens(), result.completionTokens());
            var analysisId = aiAnalysisPersistenceService.persist(queryRequestId, command);
            eventPublisher.publishEvent(new AiAnalysisCompletedEvent(queryRequestId, analysisId, result.riskLevel()));
        } catch (AiAnalysisException | AiAnalysisParseException e) {
            log.warn("AI analysis failed for query {}: {}", queryRequestId, e.getMessage());
            persistFailureAndPublish(queryRequestId, e.getMessage());
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

    private void persistFailureAndPublish(UUID queryRequestId, String reason) {
        var command = new PersistAiAnalysisCommand(
                properties.provider(), configuredModel, 100, RiskLevel.CRITICAL,
                "AI analysis failed: " + reason, "[]", false, null, 0, 0);
        try {
            aiAnalysisPersistenceService.persist(queryRequestId, command);
        } catch (RuntimeException persistError) {
            log.error("Failed to persist sentinel AI failure row for query {}: {}",
                    queryRequestId, persistError.getMessage(), persistError);
        }
        eventPublisher.publishEvent(new AiAnalysisFailedEvent(queryRequestId, reason));
    }
}
