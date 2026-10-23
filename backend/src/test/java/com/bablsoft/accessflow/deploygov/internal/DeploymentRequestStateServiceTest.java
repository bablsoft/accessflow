package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentReviewEscalatedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentReviewNudgedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRequestStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    private DeploymentRequestRepository repository;
    private DeploygovAuditWriter auditWriter;
    private ApplicationEventPublisher eventPublisher;
    private DeploymentVersionTrackerService versionTracker;
    private DeploymentRequestStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentRequestRepository.class);
        auditWriter = mock(DeploygovAuditWriter.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        versionTracker = mock(DeploymentVersionTrackerService.class);
        service = new DeploymentRequestStateService(repository, auditWriter, eventPublisher,
                versionTracker, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requireReturnsTheEntity() {
        var entity = request(QueryStatus.PENDING_AI);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.require(entity.getId())).isSameAs(entity);
    }

    @Test
    void requireThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(id))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource({
            // #691 reaches these itself.
            "PENDING_AI,PENDING_REVIEW",
            "PENDING_AI,APPROVED",
            "PENDING_AI,REJECTED",
            "PENDING_AI,CANCELLED",
            "PENDING_REVIEW,CANCELLED",
            "APPROVED,CANCELLED",
            // Reserved for the review flow (#692).
            "PENDING_REVIEW,APPROVED",
            "PENDING_REVIEW,REJECTED",
            "PENDING_REVIEW,TIMED_OUT",
            // The gate and outcome reporting (#693).
            "APPROVED,EXECUTED",
            "APPROVED,FAILED",
            "APPROVED,TIMED_OUT",
            // The one post-terminal flip: a FAILED outcome report (#693).
            "EXECUTED,FAILED",
    })
    void allowedTransitionsPersistAndPublish(QueryStatus from, QueryStatus to) {
        var entity = request(from);

        service.apply(entity, to);

        assertThat(entity.getStatus()).isEqualTo(to);
        verify(repository).save(entity);
        var captor = ArgumentCaptor.forClass(DeploymentStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().oldStatus()).isEqualTo(from);
        assertThat(captor.getValue().newStatus()).isEqualTo(to);
        assertThat(captor.getValue().submitterId()).isEqualTo(entity.getSubmittedBy());
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_AI,EXECUTED",
            "PENDING_AI,FAILED",
            "PENDING_AI,TIMED_OUT",
            "PENDING_REVIEW,PENDING_AI",
            "PENDING_REVIEW,EXECUTED",
            "APPROVED,PENDING_REVIEW",
            "APPROVED,REJECTED",
            "REJECTED,APPROVED",
            "TIMED_OUT,APPROVED",
            "EXECUTED,APPROVED",
            "EXECUTED,CANCELLED",
            "FAILED,EXECUTED",
            "CANCELLED,APPROVED",
    })
    void illegalTransitionsThrowAndChangeNothing(QueryStatus from, QueryStatus to) {
        var entity = request(from);

        assertThatThrownBy(() -> service.apply(entity, to))
                .isInstanceOf(IllegalDeploymentRequestStateException.class)
                .extracting(ex -> ((IllegalDeploymentRequestStateException) ex).getCurrentStatus())
                .isEqualTo(from);
        assertThat(entity.getStatus()).isEqualTo(from);
        verify(repository, never()).save(entity);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reapplyingTheSameStatusIsASilentNoOp() {
        var entity = request(QueryStatus.APPROVED);

        service.apply(entity, QueryStatus.APPROVED);

        verify(repository, never()).save(entity);
        verify(eventPublisher, never()).publishEvent(any());
        verify(versionTracker, never()).recordExecution(any());
    }

    @Test
    void applyingExecutedRecordsTheDeployedVersion() {
        var entity = request(QueryStatus.APPROVED);

        service.apply(entity, QueryStatus.EXECUTED);

        verify(versionTracker).recordExecution(entity);
    }

    // #742: the drift math's time axis.
    @Test
    void applyingExecutedStampsExecutedAtFromTheClock() {
        var entity = request(QueryStatus.APPROVED);

        service.apply(entity, QueryStatus.EXECUTED);

        assertThat(entity.getExecutedAt()).isEqualTo(NOW);
    }

    @Test
    void reapplyingExecutedDoesNotRestampExecutedAt() {
        var entity = request(QueryStatus.EXECUTED);
        var original = NOW.minusSeconds(3600);
        entity.setExecutedAt(original);

        service.apply(entity, QueryStatus.EXECUTED);

        assertThat(entity.getExecutedAt()).isEqualTo(original);
    }

    // #984: the approval-latency axis.
    @Test
    void enteringPendingReviewStampsReviewStartedAtFromTheClock() {
        var entity = request(QueryStatus.PENDING_AI);

        service.apply(entity, QueryStatus.PENDING_REVIEW);

        assertThat(entity.getReviewStartedAt()).isEqualTo(NOW);
        assertThat(entity.getExecutedAt()).isNull();
    }

    @Test
    void reapplyingPendingReviewDoesNotRestampReviewStartedAt() {
        var entity = request(QueryStatus.PENDING_REVIEW);
        var original = NOW.minusSeconds(3600);
        entity.setReviewStartedAt(original);

        service.apply(entity, QueryStatus.PENDING_REVIEW);

        assertThat(entity.getReviewStartedAt()).isEqualTo(original);
        verify(repository, never()).save(entity);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_AI,APPROVED",
            "PENDING_AI,REJECTED",
            "PENDING_AI,CANCELLED",
            "PENDING_REVIEW,APPROVED",
            "PENDING_REVIEW,REJECTED",
            "PENDING_REVIEW,TIMED_OUT",
            "PENDING_REVIEW,CANCELLED",
            "APPROVED,EXECUTED",
            "APPROVED,FAILED",
            "APPROVED,TIMED_OUT",
            "APPROVED,CANCELLED",
            "EXECUTED,FAILED",
    })
    void otherTransitionsNeverTouchReviewStartedAt(QueryStatus from, QueryStatus to) {
        var entity = request(from);
        var original = from == QueryStatus.PENDING_AI ? null : NOW.minusSeconds(60);
        entity.setReviewStartedAt(original);

        service.apply(entity, to);

        assertThat(entity.getReviewStartedAt()).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_AI,APPROVED",
            "PENDING_REVIEW,REJECTED",
            "APPROVED,FAILED",
            "APPROVED,TIMED_OUT",
            "EXECUTED,FAILED",
    })
    void nonExecutedTransitionsDoNotStampExecutedAt(QueryStatus from, QueryStatus to) {
        var entity = request(from);

        service.apply(entity, to);

        assertThat(entity.getExecutedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_AI,APPROVED",
            "PENDING_REVIEW,REJECTED",
            "APPROVED,FAILED",
            "APPROVED,TIMED_OUT",
            "EXECUTED,FAILED",
    })
    void nonExecutedTransitionsDoNotTouchTheVersionTracker(QueryStatus from, QueryStatus to) {
        service.apply(request(from), to);

        verify(versionTracker, never()).recordExecution(any());
    }

    @Test
    void reapplyingExecutedDoesNotDoubleRecord() {
        var entity = request(QueryStatus.EXECUTED);

        service.apply(entity, QueryStatus.EXECUTED);

        verify(versionTracker, never()).recordExecution(any());
    }

    @Test
    void markTimedOutRejectsAPendingReviewRequest() {
        var entity = request(QueryStatus.PENDING_REVIEW);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markTimedOut(entity.getId())).isTrue();

        assertThat(entity.getStatus()).isEqualTo(QueryStatus.TIMED_OUT);
        verify(repository).save(entity);
        verify(eventPublisher).publishEvent(new DeploymentDecidedEvent(entity.getId(),
                QueryStatus.TIMED_OUT, "review_timeout"));
        // #695: the timeout is a system decision — null actor, trigger names the mechanism.
        var metadataCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditWriter).record(
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditAction.DEPLOYMENT_TIMED_OUT),
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditResourceType.DEPLOYMENT_REQUEST),
                org.mockito.ArgumentMatchers.eq(entity.getId()),
                org.mockito.ArgumentMatchers.eq(entity.getOrganizationId()),
                org.mockito.ArgumentMatchers.isNull(), metadataCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(metadataCaptor.getValue()).containsEntry("trigger", "timeout");
    }

    @Test
    void markTimedOutIsANoOpOnAnAlreadyDecidedRequest() {
        var entity = request(QueryStatus.APPROVED);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markTimedOut(entity.getId())).isFalse();

        assertThat(entity.getStatus()).isEqualTo(QueryStatus.APPROVED);
        verify(repository, never()).save(entity);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markTimedOutThrowsWhenTheRequestIsMissing() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markTimedOut(id))
                .isInstanceOf(DeploymentRequestNotFoundException.class);
    }

    @Test
    void markEscalatedStampsAuditsAndPublishesOnce() {
        var entity = request(QueryStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markEscalated(entity.getId(), NOW)).isTrue();

        assertThat(entity.getEscalatedAt()).isEqualTo(NOW);
        assertThat(entity.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(repository).save(entity);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_REVIEW_ESCALATED),
                eq(AuditResourceType.DEPLOYMENT_REQUEST), eq(entity.getId()),
                eq(entity.getOrganizationId()), isNull(),
                eq(Map.of("trigger", "escalation", "pipeline_id",
                        entity.getPipelineId().toString())),
                isNull());
        verify(eventPublisher).publishEvent(new DeploymentReviewEscalatedEvent(entity.getId()));

        // A second pass (another replica, the next tick) finds the stamp and does nothing.
        assertThat(service.markEscalated(entity.getId(), NOW.plusSeconds(300))).isFalse();
        assertThat(entity.getEscalatedAt()).isEqualTo(NOW);
        verify(repository).save(entity);
        verify(eventPublisher).publishEvent(any(DeploymentReviewEscalatedEvent.class));
    }

    @ParameterizedTest
    @CsvSource({"APPROVED", "REJECTED", "TIMED_OUT", "CANCELLED", "PENDING_AI"})
    void markEscalatedIsANoOpOnceTheRequestLeftReview(QueryStatus status) {
        var entity = request(status);
        when(repository.findByIdForUpdate(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markEscalated(entity.getId(), NOW)).isFalse();

        assertThat(entity.getEscalatedAt()).isNull();
        verify(repository, never()).save(any());
        verifyNoInteractions(auditWriter, eventPublisher);
    }

    @Test
    void markEscalatedIsANoOpForAMissingRow() {
        var id = UUID.randomUUID();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThat(service.markEscalated(id, NOW)).isFalse();
        assertThat(service.markNudged(id, NOW)).isFalse();
        verifyNoInteractions(auditWriter, eventPublisher);
    }

    @Test
    void markNudgedStampsEveryTimeWhileInReview() {
        var entity = request(QueryStatus.PENDING_REVIEW);
        entity.setEscalatedAt(NOW.minusSeconds(3600));
        when(repository.findByIdForUpdate(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markNudged(entity.getId(), NOW)).isTrue();
        assertThat(entity.getLastNudgedAt()).isEqualTo(NOW);
        assertThat(service.markNudged(entity.getId(), NOW.plusSeconds(3600))).isTrue();
        assertThat(entity.getLastNudgedAt()).isEqualTo(NOW.plusSeconds(3600));

        verify(auditWriter, times(2)).record(
                eq(AuditAction.DEPLOYMENT_REVIEW_NUDGED), eq(AuditResourceType.DEPLOYMENT_REQUEST),
                eq(entity.getId()), eq(entity.getOrganizationId()), isNull(),
                eq(Map.of("trigger", "nudge", "pipeline_id", entity.getPipelineId().toString())),
                isNull());
        verify(eventPublisher, times(2))
                .publishEvent(new DeploymentReviewNudgedEvent(entity.getId()));
    }

    @Test
    void markNudgedIsANoOpAfterADecision() {
        var entity = request(QueryStatus.APPROVED);
        when(repository.findByIdForUpdate(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.markNudged(entity.getId(), NOW)).isFalse();

        assertThat(entity.getLastNudgedAt()).isNull();
        verify(repository, never()).save(any());
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), anyMap(), any());
        verifyNoInteractions(eventPublisher);
    }

    private static DeploymentRequestEntity request(QueryStatus status) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setPipelineId(UUID.randomUUID());
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setVersion("2.4.1");
        entity.setStatus(status);
        return entity;
    }
}
