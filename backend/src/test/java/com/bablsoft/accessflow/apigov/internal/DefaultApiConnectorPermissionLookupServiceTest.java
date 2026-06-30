package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorPermissionLookupServiceTest {

    @Mock
    private ApiConnectorUserPermissionRepository repository;
    @InjectMocks
    private DefaultApiConnectorPermissionLookupService service;

    @Test
    void mapsPermissionEntityToView() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var expiry = Instant.now();
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setConnectorId(connectorId);
        entity.setUserId(userId);
        entity.setCanRead(true);
        entity.setCanWrite(false);
        entity.setCanBreakGlass(true);
        entity.setAllowedOperations(new String[]{"listCharges"});
        entity.setExpiresAt(expiry);
        when(repository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.of(entity));

        var view = service.findFor(connectorId, userId).orElseThrow();

        assertThat(view.canRead()).isTrue();
        assertThat(view.canBreakGlass()).isTrue();
        assertThat(view.allowedOperations()).containsExactly("listCharges");
        assertThat(view.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void nullAllowedOperationsBecomesEmptyList() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setConnectorId(connectorId);
        entity.setUserId(userId);
        entity.setAllowedOperations(null);
        when(repository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.of(entity));

        assertThat(service.findFor(connectorId, userId).orElseThrow().allowedOperations()).isEmpty();
    }

    @Test
    void emptyWhenNoPermission() {
        var connectorId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(repository.findByConnectorIdAndUserId(connectorId, userId)).thenReturn(Optional.empty());

        assertThat(service.findFor(connectorId, userId)).isEmpty();
    }
}
