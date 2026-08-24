package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentSubmittedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentRequestServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();

    private DeploymentRequestRepository requestRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentReviewDecisionRepository decisionRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private FreezeWindowEvaluator freezeWindowEvaluator;
    private DeploymentRequestStateService stateService;
    private AiAnalysisLookupService aiAnalysisLookupService;
    private UserQueryService userQueryService;
    private ApplicationEventPublisher eventPublisher;
    private DefaultDeploymentRequestService service;

    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity environment;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        decisionRepository = mock(DeploymentReviewDecisionRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        freezeWindowEvaluator = mock(FreezeWindowEvaluator.class);
        stateService = mock(DeploymentRequestStateService.class);
        aiAnalysisLookupService = mock(AiAnalysisLookupService.class);
        userQueryService = mock(UserQueryService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DefaultDeploymentRequestService(requestRepository, pipelineRepository,
                environmentRepository, decisionRepository, permissionResolver, freezeWindowEvaluator,
                stateService, aiAnalysisLookupService, userQueryService, eventPublisher,
                JsonMapper.builder().build());

        pipeline = pipeline();
        environment = environment();
        lenient().when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), ORG))
                .thenReturn(Optional.of(pipeline));
        lenient().when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.of(pipeline));
        lenient().when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(), "production"))
                .thenReturn(Optional.of(environment));
        lenient().when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        lenient().when(permissionResolver.resolve(pipeline.getId(), SUBMITTER))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipeline.getId(), SUBMITTER,
                        true, false, null)));
        lenient().when(freezeWindowEvaluator.evaluate(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(userQueryService.findById(any())).thenReturn(Optional.empty());
        lenient().when(decisionRepository.findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(any()))
                .thenReturn(List.of());
    }

    // ---- submit -------------------------------------------------------------------------------

    @Test
    void submitPersistsPendingAiAndPublishesSubmitted() {
        var result = service.submit(command("run-1"));

        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_AI);
        assertThat(result.request().pipelineName()).isEqualTo("payments-api");
        assertThat(result.request().environmentName()).isEqualTo("production");
        assertThat(result.replay()).isFalse();
        verify(eventPublisher).publishEvent(any(DeploymentSubmittedEvent.class));
        var saved = captureSaved();
        assertThat(saved.getOrganizationId()).isEqualTo(ORG);
        assertThat(saved.getPipelineId()).isEqualTo(pipeline.getId());
        assertThat(saved.getEnvironmentId()).isEqualTo(environment.getId());
        assertThat(saved.getVersion()).isEqualTo("2.4.1");
        assertThat(saved.getExternalRunId()).isEqualTo("run-1");
        assertThat(saved.getSubmittedIp()).isEqualTo("10.0.0.1");
        assertThat(saved.getMetadata()).contains("changelog");
    }

    @Test
    void submitCoercesNullMetadataToAnEmptyObject() {
        service.submit(new SubmitDeploymentRequestCommand(pipeline.getId(), "production", ORG,
                SUBMITTER, false, "2.4.1", null, null, null, null, null, null, null, null, null));

        assertThat(captureSaved().getMetadata()).isEqualTo("{}");
    }

    @Test
    void submitStoresNoExternalRunIdWhenBlank() {
        service.submit(command("   "));

        assertThat(captureSaved().getExternalRunId()).isNull();
        verify(requestRepository, never())
                .findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(any(), any(), any(), any());
    }

    @Test
    void submitRejectsAnUnknownPipeline() {
        var unknown = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(unknown, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(new SubmitDeploymentRequestCommand(unknown,
                "production", ORG, SUBMITTER, false, "2.4.1", null, null, null, null, null, null,
                null, null, null)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void submitRejectsAnInactivePipeline() {
        pipeline.setActive(false);

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void submitRejectsAnUnknownEnvironmentName() {
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(), "production"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    @Test
    void submitRejectsACallerWithNoGrant() {
        when(permissionResolver.resolve(pipeline.getId(), SUBMITTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DeploymentRequestPermissionException.class);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void submitRejectsAGrantWithoutTrigger() {
        when(permissionResolver.resolve(pipeline.getId(), SUBMITTER)).thenReturn(
                Optional.of(new EffectiveDeploymentPermission(pipeline.getId(), SUBMITTER, false,
                        true, null)));

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DeploymentRequestPermissionException.class);
    }

    @Test
    void anAdminMayTriggerWithoutAGrant() {
        when(permissionResolver.resolve(pipeline.getId(), SUBMITTER)).thenReturn(Optional.empty());

        var result = service.submit(new SubmitDeploymentRequestCommand(pipeline.getId(), "production",
                ORG, SUBMITTER, true, "2.4.1", null, null, null, null, null, null, null, null, null));

        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void aRejectFreezeWindowAutoRejectsAtSubmission() {
        when(freezeWindowEvaluator.evaluate(ORG, pipeline.getId(), environment.getId()))
                .thenReturn(Optional.of(freeze(FreezeBehavior.REJECT)));
        doAnswer(inv -> {
            inv.getArgument(0, DeploymentRequestEntity.class).setStatus(QueryStatus.REJECTED);
            return null;
        }).when(stateService).apply(any(), any());

        var result = service.submit(command("run-1"));

        assertThat(result.request().status()).isEqualTo(QueryStatus.REJECTED);
        verify(stateService).apply(any(), org.mockito.ArgumentMatchers.eq(QueryStatus.REJECTED));
        verify(eventPublisher, never()).publishEvent(any(DeploymentSubmittedEvent.class));
        var captor = org.mockito.ArgumentCaptor.forClass(DeploymentDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).startsWith("freeze:");
    }

    @Test
    void aHoldFreezeWindowDoesNotBlockSubmission() {
        when(freezeWindowEvaluator.evaluate(ORG, pipeline.getId(), environment.getId()))
                .thenReturn(Optional.of(freeze(FreezeBehavior.HOLD)));

        var result = service.submit(command("run-1"));

        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_AI);
        verify(stateService, never()).apply(any(), any());
        verify(eventPublisher).publishEvent(any(DeploymentSubmittedEvent.class));
    }

    @Test
    void aReplayedTriggerReturnsTheExistingRequestAndCreatesNothing() {
        var existing = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
                pipeline.getId(), environment.getId(), "2.4.1", "run-1"))
                .thenReturn(Optional.of(existing));

        var result = service.submit(command("run-1"));

        assertThat(result.replay()).isTrue();
        assertThat(result.request().id()).isEqualTo(existing.getId());
        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(requestRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(DeploymentSubmittedEvent.class));
        // The grant is checked before the replay lookup, so a repeat cannot probe for existing runs.
        verify(permissionResolver).resolve(pipeline.getId(), SUBMITTER);
    }

    @Test
    void aReplayWithoutAGrantIsDeniedRatherThanConfirmingTheRunExists() {
        var existing = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
                pipeline.getId(), environment.getId(), "2.4.1", "run-1"))
                .thenReturn(Optional.of(existing));
        when(permissionResolver.resolve(pipeline.getId(), SUBMITTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DeploymentRequestPermissionException.class);
    }

    @Test
    void aConcurrentTriggerLosingTheUniqueIndexResolvesToTheWinner() {
        var winner = existing(QueryStatus.PENDING_AI);
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
                pipeline.getId(), environment.getId(), "2.4.1", "run-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(requestRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_deployment_requests_trigger_idem"));

        var result = service.submit(command("run-1"));

        assertThat(result.replay()).isTrue();
        assertThat(result.request().id()).isEqualTo(winner.getId());
        verify(eventPublisher, never()).publishEvent(any(DeploymentSubmittedEvent.class));
    }

    @Test
    void anUnrelatedIntegrityViolationIsRethrown() {
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.submit(command("run-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theEnvironmentApprovalOverrideSeedsTheProvisionalCount() {
        environment.setRequiredApprovals(3);

        service.submit(command("run-1"));

        assertThat(captureSaved().getRequiredApprovals()).isEqualTo(3);
    }

    // ---- list ---------------------------------------------------------------------------------

    @Test
    void listResolvesTheEnvironmentNameFilterToIds() {
        when(environmentRepository.findIdsByOrganizationIdAndNameIgnoreCase(ORG, "production"))
                .thenReturn(List.of(environment.getId()));
        when(requestRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existing(QueryStatus.APPROVED))));

        var page = service.list(new DeploymentRequestListFilter(ORG, null, null, " production ",
                null, null, null, null), new PageRequest(0, 20, List.of()));

        verify(environmentRepository).findIdsByOrganizationIdAndNameIgnoreCase(ORG, "production");
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void listWithoutAnEnvironmentFilterSkipsTheNameLookup() {
        when(requestRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.list(new DeploymentRequestListFilter(ORG, SUBMITTER, null, null, null,
                QueryStatus.APPROVED, null, null), new PageRequest(0, 20, List.of()));

        verify(environmentRepository, never()).findIdsByOrganizationIdAndNameIgnoreCase(any(), any());
        assertThat(page.content()).isEmpty();
    }

    // ---- get ----------------------------------------------------------------------------------

    @Test
    void getReturnsTheDetailViewToTheSubmitter() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        entity.setAiAnalysisId(UUID.randomUUID());
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));
        when(aiAnalysisLookupService.findById(entity.getAiAnalysisId())).thenReturn(Optional.of(
                new AiAnalysisSummaryView(entity.getAiAnalysisId(), null, RiskLevel.HIGH, 80,
                        "migrations", false, null)));
        when(userQueryService.findById(SUBMITTER)).thenReturn(Optional.of(user()));

        var view = service.get(entity.getId(), ORG, SUBMITTER, Set.of());

        assertThat(view.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.aiRiskScore()).isEqualTo(80);
        assertThat(view.aiSummary()).isEqualTo("migrations");
        assertThat(view.submittedByEmail()).isEqualTo("ci@example.com");
        assertThat(view.pipelineName()).isEqualTo("payments-api");
        assertThat(view.environmentName()).isEqualTo("production");
        assertThat(view.metadata()).containsKey("changelog");
    }

    @Test
    void getIsVisibleToDeploymentReviewersAndAdmins() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThat(service.get(entity.getId(), ORG, UUID.randomUUID(),
                Set.of(Permission.DEPLOYMENT_REVIEW))).isNotNull();
        assertThat(service.get(entity.getId(), ORG, UUID.randomUUID(),
                Set.of(Permission.QUERY_ADMIN))).isNotNull();
    }

    @Test
    void getHidesTheRequestFromAnUnrelatedUserAsA404() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.get(entity.getId(), ORG, UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
        assertThatThrownBy(() -> service.get(entity.getId(), ORG, UUID.randomUUID(), null))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @Test
    void getRejectsACrossOrgId() {
        var id = UUID.randomUUID();
        when(requestRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, ORG, SUBMITTER, Set.of()))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @Test
    void malformedStoredMetadataStillRenders() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        entity.setMetadata("{not json");
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThat(service.get(entity.getId(), ORG, SUBMITTER, Set.of()).metadata()).isEmpty();
    }

    // ---- cancel -------------------------------------------------------------------------------

    @Test
    void cancelTransitionsAPendingReviewRequest() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        service.cancel(entity.getId(), ORG, SUBMITTER);

        verify(stateService).apply(entity, QueryStatus.CANCELLED);
    }

    @Test
    void cancelTransitionsAnApprovedButNotYetScheduledRun() {
        var entity = existing(QueryStatus.APPROVED);
        entity.setScheduledFor(Instant.parse("2030-01-01T00:00:00Z"));
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        service.cancel(entity.getId(), ORG, SUBMITTER);

        verify(stateService).apply(entity, QueryStatus.CANCELLED);
    }

    @Test
    void onlyTheSubmitterMayCancel() {
        var entity = existing(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.cancel(entity.getId(), ORG, UUID.randomUUID()))
                .isInstanceOf(DeploymentRequestPermissionException.class);
        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void cancelRejectsAnUncancellableState() {
        var pendingAi = existing(QueryStatus.PENDING_AI);
        when(requestRepository.findByIdAndOrganizationId(pendingAi.getId(), ORG))
                .thenReturn(Optional.of(pendingAi));
        var approvedNow = existing(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(approvedNow.getId(), ORG))
                .thenReturn(Optional.of(approvedNow));

        assertThatThrownBy(() -> service.cancel(pendingAi.getId(), ORG, SUBMITTER))
                .isInstanceOf(IllegalDeploymentRequestStateException.class);
        assertThatThrownBy(() -> service.cancel(approvedNow.getId(), ORG, SUBMITTER))
                .isInstanceOf(IllegalDeploymentRequestStateException.class);
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private DeploymentRequestEntity captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(DeploymentRequestEntity.class);
        verify(requestRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private SubmitDeploymentRequestCommand command(String externalRunId) {
        return new SubmitDeploymentRequestCommand(pipeline.getId(), "production", ORG, SUBMITTER,
                false, "2.4.1", "abc123", "ghcr.io/app:2.4.1", "https://ci/run/1", externalRunId,
                Map.of("changelog", "fix things"), "ship it", null, null, "10.0.0.1");
    }

    private DeploymentRequestEntity existing(QueryStatus status) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setPipelineId(pipeline.getId());
        entity.setEnvironmentId(environment.getId());
        entity.setSubmittedBy(SUBMITTER);
        entity.setVersion("2.4.1");
        entity.setMetadata("{\"changelog\":\"fix things\"}");
        entity.setStatus(status);
        return entity;
    }

    private static FreezeWindowEvaluator.ActiveFreeze freeze(FreezeBehavior behavior) {
        return new FreezeWindowEvaluator.ActiveFreeze(UUID.randomUUID(), behavior, "change freeze",
                2, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static DeploymentPipelineEntity pipeline() {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName("payments-api");
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
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

    private static UserView user() {
        return new UserView(SUBMITTER, "ci@example.com", "CI Runner", UserRoleType.ANALYST, ORG,
                true, AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }
}
