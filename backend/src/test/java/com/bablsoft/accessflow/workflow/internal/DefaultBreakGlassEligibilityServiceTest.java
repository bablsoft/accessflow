package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBreakGlassEligibilityServiceTest {

    @Mock DatasourceUserPermissionLookupService permissionLookupService;

    DefaultBreakGlassEligibilityService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultBreakGlassEligibilityService(permissionLookupService);
    }

    @Test
    void mapsEligibleGrantsToDatasourceIdAndExpiry() {
        var dsA = UUID.randomUUID();
        var dsB = UUID.randomUUID();
        var expiry = Instant.now().plusSeconds(3600);
        when(permissionLookupService.findBreakGlassEligible(userId)).thenReturn(List.of(
                view(dsA, null),
                view(dsB, expiry)));

        var result = service.findEligible(userId, organizationId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).datasourceId()).isEqualTo(dsA);
        assertThat(result.get(0).expiresAt()).isNull();
        assertThat(result.get(1).datasourceId()).isEqualTo(dsB);
        assertThat(result.get(1).expiresAt()).isEqualTo(expiry);
    }

    @Test
    void returnsEmptyWhenNoGrants() {
        when(permissionLookupService.findBreakGlassEligible(userId)).thenReturn(List.of());

        assertThat(service.findEligible(userId, organizationId)).isEmpty();
    }

    private DatasourceUserPermissionView view(UUID datasourceId, Instant expiresAt) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, true, List.of(), List.of(), List.of(), expiresAt);
    }
}
