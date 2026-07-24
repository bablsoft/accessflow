package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AiIssue;
import com.bablsoft.accessflow.ai.api.AiModelResult;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.OptimizationSuggestion;
import com.bablsoft.accessflow.ai.api.OptimizationType;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.LocalizationConfigView;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiAnalyzerServiceTest {

    @Mock AiAnalyzerStrategy strategy;
    @Mock AiConfigRepository aiConfigRepository;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock AiAnalysisPersistenceService aiAnalysisPersistenceService;
    @Mock LocalizationConfigService localizationConfigService;
    @Mock DataClassificationQueryService dataClassificationQueryService;
    @Mock SqlParserService sqlParserService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AiRateLimiter aiRateLimiter;
    @Mock com.bablsoft.accessflow.proxy.api.QueryCostEstimateService queryCostEstimateService;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();
    private final AiResponseParser responseParser = new AiResponseParser(JsonMapper.builder().build());
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final io.micrometer.observation.ObservationRegistry observationRegistry =
            io.micrometer.observation.ObservationRegistry.create();

    private DefaultAiAnalyzerService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID queryRequestId = UUID.randomUUID();
    private final UUID aiConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultAiAnalyzerService(strategy, aiConfigRepository, promptRenderer, responseParser,
                datasourceLookupService, datasourceAdminService, queryRequestLookupService,
                permissionLookupService, aiAnalysisPersistenceService, localizationConfigService,
                dataClassificationQueryService, sqlParserService, eventPublisher, aiRateLimiter,
                observationRegistry, queryCostEstimateService, meterRegistry);
        org.mockito.Mockito.lenient().when(localizationConfigService.getOrDefault(any()))
                .thenReturn(new LocalizationConfigView(organizationId, List.of("en"), "en", "en"));
        org.mockito.Mockito.lenient().when(aiConfigRepository.findById(aiConfigId))
                .thenReturn(Optional.of(aiConfigEntity("model-x")));
    }

    private AiConfigEntity aiConfigEntity(String model) {
        var entity = new AiConfigEntity();
        entity.setId(aiConfigId);
        entity.setOrganizationId(organizationId);
        entity.setName("Primary");
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setModel(model);
        return entity;
    }

    private DatasourceConnectionDescriptor descriptor(DbType dbType) {
        return descriptor(dbType, true, aiConfigId);
    }

    private DatasourceConnectionDescriptor descriptor(DbType dbType, boolean aiEnabled, UUID boundAiConfigId) {
        return new DatasourceConnectionDescriptor(datasourceId, organizationId, dbType, "h",
                5432, "db", "u", "ENC(p)", SslMode.DISABLE, 5, 1000, aiEnabled, boundAiConfigId,
                false, null, null, null, null, null, null, true);
    }

    private DatabaseSchemaView schemaView() {
        return new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true)),
                                List.of())))));
    }

    private AiAnalysisResult sampleResult() {
        return new AiAnalysisResult(85, RiskLevel.HIGH, "summary",
                List.of(new AiIssue(RiskLevel.HIGH, "C", "m", "s")),
                false, null, AiProviderType.ANTHROPIC, "model-x", 100, 50,
                List.of(new OptimizationSuggestion(OptimizationType.INDEX,
                        "Add index on users(email)", "Speeds up the lookup",
                        "CREATE INDEX idx_users_email ON users(email)")));
    }

    @Test
    void analyzePreviewResolvesDbTypeAndSchema() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), any(), any(), eq(aiConfigId)))
                .thenReturn(sampleResult());

        var result = service.analyzePreview(datasourceId, "SELECT 1", userId, organizationId, false);

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        verify(aiAnalysisPersistenceService, never()).persist(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void analyzePreviewThrowsWhenDatasourceMissing() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyzePreview(datasourceId, "SELECT 1", userId, organizationId, false))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("Datasource not found");
    }

    @Test
    void analyzePreviewThrowsWhenAiAnalysisDisabled() {
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(DbType.POSTGRESQL, false, aiConfigId)));

        assertThatThrownBy(() -> service.analyzePreview(datasourceId, "SELECT 1", userId, organizationId, false))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void analyzePreviewThrowsWhenAiConfigNotBound() {
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(DbType.POSTGRESQL, true, null)));

        assertThatThrownBy(() -> service.analyzePreview(datasourceId, "SELECT 1", userId, organizationId, false))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("No AI configuration");
    }

    @Test
    void analyzePreviewIncludesRestrictedColumnMarkerInSchemaContext() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(new com.bablsoft.accessflow.core.api.DatabaseSchemaView(List.of(
                        new com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema("public", List.of(
                                new com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table("users", List.of(
                                        new com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column(
                                                "ssn", "text", true, false)),
                                        List.of()))))));
        var permission = new com.bablsoft.accessflow.core.api.DatasourceUserPermissionView(
                UUID.randomUUID(), userId, datasourceId, true, false, false, false,
                List.of(), List.of(), List.of("public.users.ssn"), null);
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.of(permission));
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        when(strategy.analyze(eq("SELECT ssn FROM users"), eq(DbType.POSTGRESQL), contextCaptor.capture(),
                any(), eq(aiConfigId)))
                .thenReturn(sampleResult());

        service.analyzePreview(datasourceId, "SELECT ssn FROM users", userId, organizationId, false);

        assertThat(contextCaptor.getValue()).contains("ssn text *RESTRICTED*");
    }

    @Test
    void analyzeSubmittedQueryPersistsResultAndPublishesCompleted() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.MYSQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.MYSQL), any(), any(), any(), eq(aiConfigId)))
                .thenReturn(sampleResult());
        var newAnalysisId = UUID.randomUUID();
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(newAnalysisId);

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        var cmd = cmdCaptor.getValue();
        assertThat(cmd.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(cmd.aiModel()).isEqualTo("model-x");
        assertThat(cmd.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(cmd.optimizationsJson()).contains("\"type\":\"INDEX\"")
                .contains("CREATE INDEX idx_users_email ON users(email)");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisCompletedEvent.class, ev -> {
                    assertThat(ev.queryRequestId()).isEqualTo(queryRequestId);
                    assertThat(ev.aiAnalysisId()).isEqualTo(newAnalysisId);
                });

        // AF-454: the AI call is recorded as the accessflow.ai.tokens distribution summary,
        // split per provider + token type, and the strategy is timed under accessflow.ai.analyze.
        assertThat(meterRegistry.get("accessflow.ai.tokens")
                .tags("provider", "ANTHROPIC", "type", "prompt").summary().totalAmount())
                .isEqualTo(100.0);
        assertThat(meterRegistry.get("accessflow.ai.tokens")
                .tags("provider", "ANTHROPIC", "type", "completion").summary().totalAmount())
                .isEqualTo(50.0);
    }

    @Test
    void analyzeSubmittedQueryPersistsPerModelBreakdown() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.MYSQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        var multiModel = new AiAnalysisResult(85, RiskLevel.HIGH, "summary",
                List.of(new AiIssue(RiskLevel.HIGH, "C", "m", "s")), false, null,
                AiProviderType.ANTHROPIC, "model-x", 150, 60, List.of(),
                List.of(
                        new AiModelResult(AiProviderType.ANTHROPIC, "model-x", 85, RiskLevel.HIGH, 1.0,
                                100, 40, 900L, false, null),
                        new AiModelResult(AiProviderType.OLLAMA, "llama3", 50, RiskLevel.MEDIUM, 2.0,
                                50, 20, 200L, false, null)));
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.MYSQL), any(), any(), any(), eq(aiConfigId)))
                .thenReturn(multiModel);
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        var cmd = cmdCaptor.getValue();
        assertThat(cmd.modelResults()).hasSize(2);
        assertThat(cmd.modelResults().get(0).model()).isEqualTo("model-x");
        assertThat(cmd.modelResults().get(0).latencyMs()).isEqualTo(900L);
        assertThat(cmd.modelResults().get(1).provider()).isEqualTo(AiProviderType.OLLAMA);
        assertThat(cmd.modelResults().get(1).weight()).isEqualTo(2.0);
    }

    @Test
    void analyzeSubmittedQueryRaisesRiskWhenReferencedTableIsClassified() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT * FROM users", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(dataClassificationQueryService.findByDatasource(datasourceId, organizationId))
                .thenReturn(List.of(new DataClassificationTagView(UUID.randomUUID(), datasourceId, "users",
                        "id", DataClassification.PCI, null, Instant.now(), Instant.now())));
        when(sqlParserService.parse("SELECT * FROM users"))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("SELECT * FROM users"),
                        Set.of("users")));
        // LLM verdict is MEDIUM/60; PCI adds +30 → 90 → CRITICAL.
        when(strategy.analyze(any(), any(), any(), any(), any(), eq(aiConfigId)))
                .thenReturn(new AiAnalysisResult(60, RiskLevel.MEDIUM, "s", List.of(), false, null,
                        AiProviderType.ANTHROPIC, "model-x", 1, 1, List.of()));
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().riskScore()).isEqualTo(90);
        assertThat(cmdCaptor.getValue().riskLevel()).isEqualTo(RiskLevel.CRITICAL);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisCompletedEvent.class,
                        ev -> assertThat(ev.riskScore()).isEqualTo(90));
    }

    @Test
    void analyzeSubmittedQueryAnnotatesSchemaContextWithClassifications() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT id FROM users", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(dataClassificationQueryService.findByDatasource(datasourceId, organizationId))
                .thenReturn(List.of(new DataClassificationTagView(UUID.randomUUID(), datasourceId, "users",
                        "id", DataClassification.PII, null, Instant.now(), Instant.now())));
        when(sqlParserService.parse("SELECT id FROM users"))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("SELECT id FROM users"),
                        Set.of("users")));
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        when(strategy.analyze(eq("SELECT id FROM users"), eq(DbType.POSTGRESQL), contextCaptor.capture(), any(),
                any(), eq(aiConfigId))).thenReturn(sampleResult());
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        assertThat(contextCaptor.getValue()).contains("id uuid pk not null [PII]");
    }

    @Test
    void analyzeSubmittedQueryPublishesSkippedEventWhenAiAnalysisDisabled() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(DbType.POSTGRESQL, false, aiConfigId)));

        service.analyzeSubmittedQuery(queryRequestId);

        verify(aiAnalysisPersistenceService, never()).persist(any(), any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisSkippedEvent.class, ev -> {
                    assertThat(ev.queryRequestId()).isEqualTo(queryRequestId);
                    assertThat(ev.reason()).isEqualTo("ai_analysis_enabled=false");
                });
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelWhenAiConfigNotBound() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(DbType.POSTGRESQL, true, null)));

        service.analyzeSubmittedQuery(queryRequestId);

        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), any());
        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelOnStrategyFailure() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), any(), eq(aiConfigId)))
                .thenThrow(new AiAnalysisException("provider down"));

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        var cmd = cmdCaptor.getValue();
        assertThat(cmd.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(cmd.summary()).startsWith("AI analysis failed:");
        assertThat(cmd.aiModel()).isEqualTo("model-x");
        assertThat(cmd.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);

        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelOnParseFailure() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), any(), eq(aiConfigId)))
                .thenThrow(new AiAnalysisParseException("bad json"));

        service.analyzeSubmittedQuery(queryRequestId);

        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
    }

    @Test
    void analyzeSubmittedQueryFallsBackToNullSchemaWhenIntrospectionFails() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenThrow(new RuntimeException("connection refused"));
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null), any(), any(), eq(aiConfigId)))
                .thenReturn(sampleResult());
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        verify(strategy).analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null), any(), any(), eq(aiConfigId));
        verify(eventPublisher).publishEvent(any(AiAnalysisCompletedEvent.class));
    }

    @Test
    void analyzeSubmittedQueryNoOpsWhenSnapshotMissing() {
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.empty());

        service.analyzeSubmittedQuery(queryRequestId);

        verify(aiAnalysisPersistenceService, never()).persist(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelWhenDatasourceMissing() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> captor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().summary()).contains("Datasource not found");
        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
    }

    @Test
    void sentinelFallsBackToUnknownWhenAiConfigLookupFails() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), any(), eq(aiConfigId)))
                .thenThrow(new AiAnalysisException("provider down"));
        when(aiConfigRepository.findById(aiConfigId)).thenThrow(new RuntimeException("db unreachable"));

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> captor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().aiModel()).isEqualTo("unknown");
        assertThat(captor.getValue().aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(captor.getValue().optimizationsJson()).isEqualTo("[]");
    }

    @Test
    void analyzePreviewPropagatesRateLimitExceeded() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchema(datasourceId, organizationId, userId, false))
                .thenReturn(schemaView());
        org.mockito.Mockito.doThrow(new AiRateLimitExceededException(30, 60))
                .when(aiRateLimiter).enforce(organizationId);

        assertThatThrownBy(() -> service.analyzePreview(datasourceId, "SELECT 1", userId, organizationId, false))
                .isInstanceOf(AiRateLimitExceededException.class);
        verify(strategy, never()).analyze(any(), any(), any(), any(), any());
        verify(strategy, never()).analyze(any(), any(), any(), any(), any(), any());
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelWhenBudgetExhausted() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        org.mockito.Mockito.doThrow(new AiBudgetExceededException(1000, 1000))
                .when(aiRateLimiter).enforce(organizationId);

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> captor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.summary()).isEqualTo("AI budget exhausted");
        assertThat(cmd.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(cmd.failed()).isTrue();
        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
        verify(strategy, never()).analyze(any(), any(), any(), any(), any());
        verify(strategy, never()).analyze(any(), any(), any(), any(), any(), any());
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelWhenRateLimited() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        org.mockito.Mockito.doThrow(new AiRateLimitExceededException(30, 60))
                .when(aiRateLimiter).enforce(organizationId);

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> captor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().summary()).isEqualTo("AI rate limit exceeded");
        assertThat(captor.getValue().riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
        verify(strategy, never()).analyze(any(), any(), any(), any(), any());
        verify(strategy, never()).analyze(any(), any(), any(), any(), any(), any());
    }
}
