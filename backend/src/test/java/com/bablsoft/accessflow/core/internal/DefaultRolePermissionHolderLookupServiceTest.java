package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRolePermissionHolderLookupServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks DefaultRolePermissionHolderLookupService service;

    private final UUID organizationId = UUID.randomUUID();

    @Test
    void passesOnlyTheSystemRolesThatActuallyGrantThePermission() {
        var holder = UUID.randomUUID();
        when(userRepository.findUserIdsWithPermission(eq(organizationId),
                eq(Permission.QUERY_REVIEW), any(), any())).thenReturn(List.of(holder));

        var result = service.findUserIdsWithPermission(organizationId, Permission.QUERY_REVIEW);

        assertThat(result).containsExactly(holder);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserRoleType>> roles = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findUserIdsWithPermission(eq(organizationId),
                eq(Permission.QUERY_REVIEW), names.capture(), roles.capture());
        // REVIEWER and ADMIN hold QUERY_REVIEW; READONLY, ANALYST and AUDITOR do not.
        assertThat(roles.getValue())
                .containsExactlyInAnyOrder(UserRoleType.REVIEWER, UserRoleType.ADMIN);
        assertThat(names.getValue()).containsExactlyInAnyOrder("REVIEWER", "ADMIN");
    }

    @Test
    void queryAdminResolvesToAdminOnly() {
        when(userRepository.findUserIdsWithPermission(eq(organizationId),
                eq(Permission.QUERY_ADMIN), any(), any())).thenReturn(List.of());

        service.findUserIdsWithPermission(organizationId, Permission.QUERY_ADMIN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserRoleType>> roles = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findUserIdsWithPermission(eq(organizationId),
                eq(Permission.QUERY_ADMIN), any(), roles.capture());
        assertThat(roles.getValue()).containsExactly(UserRoleType.ADMIN);
    }

    @Test
    void everyCatalogPermissionResolvesToAtLeastOneSystemRole() {
        // ADMIN holds the whole catalog, so the custom-role-only fallback below is unreachable in
        // practice. Asserting it here means a future narrowing of ADMIN fails loudly rather than
        // silently changing which query runs.
        when(userRepository.findUserIdsWithPermission(any(), any(), any(), any()))
                .thenReturn(List.of());

        for (var permission : Permission.values()) {
            service.findUserIdsWithPermission(organizationId, permission);
        }

        verify(userRepository, never()).findUserIdsWithCustomRolePermission(any(), any());
    }
}
