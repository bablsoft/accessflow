package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.events.ApiReviewEscalatedEvent;
import com.bablsoft.accessflow.apigov.events.ApiReviewNudgedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The #622 escalation and nudge stamping on API requests.
 *
 * <p>The job tests mock this service, so these are the only tests that execute the guards that make
 * escalation fire exactly once and make a nudge stop when the request leaves review.
 */
@ExtendWith(MockitoExtension.class)
class ApiRequestStateServiceTest {

    private static final Instant AT = Instant.parse("2026-08-17T12:00:00Z");

    @Mock ApiRequestRepository repository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ApiRequestStateService service;

    private final UUID requestId = UUID.randomUUID();
    private ApiRequestEntity request;

    @BeforeEach
    void setUp() {
        request = new ApiRequestEntity();
        request.setId(requestId);
        request.setStatus(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void markEscalatedStampsAndPublishes() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertThat(service.markEscalated(requestId, AT)).isTrue();

        assertThat(request.getEscalatedAt()).isEqualTo(AT);
        verify(repository).save(request);
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ApiReviewEscalatedEvent.class);
    }

    @Test
    void markEscalatedTakesTheRowLockRatherThanAPlainRead() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        service.markEscalated(requestId, AT);

        // Without the lock, a reviewer deciding in the same window loses the @Version race and
        // gets a 500 — the human, not the job.
        verify(repository).findByIdForUpdate(requestId);
        verify(repository, never()).findById(any());
    }

    @Test
    void markEscalatedIsANoOpWhenAlreadyEscalated() {
        request.setEscalatedAt(AT.minusSeconds(3600));
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertThat(service.markEscalated(requestId, AT)).isFalse();

        assertThat(request.getEscalatedAt()).isEqualTo(AT.minusSeconds(3600));
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markEscalatedIsANoOpOnceTheRequestLeavesReview() {
        request.setStatus(QueryStatus.APPROVED);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertThat(service.markEscalated(requestId, AT)).isFalse();

        assertThat(request.getEscalatedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markEscalatedIsANoOpWhenTheRequestIsGone() {
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());

        assertThat(service.markEscalated(requestId, AT)).isFalse();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markNudgedAdvancesTheCursorAndPublishes() {
        request.setLastNudgedAt(AT.minusSeconds(7200));
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertThat(service.markNudged(requestId, AT)).isTrue();

        // Unlike escalation a nudge repeats, so the cursor moves forward each time.
        assertThat(request.getLastNudgedAt()).isEqualTo(AT);
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ApiReviewNudgedEvent.class);
    }

    @Test
    void markNudgedIsANoOpOnceTheRequestLeavesReview() {
        request.setStatus(QueryStatus.REJECTED);
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertThat(service.markNudged(requestId, AT)).isFalse();

        assertThat(request.getLastNudgedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }
}
