package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorPermissionLookupServiceTest {

    @Mock
    private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock
    private ApiConnectorUserPermissionRepository userPermissionRepository;
    @InjectMocks
    private DefaultApiConnectorPermissionLookupService service;

    @Test
    void mapsResolvedPermissionToView() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var expiry = Instant.now();
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.of(
                new ResolvedApiConnectorPermission(connectorId, userId, true, false, true,
                        List.of("listCharges"), List.of(), expiry)));

        var view = service.findFor(connectorId, userId).orElseThrow();

        assertThat(view.canRead()).isTrue();
        assertThat(view.canBreakGlass()).isTrue();
        assertThat(view.allowedOperations()).containsExactly("listCharges");
        assertThat(view.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void emptyWhenNoPermission() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());

        assertThat(service.findFor(connectorId, userId)).isEmpty();
    }

    @Test
    void findDirectForMapsDirectRow() {
        var permissionId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var expiry = Instant.now().plusSeconds(3600);
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setId(permissionId);
        entity.setConnectorId(connectorId);
        entity.setUserId(userId);
        entity.setExpiresAt(expiry);
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(Optional.of(entity));

        var view = service.findDirectFor(connectorId, userId).orElseThrow();

        assertThat(view.id()).isEqualTo(permissionId);
        assertThat(view.connectorId()).isEqualTo(connectorId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void findDirectForMapsStandingRowWithNullExpiry() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connectorId);
        entity.setUserId(userId);
        entity.setExpiresAt(null);
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(Optional.of(entity));

        assertThat(service.findDirectFor(connectorId, userId).orElseThrow().expiresAt()).isNull();
    }

    @Test
    void findDirectForEmptyWhenNoDirectRow() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(Optional.empty());

        assertThat(service.findDirectFor(connectorId, userId)).isEmpty();
    }
}
