package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorGroupPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.core.api.UserGroupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveApiConnectorPermissionResolverTest {

    @Mock ApiConnectorUserPermissionRepository userPermissionRepository;
    @Mock ApiConnectorGroupPermissionRepository groupPermissionRepository;
    @Mock UserGroupService userGroupService;
    @InjectMocks EffectiveApiConnectorPermissionResolver resolver;

    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void resolveUnionsFlagsAcrossDirectAndGroupGrants() {
        var direct = userPermission();
        direct.setCanRead(true);
        var group = groupPermission();
        group.setCanWrite(true);
        group.setCanBreakGlass(true);
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        var resolved = resolver.resolve(connectorId, userId).orElseThrow();

        assertThat(resolved.canRead()).isTrue();
        assertThat(resolved.canWrite()).isTrue();
        assertThat(resolved.canBreakGlass()).isTrue();
    }

    @Test
    void resolveGroupOnlyGrant() {
        var group = groupPermission();
        group.setCanRead(true);
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        assertThat(resolver.resolve(connectorId, userId).orElseThrow().canRead()).isTrue();
    }

    @Test
    void resolveIgnoresExpiredGroupGrant() {
        var group = groupPermission();
        group.setCanRead(true);
        group.setExpiresAt(Instant.now().minusSeconds(60));
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        assertThat(resolver.resolve(connectorId, userId)).isEmpty();
    }

    @Test
    void resolveUnionsAllowedOperationsAndIntersectsRestrictedFields() {
        var direct = userPermission();
        direct.setCanRead(true);
        direct.setAllowedOperations(new String[] {"listCharges"});
        direct.setRestrictedResponseFields(new String[] {"ssn", "email"});
        var group = groupPermission();
        group.setCanRead(true);
        group.setAllowedOperations(new String[] {"getCharge"});
        group.setRestrictedResponseFields(new String[] {"ssn"});
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        var resolved = resolver.resolve(connectorId, userId).orElseThrow();

        assertThat(resolved.allowedOperations()).containsExactlyInAnyOrder("listCharges", "getCharge");
        assertThat(resolved.restrictedResponseFields()).containsExactly("ssn");
    }

    @Test
    void resolveAllowedOperationsWideOpenWhenOneGrantHasNoAllowList() {
        var direct = userPermission();
        direct.setCanRead(true);
        direct.setAllowedOperations(new String[] {"listCharges"});
        var group = groupPermission();
        group.setCanRead(true);
        group.setAllowedOperations(null); // all operations allowed
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        assertThat(resolver.resolve(connectorId, userId).orElseThrow().allowedOperations()).isEmpty();
    }

    @Test
    void resolveIgnoresExpiredDirectGrant() {
        // AF-567 acceptance criterion: once a JIT-materialised direct row's expires_at passes,
        // the effective permission disappears without any cleanup job having run yet.
        var direct = userPermission();
        direct.setCanRead(true);
        direct.setExpiresAt(Instant.now().minusSeconds(60));
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(resolver.resolve(connectorId, userId)).isEmpty();
    }

    @Test
    void resolveEmptyWhenNoGrant() {
        when(userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId))
                .thenReturn(java.util.Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(resolver.resolve(connectorId, userId)).isEmpty();
    }

    @Test
    void connectorIdsForUnionsDirectAndGroupGrants() {
        var otherConnector = UUID.randomUUID();
        var direct = userPermission();
        var group = groupPermission();
        group.setConnectorId(otherConnector);
        when(userPermissionRepository.findByUserId(userId)).thenReturn(List.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        assertThat(resolver.connectorIdsFor(userId)).containsExactlyInAnyOrder(connectorId, otherConnector);
    }

    private ApiConnectorUserPermissionEntity userPermission() {
        var p = new ApiConnectorUserPermissionEntity();
        p.setConnectorId(connectorId);
        p.setUserId(userId);
        return p;
    }

    private ApiConnectorGroupPermissionEntity groupPermission() {
        var p = new ApiConnectorGroupPermissionEntity();
        p.setConnectorId(connectorId);
        p.setGroupId(groupId);
        return p;
    }
}
