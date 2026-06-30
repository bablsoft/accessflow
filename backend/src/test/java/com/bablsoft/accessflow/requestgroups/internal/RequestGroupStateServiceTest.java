package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupStatusChangedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestGroupStateServiceTest {

    @Mock
    private RequestGroupRepository repository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private RequestGroupStateService service;

    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setStatus(RequestGroupStatus.PENDING_AI);
    }

    @Test
    void appliesLegalTransitionAndPublishesEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.apply(group, RequestGroupStatus.PENDING_REVIEW);

        assertThat(group.getStatus()).isEqualTo(RequestGroupStatus.PENDING_REVIEW);
        var captor = ArgumentCaptor.forClass(RequestGroupStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().oldStatus()).isEqualTo(RequestGroupStatus.PENDING_AI);
        assertThat(captor.getValue().newStatus()).isEqualTo(RequestGroupStatus.PENDING_REVIEW);
    }

    @Test
    void noOpWhenStatusUnchanged() {
        service.apply(group, RequestGroupStatus.PENDING_AI);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsIllegalTransition() {
        group.setStatus(RequestGroupStatus.EXECUTED);
        assertThatThrownBy(() -> service.apply(group, RequestGroupStatus.APPROVED))
                .isInstanceOf(IllegalRequestGroupStateException.class)
                .extracting("currentStatus").isEqualTo(RequestGroupStatus.EXECUTED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markTimedOutTransitionsPendingReview() {
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);
        when(repository.findById(group.getId())).thenReturn(Optional.of(group));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markTimedOut(group.getId())).isTrue();
        assertThat(group.getStatus()).isEqualTo(RequestGroupStatus.TIMED_OUT);
    }

    @Test
    void markTimedOutIgnoresNonPendingReview() {
        group.setStatus(RequestGroupStatus.APPROVED);
        when(repository.findById(group.getId())).thenReturn(Optional.of(group));
        assertThat(service.markTimedOut(group.getId())).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void isLegalReflectsTransitionTable() {
        assertThat(RequestGroupStateService.isLegal(
                RequestGroupStatus.APPROVED, RequestGroupStatus.EXECUTING)).isTrue();
        assertThat(RequestGroupStateService.isLegal(
                RequestGroupStatus.EXECUTED, RequestGroupStatus.APPROVED)).isFalse();
        assertThat(RequestGroupStateService.isLegal(
                RequestGroupStatus.DRAFT, RequestGroupStatus.DRAFT)).isTrue();
    }
}
