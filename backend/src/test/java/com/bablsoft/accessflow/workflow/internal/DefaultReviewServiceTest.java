package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordApprovalCommand;
import com.bablsoft.accessflow.core.api.RecordDecisionResult;
import com.bablsoft.accessflow.core.api.ReviewDecisionSnapshot;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.workflow.api.QueryNotPendingReviewException;
import com.bablsoft.accessflow.workflow.api.ReviewService.ReviewerContext;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowStatus;
import com.bablsoft.accessflow.workflow.api.ReviewerNotEligibleException;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import com.bablsoft.accessflow.workflow.events.ReviewDecisionMadeEvent;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock com.bablsoft.accessflow.core.api.ReviewerEligibilityService reviewerEligibilityService;
    @Mock RoutingDecisionService routingDecisionService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;
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
    void approveUsesRoutingOverrideForEffectiveMinApprovals() {
        // A single-stage plan needs 1 approval, but a routing policy escalated it to 2: the
        // effective minimum must flow into the approval command and gate stage advancement.
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(routingDecisionService.findEffectiveMinApprovals(queryId)).thenReturn(Optional.of(2));
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(),
                        QueryStatus.PENDING_REVIEW, false));

        service.approve(queryId, reviewerContext(UserRoleType.REVIEWER), "ok");

        var captor = ArgumentCaptor.forClass(RecordApprovalCommand.class);
        verify(queryRequestStateService).recordApprovalAndAdvance(captor.capture());
        assertThat(captor.getValue().minApprovalsRequired()).isEqualTo(2);
        assertThat(captor.getValue().stage()).isEqualTo(1);
        assertThat(captor.getValue().isLastStage()).isTrue();
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
        var ctx = new ReviewerContext(reviewerId, otherOrgId, "REVIEWER",
                SystemRolePermissions.of(UserRoleType.REVIEWER));

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

        assertThat(page.content()).isEmpty();
        verify(queryRequestLookupService, never()).findPendingForReviewer(any(), any(), any(),
                any());
    }

    @Test
    void listPendingFiltersOutQueriesWhereCallerIsSubmitter() {
        var view = view(QueryStatus.PENDING_REVIEW, reviewerId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq("REVIEWER"), any()))
                .thenReturn(new PageResponse<>(List.of(view), 0, 20, 1, 1));

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }

    @Test
    void listPendingIncludesActionableQueries() {
        var view = view(QueryStatus.PENDING_REVIEW, submitterId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq("REVIEWER"), any()))
                .thenReturn(new PageResponse<>(List.of(view), 0, 20, 1, 1));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(null, "REVIEWER", 1)))));
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).queryRequestId()).isEqualTo(queryId);
        assertThat(page.content().get(0).currentStage()).isEqualTo(1);
    }

    @Test
    void listPendingFiltersOutQueriesWhereCallerIsNotApproverAtCurrentStage() {
        var view = view(QueryStatus.PENDING_REVIEW, submitterId);
        when(queryRequestLookupService.findPendingForReviewer(eq(organizationId), eq(reviewerId),
                eq("REVIEWER"), any()))
                .thenReturn(new PageResponse<>(List.of(view), 0, 20, 1, 1));
        // Caller is approver only at stage 2; stage 1 still pending
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(UUID.randomUUID(), null, 1),
                        new ApproverRule(reviewerId, null, 2)))));
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());

        var page = service.listPendingForReviewer(reviewerContext(UserRoleType.REVIEWER),
                PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }

    @Test
    void bulkDecideMapsExceptionsToPerRowStatuses() {
        // Stub MessageSource so the failure-row error messages resolve.
        when(messageSource.getMessage(any(String.class), eq(null), any())).thenReturn("err");

        var notFoundId = UUID.randomUUID();
        var selfApproveId = UUID.randomUUID();
        var wrongStateId = UUID.randomUUID();
        var successId = UUID.randomUUID();

        // NOT_FOUND
        when(queryRequestLookupService.findPendingReview(notFoundId)).thenReturn(Optional.empty());
        // FORBIDDEN — self approval
        when(queryRequestLookupService.findPendingReview(selfApproveId))
                .thenReturn(Optional.of(viewFor(selfApproveId, QueryStatus.PENDING_REVIEW, reviewerId)));
        // INVALID_STATE — query already approved
        when(queryRequestLookupService.findPendingReview(wrongStateId))
                .thenReturn(Optional.of(viewFor(wrongStateId, QueryStatus.APPROVED, submitterId)));
        // SUCCESS — happy path
        when(queryRequestLookupService.findPendingReview(successId))
                .thenReturn(Optional.of(viewFor(successId, QueryStatus.PENDING_REVIEW, submitterId)));
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(successId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.APPROVED, false));

        var outcome = service.bulkDecide(
                List.of(notFoundId, selfApproveId, wrongStateId, successId),
                DecisionType.APPROVED,
                reviewerContext(UserRoleType.REVIEWER),
                "ok");

        assertThat(outcome.rows()).hasSize(4);
        assertThat(outcome.rows().get(0).queryRequestId()).isEqualTo(notFoundId);
        assertThat(outcome.rows().get(0).status()).isEqualTo(RowStatus.NOT_FOUND);
        assertThat(outcome.rows().get(0).errorCode()).isEqualTo("QUERY_REQUEST_NOT_FOUND");
        assertThat(outcome.rows().get(1).status()).isEqualTo(RowStatus.FORBIDDEN);
        assertThat(outcome.rows().get(1).errorCode()).isEqualTo("FORBIDDEN");
        assertThat(outcome.rows().get(2).status()).isEqualTo(RowStatus.INVALID_STATE);
        assertThat(outcome.rows().get(2).errorCode()).isEqualTo("QUERY_NOT_PENDING_REVIEW");
        assertThat(outcome.rows().get(3).status()).isEqualTo(RowStatus.SUCCESS);
        assertThat(outcome.rows().get(3).outcome().decision()).isEqualTo(DecisionType.APPROVED);
        // Exactly one ReviewDecisionMadeEvent per SUCCESS — the failed rows must not publish.
        verify(eventPublisher).publishEvent(any(ReviewDecisionMadeEvent.class));
    }

    @Test
    void bulkDecideForbiddenWhenReviewerNotEligibleAtStage() {
        when(messageSource.getMessage(any(String.class), eq(null), any())).thenReturn("err");
        var id = UUID.randomUUID();
        when(queryRequestLookupService.findPendingReview(id))
                .thenReturn(Optional.of(viewFor(id, QueryStatus.PENDING_REVIEW, submitterId)));
        // Plan has a single approver who is not this reviewer.
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWith(List.of(
                        new ApproverRule(UUID.randomUUID(), null, 1)))));
        when(queryRequestStateService.listDecisions(id)).thenReturn(List.of());

        var outcome = service.bulkDecide(List.of(id), DecisionType.REJECTED,
                reviewerContext(UserRoleType.REVIEWER), "no");

        assertThat(outcome.rows()).hasSize(1);
        assertThat(outcome.rows().get(0).status()).isEqualTo(RowStatus.FORBIDDEN);
        assertThat(outcome.rows().get(0).errorCode()).isEqualTo("REVIEWER_NOT_ELIGIBLE");
        verify(queryRequestStateService, never()).recordRejection(any(), any(), anyInt(), any());
    }

    @Test
    void bulkDecideRequestChangesRoutesThroughRequestChangesMethod() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordChangesRequested(eq(queryId), eq(reviewerId), eq(1),
                any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(),
                        QueryStatus.PENDING_REVIEW, false));

        var outcome = service.bulkDecide(List.of(queryId), DecisionType.REQUESTED_CHANGES,
                reviewerContext(UserRoleType.REVIEWER), "please narrow");

        assertThat(outcome.rows()).hasSize(1);
        assertThat(outcome.rows().get(0).status()).isEqualTo(RowStatus.SUCCESS);
        assertThat(outcome.rows().get(0).outcome().decision())
                .isEqualTo(DecisionType.REQUESTED_CHANGES);
        verify(queryRequestStateService).recordChangesRequested(eq(queryId), eq(reviewerId),
                eq(1), eq("please narrow"));
    }

    @Test
    void bulkDecideEmptyListReturnsEmptyOutcome() {
        var outcome = service.bulkDecide(List.of(), DecisionType.APPROVED,
                reviewerContext(UserRoleType.REVIEWER), null);

        assertThat(outcome.rows()).isEmpty();
        verify(queryRequestLookupService, never()).findPendingReview(any());
    }

    @Test
    void bulkDecideIdempotentReplayDoesNotPublishEvent() {
        givenPendingReview();
        givenSingleStagePlan();
        when(queryRequestStateService.listDecisions(queryId)).thenReturn(List.of());
        when(queryRequestStateService.recordApprovalAndAdvance(any()))
                .thenReturn(new RecordDecisionResult(UUID.randomUUID(), QueryStatus.APPROVED, true));

        var outcome = service.bulkDecide(List.of(queryId), DecisionType.APPROVED,
                reviewerContext(UserRoleType.REVIEWER), "ok");

        assertThat(outcome.rows()).hasSize(1);
        assertThat(outcome.rows().get(0).status()).isEqualTo(RowStatus.SUCCESS);
        assertThat(outcome.rows().get(0).outcome().wasIdempotentReplay()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(QueryApprovedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(ReviewDecisionMadeEvent.class));
    }

    private PendingReviewView viewFor(UUID id, QueryStatus status, UUID submittedBy) {
        return new PendingReviewView(
                id, datasourceId, "ds", organizationId, submittedBy, "submitter@example.com",
                "SELECT 1", QueryType.SELECT, status, "justification",
                UUID.randomUUID(), RiskLevel.LOW, 10, "summary",
                Instant.parse("2025-01-15T10:00:00Z"));
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
                        new ApproverRule(null, "REVIEWER", 1)))));
    }

    private void givenSingleStagePlanWithMinApprovals(int min) {
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, true, min, false, 1,
                        List.of(new ApproverRule(null, "REVIEWER", 1)),
                        List.of())));
    }

    private void givenTwoStagePlan() {
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, true, 1, false, 2,
                        List.of(
                                new ApproverRule(null, "REVIEWER", 1),
                                new ApproverRule(null, "ADMIN", 2)),
                        List.of())));
    }

    private ReviewPlanSnapshot planWith(List<ApproverRule> approvers) {
        var maxStage = approvers.stream().mapToInt(ApproverRule::stage).max().orElse(0);
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, true, true, 1, false,
                maxStage, approvers, List.of());
    }

    private ReviewerContext reviewerContext(UserRoleType role) {
        return new ReviewerContext(reviewerId, organizationId, role.name(),
                SystemRolePermissions.of(role));
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
