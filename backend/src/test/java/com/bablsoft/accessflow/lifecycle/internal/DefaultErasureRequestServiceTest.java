package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestService.SubmitErasureCommand;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestSubmittedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultErasureRequestServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID REQ = UUID.randomUUID();

    @Mock
    private DeletionRequestRepository repository;
    @Mock
    private ErasureRequestViewMapper mapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefaultErasureRequestService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        service = new DefaultErasureRequestService(repository, mapper, eventPublisher, clock);
    }

    @Test
    void submit_persistsAndPublishesEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toView(any())).thenReturn(stubView(ErasureStatus.PENDING_SCOPE_AI));

        service.submit(new SubmitErasureCommand(ORG, DS, LifecycleSubjectType.EMAIL,
                "user@example.com", "GDPR", USER));

        verify(repository).save(any(DeletionRequestEntity.class));
        verify(eventPublisher).publishEvent(any(ErasureRequestSubmittedEvent.class));
    }

    @Test
    void get_throwsWhenMissing() {
        when(repository.findByIdAndOrganizationId(REQ, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(REQ, ORG))
                .isInstanceOf(DeletionRequestNotFoundException.class);
    }

    @Test
    void cancel_transitionsToCancelledWhenPendingReview() {
        var entity = entity(ErasureStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        service.cancel(REQ, USER, ORG);

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.CANCELLED);
    }

    @Test
    void cancel_throwsWhenForeign() {
        var entity = entity(ErasureStatus.PENDING_REVIEW);
        entity.setRequestedBy(UUID.randomUUID());
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.cancel(REQ, USER, ORG))
                .isInstanceOf(DeletionRequestNotFoundException.class);
    }

    @Test
    void cancel_throwsWhenNotCancellable() {
        var entity = entity(ErasureStatus.APPROVED);
        when(repository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.cancel(REQ, USER, ORG))
                .isInstanceOf(DeletionRequestInvalidStateException.class);
        verify(repository, never()).save(any());
    }

    private DeletionRequestEntity entity(ErasureStatus status) {
        var e = new DeletionRequestEntity();
        e.setId(REQ);
        e.setOrganizationId(ORG);
        e.setDatasourceId(DS);
        e.setRequestedBy(USER);
        e.setSubjectType(LifecycleSubjectType.EMAIL);
        e.setSubjectIdentifier("user@example.com");
        e.setStatus(status);
        return e;
    }

    private static ErasureRequestView stubView(ErasureStatus status) {
        return new ErasureRequestView(REQ, ORG, DS, "DS", LifecycleSubjectType.EMAIL,
                "user@example.com", status, "GDPR", USER, "u@e.com", null, null, null, null,
                null, null, Instant.now(), Instant.now());
    }
}
