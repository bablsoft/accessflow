package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.DeploymentAnalyzer;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisCompletedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisFailedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisSkippedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentSubmittedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentAnalysisListenerTest {

    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRequestRepository requestRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentAnalyzer deploymentAnalyzer;
    private AiAnalysisPersistenceService persistenceService;
    private ApplicationEventPublisher eventPublisher;
    private DeploymentAnalysisListener listener;

    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity environment;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        deploymentAnalyzer = mock(DeploymentAnalyzer.class);
        persistenceService = mock(AiAnalysisPersistenceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        listener = new DeploymentAnalysisListener(requestRepository, pipelineRepository,
                environmentRepository, deploymentAnalyzer, persistenceService, eventPublisher,
                JsonMapper.builder().build());

        pipeline = pipeline(true, UUID.randomUUID());
        environment = environment();
        lenient().when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.of(pipeline));
        lenient().when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
    }

    @Test
    void unknownRequestIsIgnored() {
        var id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        listener.onSubmitted(new DeploymentSubmittedEvent(id));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aiDisabledPublishesSkipped() {
        pipeline.setAiAnalysisEnabled(false);
        var request = stubRequest("{}");

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        verify(eventPublisher).publishEvent(any(DeploymentAnalysisSkippedEvent.class));
        verify(deploymentAnalyzer, never()).analyzeDeployment(any());
    }

    @Test
    void missingAiConfigPublishesSkipped() {
        pipeline.setAiConfigId(null);
        var request = stubRequest("{}");

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        verify(eventPublisher).publishEvent(any(DeploymentAnalysisSkippedEvent.class));
    }

    @Test
    void missingPipelinePublishesSkipped() {
        var request = stubRequest("{}");
        when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.empty());

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        verify(eventPublisher).publishEvent(any(DeploymentAnalysisSkippedEvent.class));
    }

    @Test
    void successPersistsTheAnalysisAndStampsTheRequest() {
        var request = stubRequest("{\"changelog\":\"fix things\"}");
        var analysisId = UUID.randomUUID();
        when(deploymentAnalyzer.analyzeDeployment(any())).thenReturn(result());
        when(persistenceService.persistForDeploymentRequest(eq(request.getId()), any()))
                .thenReturn(analysisId);

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        assertThat(request.getAiAnalysisId()).isEqualTo(analysisId);
        verify(requestRepository).save(request);
        var commandCaptor = ArgumentCaptor.forClass(PersistAiAnalysisCommand.class);
        verify(persistenceService).persistForDeploymentRequest(eq(request.getId()),
                commandCaptor.capture());
        assertThat(commandCaptor.getValue().failed()).isFalse();
        assertThat(commandCaptor.getValue().issuesJson()).isEqualTo("[]");
        var eventCaptor = ArgumentCaptor.forClass(DeploymentAnalysisCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(eventCaptor.getValue().aiAnalysisId()).isEqualTo(analysisId);
    }

    @Test
    void theAnalyzerReceivesThePipelineAndEnvironmentContext() {
        var request = stubRequest("{\"changelog\":\"fix things\"}");
        when(deploymentAnalyzer.analyzeDeployment(any())).thenReturn(result());

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        var captor = ArgumentCaptor.forClass(DeploymentAnalyzer.DeploymentAnalysisInput.class);
        verify(deploymentAnalyzer).analyzeDeployment(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG);
        assertThat(captor.getValue().aiConfigId()).isEqualTo(pipeline.getAiConfigId());
        assertThat(captor.getValue().provider()).isEqualTo("GITHUB_ACTIONS");
        assertThat(captor.getValue().environment()).isEqualTo("production");
        assertThat(captor.getValue().version()).isEqualTo("2.4.1");
        assertThat(captor.getValue().metadataContext()).contains("changelog");
    }

    @Test
    void emptyMetadataBecomesNullContext() {
        var request = stubRequest("{}");
        when(deploymentAnalyzer.analyzeDeployment(any())).thenReturn(result());

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        var captor = ArgumentCaptor.forClass(DeploymentAnalyzer.DeploymentAnalysisInput.class);
        verify(deploymentAnalyzer).analyzeDeployment(captor.capture());
        assertThat(captor.getValue().metadataContext()).isNull();
    }

    @Test
    void oversizedMetadataIsTruncated() {
        var request = stubRequest("{\"c\":\"" + "x".repeat(40_000) + "\"}");
        when(deploymentAnalyzer.analyzeDeployment(any())).thenReturn(result());

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        var captor = ArgumentCaptor.forClass(DeploymentAnalyzer.DeploymentAnalysisInput.class);
        verify(deploymentAnalyzer).analyzeDeployment(captor.capture());
        assertThat(captor.getValue().metadataContext())
                .hasSizeLessThan(DeploymentAnalysisListener.MAX_METADATA_CONTEXT_CHARS + 100)
                .endsWith("(truncated)");
    }

    @Test
    void providerFailurePublishesFailed() {
        var request = stubRequest("{}");
        when(deploymentAnalyzer.analyzeDeployment(any()))
                .thenThrow(new AiAnalysisException("provider down"));

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        var captor = ArgumentCaptor.forClass(DeploymentAnalysisFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("provider down");
        verify(persistenceService, never()).persistForDeploymentRequest(any(), any());
    }

    @Test
    void strictJsonParseFailurePublishesFailedRatherThanPropagating() {
        var request = stubRequest("{}");
        when(deploymentAnalyzer.analyzeDeployment(any()))
                .thenThrow(new AiAnalysisParseException("risk_score must be in [0, 100]"));

        listener.onSubmitted(new DeploymentSubmittedEvent(request.getId()));

        var captor = ArgumentCaptor.forClass(DeploymentAnalysisFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).contains("risk_score");
    }

    private DeploymentRequestEntity stubRequest(String metadata) {
        var request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setOrganizationId(ORG);
        request.setPipelineId(pipeline.getId());
        request.setEnvironmentId(environment.getId());
        request.setVersion("2.4.1");
        request.setCommitSha("abc123");
        request.setMetadata(metadata);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private static AiAnalysisResult result() {
        return new AiAnalysisResult(80, RiskLevel.HIGH, "schema migration", List.of(), false, null,
                AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 100, 50, List.of());
    }

    private static DeploymentPipelineEntity pipeline(boolean aiEnabled, UUID aiConfigId) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName("payments-api");
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setAiAnalysisEnabled(aiEnabled);
        entity.setAiConfigId(aiConfigId);
        entity.setActive(true);
        return entity;
    }

    private static DeploymentEnvironmentEntity environment() {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("production");
        entity.setRequireReview(true);
        return entity;
    }
}
