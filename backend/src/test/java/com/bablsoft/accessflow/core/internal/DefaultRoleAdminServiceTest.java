package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateRoleCommand;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleInUseException;
import com.bablsoft.accessflow.core.api.RoleNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.RoleNotFoundException;
import com.bablsoft.accessflow.core.api.SystemRoleImmutableException;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UpdateRoleCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRoleAdminServiceTest {

    @Mock RoleRepository roleRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @InjectMocks DefaultRoleAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();

    private RoleEntity customRole(String name) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setOrganization(org);
        entity.setName(name);
        entity.setSystem(false);
        return entity;
    }

    private RoleEntity systemRole(String name) {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName(name);
        entity.setSystem(true);
        return entity;
    }

    @Test
    void listReturnsScopedRolesWithPermissions() {
        var system = systemRole("ADMIN");
        var custom = customRole("Data Steward");
        when(roleRepository.findAllInScope(orgId)).thenReturn(List.of(system, custom));
        when(rolePermissionRepository.findAllByRole_Id(custom.getId()))
                .thenReturn(List.of(new RolePermissionEntity(custom, Permission.QUERY_REVIEW)));
        when(userRepository.countByRoleRef_Id(any())).thenReturn(3L);

        var result = service.list(orgId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).system()).isTrue();
        assertThat(result.get(0).permissions())
                .isEqualTo(SystemRolePermissions.of(UserRoleType.ADMIN));
        assertThat(result.get(1).name()).isEqualTo("Data Steward");
        assertThat(result.get(1).permissions()).containsExactly(Permission.QUERY_REVIEW);
        assertThat(result.get(1).assignedUserCount()).isEqualTo(3L);
    }

    @Test
    void getThrowsWhenRoleNotInScope() {
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(roleId, orgId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void createPersistsRoleAndPermissionRows() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(roleRepository.findByNameInScope(orgId, "Data Steward")).thenReturn(Optional.empty());
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(roleRepository.save(any(RoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rolePermissionRepository.findAllByRole_Id(any())).thenReturn(List.of());
        when(userRepository.countByRoleRef_Id(any())).thenReturn(0L);

        var view = service.create(new CreateRoleCommand(orgId, " Data Steward ", "desc",
                Set.of(Permission.QUERY_REVIEW, Permission.QUERY_SUBMIT_SELECT)));

        assertThat(view.name()).isEqualTo("Data Steward");
        assertThat(view.system()).isFalse();
        verify(rolePermissionRepository, times(2)).save(any(RolePermissionEntity.class));
    }

    @Test
    void createRejectsDuplicateNameCaseInsensitively() {
        when(roleRepository.findByNameInScope(orgId, "admin"))
                .thenReturn(Optional.of(systemRole("ADMIN")));

        assertThatThrownBy(() -> service.create(
                new CreateRoleCommand(orgId, "admin", null, Set.of())))
                .isInstanceOf(RoleNameAlreadyExistsException.class);
        verify(roleRepository, never()).save(any());
    }

    @Test
    void updateRewritesPermissionsAndName() {
        var entity = customRole("Old Name");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(roleRepository.existsByNameInScopeAndIdNot(orgId, "New Name", roleId))
                .thenReturn(false);
        when(rolePermissionRepository.findAllByRole_Id(roleId))
                .thenReturn(List.of(new RolePermissionEntity(entity, Permission.AUDIT_LOG_VIEW)));
        when(userRepository.countByRoleRef_Id(roleId)).thenReturn(0L);

        var view = service.update(roleId, orgId, new UpdateRoleCommand("New Name", "d",
                Set.of(Permission.AUDIT_LOG_VIEW)));

        assertThat(view.name()).isEqualTo("New Name");
        verify(rolePermissionRepository).deleteAllByRole_Id(roleId);
        verify(rolePermissionRepository).save(any(RolePermissionEntity.class));
    }

    @Test
    void updateRejectsSystemRole() {
        when(roleRepository.findByIdInScope(roleId, orgId))
                .thenReturn(Optional.of(systemRole("ADMIN")));

        assertThatThrownBy(() -> service.update(roleId, orgId,
                new UpdateRoleCommand("x", null, null)))
                .isInstanceOf(SystemRoleImmutableException.class);
    }

    @Test
    void updateRejectsNameCollision() {
        var entity = customRole("Old");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(roleRepository.existsByNameInScopeAndIdNot(orgId, "Taken", roleId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(roleId, orgId,
                new UpdateRoleCommand("Taken", null, null)))
                .isInstanceOf(RoleNameAlreadyExistsException.class);
    }

    @Test
    void deleteRemovesUnassignedCustomRole() {
        var entity = customRole("Data Steward");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(userRepository.countByRoleRef_Id(roleId)).thenReturn(0L);

        service.delete(roleId, orgId);

        verify(rolePermissionRepository).deleteAllByRole_Id(roleId);
        verify(roleRepository).delete(entity);
    }

    @Test
    void deleteRejectsSystemRole() {
        when(roleRepository.findByIdInScope(roleId, orgId))
                .thenReturn(Optional.of(systemRole("REVIEWER")));

        assertThatThrownBy(() -> service.delete(roleId, orgId))
                .isInstanceOf(SystemRoleImmutableException.class);
        verify(roleRepository, never()).delete(any(RoleEntity.class));
    }

    @Test
    void deleteRejectsRoleStillAssignedToUsers() {
        var entity = customRole("Data Steward");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(userRepository.countByRoleRef_Id(roleId)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(roleId, orgId))
                .isInstanceOf(RoleInUseException.class);
        verify(roleRepository, never()).delete(any(RoleEntity.class));
    }

    @Test
    void deleteThrowsWhenNotFoundInScope() {
        when(roleRepository.findByIdInScope(eq(roleId), eq(orgId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(roleId, orgId))
                .isInstanceOf(RoleNotFoundException.class);
    }
}
