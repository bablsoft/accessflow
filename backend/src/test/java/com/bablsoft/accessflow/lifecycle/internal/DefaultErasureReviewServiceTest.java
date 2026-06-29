package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService.ReviewerContext;
import com.bablsoft.accessflow.lifecycle.api.ErasureSelfApprovalException;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestApprovedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestRejectedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestDecisionRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultErasureReviewServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID REQ = UUID.randomUUID();

    @Mock
    private DeletionRequestRepository repository;
    @Mock
    private DeletionRequestDecisionRepository decisionRepository;
    @Mock
    private ErasureRequestViewMapper mapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefaultErasureReviewService service;

    @BeforeEach
    void setUp() {
        service = new DefaultErasureReviewService(repository, decisionRepository, mapper,
                eventPublisher);
    }

    private ReviewerContext reviewer() {
        return new ReviewerContext(REVIEWER, ORG);
    }

    private DeletionRequestEntity pending() {
        var e = new DeletionRequestEntity();
        e.setId(REQ);
        e.setOrganizationId(ORG);
        e.setDatasourceId(DS);
        e.setRequestedBy(SUBMITTER);
        e.setSubjectType(LifecycleSubjectType.EMAIL);
        e.setSubjectIdentifier("user@example.com");
        e.setStatus(ErasureStatus.PENDING_REVIEW);
        return e;
    }

    @Test
    void approve_transitionsToApprovedAndRecordsDecision() {
        var entity = pending();
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(decisionRepository.existsByRequestIdAndReviewerId(REQ, REVIEWER)).thenReturn(false);
        when(mapper.toView(entity)).thenReturn(stubView(ErasureStatus.APPROVED));

        service.approve(REQ, reviewer(), "ok");

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.APPROVED);
        verify(decisionRepository).save(any());
        verify(eventPublisher).publishEvent(any(ErasureRequestApprovedEvent.class));
    }

    @Test
    void reject_transitionsToRejected() {
        var entity = pending();
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(decisionRepository.existsByRequestIdAndReviewerId(REQ, REVIEWER)).thenReturn(true);
        when(mapper.toView(entity)).thenReturn(stubView(ErasureStatus.REJECTED));

        service.reject(REQ, reviewer(), "no");

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.REJECTED);
        verify(decisionRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(ErasureRequestRejectedEvent.class));
    }

    @Test
    void approve_blocksSelfApproval() {
        var entity = pending();
        entity.setRequestedBy(REVIEWER);
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.approve(REQ, reviewer(), null))
                .isInstanceOf(ErasureSelfApprovalException.class);
    }

    @Test
    void approve_throwsWhenMissing() {
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(REQ, reviewer(), null))
                .isInstanceOf(DeletionRequestNotFoundException.class);
    }

    @Test
    void approve_throwsWhenNotPendingReview() {
        var entity = pending();
        entity.setStatus(ErasureStatus.APPROVED);
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.approve(REQ, reviewer(), null))
                .isInstanceOf(DeletionRequestInvalidStateException.class);
    }

    private static ErasureRequestView stubView(ErasureStatus status) {
        return new ErasureRequestView(REQ, ORG, DS, "DS", LifecycleSubjectType.EMAIL,
                "user@example.com", status, null, SUBMITTER, "s@e.com", null, null, null, null,
                null, null, Instant.now(), Instant.now());
    }
}
