package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionResolver;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRolePermissionResolver implements RolePermissionResolver {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional(readOnly = true)
    public Set<Permission> resolve(UUID roleId, UserRoleType fallbackSystemRole) {
        if (roleId != null) {
            var role = roleRepository.findById(roleId).orElse(null);
            if (role != null) {
                if (role.isSystem()) {
                    return SystemRolePermissions.of(UserRoleType.valueOf(role.getName()));
                }
                var rows = rolePermissionRepository.findAllByRole_Id(roleId);
                return rows.isEmpty()
                        ? Set.of()
                        : EnumSet.copyOf(rows.stream()
                                .map(RolePermissionEntity::getPermission)
                                .toList());
            }
        }
        return fallbackSystemRole != null
                ? SystemRolePermissions.of(fallbackSystemRole)
                : Set.of();
    }
}
