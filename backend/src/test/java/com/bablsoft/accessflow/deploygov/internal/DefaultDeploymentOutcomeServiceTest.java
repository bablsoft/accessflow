package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentOutcomeReportedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class DefaultDeploymentOutcomeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRequestRepository requestRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentRollbackReviewRepository rollbackReviewRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private DeploymentRequestStateService stateService;
    private DefaultDeploymentRequestService requestService;
    private DeploygovAuditWriter auditWriter;
    private ApplicationEventPublisher eventPublisher;
    private DefaultDeploymentOutcomeService service;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        rollbackReviewRepository = mock(DeploymentRollbackReviewRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        stateService = mock(DeploymentRequestStateService.class);
        requestService = mock(DefaultDeploymentRequestService.class);
        auditWriter = mock(DeploygovAuditWriter.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DefaultDeploymentOutcomeService(requestRepository, environmentRepository,
                rollbackReviewRepository, permissionResolver, stateService, requestService,
                auditWriter, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstReportRecordsTheOutcomeAndPublishes() {
        var request = executed();
        stub(request);

        service.reportOutcome(request.getId(), DeploymentOutcome.SUCCEEDED, "green", ORG,
                request.getSubmittedBy(), Set.of(), "1.2.3.4");

        assertThat(request.getOutcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(request.getOutcomeDetail()).isEqualTo("green");
        assertThat(request.getOutcomeReportedAt()).isEqualTo(NOW);
        verify(requestRepository).save(request);
        verify(stateService, never()).apply(any(), any());
        var event = ArgumentCaptor.forClass(DeploymentOutcomeReportedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_OUTCOME_REPORTED),
                eq(AuditResourceType.DEPLOYMENT_REQUEST), eq(request.getId()), eq(ORG),
                eq(request.getSubmittedBy()), metadata.capture(), eq("1.2.3.4"));
        assertThat(metadata.getValue()).containsEntry("outcome", "SUCCEEDED")
                .containsEntry("trigger", "pipeline");
    }

    @Test
    void identicalRepeatIsANoOp() {
        var request = executed();
        request.setOutcome(DeploymentOutcome.SUCCEEDED);
        request.setOutcomeReportedAt(NOW.minusSeconds(60));
        stub(request);

        service.reportOutcome(request.getId(), DeploymentOutcome.SUCCEEDED, "again", ORG,
                request.getSubmittedBy(), Set.of(), null);

        verify(requestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any());
        assertThat(request.getOutcomeReportedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(requestService).detailView(request);
    }

    @Test
    void conflictingOutcomeThrows() {
        var request = executed();
        request.setOutcome(DeploymentOutcome.SUCCEEDED);
        stub(request);

        assertThatThrownBy(() -> service.reportOutcome(request.getId(), DeploymentOutcome.FAILED,
                null, ORG, request.getSubmittedBy(), Set.of(), null))
                .isInstanceOf(DeploymentOutcomeConflictException.class);
        verify(requestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void failedOutcomeFlipsTheStatus() {
        var request = executed();
        stub(request);

        service.reportOutcome(request.getId(), DeploymentOutcome.FAILED, "crashed", ORG,
                request.getSubmittedBy(), Set.of(), null);

        assertThat(request.getOutcome()).isEqualTo(DeploymentOutcome.FAILED);
        verify(stateService).apply(request, QueryStatus.FAILED);
    }

    @Test
    void failedRepeatAfterTheFlipIsStillIdempotent() {
        var request = executed();
        request.setStatus(QueryStatus.FAILED);
        request.setOutcome(DeploymentOutcome.FAILED);
        stub(request);

        service.reportOutcome(request.getId(), DeploymentOutcome.FAILED, null, ORG,
                request.getSubmittedBy(), Set.of(), null);

        verify(stateService, never()).apply(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(requestService).detailView(request);
    }

    @Test
    void reportBeforeExecutionIsRejected() {
        var request = executed();
        request.setStatus(QueryStatus.APPROVED);
        stub(request);

        assertThatThrownBy(() -> service.reportOutcome(request.getId(),
                DeploymentOutcome.SUCCEEDED, null, ORG, request.getSubmittedBy(), Set.of(), null))
                .isInstanceOf(IllegalDeploymentRequestStateException.class);
    }

    @Test
    void rolledBackOnAReviewRequiringEnvironmentOpensAFollowUpReview() {
        var request = executed();
        stub(request);
        stubEnvironment(request, true);

        service.reportOutcome(request.getId(), DeploymentOutcome.ROLLED_BACK, "rollback detail",
                ORG, request.getSubmittedBy(), Set.of(), null);

        var review = ArgumentCaptor.forClass(DeploymentRollbackReviewEntity.class);
        verify(rollbackReviewRepository).save(review.capture());
        assertThat(review.getValue().getDeploymentRequestId()).isEqualTo(request.getId());
        assertThat(review.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(review.getValue().getSubmittedBy()).isEqualTo(request.getSubmittedBy());
        assertThat(review.getValue().getOutcomeDetail()).isEqualTo("rollback detail");
        assertThat(review.getValue().getStatus())
                .isEqualTo(DeploymentRollbackReviewStatus.PENDING_REVIEW);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(any(), any(), any(), any(), any(), metadata.capture(), any());
        assertThat(metadata.getValue()).containsEntry("rollback_review_opened", true);
    }

    @Test
    void rolledBackOnAMissingEnvironmentStillOpensAReviewFailClosed() {
        // The environment row being gone must not silently drop a governed rollback review.
        var request = executed();
        stub(request);
        when(environmentRepository.findById(request.getEnvironmentId()))
                .thenReturn(Optional.empty());

        service.reportOutcome(request.getId(), DeploymentOutcome.ROLLED_BACK, null, ORG,
                request.getSubmittedBy(), Set.of(), null);

        verify(rollbackReviewRepository).save(any(DeploymentRollbackReviewEntity.class));
    }

    @Test
    void rolledBackOnANonReviewEnvironmentOpensNothing() {
        var request = executed();
        stub(request);
        stubEnvironment(request, false);

        service.reportOutcome(request.getId(), DeploymentOutcome.ROLLED_BACK, null, ORG,
                request.getSubmittedBy(), Set.of(), null);

        verify(rollbackReviewRepository, never()).save(any());
    }

    @Test
    void nonActorIsRejected() {
        var request = executed();
        stub(request);
        when(permissionResolver.resolve(eq(request.getPipelineId()), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportOutcome(request.getId(),
                DeploymentOutcome.SUCCEEDED, null, ORG, UUID.randomUUID(), Set.of(), null))
                .isInstanceOf(DeploymentRequestPermissionException.class);
    }

    @Test
    void adminMayReportForAnotherSubmitter() {
        var request = executed();
        stub(request);

        service.reportOutcome(request.getId(), DeploymentOutcome.SUCCEEDED, null, ORG,
                UUID.randomUUID(), Set.of(Permission.QUERY_ADMIN), null);

        verify(requestRepository).save(request);
    }

    @Test
    void crossOrgIdReadsAsNotFound() {
        var id = UUID.randomUUID();
        when(requestRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportOutcome(id, DeploymentOutcome.SUCCEEDED, null, ORG,
                UUID.randomUUID(), Set.of(), null))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    private DeploymentRequestEntity executed() {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setPipelineId(UUID.randomUUID());
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setVersion("2.4.1");
        entity.setStatus(QueryStatus.EXECUTED);
        return entity;
    }

    private void stub(DeploymentRequestEntity request) {
        when(requestRepository.findByIdAndOrganizationId(request.getId(), ORG))
                .thenReturn(Optional.of(request));
    }

    private void stubEnvironment(DeploymentRequestEntity request, boolean requireReview) {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(request.getEnvironmentId());
        environment.setPipelineId(request.getPipelineId());
        environment.setName("production");
        environment.setRequireReview(requireReview);
        when(environmentRepository.findById(request.getEnvironmentId()))
                .thenReturn(Optional.of(environment));
    }
}
