package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleLookupService;
import com.bablsoft.accessflow.core.api.RoleView;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRoleLookupService implements RoleLookupService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleView> findById(UUID roleId, UUID organizationId) {
        return roleRepository.findByIdInScope(roleId, organizationId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleView> findByNameInScope(UUID organizationId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return roleRepository.findByNameInScope(organizationId, name.trim()).map(this::toView);
    }

    private RoleView toView(RoleEntity entity) {
        Set<Permission> permissions;
        if (entity.isSystem()) {
            permissions = SystemRolePermissions.of(UserRoleType.valueOf(entity.getName()));
        } else {
            var rows = rolePermissionRepository.findAllByRole_Id(entity.getId());
            permissions = rows.isEmpty()
                    ? Set.of()
                    : EnumSet.copyOf(rows.stream().map(RolePermissionEntity::getPermission).toList());
        }
        return new RoleView(
                entity.getId(),
                entity.getOrganization() != null ? entity.getOrganization().getId() : null,
                entity.getName(),
                entity.getDescription(),
                entity.isSystem(),
                permissions,
                0,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
