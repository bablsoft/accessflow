package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
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
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final DataClassificationQueryService dataClassificationQueryService;
    private final SqlParserService sqlParserService;
    private final ApplicationEventPublisher eventPublisher;
    private final AiRateLimiter aiRateLimiter;

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
        var classificationTags = loadClassificationTags(datasourceId, organizationId);
        var schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns,
                classificationAnnotations(classificationTags));
        aiRateLimiter.enforce(organizationId);
        var result = strategy.analyze(sql, descriptor.dbType(), schemaContext,
                resolveLanguage(organizationId), aiConfigId);
        return ClassificationRiskBooster.boost(result,
                ClassificationRiskBooster.bumpFor(referencedClassifications(sql, classificationTags)));
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
            eventPublisher.publishEvent(
                    new AiAnalysisSkippedEvent(queryRequestId, "ai_analysis_enabled=false"));
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
        var classificationTags = loadClassificationTags(datasourceId, snapshot.organizationId());
        String schemaContext = null;
        try {
            var schemaView = datasourceAdminService.introspectSchemaForSystem(datasourceId, snapshot.organizationId());
            schemaContext = promptRenderer.describeSchema(schemaView, restrictedColumns,
                    classificationAnnotations(classificationTags));
        } catch (RuntimeException e) {
            log.warn("Schema introspection failed for query {}: {}", queryRequestId, e.getMessage());
        }
        try {
            aiRateLimiter.enforce(snapshot.organizationId());
            var analysis = strategy.analyze(snapshot.sqlText(), descriptor.dbType(), schemaContext,
                    resolveLanguage(snapshot.organizationId()), descriptor.aiConfigId());
            var result = ClassificationRiskBooster.boost(analysis, ClassificationRiskBooster.bumpFor(
                    referencedClassifications(snapshot.sqlText(), classificationTags)));
            var issuesJson = responseParser.issuesAsJson(result.issues());
            var optimizationsJson = responseParser.optimizationsAsJson(result.optimizations());
            var command = new PersistAiAnalysisCommand(
                    result.aiProvider(), result.aiModel(), result.riskScore(), result.riskLevel(),
                    result.summary(), issuesJson, optimizationsJson, result.missingIndexesDetected(),
                    result.affectsRowEstimate(), result.promptTokens(), result.completionTokens(),
                    false, null);
            var analysisId = aiAnalysisPersistenceService.persist(queryRequestId, command);
            eventPublisher.publishEvent(new AiAnalysisCompletedEvent(queryRequestId, analysisId,
                    result.riskLevel(), result.riskScore()));
        } catch (AiBudgetExceededException e) {
            log.warn("AI analysis blocked for query {}: monthly token budget exhausted ({})",
                    queryRequestId, e.getMessage());
            persistSentinel(queryRequestId, descriptor.aiConfigId(), "AI budget exhausted", e.getMessage());
        } catch (AiRateLimitExceededException e) {
            log.warn("AI analysis blocked for query {}: rate limit exceeded ({})",
                    queryRequestId, e.getMessage());
            persistSentinel(queryRequestId, descriptor.aiConfigId(), "AI rate limit exceeded", e.getMessage());
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

    private List<DataClassificationTagView> loadClassificationTags(UUID datasourceId, UUID organizationId) {
        try {
            return dataClassificationQueryService.findByDatasource(datasourceId, organizationId);
        } catch (RuntimeException e) {
            log.warn("Failed to load classification tags for datasource {}: {}", datasourceId,
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Builds the prompt-annotation map keyed by lowercase {@code table} (table-level tags) and
     * {@code table.column} (column-level tags), so the schema context can surface classifications
     * next to each object.
     */
    private Map<String, List<DataClassification>> classificationAnnotations(
            List<DataClassificationTagView> tags) {
        var map = new LinkedHashMap<String, List<DataClassification>>();
        for (var tag : tags) {
            var table = lastSegment(tag.tableName());
            var key = tag.columnName() == null
                    ? table
                    : table + "." + tag.columnName().toLowerCase(Locale.ROOT);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(tag.classification());
        }
        return map;
    }

    /**
     * Resolves the set of classifications carried by tables the query references. Parsing failures
     * (unparseable SQL) yield an empty set — the bump is best-effort and never blocks analysis.
     */
    private Set<DataClassification> referencedClassifications(String sql,
                                                              List<DataClassificationTagView> tags) {
        if (tags.isEmpty()) {
            return Set.of();
        }
        Set<String> referenced;
        try {
            referenced = sqlParserService.parse(sql).referencedTables();
        } catch (RuntimeException e) {
            log.debug("Classification risk: could not resolve referenced tables: {}", e.getMessage());
            return Set.of();
        }
        if (referenced == null || referenced.isEmpty()) {
            return Set.of();
        }
        var referencedTables = referenced.stream().map(this::lastSegment).collect(Collectors.toSet());
        var result = EnumSet.noneOf(DataClassification.class);
        for (var tag : tags) {
            if (referencedTables.contains(lastSegment(tag.tableName()))) {
                result.add(tag.classification());
            }
        }
        return result;
    }

    private String lastSegment(String qualifiedName) {
        if (qualifiedName == null) {
            return "";
        }
        var normalized = qualifiedName.toLowerCase(Locale.ROOT);
        var dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private void persistFailureAndPublish(UUID queryRequestId, UUID aiConfigId, String reason) {
        persistSentinel(queryRequestId, aiConfigId, "AI analysis failed: " + reason, reason);
    }

    /**
     * Writes a sentinel {@code CRITICAL} analysis row (risk 100, no tokens) carrying {@code summary}
     * as the reviewer-facing reason, then publishes {@link AiAnalysisFailedEvent}. Used for provider
     * failures ({@code "AI analysis failed: …"}) and for the AF-55 guardrails
     * ({@code "AI budget exhausted"} / {@code "AI rate limit exceeded"}).
     */
    private void persistSentinel(UUID queryRequestId, UUID aiConfigId, String summary, String reason) {
        var fallback = resolveSentinelConfig(aiConfigId);
        var command = new PersistAiAnalysisCommand(
                fallback.provider(), fallback.model(), 100, RiskLevel.CRITICAL,
                summary, "[]", "[]", false, null, 0, 0,
                true, reason);
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
