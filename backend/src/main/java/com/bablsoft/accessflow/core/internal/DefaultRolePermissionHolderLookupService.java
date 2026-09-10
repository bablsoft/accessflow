package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRolePermissionHolderLookupService implements RolePermissionHolderLookupService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findUserIdsWithPermission(UUID organizationId, Permission permission) {
        var systemRoles = new ArrayList<UserRoleType>();
        for (var role : UserRoleType.values()) {
            var granted = SystemRolePermissions.of(role);
            if (granted != null && granted.contains(permission)) {
                systemRoles.add(role);
            }
        }
        if (systemRoles.isEmpty()) {
            // Unreachable while ADMIN holds the whole catalog, but not something to rely on: a
            // permission no system role grants can still be on a custom role.
            return userRepository.findUserIdsWithCustomRolePermission(organizationId, permission);
        }
        var names = systemRoles.stream().map(Enum::name).toList();
        return userRepository.findUserIdsWithPermission(organizationId, permission, names,
                systemRoles);
    }
}
