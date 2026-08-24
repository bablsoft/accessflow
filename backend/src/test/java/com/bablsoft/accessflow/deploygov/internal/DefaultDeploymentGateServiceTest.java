package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentNotReleasableException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentReleasableEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentGateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRequestRepository requestRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentReviewDecisionRepository decisionRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private FreezeWindowEvaluator freezeWindowEvaluator;
    private DeploymentRequestStateService stateService;
    private DefaultDeploymentRequestService requestService;
    private DeploygovAuditWriter auditWriter;
    private AiAnalysisLookupService aiAnalysisLookupService;
    private ApplicationEventPublisher eventPublisher;
    private DefaultDeploymentGateService service;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        decisionRepository = mock(DeploymentReviewDecisionRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        freezeWindowEvaluator = mock(FreezeWindowEvaluator.class);
        stateService = mock(DeploymentRequestStateService.class);
        requestService = mock(DefaultDeploymentRequestService.class);
        auditWriter = mock(DeploygovAuditWriter.class);
        aiAnalysisLookupService = mock(AiAnalysisLookupService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DefaultDeploymentGateService(requestRepository, pipelineRepository,
                environmentRepository, decisionRepository, permissionResolver,
                freezeWindowEvaluator, stateService, requestService, auditWriter,
                aiAnalysisLookupService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- the pure function -------------------------------------------------------------------

    @Test
    void releasableOnlyForAnUnfrozenApprovedRequestPastItsSchedule() {
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.APPROVED, false, null, NOW))
                .isTrue();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.APPROVED, false,
                NOW.minusSeconds(1), NOW)).isTrue();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.APPROVED, false, NOW, NOW))
                .isTrue();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.PENDING_REVIEW, false, null,
                NOW)).isFalse();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.REJECTED, false, null, NOW))
                .isFalse();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.EXECUTED, false, null, NOW))
                .isFalse();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.APPROVED, true, null, NOW))
                .isFalse();
        assertThat(DefaultDeploymentGateService.releasable(QueryStatus.APPROVED, false,
                NOW.plusSeconds(1), NOW)).isFalse();
    }

    // --- gate by tuple -----------------------------------------------------------------------

    @Test
    void gateAnswersReleasableForAnApprovedRequest() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        stubNoFreeze(fixture.request());
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(
                fixture.request().getId(), 1, DecisionType.APPROVED)).thenReturn(2L);

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isTrue();
        assertThat(view.status()).isEqualTo(QueryStatus.APPROVED);
        assertThat(view.grantedApprovals()).isEqualTo(2);
        assertThat(view.frozen()).isFalse();
        assertThat(view.freezeReason()).isNull();
    }

    @Test
    void gateAnswersNotReleasableForAPendingReviewRequest() {
        var fixture = tupleFixture(request(QueryStatus.PENDING_REVIEW));
        stubNoFreeze(fixture.request());

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isFalse();
        assertThat(view.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void gateAnswersNotReleasableForARejectedRequest() {
        var fixture = tupleFixture(request(QueryStatus.REJECTED));
        stubNoFreeze(fixture.request());

        assertThat(service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of()).releasable()).isFalse();
    }

    @Test
    void gateAnswersNotReleasableUnderAnActiveHoldFreeze() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        stubFreeze(fixture.request(), FreezeBehavior.HOLD, "change freeze");

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isFalse();
        assertThat(view.frozen()).isTrue();
        assertThat(view.freezeReason()).isEqualTo("change freeze");
    }

    @Test
    void gateAnswersNotReleasableUnderAnActiveRejectFreeze() {
        // Stricter than the issue formula: a REJECT window normally auto-rejects at submission,
        // but a request approved before the window started must not sail through mid-freeze.
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        stubFreeze(fixture.request(), FreezeBehavior.REJECT, "release lockdown");

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isFalse();
        assertThat(view.frozen()).isTrue();
    }

    @Test
    void gateAnswersNotReleasableWhileScheduledForIsInTheFuture() {
        var request = request(QueryStatus.APPROVED);
        request.setScheduledFor(NOW.plusSeconds(3600));
        var fixture = tupleFixture(request);
        stubNoFreeze(request);

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isFalse();
        assertThat(view.scheduledFor()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void gateFailsClosedWhenTheFreezeEvaluatorThrows() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        when(freezeWindowEvaluator.evaluate(ORG, fixture.request().getPipelineId(),
                fixture.request().getEnvironmentId()))
                .thenThrow(new IllegalStateException("boom"));

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isFalse();
        assertThat(view.frozen()).isFalse();
    }

    @Test
    void breakGlassRequestsSkipTheFreezeCheck() {
        var request = request(QueryStatus.APPROVED);
        request.setSubmissionReason(SubmissionReason.EMERGENCY_ACCESS);
        var fixture = tupleFixture(request);

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                request.getSubmittedBy(), Set.of());

        assertThat(view.releasable()).isTrue();
        assertThat(view.frozen()).isFalse();
        verify(freezeWindowEvaluator, never()).evaluate(any(), any(), any());
    }

    @Test
    void gateResolvesTheNewestRequestForTheTuple() {
        var newest = request(QueryStatus.APPROVED);
        var older = request(QueryStatus.REJECTED);
        var fixture = tupleFixture(newest);
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                fixture.pipeline().getId(), fixture.environment().getId(), "2.4.1"))
                .thenReturn(List.of(newest, older));
        stubNoFreeze(newest);

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                newest.getSubmittedBy(), Set.of());

        assertThat(view.requestId()).isEqualTo(newest.getId());
    }

    @Test
    void gateThrowsNotFoundForAnUnknownPipeline() {
        when(pipelineRepository.findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(ORG, "ghost"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.gate("ghost", "production", "2.4.1", ORG,
                UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void gatePrefersTheExactCasePipelineWhenNamesDifferOnlyByCase() {
        // Org-name uniqueness is case-sensitive (V149), so "Payments-API" and "payments-api" may
        // coexist; the exact-case match must win deterministically instead of a 500.
        var exact = pipeline();
        var other = pipeline();
        other.setName("Payments-API");
        var environment = environment(exact.getId());
        var request = request(QueryStatus.APPROVED);
        request.setPipelineId(exact.getId());
        request.setEnvironmentId(environment.getId());
        when(pipelineRepository.findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(ORG,
                "payments-api")).thenReturn(List.of(other, exact));
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(exact.getId(), "production"))
                .thenReturn(Optional.of(environment));
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                exact.getId(), environment.getId(), "2.4.1")).thenReturn(List.of(request));
        stubNoFreeze(request);

        var view = service.gate("payments-api", "production", "2.4.1", ORG,
                request.getSubmittedBy(), Set.of());

        assertThat(view.requestId()).isEqualTo(request.getId());
    }

    @Test
    void gateThrowsNotFoundForAnUnknownEnvironment() {
        var pipeline = pipeline();
        when(pipelineRepository.findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(ORG,
                "payments-api")).thenReturn(List.of(pipeline));
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(), "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.gate("payments-api", "ghost", "2.4.1", ORG,
                UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    @Test
    void gateThrowsNotFoundForAnUnknownTuple() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                fixture.pipeline().getId(), fixture.environment().getId(), "9.9.9"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.gate("payments-api", "production", "9.9.9", ORG,
                UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @Test
    void gateThrowsNotFoundNotForbiddenForAnInvisibleRequest() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        when(permissionResolver.resolve(eq(fixture.request().getPipelineId()), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.gate("payments-api", "production", "2.4.1", ORG,
                UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @Test
    void gateIsVisibleToACanTriggerHolder() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        var caller = UUID.randomUUID();
        when(permissionResolver.resolve(fixture.request().getPipelineId(), caller))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(
                        fixture.request().getPipelineId(), caller, true, false, null)));
        stubNoFreeze(fixture.request());

        assertThat(service.gate("payments-api", "production", "2.4.1", ORG, caller, Set.of())
                .releasable()).isTrue();
    }

    @Test
    void gateIsVisibleToAReviewer() {
        var fixture = tupleFixture(request(QueryStatus.APPROVED));
        stubNoFreeze(fixture.request());

        assertThat(service.gate("payments-api", "production", "2.4.1", ORG, UUID.randomUUID(),
                Set.of(Permission.DEPLOYMENT_REVIEW)).releasable()).isTrue();
    }

    @Test
    void gateSurfacesTheAiRiskLevel() {
        var request = request(QueryStatus.APPROVED);
        var analysisId = UUID.randomUUID();
        request.setAiAnalysisId(analysisId);
        var fixture = tupleFixture(request);
        stubNoFreeze(request);
        var analysis = mock(AiAnalysisSummaryView.class);
        when(analysis.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(aiAnalysisLookupService.findById(analysisId)).thenReturn(Optional.of(analysis));

        assertThat(service.gate("payments-api", "production", "2.4.1", ORG,
                fixture.request().getSubmittedBy(), Set.of()).aiRiskLevel())
                .isEqualTo(RiskLevel.HIGH);
    }

    // --- gate by request id ------------------------------------------------------------------

    @Test
    void gateByRequestIdAnswersForAVisibleRequest() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));
        stubNoFreeze(request);

        assertThat(service.gateByRequestId(request.getId(), ORG, request.getSubmittedBy(),
                Set.of()).releasable()).isTrue();
    }

    @Test
    void gateByRequestIdThrowsNotFoundForACrossOrgId() {
        var id = UUID.randomUUID();
        when(requestRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.gateByRequestId(id, ORG, UUID.randomUUID(), Set.of()))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    // --- confirm-execution -------------------------------------------------------------------

    @Test
    void confirmExecutionMovesAnApprovedReleasableRequestToExecuted() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));
        stubNoFreeze(request);

        service.confirmExecution(request.getId(), ORG, request.getSubmittedBy(), Set.of(), "1.2.3.4");

        verify(stateService).apply(request, QueryStatus.EXECUTED);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_EXECUTED),
                eq(AuditResourceType.DEPLOYMENT_REQUEST), eq(request.getId()), eq(ORG),
                eq(request.getSubmittedBy()), metadata.capture(), eq("1.2.3.4"));
        assertThat(metadata.getValue()).containsEntry("trigger", "pipeline");
        verify(requestService).detailView(request);
    }

    @Test
    void confirmExecutionIsIdempotentOnAnExecutedRequest() {
        var request = request(QueryStatus.EXECUTED);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));

        service.confirmExecution(request.getId(), ORG, request.getSubmittedBy(), Set.of(), null);

        verify(stateService, never()).apply(any(), any());
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(requestService).detailView(request);
    }

    @Test
    void confirmExecutionRejectsANonApprovedRequest() {
        var request = request(QueryStatus.PENDING_REVIEW);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.confirmExecution(request.getId(), ORG,
                request.getSubmittedBy(), Set.of(), null))
                .isInstanceOf(IllegalDeploymentRequestStateException.class);
        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void confirmExecutionRejectsAnApprovedButFrozenRequest() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));
        stubFreeze(request, FreezeBehavior.HOLD, "freeze");

        assertThatThrownBy(() -> service.confirmExecution(request.getId(), ORG,
                request.getSubmittedBy(), Set.of(), null))
                .isInstanceOf(DeploymentNotReleasableException.class);
        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void confirmExecutionRejectsANonActor() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));
        when(permissionResolver.resolve(eq(request.getPipelineId()), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmExecution(request.getId(), ORG, UUID.randomUUID(),
                Set.of(), null))
                .isInstanceOf(DeploymentRequestPermissionException.class);
    }

    @Test
    void confirmExecutionThrowsNotFoundForACrossOrgId() {
        var id = UUID.randomUUID();
        when(requestRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmExecution(id, ORG, UUID.randomUUID(), Set.of(),
                null))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    // --- markReleasable ----------------------------------------------------------------------

    @Test
    void markReleasableStampsAndPublishesOnce() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        stubNoFreeze(request);

        assertThat(service.markReleasable(request.getId())).isTrue();

        assertThat(request.getReleaseNotifiedAt()).isEqualTo(NOW);
        verify(requestRepository).save(request);
        var captor = ArgumentCaptor.forClass(DeploymentReleasableEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deploymentRequestId()).isEqualTo(request.getId());
    }

    @Test
    void markReleasableLosesTheRaceGracefully() {
        var decided = request(QueryStatus.REJECTED);
        when(requestRepository.findById(decided.getId())).thenReturn(Optional.of(decided));

        assertThat(service.markReleasable(decided.getId())).isFalse();
        verify(requestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markReleasableSkipsAnAlreadyAnnouncedRequest() {
        var request = request(QueryStatus.APPROVED);
        request.setReleaseNotifiedAt(NOW.minusSeconds(60));
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));

        assertThat(service.markReleasable(request.getId())).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markReleasableSkipsAFrozenRequest() {
        var request = request(QueryStatus.APPROVED);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        stubFreeze(request, FreezeBehavior.HOLD, "freeze");

        assertThat(service.markReleasable(request.getId())).isFalse();
        assertThat(request.getReleaseNotifiedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- fixtures ----------------------------------------------------------------------------

    private record TupleFixture(DeploymentPipelineEntity pipeline,
                                DeploymentEnvironmentEntity environment,
                                DeploymentRequestEntity request) {
    }

    private TupleFixture tupleFixture(DeploymentRequestEntity request) {
        var pipeline = pipeline();
        var environment = environment(pipeline.getId());
        request.setPipelineId(pipeline.getId());
        request.setEnvironmentId(environment.getId());
        when(pipelineRepository.findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(ORG,
                "payments-api")).thenReturn(List.of(pipeline));
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(),
                "production")).thenReturn(Optional.of(environment));
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                pipeline.getId(), environment.getId(), "2.4.1")).thenReturn(List.of(request));
        return new TupleFixture(pipeline, environment, request);
    }

    private DeploymentPipelineEntity pipeline() {
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(ORG);
        pipeline.setName("payments-api");
        return pipeline;
    }

    private DeploymentEnvironmentEntity environment(UUID pipelineId) {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        environment.setPipelineId(pipelineId);
        environment.setName("production");
        return environment;
    }

    private DeploymentRequestEntity request(QueryStatus status) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setPipelineId(UUID.randomUUID());
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setVersion("2.4.1");
        entity.setStatus(status);
        entity.setRequiredApprovals(2);
        return entity;
    }

    private void stubNoFreeze(DeploymentRequestEntity request) {
        when(freezeWindowEvaluator.evaluate(request.getOrganizationId(), request.getPipelineId(),
                request.getEnvironmentId())).thenReturn(Optional.empty());
    }

    private void stubFreeze(DeploymentRequestEntity request, FreezeBehavior behavior,
                            String reason) {
        when(freezeWindowEvaluator.evaluate(request.getOrganizationId(), request.getPipelineId(),
                request.getEnvironmentId()))
                .thenReturn(Optional.of(new FreezeWindowEvaluator.ActiveFreeze(UUID.randomUUID(),
                        behavior, reason, 2, NOW.minusSeconds(3600))));
    }
}
