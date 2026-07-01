package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureDecision;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestDecisionEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestDecisionRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultErasureRequestStateServiceTest {

    private static final UUID REQ = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();

    @Mock DeletionRequestRepository requestRepository;
    @Mock DeletionRequestDecisionRepository decisionRepository;
    @InjectMocks DefaultErasureRequestStateService service;

    private DeletionRequestEntity pending() {
        var e = new DeletionRequestEntity();
        e.setId(REQ);
        e.setStatus(ErasureStatus.PENDING_REVIEW);
        return e;
    }

    private DeletionRequestDecisionEntity decision(UUID reviewer, int stage, ErasureDecision d) {
        var row = new DeletionRequestDecisionEntity();
        row.setId(UUID.randomUUID());
        row.setRequestId(REQ);
        row.setReviewerId(reviewer);
        row.setStage(stage);
        row.setDecision(d);
        return row;
    }

    @Test
    void approvalFinalStageTransitionsToApproved() {
        var entity = pending();
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(decisionRepository.findAllByRequestIdAndStage(REQ, 0))
                .thenReturn(List.of()) // no existing decision for this reviewer
                .thenReturn(List.of(decision(REVIEWER, 0, ErasureDecision.APPROVED))); // after insert
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordApprovalAndAdvance(
                new RecordErasureApprovalCommand(REQ, REVIEWER, 0, 1, true, "ok"));

        assertThat(result.resultingStatus()).isEqualTo(ErasureStatus.APPROVED);
        assertThat(result.wasIdempotentReplay()).isFalse();
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.APPROVED);
    }

    @Test
    void approvalNonFinalStageStaysPending() {
        var entity = pending();
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(decisionRepository.findAllByRequestIdAndStage(REQ, 0))
                .thenReturn(List.of())
                .thenReturn(List.of(decision(REVIEWER, 0, ErasureDecision.APPROVED)));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordApprovalAndAdvance(
                new RecordErasureApprovalCommand(REQ, REVIEWER, 0, 1, false, "stage 1"));

        assertThat(result.resultingStatus()).isEqualTo(ErasureStatus.PENDING_REVIEW);
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.PENDING_REVIEW);
    }

    @Test
    void approvalIsIdempotentOnExistingDecision() {
        var entity = pending();
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(decisionRepository.findAllByRequestIdAndStage(REQ, 0))
                .thenReturn(List.of(decision(REVIEWER, 0, ErasureDecision.APPROVED)));

        var result = service.recordApprovalAndAdvance(
                new RecordErasureApprovalCommand(REQ, REVIEWER, 0, 1, true, "again"));

        assertThat(result.wasIdempotentReplay()).isTrue();
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void rejectionTransitionsToRejected() {
        var entity = pending();
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(decisionRepository.findAllByRequestIdAndStage(REQ, 0)).thenReturn(List.of());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordRejection(REQ, REVIEWER, 0, "no");

        assertThat(result.resultingStatus()).isEqualTo(ErasureStatus.REJECTED);
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.REJECTED);
    }

    @Test
    void listDecisionsMapsSnapshots() {
        when(decisionRepository.findAllByRequestIdOrderByCreatedAtAsc(REQ))
                .thenReturn(List.of(decision(REVIEWER, 0, ErasureDecision.APPROVED)));
        var snapshots = service.listDecisions(REQ);
        assertThat(snapshots).singleElement()
                .satisfies(s -> assertThat(s.decision()).isEqualTo(ErasureDecision.APPROVED));
    }

    @Test
    void markTimedOut_rejectsPendingReview() {
        var entity = pending();
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));

        assertThat(service.markTimedOut(REQ)).isTrue();
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.REJECTED);
        assertThat(entity.getFailureReason()).isEqualTo("review timeout");
    }

    @Test
    void markTimedOut_noOpWhenNotPending() {
        var entity = pending();
        entity.setStatus(ErasureStatus.APPROVED);
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));

        assertThat(service.markTimedOut(REQ)).isFalse();
        verify(requestRepository, never()).save(any());
    }
}
