package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.RecordApprovalCommand;
import com.partqam.accessflow.core.api.RecordExecutionCommand;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @InjectMocks DefaultQueryRequestStateService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    private QueryRequestEntity query;
    private UserEntity reviewer;

    @BeforeEach
    void setUp() {
        query = new QueryRequestEntity();
        query.setId(queryId);
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
