package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateRoleCommand;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleAdminService;
import com.bablsoft.accessflow.core.api.RoleInUseException;
import com.bablsoft.accessflow.core.api.RoleNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.RoleNotFoundException;
import com.bablsoft.accessflow.core.api.RoleView;
import com.bablsoft.accessflow.core.api.SystemRoleImmutableException;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UpdateRoleCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRoleAdminService implements RoleAdminService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> list(UUID organizationId) {
        return roleRepository.findAllInScope(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoleView get(UUID id, UUID organizationId) {
        return toView(loadInScope(id, organizationId));
    }

    @Override
    @Transactional
    public RoleView create(CreateRoleCommand command) {
        var name = normalizedName(command.name());
        if (roleRepository.findByNameInScope(command.organizationId(), name).isPresent()) {
            throw new RoleNameAlreadyExistsException(name);
        }
        var entity = new RoleEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organizationRepository.getReferenceById(command.organizationId()));
        entity.setName(name);
        entity.setDescription(command.description());
        entity.setSystem(false);
        var saved = roleRepository.save(entity);
        replacePermissions(saved, command.permissions());
        return toView(saved);
    }

    @Override
    @Transactional
    public RoleView update(UUID id, UUID organizationId, UpdateRoleCommand command) {
        var entity = loadInScope(id, organizationId);
        if (entity.isSystem()) {
            throw new SystemRoleImmutableException(id);
        }
        if (command.name() != null) {
            var name = normalizedName(command.name());
            if (roleRepository.existsByNameInScopeAndIdNot(organizationId, name, id)) {
                throw new RoleNameAlreadyExistsException(name);
            }
            entity.setName(name);
        }
        if (command.description() != null) {
            entity.setDescription(command.description());
        }
        if (command.permissions() != null) {
            replacePermissions(entity, command.permissions());
        }
        return toView(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = loadInScope(id, organizationId);
        if (entity.isSystem()) {
            throw new SystemRoleImmutableException(id);
        }
        long assigned = userRepository.countByRoleRef_Id(id);
        if (assigned > 0) {
            throw new RoleInUseException(id, assigned);
        }
        rolePermissionRepository.deleteAllByRole_Id(id);
        roleRepository.delete(entity);
    }

    private RoleEntity loadInScope(UUID id, UUID organizationId) {
        return roleRepository.findByIdInScope(id, organizationId)
                .orElseThrow(() -> new RoleNotFoundException(id));
    }

    private void replacePermissions(RoleEntity role, Set<Permission> permissions) {
        rolePermissionRepository.deleteAllByRole_Id(role.getId());
        if (permissions == null || permissions.isEmpty()) {
            return;
        }
        for (var permission : permissions) {
            rolePermissionRepository.save(new RolePermissionEntity(role, permission));
        }
    }

    private static String normalizedName(String name) {
        return name == null ? null : name.trim();
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
                userRepository.countByRoleRef_Id(entity.getId()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
