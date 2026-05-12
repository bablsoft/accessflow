package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SupportedLanguage;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiAnalyzerService implements AiAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiAnalyzerService.class);
    private static final String UNKNOWN_MODEL = "unknown";

    private final AiAnalyzerStrategy strategy;
    private final AiConfigRepository aiConfigRepository;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final AiAnalysisPersistenceService aiAnalysisPersistenceService;
    private final LocalizationConfigService localizationConfigService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public AiAnalysisResult analyzePreview(UUID datasourceId, String sql, UUID userId,
                                           UUID organizationId, boolean isAdmin) {
        var descriptor = datasourceLookupService.findById(datasourceId)
                .orElseThrow(() -> new AiAnalysisException("Datasource not found: " + datasourceId));
        requireAnalysisEnabled(descriptor);
        var aiConfigId = requireBoundAiConfig(descriptor);
        verifySameOrg(aiConfigId, descriptor.organizationId());
        var schemaView = datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, isAdmin);
        var restrictedColumns = permissionLookupService.findFor(userId, datasourceId)
                .map(p -> p.restrictedColumns())
                .orElse(List.of());
        var schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns);
        return strategy.analyze(sql, descriptor.dbType(), schemaContext,
                resolveLanguage(organizationId), aiConfigId);
    }

    private void verifySameOrg(UUID aiConfigId, UUID datasourceOrgId) {
        var orgMatches = aiConfigRepository.findById(aiConfigId)
                .map(e -> e.getOrganizationId().equals(datasourceOrgId))
                .orElse(false);
        if (!orgMatches) {
            throw new AiAnalysisException("AI configuration does not belong to this organization");
        }
    }

    @Override
    public void analyzeSubmittedQuery(UUID queryRequestId) {
        var snapshot = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (snapshot == null) {
            log.warn("AI analysis skipped: query request {} not found", queryRequestId);
            return;
        }
        var datasourceId = snapshot.datasourceId();
        var descriptor = datasourceLookupService.findById(datasourceId).orElse(null);
        if (descriptor == null) {
            persistFailureAndPublish(queryRequestId, null,
                    "Datasource not found: " + datasourceId);
            return;
        }
        if (!descriptor.aiAnalysisEnabled()) {
            log.info("AI analysis skipped for query {} — datasource {} has ai_analysis_enabled=false",
                    queryRequestId, datasourceId);
            return;
        }
        if (descriptor.aiConfigId() == null) {
            log.warn("AI analysis skipped for query {} — datasource {} has no ai_config bound",
                    queryRequestId, datasourceId);
            persistFailureAndPublish(queryRequestId, null,
                    "No AI configuration bound to datasource " + datasourceId);
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
            var result = strategy.analyze(snapshot.sqlText(), descriptor.dbType(), schemaContext,
                    resolveLanguage(snapshot.organizationId()), descriptor.aiConfigId());
            var issuesJson = responseParser.issuesAsJson(result.issues());
            var command = new PersistAiAnalysisCommand(
                    result.aiProvider(), result.aiModel(), result.riskScore(), result.riskLevel(),
                    result.summary(), issuesJson, result.missingIndexesDetected(),
                    result.affectsRowEstimate(), result.promptTokens(), result.completionTokens());
            var analysisId = aiAnalysisPersistenceService.persist(queryRequestId, command);
            eventPublisher.publishEvent(new AiAnalysisCompletedEvent(queryRequestId, analysisId, result.riskLevel()));
        } catch (AiAnalysisException | AiAnalysisParseException e) {
            log.warn("AI analysis failed for query {}: {}", queryRequestId, e.getMessage());
            persistFailureAndPublish(queryRequestId, descriptor.aiConfigId(), e.getMessage());
        }
    }

    private void requireAnalysisEnabled(DatasourceConnectionDescriptor descriptor) {
        if (!descriptor.aiAnalysisEnabled()) {
            throw new AiAnalysisException("AI analysis is disabled for this datasource");
        }
    }

    private UUID requireBoundAiConfig(DatasourceConnectionDescriptor descriptor) {
        var aiConfigId = descriptor.aiConfigId();
        if (aiConfigId == null) {
            throw new AiAnalysisException("No AI configuration bound to this datasource");
        }
        return aiConfigId;
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

    private void persistFailureAndPublish(UUID queryRequestId, UUID aiConfigId, String reason) {
        var fallback = resolveSentinelConfig(aiConfigId);
        var command = new PersistAiAnalysisCommand(
                fallback.provider(), fallback.model(), 100, RiskLevel.CRITICAL,
                "AI analysis failed: " + reason, "[]", false, null, 0, 0);
        try {
            aiAnalysisPersistenceService.persist(queryRequestId, command);
        } catch (RuntimeException persistError) {
            log.error("Failed to persist sentinel AI failure row for query {}: {}",
                    queryRequestId, persistError.getMessage(), persistError);
        }
        eventPublisher.publishEvent(new AiAnalysisFailedEvent(queryRequestId, reason));
    }

    private SentinelConfig resolveSentinelConfig(UUID aiConfigId) {
        if (aiConfigId == null) {
            return new SentinelConfig(AiProviderType.ANTHROPIC, UNKNOWN_MODEL);
        }
        try {
            return aiConfigRepository.findById(aiConfigId)
                    .map(e -> {
                        var model = (e.getModel() == null || e.getModel().isBlank())
                                ? UNKNOWN_MODEL : e.getModel();
                        return new SentinelConfig(e.getProvider(), model);
                    })
                    .orElse(new SentinelConfig(AiProviderType.ANTHROPIC, UNKNOWN_MODEL));
        } catch (RuntimeException e) {
            log.warn("Failed to resolve AI config {} when recording sentinel failure: {}",
                    aiConfigId, e.getMessage());
            return new SentinelConfig(AiProviderType.ANTHROPIC, UNKNOWN_MODEL);
        }
    }

    private record SentinelConfig(AiProviderType provider, String model) {
    }
}
