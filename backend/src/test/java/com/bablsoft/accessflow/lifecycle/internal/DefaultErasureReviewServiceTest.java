package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService.ReviewerContext;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewerNotEligibleException;
import com.bablsoft.accessflow.lifecycle.api.ErasureSelfApprovalException;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestApprovedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestRejectedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultErasureReviewServiceTest {

    @Mock DeletionRequestRepository repository;
    @Mock ErasureRequestStateService stateService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock ErasureRequestViewMapper mapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks DefaultErasureReviewService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    private ReviewerContext reviewer(UserRoleType role) {
        return new ReviewerContext(reviewerId, organizationId, role);
    }

    private DeletionRequestEntity pending() {
        var e = new DeletionRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setDatasourceId(datasourceId);
        e.setRequestedBy(requesterId);
        e.setSubjectType(LifecycleSubjectType.EMAIL);
        e.setSubjectIdentifier("user@example.com");
        e.setStatus(ErasureStatus.PENDING_REVIEW);
        return e;
    }

    private ReviewPlanSnapshot reviewerRolePlan() {
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(null, UserRoleType.REVIEWER, 0)), List.of());
    }

    private ReviewPlanSnapshot adminOnlyPlan() {
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(null, UserRoleType.ADMIN, 0)), List.of());
    }

    private void stubEligible() {
        var entity = pending();
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(entity));
        lenient().when(repository.findById(requestId)).thenReturn(Optional.of(entity));
        lenient().when(mapper.toView(any())).thenReturn(view());
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(requestId)).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
    }

    @Test
    void approveBlocksSelfApproval() {
        var e = pending();
        e.setRequestedBy(reviewerId);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(ErasureSelfApprovalException.class);
        verify(stateService, never()).recordApprovalAndAdvance(any());
    }

    @Test
    void approveRejectsUnknownRequest() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(DeletionRequestNotFoundException.class);
    }

    @Test
    void approveRejectsForeignOrganization() {
        var e = pending();
        e.setOrganizationId(UUID.randomUUID());
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(DeletionRequestNotFoundException.class);
    }

    @Test
    void approveRejectsWhenNotPendingReview() {
        var e = pending();
        e.setStatus(ErasureStatus.APPROVED);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(DeletionRequestInvalidStateException.class);
    }

    @Test
    void approveRejectsNonReviewerRole() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pending()));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.ANALYST), null))
                .isInstanceOf(ErasureReviewerNotEligibleException.class);
    }

    @Test
    void approveRejectsReviewerWhenNoPlan() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(ErasureReviewerNotEligibleException.class);
    }

    @Test
    void approveRejectsWhenNotInDatasourceScope() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(requestId)).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(UUID.randomUUID())));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(ErasureReviewerNotEligibleException.class);
    }

    @Test
    void approveFinalStagePublishesApprovedEvent() {
        stubEligible();
        when(stateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordErasureDecisionResult(UUID.randomUUID(),
                        ErasureStatus.APPROVED, false));

        service.approve(requestId, reviewer(UserRoleType.REVIEWER), "ok");

        verify(eventPublisher).publishEvent(any(ErasureRequestApprovedEvent.class));
    }

    @Test
    void approveIdempotentReplaySkipsEvent() {
        stubEligible();
        when(stateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordErasureDecisionResult(UUID.randomUUID(),
                        ErasureStatus.APPROVED, true));

        service.approve(requestId, reviewer(UserRoleType.REVIEWER), "ok");

        verify(eventPublisher, never()).publishEvent(any(ErasureRequestApprovedEvent.class));
    }

    @Test
    void rejectPublishesRejectedEvent() {
        stubEligible();
        when(stateService.recordRejection(any(), any(), anyInt(), any()))
                .thenReturn(new RecordErasureDecisionResult(UUID.randomUUID(),
                        ErasureStatus.REJECTED, false));

        service.reject(requestId, reviewer(UserRoleType.REVIEWER), "no");

        verify(eventPublisher).publishEvent(any(ErasureRequestRejectedEvent.class));
    }

    @Test
    void listPendingReturnsEmptyForNonReviewer() {
        var page = service.listPending(reviewer(UserRoleType.ANALYST), PageRequest.of(0, 20));
        assertThat(page.content()).isEmpty();
        verify(repository, never())
                .findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void listPendingFiltersToActionableRequests() {
        var actionable = pending();
        var own = pending();
        own.setId(UUID.randomUUID());
        own.setRequestedBy(reviewerId); // self → excluded
        when(repository.findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
                organizationId, ErasureStatus.PENDING_REVIEW))
                .thenReturn(List.of(actionable, own));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(any())).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
        when(mapper.toView(actionable)).thenReturn(view());

        var page = service.listPending(reviewer(UserRoleType.REVIEWER), PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
    }

    @Test
    void adminSeesPendingRequestEvenWithNoPlan() {
        when(repository.findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
                organizationId, ErasureStatus.PENDING_REVIEW))
                .thenReturn(List.of(pending()));
        when(mapper.toView(any())).thenReturn(view());

        var page = service.listPending(reviewer(UserRoleType.ADMIN), PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
    }

    @Test
    void adminOverrideApproveFinalisesWithNoPlan() {
        var entity = pending();
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(entity));
        when(repository.findById(requestId)).thenReturn(Optional.of(entity));
        when(mapper.toView(any())).thenReturn(view());
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        var command = ArgumentCaptor.forClass(RecordErasureApprovalCommand.class);
        when(stateService.recordApprovalAndAdvance(command.capture()))
                .thenReturn(new RecordErasureDecisionResult(UUID.randomUUID(),
                        ErasureStatus.APPROVED, false));

        service.approve(requestId, reviewer(UserRoleType.ADMIN), "ok");

        assertThat(command.getValue().minApprovalsRequired()).isEqualTo(1);
        assertThat(command.getValue().isLastStage()).isTrue();
        verify(eventPublisher).publishEvent(any(ErasureRequestApprovedEvent.class));
    }

    @Test
    void adminCannotApproveOwnRequest() {
        var own = pending();
        own.setRequestedBy(reviewerId);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(own));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.ADMIN), null))
                .isInstanceOf(ErasureSelfApprovalException.class);
        verify(stateService, never()).recordApprovalAndAdvance(any());
    }

    @Test
    void reviewerNotNamedInPlanStaysIneligible() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(adminOnlyPlan()));
        when(stateService.listDecisions(requestId)).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(ErasureReviewerNotEligibleException.class);
        verify(stateService, never()).recordApprovalAndAdvance(any());
    }

    private ErasureRequestView view() {
        return new ErasureRequestView(requestId, organizationId, datasourceId, "DS",
                LifecycleSubjectType.EMAIL, "user@example.com", null, List.of(), null, null,
                ErasureStatus.PENDING_REVIEW, "GDPR", requesterId, "u@e.com", null, null, null,
                null, null, null, Instant.now(), Instant.now());
    }
}
