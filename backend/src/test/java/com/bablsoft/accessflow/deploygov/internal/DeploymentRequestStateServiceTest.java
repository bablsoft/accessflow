package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRequestStateServiceTest {

    private DeploymentRequestRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private DeploymentRequestStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentRequestRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DeploymentRequestStateService(repository, eventPublisher);
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
            // Reserved for the gate and outcome reporting (#693).
            "APPROVED,EXECUTED",
            "APPROVED,FAILED",
            "APPROVED,TIMED_OUT",
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
            "EXECUTED,FAILED",
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
    }

    private static DeploymentRequestEntity request(QueryStatus status) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setStatus(status);
        return entity;
    }
}
