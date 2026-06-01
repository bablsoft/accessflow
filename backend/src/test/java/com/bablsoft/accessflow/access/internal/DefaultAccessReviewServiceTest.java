package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestNotPendingException;
import com.bablsoft.accessflow.access.api.AccessReviewService.ReviewerContext;
import com.bablsoft.accessflow.access.api.AccessReviewerNotEligibleException;
import com.bablsoft.accessflow.access.events.AccessRequestApprovedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestRejectedEvent;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessReviewServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantRequestStateService stateService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock AccessGrantMaterializer materializer;
    @Mock UserQueryService userQueryService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;
    @InjectMocks DefaultAccessReviewService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    private ReviewerContext reviewer(UserRoleType role) {
        return new ReviewerContext(reviewerId, organizationId, role);
    }

    private AccessGrantRequestEntity pending() {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(AccessGrantStatus.PENDING);
        e.setRequestedDuration("PT4H");
        return e;
    }

    private ReviewPlanSnapshot reviewerRolePlan() {
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, true, 1, false, 0,
                List.of(new ApproverRule(null, UserRoleType.REVIEWER, 0)), List.of());
    }

    @BeforeEach
    void messages() {
        lenient().when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
    }

    private void stubEligible() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(requestId)).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
    }

    @Test
    void approveBlocksSelfApprovalAtServiceLayer() {
        var e = pending();
        e.setRequesterId(reviewerId); // requester == reviewer
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessDeniedException.class);
        verify(stateService, never()).recordApprovalAndAdvance(any());
    }

    @Test
    void approveRejectsUnknownRequest() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void approveRejectsForeignOrganization() {
        var e = pending();
        e.setOrganizationId(UUID.randomUUID());
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void approveRejectsWhenNotPending() {
        var e = pending();
        e.setStatus(AccessGrantStatus.APPROVED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessRequestNotPendingException.class);
    }

    @Test
    void approveRejectsNonReviewerRole() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending()));
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.ANALYST), null))
                .isInstanceOf(AccessReviewerNotEligibleException.class);
    }

    @Test
    void approveRejectsWhenNoReviewPlan() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessReviewerNotEligibleException.class);
    }

    @Test
    void approveRejectsWhenNotInDatasourceScope() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pending()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(requestId)).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(UUID.randomUUID()))); // reviewer not in set
        assertThatThrownBy(() -> service.approve(requestId, reviewer(UserRoleType.REVIEWER), null))
                .isInstanceOf(AccessReviewerNotEligibleException.class);
    }

    @Test
    void approveFinalStageMaterialisesGrant() {
        stubEligible();
        when(stateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordAccessDecisionResult(UUID.randomUUID(),
                        AccessGrantStatus.APPROVED, false));

        var outcome = service.approve(requestId, reviewer(UserRoleType.REVIEWER), "ok");

        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.APPROVED);
        verify(materializer).materialize(requestId, reviewerId);
        verify(eventPublisher).publishEvent(any(AccessRequestApprovedEvent.class));
    }

    @Test
    void approveIdempotentReplaySkipsMaterialize() {
        stubEligible();
        when(stateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordAccessDecisionResult(UUID.randomUUID(),
                        AccessGrantStatus.APPROVED, true));

        service.approve(requestId, reviewer(UserRoleType.REVIEWER), "ok");

        verify(materializer, never()).materialize(any(), any());
        verify(eventPublisher, never()).publishEvent(any(AccessRequestApprovedEvent.class));
    }

    @Test
    void rejectPublishesRejectedEvent() {
        stubEligible();
        when(stateService.recordRejection(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new RecordAccessDecisionResult(UUID.randomUUID(),
                        AccessGrantStatus.REJECTED, false));

        var outcome = service.reject(requestId, reviewer(UserRoleType.REVIEWER), "no");

        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.REJECTED);
        verify(eventPublisher).publishEvent(any(AccessRequestRejectedEvent.class));
    }

    @Test
    void revokeReturnsRevokedWhenStateFlips() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approved()));
        when(stateService.revoke(requestId, reviewerId)).thenReturn(true);

        var outcome = service.revoke(requestId, reviewer(UserRoleType.ADMIN), "cleanup");

        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.REVOKED);
        assertThat(outcome.wasNoOp()).isFalse();
    }

    @Test
    void revokeIsNoOpWhenStateUnchanged() {
        var e = approved();
        e.setStatus(AccessGrantStatus.EXPIRED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        when(stateService.revoke(requestId, reviewerId)).thenReturn(false);

        var outcome = service.revoke(requestId, reviewer(UserRoleType.ADMIN), null);

        assertThat(outcome.wasNoOp()).isTrue();
        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.EXPIRED);
    }

    @Test
    void revokeRejectsForeignOrganization() {
        var e = approved();
        e.setOrganizationId(UUID.randomUUID());
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.revoke(requestId, reviewer(UserRoleType.ADMIN), null))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void listPendingReturnsEmptyForNonReviewer() {
        var page = service.listPendingForReviewer(reviewer(UserRoleType.ANALYST),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));
        assertThat(page.content()).isEmpty();
        verify(requestRepository, never())
                .findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void listPendingFiltersToActionableRequests() {
        var actionable = pending();
        var ownByReviewer = pending();
        ownByReviewer.setId(UUID.randomUUID());
        ownByReviewer.setRequesterId(reviewerId); // self → excluded
        when(requestRepository.findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
                organizationId, AccessGrantStatus.PENDING))
                .thenReturn(List.of(actionable, ownByReviewer));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(reviewerRolePlan()));
        when(stateService.listDecisions(any())).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
        when(userQueryService.findById(requesterId)).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(datasourceId)).thenReturn(Optional.empty());

        var page = service.listPendingForReviewer(reviewer(UserRoleType.REVIEWER),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(requestId);
    }

    private AccessGrantRequestEntity approved() {
        var e = pending();
        e.setStatus(AccessGrantStatus.APPROVED);
        return e;
    }
}
