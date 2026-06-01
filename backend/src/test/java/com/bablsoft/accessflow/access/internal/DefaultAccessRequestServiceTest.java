package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotCancellableException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestService.SubmitCommand;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.InvalidAccessDurationException;
import com.bablsoft.accessflow.access.events.AccessRequestSubmittedEvent;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessRequestServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantRequestStateService stateService;
    @Mock AccessRequestViewMapper viewMapper;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;

    private DefaultAccessRequestService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var properties = new AccessProperties(Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofDays(30));
        service = new DefaultAccessRequestService(requestRepository, stateService, viewMapper,
                datasourceLookupService, properties, eventPublisher, messageSource);
        lenient().when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
    }

    private SubmitCommand command(String duration) {
        return new SubmitCommand(organizationId, requesterId, datasourceId, true, false, false,
                List.of("public"), null, duration, "need access");
    }

    @Test
    void submitRejectsNonRequestableDatasource() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(UUID.randomUUID(), "other")));
        assertThatThrownBy(() -> service.submit(command("PT4H")))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void submitRejectsUnparseableDuration() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(datasourceId, "db")));
        assertThatThrownBy(() -> service.submit(command("4 hours")))
                .isInstanceOf(InvalidAccessDurationException.class);
    }

    @Test
    void submitRejectsDurationBelowMinimum() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(datasourceId, "db")));
        assertThatThrownBy(() -> service.submit(command("PT1M")))
                .isInstanceOf(InvalidAccessDurationException.class);
    }

    @Test
    void submitRejectsDurationAboveMaximum() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(datasourceId, "db")));
        assertThatThrownBy(() -> service.submit(command("P60D")))
                .isInstanceOf(InvalidAccessDurationException.class);
    }

    @Test
    void submitPersistsPendingAndPublishesEvent() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(datasourceId, "db")));
        when(requestRepository.save(any())).thenAnswer(inv -> {
            var e = (AccessGrantRequestEntity) inv.getArgument(0);
            return e;
        });
        when(viewMapper.toView(any())).thenReturn(view());

        var result = service.submit(command("PT4H"));

        assertThat(result.status()).isEqualTo(AccessGrantStatus.PENDING);
        var captor = org.mockito.ArgumentCaptor.forClass(AccessGrantRequestEntity.class);
        verify(requestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AccessGrantStatus.PENDING);
        assertThat(captor.getValue().getAllowedSchemas()).containsExactly("public");
        verify(eventPublisher).publishEvent(any(AccessRequestSubmittedEvent.class));
    }

    @Test
    void cancelRejectsUnknownRequest() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(requestId, requesterId, organizationId))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void cancelRejectsForeignRequester() {
        var e = pendingEntity();
        e.setRequesterId(UUID.randomUUID());
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.cancel(requestId, requesterId, organizationId))
                .isInstanceOf(AccessRequestNotFoundException.class);
        verify(stateService, never()).cancel(any());
    }

    @Test
    void cancelRejectsNonPending() {
        var e = pendingEntity();
        e.setStatus(AccessGrantStatus.APPROVED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.cancel(requestId, requesterId, organizationId))
                .isInstanceOf(AccessRequestNotCancellableException.class);
    }

    @Test
    void cancelDelegatesToStateService() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingEntity()));
        service.cancel(requestId, requesterId, organizationId);
        verify(stateService).cancel(requestId);
    }

    @Test
    void listRequestableDatasourcesMapsRefs() {
        when(datasourceLookupService.findActiveRefsByOrganization(organizationId))
                .thenReturn(List.of(new DatasourceRef(datasourceId, "analytics")));
        var options = service.listRequestableDatasources(organizationId);
        assertThat(options).singleElement()
                .satisfies(o -> assertThat(o.name()).isEqualTo("analytics"));
    }

    private AccessGrantRequestEntity pendingEntity() {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(AccessGrantStatus.PENDING);
        return e;
    }

    private AccessRequestView view() {
        return new AccessRequestView(requestId, organizationId, requesterId, "u@x.io", datasourceId,
                "db", true, false, false, List.of("public"), null, "PT4H", "need access",
                AccessGrantStatus.PENDING, null, null, null, null);
    }
}
