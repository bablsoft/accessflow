package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRolePermissionResolverTest {

    @Mock RoleRepository roleRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @InjectMocks DefaultRolePermissionResolver resolver;

    private final UUID roleId = UUID.randomUUID();

    @Test
    void systemRoleRowResolvesFromCodeMap() {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName("AUDITOR");
        entity.setSystem(true);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(entity));

        assertThat(resolver.resolve(roleId, null))
                .isEqualTo(SystemRolePermissions.of(UserRoleType.AUDITOR));
        verifyNoInteractions(rolePermissionRepository);
    }

    @Test
    void customRoleResolvesFromPermissionRows() {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName("Data Steward");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(entity));
        when(rolePermissionRepository.findAllByRole_Id(roleId)).thenReturn(List.of(
                new RolePermissionEntity(entity, Permission.QUERY_REVIEW),
                new RolePermissionEntity(entity, Permission.QUERY_SUBMIT_SELECT)));

        assertThat(resolver.resolve(roleId, UserRoleType.ADMIN))
                .containsExactlyInAnyOrder(Permission.QUERY_REVIEW, Permission.QUERY_SUBMIT_SELECT);
    }

    @Test
    void customRoleWithNoRowsResolvesEmpty() {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName("Empty");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(entity));
        when(rolePermissionRepository.findAllByRole_Id(roleId)).thenReturn(List.of());

        assertThat(resolver.resolve(roleId, UserRoleType.ADMIN)).isEmpty();
    }

    @Test
    void missingRoleRowFallsBackToSystemRole() {
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(roleId, UserRoleType.ANALYST))
                .isEqualTo(SystemRolePermissions.of(UserRoleType.ANALYST));
    }

    @Test
    void nullRoleIdFallsBackToSystemRole() {
        assertThat(resolver.resolve(null, UserRoleType.READONLY))
                .isEqualTo(SystemRolePermissions.of(UserRoleType.READONLY));
        verifyNoInteractions(roleRepository);
    }

    @Test
    void bothNullResolvesEmpty() {
        assertThat(resolver.resolve(null, null)).isEmpty();
    }
}
