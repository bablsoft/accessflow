package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordApprovalCommand;
import com.bablsoft.accessflow.core.api.RecordExecutionCommand;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.events.QueryStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryRequestStateServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock ReviewDecisionRepository reviewDecisionRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks DefaultQueryRequestStateService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    private QueryRequestEntity query;
    private UserEntity reviewer;
    private UserEntity submitter;

    @BeforeEach
    void setUp() {
        query = new QueryRequestEntity();
        query.setId(queryId);
        submitter = new UserEntity();
        submitter.setId(submitterId);
        query.setSubmittedBy(submitter);
        reviewer = new UserEntity();
        reviewer.setId(reviewerId);
    }

    @Test
    void transitionToWritesNextStatusWhenExpectedMatches() {
        query.setStatus(QueryStatus.PENDING_AI);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.transitionTo(queryId, QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW);

        assertThat(query.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(queryRequestRepository).save(query);
    }

    @Test
    void transitionToThrowsWhenStatusDoesNotMatchExpected() {
        query.setStatus(QueryStatus.APPROVED);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        assertThatThrownBy(() -> service.transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW))
                .isInstanceOf(IllegalQueryStatusTransitionException.class);

        verify(queryRequestRepository, never()).save(any());
    }

    @Test
    void transitionToThrowsWhenQueryMissing() {
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void recordApprovalAndAdvancePromotesToApprovedAtLastStage() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of())
                .thenReturn(List.of(approvedAt(1)));
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryId, reviewerId, 1, 1, true, "ok"));

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(result.wasIdempotentReplay()).isFalse();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void recordApprovalAndAdvanceStaysPendingWhenStageNotComplete() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of())
                .thenReturn(List.of(approvedAt(1)));
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // need 2 approvals at stage 1, only got 1 → stay PENDING_REVIEW
        var result = service.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryId, reviewerId, 1, 2, true, "ok"));

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(query.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void recordApprovalAndAdvanceStaysPendingWhenNotLastStage() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of())
                .thenReturn(List.of(approvedAt(1)));
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryId, reviewerId, 1, 1, false, "ok"));

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void recordApprovalIsIdempotentWhenDecisionAlreadyExists() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        var existing = approvedAt(1);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of(existing));

        var result = service.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryId, reviewerId, 1, 1, true, "ok"));

        assertThat(result.wasIdempotentReplay()).isTrue();
        assertThat(result.decisionId()).isEqualTo(existing.getId());
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(reviewDecisionRepository, never()).save(any());
    }

    @Test
    void recordApprovalAndAdvanceThrowsWhenNotPendingReview() {
        query.setStatus(QueryStatus.PENDING_AI);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        assertThatThrownBy(() -> service.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryId, reviewerId, 1, 1, true, "ok")))
                .isInstanceOf(IllegalQueryStatusTransitionException.class);
    }

    @Test
    void recordRejectionTransitionsToRejected() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of());
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordRejection(queryId, reviewerId, 1, "needs work");

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        assertThat(query.getStatus()).isEqualTo(QueryStatus.REJECTED);

        var captor = ArgumentCaptor.forClass(ReviewDecisionEntity.class);
        verify(reviewDecisionRepository).save(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo(DecisionType.REJECTED);
    }

    @Test
    void recordChangesRequestedKeepsPendingReview() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of());
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordChangesRequested(queryId, reviewerId, 1, "fix WHERE");

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(query.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        var captor = ArgumentCaptor.forClass(ReviewDecisionEntity.class);
        verify(reviewDecisionRepository).save(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo(DecisionType.REQUESTED_CHANGES);
    }

    @Test
    void recordExecutionOutcomeWritesAllFieldsAndTransitionsToExecuted() {
        query.setStatus(QueryStatus.APPROVED);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        var startedAt = java.time.Instant.parse("2026-05-07T10:00:00Z");
        var completedAt = java.time.Instant.parse("2026-05-07T10:00:01Z");

        service.recordExecutionOutcome(new RecordExecutionCommand(queryId, QueryStatus.EXECUTED,
                42L, 100, null, startedAt, completedAt));

        assertThat(query.getStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(query.getRowsAffected()).isEqualTo(42L);
        assertThat(query.getExecutionDurationMs()).isEqualTo(100);
        assertThat(query.getErrorMessage()).isNull();
        assertThat(query.getExecutionStartedAt()).isEqualTo(startedAt);
        assertThat(query.getExecutionCompletedAt()).isEqualTo(completedAt);
        verify(queryRequestRepository).save(query);
    }

    @Test
    void recordExecutionOutcomeStoresErrorMessageOnFailure() {
        query.setStatus(QueryStatus.APPROVED);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.recordExecutionOutcome(new RecordExecutionCommand(queryId, QueryStatus.FAILED,
                null, 50, "connection refused",
                java.time.Instant.now(), java.time.Instant.now()));

        assertThat(query.getStatus()).isEqualTo(QueryStatus.FAILED);
        assertThat(query.getRowsAffected()).isNull();
        assertThat(query.getErrorMessage()).isEqualTo("connection refused");
    }

    @Test
    void recordExecutionOutcomeThrowsWhenStatusIsNotApproved() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        assertThatThrownBy(() -> service.recordExecutionOutcome(new RecordExecutionCommand(
                queryId, QueryStatus.EXECUTED, 1L, 10, null,
                java.time.Instant.now(), java.time.Instant.now())))
                .isInstanceOf(IllegalQueryStatusTransitionException.class);
        verify(queryRequestRepository, never()).save(any());
    }

    @Test
    void transitionToPublishesStatusChangedEvent() {
        query.setStatus(QueryStatus.PENDING_AI);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.transitionTo(queryId, QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW);

        var captor = ArgumentCaptor.forClass(QueryStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.queryRequestId()).isEqualTo(queryId);
        assertThat(event.submitterId()).isEqualTo(submitterId);
        assertThat(event.oldStatus()).isEqualTo(QueryStatus.PENDING_AI);
        assertThat(event.newStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void recordExecutionOutcomePublishesStatusChangedEvent() {
        query.setStatus(QueryStatus.APPROVED);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.recordExecutionOutcome(new RecordExecutionCommand(queryId, QueryStatus.EXECUTED,
                7L, 100, null, java.time.Instant.now(), java.time.Instant.now()));

        var captor = ArgumentCaptor.forClass(QueryStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().oldStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(captor.getValue().newStatus()).isEqualTo(QueryStatus.EXECUTED);
    }

    @Test
    void recordRejectionPublishesStatusChangedEvent() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of());
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRejection(queryId, reviewerId, 1, "no");

        var captor = ArgumentCaptor.forClass(QueryStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().newStatus()).isEqualTo(QueryStatus.REJECTED);
    }

    @Test
    void recordChangesRequestedDoesNotPublishStatusChangedEvent() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));
        when(reviewDecisionRepository.findAllByQueryRequest_IdAndStage(queryId, 1))
                .thenReturn(List.of());
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordChangesRequested(queryId, reviewerId, 1, "fix");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markTimedOutTransitionsPendingReviewToTimedOut() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        attachReviewPlan(48);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        boolean transitioned = service.markTimedOut(queryId);

        assertThat(transitioned).isTrue();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.TIMED_OUT);
        verify(queryRequestRepository).save(query);
    }

    @Test
    void markTimedOutPublishesStatusChangedAndQueryTimedOutEvents() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        attachReviewPlan(72);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.markTimedOut(queryId);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        var statusChanged = (QueryStatusChangedEvent) captor.getAllValues().get(0);
        var timedOut = (QueryTimedOutEvent) captor.getAllValues().get(1);
        assertThat(statusChanged.oldStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(statusChanged.newStatus()).isEqualTo(QueryStatus.TIMED_OUT);
        assertThat(timedOut.queryRequestId()).isEqualTo(queryId);
        assertThat(timedOut.approvalTimeoutHours()).isEqualTo(72);
    }

    @Test
    void markTimedOutReturnsFalseWhenAlreadyTimedOut() {
        query.setStatus(QueryStatus.TIMED_OUT);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        boolean transitioned = service.markTimedOut(queryId);

        assertThat(transitioned).isFalse();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.TIMED_OUT);
        verify(queryRequestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markTimedOutReturnsFalseWhenNoLongerPendingReview() {
        query.setStatus(QueryStatus.APPROVED);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        boolean transitioned = service.markTimedOut(queryId);

        assertThat(transitioned).isFalse();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.APPROVED);
        verify(queryRequestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markTimedOutThrowsWhenQueryMissing() {
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markTimedOut(queryId))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void markTimedOutEmitsZeroTimeoutHoursWhenPlanIsNull() {
        query.setStatus(QueryStatus.PENDING_REVIEW);
        var datasource = new DatasourceEntity();
        datasource.setReviewPlan(null);
        query.setDatasource(datasource);
        when(queryRequestRepository.findByIdForUpdate(queryId)).thenReturn(Optional.of(query));

        service.markTimedOut(queryId);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        var timedOut = (QueryTimedOutEvent) captor.getAllValues().get(1);
        assertThat(timedOut.approvalTimeoutHours()).isZero();
    }

    private void attachReviewPlan(int approvalTimeoutHours) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setApprovalTimeoutHours(approvalTimeoutHours);
        var datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setReviewPlan(plan);
        query.setDatasource(datasource);
    }

    @Test
    void listDecisionsMapsEntities() {
        when(reviewDecisionRepository.findAllByQueryRequest_IdOrderByDecidedAtAsc(queryId))
                .thenReturn(List.of(approvedAt(1)));

        var decisions = service.listDecisions(queryId);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).queryRequestId()).isEqualTo(queryId);
        assertThat(decisions.get(0).decision()).isEqualTo(DecisionType.APPROVED);
    }

    private ReviewDecisionEntity approvedAt(int stage) {
        var decision = new ReviewDecisionEntity();
        decision.setId(UUID.randomUUID());
        decision.setQueryRequest(query);
        decision.setReviewer(reviewer);
        decision.setDecision(DecisionType.APPROVED);
        decision.setStage(stage);
        decision.setDecidedAt(java.time.Instant.now());
        return decision;
    }
}
