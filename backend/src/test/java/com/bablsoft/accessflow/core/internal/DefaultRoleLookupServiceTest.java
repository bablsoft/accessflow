package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
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
class DefaultRoleLookupServiceTest {

    @Mock RoleRepository roleRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @InjectMocks DefaultRoleLookupService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();

    @Test
    void findByIdMapsSystemRoleFromCodeMap() {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName("REVIEWER");
        entity.setSystem(true);
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));

        var view = service.findById(roleId, orgId).orElseThrow();

        assertThat(view.system()).isTrue();
        assertThat(view.organizationId()).isNull();
        assertThat(view.permissions())
                .isEqualTo(SystemRolePermissions.of(UserRoleType.REVIEWER));
        verifyNoInteractions(rolePermissionRepository);
    }

    @Test
    void findByIdMapsCustomRoleFromPermissionRows() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setOrganization(org);
        entity.setName("Data Steward");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(rolePermissionRepository.findAllByRole_Id(roleId))
                .thenReturn(List.of(new RolePermissionEntity(entity, Permission.MASKING_POLICY_MANAGE)));

        var view = service.findById(roleId, orgId).orElseThrow();

        assertThat(view.system()).isFalse();
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.permissions()).containsExactly(Permission.MASKING_POLICY_MANAGE);
    }

    @Test
    void findByIdEmptyWhenOutOfScope() {
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.empty());

        assertThat(service.findById(roleId, orgId)).isEmpty();
    }

    @Test
    void findByNameTrimsAndDelegates() {
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setName("ADMIN");
        entity.setSystem(true);
        when(roleRepository.findByNameInScope(orgId, "admin")).thenReturn(Optional.of(entity));

        assertThat(service.findByNameInScope(orgId, "  admin  ")).isPresent();
    }

    @Test
    void findByNameEmptyOnBlankInput() {
        assertThat(service.findByNameInScope(orgId, "   ")).isEmpty();
        assertThat(service.findByNameInScope(orgId, null)).isEmpty();
        verifyNoInteractions(roleRepository);
    }

    @Test
    void customRoleWithNoRowsHasEmptyPermissionSet() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var entity = new RoleEntity();
        entity.setId(roleId);
        entity.setOrganization(org);
        entity.setName("Empty Role");
        when(roleRepository.findByIdInScope(roleId, orgId)).thenReturn(Optional.of(entity));
        when(rolePermissionRepository.findAllByRole_Id(roleId)).thenReturn(List.of());

        assertThat(service.findById(roleId, orgId).orElseThrow().permissions()).isEmpty();
    }
}
