package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessGrantExpiryServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantRequestStateService stateService;
    @Mock AuditLogService auditLogService;
    @InjectMocks DefaultAccessGrantExpiryService service;

    private final UUID requestId = UUID.randomUUID();

    @Test
    void findExpiredGrantedIdsDelegatesToRepository() {
        var now = Instant.now();
        when(requestRepository.findIdsByStatusAndExpiresAtBefore(AccessGrantStatus.APPROVED, now))
                .thenReturn(List.of(requestId));
        assertThat(service.findExpiredGrantedIds(now)).containsExactly(requestId);
    }

    @Test
    void expireAndRevokeRecordsAuditWhenExpired() {
        when(stateService.expire(requestId)).thenReturn(true);
        var datasourceId = UUID.randomUUID();
        var entity = new AccessGrantRequestEntity();
        entity.setId(requestId);
        entity.setOrganizationId(UUID.randomUUID());
        entity.setDatasourceId(datasourceId);
        entity.setGrantedPermissionId(UUID.randomUUID());
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity));

        assertThat(service.expireAndRevoke(requestId)).isTrue();

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.ACCESS_GRANT_EXPIRED);
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().metadata())
                .containsEntry("resource_kind", "DATASOURCE")
                .containsEntry("datasource_id", datasourceId.toString())
                .doesNotContainKey("connector_id");
    }

    @Test
    void expireAndRevokeRecordsConnectorMetadataForConnectorRequest() {
        when(stateService.expire(requestId)).thenReturn(true);
        var connectorId = UUID.randomUUID();
        var entity = new AccessGrantRequestEntity();
        entity.setId(requestId);
        entity.setOrganizationId(UUID.randomUUID());
        entity.setConnectorId(connectorId);
        entity.setGrantedPermissionId(UUID.randomUUID());
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(entity));

        assertThat(service.expireAndRevoke(requestId)).isTrue();

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("resource_kind", "API_CONNECTOR")
                .containsEntry("connector_id", connectorId.toString())
                .doesNotContainKey("datasource_id");
    }

    @Test
    void expireAndRevokeSkipsAuditWhenNotExpired() {
        when(stateService.expire(requestId)).thenReturn(false);
        assertThat(service.expireAndRevoke(requestId)).isFalse();
        verify(auditLogService, never()).record(any());
    }

    @Test
    void expireAndRevokeSwallowsAuditFailure() {
        when(stateService.expire(requestId)).thenReturn(true);
        when(requestRepository.findById(requestId)).thenThrow(new RuntimeException("db down"));
        // Audit failure must not propagate.
        assertThat(service.expireAndRevoke(requestId)).isTrue();
    }
}
