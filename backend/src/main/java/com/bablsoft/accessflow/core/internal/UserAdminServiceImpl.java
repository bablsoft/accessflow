package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.IllegalUserOperationException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.RoleNotFoundException;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UpdateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class UserAdminServiceImpl implements UserAdminService {

    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() {};

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserView> listUsers(UUID organizationId, PageRequest pageRequest) {
        var page = userRepository.findAllByOrganization_Id(
                organizationId, PageAdapter.toSpringPageable(pageRequest));
        return PageAdapter.toPageResponse(page.map(this::toView));
    }

    @Override
    @Transactional
    public UserView createUser(CreateUserCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new EmailAlreadyExistsException(command.email());
        }
        quotaService.checkUserQuota(command.organizationId());
        var organization = organizationRepository.getReferenceById(command.organizationId());

        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setEmail(command.email());
        entity.setDisplayName(command.displayName());
        entity.setPasswordHash(command.passwordHash());
        entity.setAuthProvider(AuthProviderType.LOCAL);
        applyRole(entity, command.organizationId(), command.role(), command.roleId());
        entity.setActive(true);
        entity.setPlatformAdmin(command.platformAdmin());

        return toView(userRepository.save(entity));
    }

    @Override
    @Transactional
    public UserView updateUser(UUID id, UUID organizationId, UUID currentUserId,
                               UpdateUserCommand command) {
        var entity = loadInOrganization(id, organizationId);

        if (id.equals(currentUserId)) {
            if ((command.role() != null || command.roleId() != null)
                    && !newRoleKeepsUserManage(organizationId, command)) {
                throw new IllegalUserOperationException(
                        "Admins cannot change their own role to one without user management");
            }
            if (Boolean.FALSE.equals(command.active())) {
                throw new IllegalUserOperationException(
                        "Admin users cannot deactivate themselves");
            }
        }

        if (command.role() != null || command.roleId() != null) {
            applyRole(entity, organizationId, command.role(), command.roleId());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        if (command.displayName() != null) {
            entity.setDisplayName(command.displayName());
        }
        if (command.attributes() != null) {
            entity.setAttributes(serializeAttributes(command.attributes()));
        }
        return toView(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getUserAttributes(UUID id, UUID organizationId) {
        return parseAttributes(loadInOrganization(id, organizationId).getAttributes());
    }

    @Override
    @Transactional
    public UserView deactivateUser(UUID id, UUID organizationId, UUID currentUserId) {
        if (id.equals(currentUserId)) {
            throw new IllegalUserOperationException(
                    "Admin users cannot deactivate themselves");
        }
        var entity = loadInOrganization(id, organizationId);
        entity.setActive(false);
        return toView(entity);
    }

    @Override
    @Transactional
    public UserView setPlatformAdmin(UUID id, boolean platformAdmin) {
        var entity = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        entity.setPlatformAdmin(platformAdmin);
        return toView(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserView> findByIds(UUID organizationId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllByOrganization_IdAndIdIn(organizationId, ids).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        this::toView,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    /**
     * Resolves the requested role onto the entity (AF-522). {@code roleId} wins over the legacy
     * enum. {@code roleRef} always points at the role row; the legacy {@code role} enum column
     * stays populated for system roles (bootstrap / SSO / fan-out compatibility) and is NULLed for
     * custom roles.
     */
    private void applyRole(UserEntity entity, UUID organizationId, UserRoleType role, UUID roleId) {
        RoleEntity roleRef;
        if (roleId != null) {
            roleRef = roleRepository.findByIdInScope(roleId, organizationId)
                    .orElseThrow(() -> new RoleNotFoundException(roleId));
        } else if (role != null) {
            roleRef = roleRepository.findByNameAndSystemTrue(role.name()).orElse(null);
        } else {
            throw new IllegalUserOperationException("A role or role_id is required");
        }
        entity.setRoleRef(roleRef);
        if (roleRef != null && roleRef.isSystem()) {
            entity.setRole(UserRoleType.valueOf(roleRef.getName()));
        } else if (roleRef != null) {
            entity.setRole(null);
        } else {
            // System-role row missing (pre-V114 data mid-deploy) — fall back to the enum column.
            entity.setRole(role);
        }
    }

    /** Whether the (self-)update leaves the caller with a role that still holds USER_MANAGE. */
    private boolean newRoleKeepsUserManage(UUID organizationId, UpdateUserCommand command) {
        if (command.roleId() != null) {
            var target = roleRepository.findByIdInScope(command.roleId(), organizationId)
                    .orElseThrow(() -> new RoleNotFoundException(command.roleId()));
            if (target.isSystem()) {
                return SystemRolePermissions.of(UserRoleType.valueOf(target.getName()))
                        .contains(Permission.USER_MANAGE);
            }
            return rolePermissionRepository.findAllByRole_Id(target.getId()).stream()
                    .anyMatch(rp -> rp.getPermission() == Permission.USER_MANAGE);
        }
        return SystemRolePermissions.of(command.role()).contains(Permission.USER_MANAGE);
    }

    private UserEntity loadInOrganization(UUID id, UUID organizationId) {
        var entity = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new UserNotFoundException(id);
        }
        return entity;
    }

    private String serializeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(attributes);
    }

    private Map<String, String> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, ATTR_TYPE);
            var out = new LinkedHashMap<String, String>();
            raw.forEach((key, value) -> {
                if (value != null) {
                    out.put(key, String.valueOf(value));
                }
            });
            return out;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private UserView toView(UserEntity entity) {
        return UserViews.toView(entity);
    }
}
