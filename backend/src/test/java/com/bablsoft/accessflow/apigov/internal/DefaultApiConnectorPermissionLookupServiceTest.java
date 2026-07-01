package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
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
}
