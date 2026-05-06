package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiIssue;
import com.partqam.accessflow.core.api.AiAnalysisPersistenceService;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.DatabaseSchemaView;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.PersistAiAnalysisCommand;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
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
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock AiAnalysisPersistenceService aiAnalysisPersistenceService;
    @Mock ApplicationEventPublisher eventPublisher;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();
    private final AiResponseParser responseParser = new AiResponseParser(JsonMapper.builder().build());

    private final AiAnalyzerProperties properties = new AiAnalyzerProperties(AiProviderType.ANTHROPIC);

    private DefaultAiAnalyzerService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID queryRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultAiAnalyzerService(strategy, properties, promptRenderer, responseParser,
                datasourceLookupService, datasourceAdminService, queryRequestLookupService,
                aiAnalysisPersistenceService, eventPublisher, "model-x");
    }

    private DatasourceConnectionDescriptor descriptor(DbType dbType) {
        return new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(), dbType, "h",
                5432, "db", "u", "ENC(p)", SslMode.DISABLE, 5, 1000, true);
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
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), any())).thenReturn(sampleResult());

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
    void analyzeSubmittedQueryPersistsResultAndPublishesCompleted() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.MYSQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.MYSQL), any())).thenReturn(sampleResult());
        var newAnalysisId = UUID.randomUUID();
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(newAnalysisId);

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        var cmd = cmdCaptor.getValue();
        assertThat(cmd.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(cmd.aiModel()).isEqualTo("model-x");
        assertThat(cmd.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(cmd.issuesJson()).contains("\"severity\":\"HIGH\"");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisCompletedEvent.class, ev -> {
                    assertThat(ev.queryRequestId()).isEqualTo(queryRequestId);
                    assertThat(ev.aiAnalysisId()).isEqualTo(newAnalysisId);
                    assertThat(ev.riskLevel()).isEqualTo(RiskLevel.HIGH);
                });
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelOnStrategyFailure() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any())).thenThrow(new AiAnalysisException("provider down"));

        service.analyzeSubmittedQuery(queryRequestId);

        ArgumentCaptor<PersistAiAnalysisCommand> cmdCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(aiAnalysisPersistenceService).persist(eq(queryRequestId), cmdCaptor.capture());
        var cmd = cmdCaptor.getValue();
        assertThat(cmd.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(cmd.riskScore()).isEqualTo(100);
        assertThat(cmd.summary()).startsWith("AI analysis failed:");
        assertThat(cmd.issuesJson()).isEqualTo("[]");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(AiAnalysisFailedEvent.class, ev -> {
                    assertThat(ev.queryRequestId()).isEqualTo(queryRequestId);
                    assertThat(ev.reason()).isEqualTo("provider down");
                });
    }

    @Test
    void analyzeSubmittedQueryPersistsSentinelOnParseFailure() {
        var snapshot = new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI);
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.of(snapshot));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(DbType.POSTGRESQL)));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId)).thenReturn(schemaView());
        when(strategy.analyze(any(), any(), any())).thenThrow(new AiAnalysisParseException("bad json"));

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
        when(strategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null))).thenReturn(sampleResult());
        when(aiAnalysisPersistenceService.persist(eq(queryRequestId), any())).thenReturn(UUID.randomUUID());

        service.analyzeSubmittedQuery(queryRequestId);

        verify(strategy).analyze("SELECT 1", DbType.POSTGRESQL, null);
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
}
