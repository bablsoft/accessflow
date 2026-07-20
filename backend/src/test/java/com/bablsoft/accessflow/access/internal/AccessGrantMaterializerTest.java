package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantAlreadyExistsException;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService.ApiConnectorDirectPermissionView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionView;
import com.bablsoft.accessflow.apigov.api.GrantApiConnectorPermissionCommand;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessGrantMaterializerTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantRequestStateService stateService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock ApiConnectorPermissionLookupService connectorPermissionLookupService;
    @Mock ApiConnectorAdminService apiConnectorAdminService;
    @InjectMocks AccessGrantMaterializer materializer;

    private final UUID requestId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();
    private final UUID newPermissionId = UUID.randomUUID();

    private AccessGrantRequestEntity approved() {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setDatasourceId(datasourceId);
        e.setStatus(AccessGrantStatus.APPROVED);
        e.setRequestedDuration("PT4H");
        e.setCanRead(true);
        e.setAllowedSchemas(new String[]{"public"});
        return e;
    }

    private DatasourcePermissionView granted() {
        return new DatasourcePermissionView(newPermissionId, datasourceId, requesterId, "u@x.io",
                "U", true, false, false, false, null, List.of("public"), null, null,
                Instant.now().plusSeconds(3600), approverId, Instant.now());
    }

    @Test
    void materialiseRejectsUnknownRequest() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> materializer.materialize(requestId, approverId))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    void materialiseGrantsPermissionAndAttaches() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approved()));
        when(permissionLookupService.findDirectFor(requesterId, datasourceId)).thenReturn(Optional.empty());
        when(datasourceAdminService.grantPermission(eq(datasourceId), eq(organizationId),
                eq(approverId), any())).thenReturn(granted());

        materializer.materialize(requestId, approverId);

        var captor = ArgumentCaptor.forClass(CreatePermissionCommand.class);
        verify(datasourceAdminService).grantPermission(eq(datasourceId), eq(organizationId),
                eq(approverId), captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(requesterId);
        assertThat(captor.getValue().canRead()).isTrue();
        assertThat(captor.getValue().expiresAt()).isNotNull();
        verify(stateService).attachGrant(eq(requestId), eq(newPermissionId), any(Instant.class));
    }

    @Test
    void materialiseThrowsWhenStandingPermissionExists() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approved()));
        var standing = new DatasourceUserPermissionView(UUID.randomUUID(), requesterId, datasourceId,
                true, false, false, false, null, null, null, null /* no expiry = standing */);
        when(permissionLookupService.findDirectFor(requesterId, datasourceId))
                .thenReturn(Optional.of(standing));

        assertThatThrownBy(() -> materializer.materialize(requestId, approverId))
                .isInstanceOf(AccessGrantAlreadyExistsException.class);
        verify(datasourceAdminService, never()).grantPermission(any(), any(), any(), any());
    }

    @Test
    void materialiseReplacesExistingTimeBoxedPermission() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approved()));
        var existingPermId = UUID.randomUUID();
        var jit = new DatasourceUserPermissionView(existingPermId, requesterId, datasourceId,
                true, false, false, false, null, null, null, Instant.now().plusSeconds(60));
        when(permissionLookupService.findDirectFor(requesterId, datasourceId)).thenReturn(Optional.of(jit));
        when(datasourceAdminService.grantPermission(any(), any(), any(), any()))
                .thenReturn(granted());

        materializer.materialize(requestId, approverId);

        verify(datasourceAdminService).revokePermission(datasourceId, organizationId, existingPermId);
        verify(datasourceAdminService).grantPermission(eq(datasourceId), eq(organizationId),
                eq(approverId), any());
    }

    // --- AF-567: connector-targeted requests ----------------------------------------------------

    private AccessGrantRequestEntity approvedConnector() {
        var e = new AccessGrantRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(organizationId);
        e.setRequesterId(requesterId);
        e.setConnectorId(connectorId);
        e.setStatus(AccessGrantStatus.APPROVED);
        e.setRequestedDuration("PT4H");
        e.setCanRead(true);
        e.setCanWrite(true);
        e.setAllowedOperations(new String[]{"listCharges"});
        return e;
    }

    private ApiConnectorPermissionView connectorGranted() {
        return new ApiConnectorPermissionView(newPermissionId, connectorId, requesterId, "u@x.io",
                "U", true, true, false, false, Instant.now().plusSeconds(3600), List.of("listCharges"),
                null, Instant.now());
    }

    @Test
    void materialiseConnectorGrantsPermissionAndAttaches() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approvedConnector()));
        when(connectorPermissionLookupService.findDirectFor(connectorId, requesterId))
                .thenReturn(Optional.empty());
        when(apiConnectorAdminService.grantPermission(eq(connectorId), eq(organizationId),
                eq(approverId), any())).thenReturn(connectorGranted());

        materializer.materialize(requestId, approverId);

        var captor = ArgumentCaptor.forClass(GrantApiConnectorPermissionCommand.class);
        verify(apiConnectorAdminService).grantPermission(eq(connectorId), eq(organizationId),
                eq(approverId), captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(requesterId);
        assertThat(captor.getValue().canRead()).isTrue();
        assertThat(captor.getValue().canWrite()).isTrue();
        assertThat(captor.getValue().canBreakGlass()).isFalse();
        assertThat(captor.getValue().expiresAt()).isNotNull();
        assertThat(captor.getValue().allowedOperations()).containsExactly("listCharges");
        assertThat(captor.getValue().restrictedResponseFields()).isNull();
        verify(stateService).attachGrant(eq(requestId), eq(newPermissionId), any(Instant.class));
        verify(datasourceAdminService, never()).grantPermission(any(), any(), any(), any());
    }

    @Test
    void materialiseConnectorThrowsWhenStandingPermissionExists() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approvedConnector()));
        var standing = new ApiConnectorDirectPermissionView(UUID.randomUUID(), connectorId,
                requesterId, null /* no expiry = standing */);
        when(connectorPermissionLookupService.findDirectFor(connectorId, requesterId))
                .thenReturn(Optional.of(standing));

        assertThatThrownBy(() -> materializer.materialize(requestId, approverId))
                .isInstanceOf(AccessGrantAlreadyExistsException.class);
        verify(apiConnectorAdminService, never()).grantPermission(any(), any(), any(), any());
        verify(stateService, never()).attachGrant(any(), any(), any());
    }

    @Test
    void materialiseConnectorReplacesExistingTimeBoxedPermissionViaUpsert() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(approvedConnector()));
        var jit = new ApiConnectorDirectPermissionView(UUID.randomUUID(), connectorId, requesterId,
                Instant.now().plusSeconds(60));
        when(connectorPermissionLookupService.findDirectFor(connectorId, requesterId))
                .thenReturn(Optional.of(jit));
        when(apiConnectorAdminService.grantPermission(eq(connectorId), eq(organizationId),
                eq(approverId), any())).thenReturn(connectorGranted());

        materializer.materialize(requestId, approverId);

        // grantPermission upserts on (connector_id, user_id) — no explicit revoke call.
        verify(apiConnectorAdminService).grantPermission(eq(connectorId), eq(organizationId),
                eq(approverId), any());
        verify(apiConnectorAdminService, never()).revokePermission(any(), any(), any());
        verify(stateService).attachGrant(eq(requestId), eq(newPermissionId), any(Instant.class));
    }
}
