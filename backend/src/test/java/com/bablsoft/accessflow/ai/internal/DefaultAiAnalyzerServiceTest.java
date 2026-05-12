package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiIssue;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
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
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
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
    @Mock ApplicationEventPublisher eventPublisher;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();
    private final AiResponseParser responseParser = new AiResponseParser(JsonMapper.builder().build());

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
                eventPublisher);
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
                5432, "db", "u", "ENC(p)", SslMode.DISABLE, 5, 1000, aiEnabled, boundAiConfigId, true);
    }

    private DatabaseSchemaView schemaView() {
        return new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true)))))));
    }

    private AiAnalysisResult sampleResult() {
        return new AiAnalysisResult(85, RiskLevel.HIGH, "summary",
                List.of(new AiIssue(RiskLevel.HIGH, "C", "m", "s")),
                false, null, AiProviderType.ANTHROPIC, "model-x", 100, 50);
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
                                                "ssn", "text", true, false))))))));
        var permission = new com.bablsoft.accessflow.core.api.DatasourceUserPermissionView(
                UUID.randomUUID(), userId, datasourceId, true, false, false,
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
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.MYSQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.MYSQL), any(), any(), eq(aiConfigId)))
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

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisCompletedEvent.class, ev -> {
                    assertThat(ev.queryRequestId()).isEqualTo(queryRequestId);
                    assertThat(ev.aiAnalysisId()).isEqualTo(newAnalysisId);
                });
    }

    @Test
    void analyzeSubmittedQueryIsSkippedWhenAiAnalysisDisabled() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(descriptor(DbType.POSTGRESQL, false, aiConfigId)));

        service.analyzeSubmittedQuery(queryRequestId);

        verify(aiAnalysisPersistenceService, never()).persist(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelWhenAiConfigNotBound() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
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
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), eq(aiConfigId)))
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
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), eq(aiConfigId)))
                .thenThrow(new AiAnalysisParseException("bad json"));

        service.analyzeSubmittedQuery(queryRequestId);

        verify(eventPublisher).publishEvent(any(AiAnalysisFailedEvent.class));
    }

    @Test
    void analyzeSubmittedQueryFallsBackToNullSchemaWhenIntrospectionFails() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenThrow(new RuntimeException("connection refused"));
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null), any(), eq(aiConfigId)))
                .thenReturn(sampleResult());
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        verify(strategy).analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null), any(), eq(aiConfigId));
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
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
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
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any(), any(), eq(aiConfigId)))
                .thenThrow(new AiAnalysisException("provider down"));
        when(aiConfigRepository.findById(aiConfigId)).thenThrow(new RuntimeException("db unreachable"));

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> captor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().aiModel()).isEqualTo("unknown");
        assertThat(captor.getValue().aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
    }
}
