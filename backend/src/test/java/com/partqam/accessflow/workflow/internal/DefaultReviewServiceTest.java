package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.partqam.accessflow.core.api.PendingReviewView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RecordApprovalCommand;
import com.partqam.accessflow.core.api.RecordDecisionResult;
import com.partqam.accessflow.core.api.ReviewDecisionSnapshot;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.workflow.api.QueryNotPendingReviewException;
import com.partqam.accessflow.workflow.api.ReviewService.ReviewerContext;
import com.partqam.accessflow.workflow.api.ReviewerNotEligibleException;
import com.partqam.accessflow.workflow.events.QueryApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
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
class DefaultReviewServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks DefaultReviewService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // no shared stubs — keep tests explicit
    }

    @Test
    void approveTransitionsToApprovedWhenLastStageThresholdMet() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.APPROVED, false));

        var outcome = service.approve(queryId, reviewerContext(UserRoleType.REVIEWER), "ok");

        assertThat(outcome.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        var captor = ArgumentCaptor.forClass(RecordApprovalCommand.class);
        verify(queryRequestStateService).recordApprovalAndAdvance(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo(1);
        assertThat(captor.getValue().isLastStage()).isTrue();
        verify(eventPublisher).publishEvent(any(QueryApprovedEvent.class));
    }

    @Test
    void approveStaysPendingWhenStageThresholdNotMet() {
        givenPendingReview();
        givenSingleStagePlanWithMinApprovals(2);
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(),
                        QueryStatus.PENDING_REVIEW, false));

        var outcome = service.approve(queryId, reviewerContext(UserRoleType.REVIEWER), null);

        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(eventPublisher, never()).publishEvent(any(QueryApprovedEvent.class));
    }

    @Test
    void approveAdvancesToStage2AfterStage1Met() {
        givenPendingReview();
        givenTwoStagePlan();
        // stage-1 approver has approved; current stage should now be 2
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of(
                decisionAt(1, DecisionType.APPROVED, UUID.randomUUID())));
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.APPROVED,
                        false));

        var outcome = service.approve(queryId, reviewerContext(UserRoleType.ADMIN), "fine");

        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        var captor = ArgumentCaptor.forClass(RecordApprovalCommand.class);
        verify(queryRequestStateService).recordApprovalAndAdvance(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo(2);
        assertThat(captor.getValue().isLastStage()).isTrue();
    }

    @Test
    void approveBlocksSubmitter() {
        givenPendingReviewSubmittedBy(reviewerId);

        assertThatThrownBy(() -> service.approve(queryId, reviewerContext(UserRoleType.REVIEWER),
                "ok"))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryRequestStateService, never()).recordApprovalAndAdvance(any());
    }

    @Test
    void approveBlocksAnalystEvenIfMatchedByUserId() {
        // The role gate runs before the plan lookup, so an ANALYST who appears as an
        // approver by user id never gets that far.
        givenPendingReview();

        assertThatThrownBy(() -> service.approve(queryId, reviewerContext(UserRoleType.ANALYST),
                "ok"))
                .isInstanceOf(ReviewerNotEligibleException.class);
    }

    @Test
    void approveBlocksReviewerNotMatchedAtCurrentStage() {
        givenPendingReview();
        // approver list maps a DIFFERENT user, no role match
        var someoneElse = UUID.randomUUID();
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(someoneElse, null, 1)))));
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(queryId, reviewerContext(UserRoleType.REVIEWER),
                null))
                .isInstanceOf(ReviewerNotEligibleException.class);
    }

    @Test
    void approveBlocksCrossTenantReviewer() {
        givenPendingReview();
        // reviewer is in a different org
        var ctx = new ReviewerContext(reviewerId, otherOrgId, UserRoleType.REVIEWER);

        assertThatThrownBy(() -> service.approve(queryId, ctx, "ok"))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void approveTranslatesIllegalTransitionToConflict() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenThrow(new IllegalQueryStatusTransitionException(queryId,
                        QueryStatus.APPROVED, QueryStatus.PENDING_REVIEW));

        assertThatThrownBy(() -> service.approve(queryId, reviewerContext(UserRoleType.REVIEWER),
                "ok"))
                .isInstanceOf(QueryNotPendingReviewException.class);
    }

    @Test
    void approveOnNonPendingReviewQueryThrowsConflict() {
        when(queryRequestLookupService.findPendingReview(queryId))
                .thenReturn(Optional.of(view(QueryStatus.APPROVED, submitterId)));

        assertThatThrownBy(() -> service.approve(queryId, reviewerContext(UserRoleType.REVIEWER),
                "ok"))
                .isInstanceOf(QueryNotPendingReviewException.class);

        verify(queryRequestStateService, never()).recordApprovalAndAdvance(any());
    }

    @Test
    void approveIdempotentReplayDoesNotPublishEvent() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.APPROVED, true));

        var outcome = service.approve(queryId, reviewerContext(UserRoleType.REVIEWER), "ok");

        assertThat(outcome.wasIdempotentReplay()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(QueryApprovedEvent.class));
    }

    @Test
    void rejectTransitionsImmediately() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordRejection(eq(queryId), eq(reviewerId), eq(1), any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.REJECTED, false));

        var outcome = service.reject(queryId, reviewerContext(UserRoleType.REVIEWER),
                "needs more thought");

        assertThat(outcome.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        verify(eventPublisher).publishEvent(any(QueryRejectedEvent.class));
    }

    @Test
    void rejectBlocksSubmitter() {
        givenPendingReviewSubmittedBy(reviewerId);

        assertThatThrownBy(() -> service.reject(queryId, reviewerContext(UserRoleType.REVIEWER),
                "no"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requestChangesStaysPendingReview() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordChangesRequested(eq(queryId), eq(reviewerId), eq(1),
                any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(),
                        QueryStatus.PENDING_REVIEW, false));

        var outcome = service.requestChanges(queryId, reviewerContext(UserRoleType.REVIEWER),
                "please add WHERE");

        assertThat(outcome.decision()).isEqualTo(DecisionType.REQUESTED_CHANGES);
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(eventPublisher, never()).publishEvent(any(QueryApprovedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(QueryRejectedEvent.class));
    }

    @Test
    void listPendingReturnsEmptyForNonReviewerRole() {
        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.ANALYST),
                PageRequest.of(0, 20));

        assertThat(page).isEmpty();
        verify(queryRequestLookupService, never()).findPendingForReviewer(any(), any(), any(),
                any());
    }

    @Test
    void listPendingFiltersOutQueriesWhereCallerIsSubmitter() {
        var view = view(QueryStatus.PENDING_REVIEW, reviewerId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq(UserRoleType.REVIEWER), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page).isEmpty();
    }

    @Test
    void listPendingIncludesActionableQueries() {
        var view = view(QueryStatus.PENDING_REVIEW, submitterId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq(UserRoleType.REVIEWER), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(null, UserRoleType.REVIEWER, 1)))));
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).queryRequestId()).isEqualTo(queryId);
        assertThat(page.getContent().get(0).currentStage()).isEqualTo(1);
    }

    @Test
    void listPendingFiltersOutQueriesWhereCallerIsNotApproverAtCurrentStage() {
        var view = view(QueryStatus.PENDING_REVIEW, submitterId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq(UserRoleType.REVIEWER), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));
        // Caller is approver only at stage 2; stage 1 still pending
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(UUID.randomUUID(), null, 1),
                        new ApproverRule(reviewerId, null, 2)))));
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page).isEmpty();
    }

    private void givenPendingReview() {
        givenPendingReviewSubmittedBy(submitterId);
    }

    private void givenPendingReviewSubmittedBy(UUID submitter) {
        when(queryRequestLookupService.findPendingReview(queryId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW, submitter)));
    }

    private void givenSingleStagePlan() {
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(null, UserRoleType.REVIEWER, 1)))));
    }

    private void givenSingleStagePlanWithMinApprovals(int min) {
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, true, min, false, 1,
                        List.of(new ApproverRule(null, UserRoleType.REVIEWER, 1)),
                        List.of())));
    }

    private void givenTwoStagePlan() {
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, true, 1, false, 2,
                        List.of(
                                new ApproverRule(null, UserRoleType.REVIEWER, 1),
                                new ApproverRule(null, UserRoleType.ADMIN, 2)),
                        List.of())));
    }

    private ReviewPlanSnapshot planWith(List<ApproverRule> approvers) {
        var maxStage = approvers.stream().mapToInt(ApproverRule::stage).max().orElse(0);
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, true, true, 1, false,
                maxStage, approvers, List.of());
    }

    private ReviewerContext reviewerContext(UserRoleType role) {
        return new ReviewerContext(reviewerId, organizationId, role);
    }

    private PendingReviewView view(QueryStatus status, UUID submittedBy) {
        return new PendingReviewView(
                queryId, datasourceId, "ds", organizationId, submittedBy, "submitter@example.com",
                "SELECT 1", QueryType.SELECT, status, "justification",
                UUID.randomUUID(), RiskLevel.LOW, 10, "summary", Instant.parse("2025-01-15T10:00:00Z"));
    }

    private ReviewDecisionSnapshot decisionAt(int stage, DecisionType decision, UUID reviewer) {
        return new ReviewDecisionSnapshot(UUID.randomUUID(), queryId, reviewer, decision, "ok",
                stage, Instant.now());
    }
}
