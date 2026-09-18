package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UpdateUserCommand;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.ApiKeyView;
import com.bablsoft.accessflow.serviceaccounts.api.CreateServiceAccountCommand;
import com.bablsoft.accessflow.serviceaccounts.api.IssueServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.McpToolName;
import com.bablsoft.accessflow.serviceaccounts.api.RotateServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountBootstrapManagedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountClearableField;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountIssuedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNameConflictException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyRevokedException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountOwnerInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRotatedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountUnknownMcpToolException;
import com.bablsoft.accessflow.serviceaccounts.api.UpdateServiceAccountCommand;
import com.bablsoft.accessflow.serviceaccounts.internal.config.ServiceAccountsProperties;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The admin half of the service-account lifecycle (#871). The {@code users} row is always written
 * through {@code core.api.UserAdminService} and the discriminator only through
 * {@link ServiceAccountProvisioningService#ensureRegistered} — the one chokepoint
 * {@code PrincipalTypeChokepointTest} allows — so this class never types a user itself. Keys go
 * through {@code security.api.ApiKeyService} with the account's id as the owner: issue and revoke
 * needed nothing new, rotation is {@code issue} + {@code expireAt}.
 */
@Service
@RequiredArgsConstructor
class DefaultServiceAccountAdminService implements ServiceAccountAdminService {

    /** Deliberately narrow — the bootstrap reconciler defaults to ADMIN, this surface must not. */
    static final UserRoleType DEFAULT_ROLE = UserRoleType.READONLY;

    private final ServiceAccountRepository repository;
    private final ServiceAccountProvisioningService provisioningService;
    private final UserAdminService userAdminService;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder passwordEncoder;
    private final ServiceAccountsProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ServiceAccountAdminView> list(UUID organizationId, ServiceAccountSource managedBy,
                                                      PageRequest pageRequest) {
        var pageable = ServiceAccountPageAdapter.toSpringPageable(pageRequest);
        var page = managedBy == null
                ? repository.findAllByOrganizationId(organizationId, pageable)
                : repository.findAllByOrganizationIdAndManagedBy(organizationId, managedBy, pageable);
        var ids = page.getContent().stream().map(ServiceAccountEntity::getUserId).toList();
        var users = userAdminService.findByIds(organizationId, ids);
        var keys = apiKeyService.listByUserIds(ids);
        var now = clock.instant();
        return ServiceAccountPageAdapter.toPageResponse(page.map(entity -> toView(entity,
                users.get(entity.getUserId()), keys.getOrDefault(entity.getUserId(), List.of()), now, false)));
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceAccountAdminView get(UUID organizationId, UUID userId) {
        return detail(load(organizationId, userId));
    }

    @Override
    @Transactional
    public ServiceAccountAdminView create(UUID organizationId, CreateServiceAccountCommand command) {
        requireValidOwner(organizationId, command.ownerUserId());
        requireKnownTools(command.mcpToolAllowList());
        var role = command.role() == null && command.roleId() == null ? DEFAULT_ROLE : command.role();
        var user = userAdminService.createUser(new CreateUserCommand(
                organizationId,
                command.email(),
                command.displayName(),
                // API key is the only credential: an unusable random hash makes password login
                // impossible — exactly what the bootstrap reconciler seeds.
                passwordEncoder.encode(UUID.randomUUID().toString()),
                role,
                command.roleId(),
                false));
        provisioningService.ensureRegistered(organizationId, user.id(), ServiceAccountSource.UI);
        // Load-and-mutate: ensureRegistered just saved the row (it carries @Version), and it never
        // touches the UI-owned fields — those are ours to set.
        var entity = repository.findByUserIdAndOrganizationId(user.id(), organizationId)
                .orElseThrow(() -> new ServiceAccountNotFoundException(user.id()));
        applyInitialUiOwnedFields(entity, command);
        return detail(repository.save(entity));
    }

    @Override
    @Transactional
    public ServiceAccountAdminView update(UUID organizationId, UUID userId, UUID actorUserId,
                                          UpdateServiceAccountCommand command) {
        var entity = load(organizationId, userId);
        requireValidOwner(organizationId, command.ownerUserId());
        requireKnownTools(command.mcpToolAllowList());
        var current = requireUser(organizationId, userId);
        if (entity.getManagedBy() == ServiceAccountSource.BOOTSTRAP) {
            rejectDeclaredFieldChange(current, command);
        }
        if (command.displayName() != null || command.role() != null || command.roleId() != null
                || command.active() != null) {
            userAdminService.updateUser(userId, organizationId, actorUserId, new UpdateUserCommand(
                    command.role(), command.roleId(), command.active(), command.displayName(), null));
        }
        // Null means unchanged; a reset is asked for by name. An omitted allow-list therefore never
        // silently re-opens every tool — the one write on this surface that widens something.
        var clear = command.clear();
        if (clear.contains(ServiceAccountClearableField.DESCRIPTION)) {
            entity.setDescription(null);
        } else if (command.description() != null) {
            entity.setDescription(command.description());
        }
        if (clear.contains(ServiceAccountClearableField.OWNER_USER_ID)) {
            entity.setOwnerUserId(null);
        } else if (command.ownerUserId() != null) {
            entity.setOwnerUserId(command.ownerUserId());
        }
        if (clear.contains(ServiceAccountClearableField.MCP_TOOL_ALLOW_LIST)) {
            entity.setMcpToolAllowList(null);
        } else if (command.mcpToolAllowList() != null) {
            entity.setMcpToolAllowList(command.mcpToolAllowList().toArray(String[]::new));
        }
        if (clear.contains(ServiceAccountClearableField.RATE_LIMIT_PER_MINUTE)) {
            entity.setRateLimitPerMinute(null);
        } else if (command.rateLimitPerMinute() != null) {
            entity.setRateLimitPerMinute(command.rateLimitPerMinute());
        }
        if (clear.contains(ServiceAccountClearableField.RATE_LIMIT_PER_DAY)) {
            entity.setRateLimitPerDay(null);
        } else if (command.rateLimitPerDay() != null) {
            entity.setRateLimitPerDay(command.rateLimitPerDay());
        }
        return detail(repository.save(entity));
    }

    @Override
    @Transactional
    public void deactivate(UUID organizationId, UUID userId, UUID actorUserId) {
        load(organizationId, userId);
        userAdminService.deactivateUser(userId, organizationId, actorUserId);
    }

    @Override
    @Transactional
    public ServiceAccountIssuedKey issueKey(UUID organizationId, UUID userId,
                                            IssueServiceAccountKeyCommand command) {
        load(organizationId, userId);
        try {
            var issued = apiKeyService.issue(userId, organizationId, command.name(), command.expiresAt());
            return new ServiceAccountIssuedKey(toKeyView(issued.view()), issued.rawKey());
        } catch (ApiKeyDuplicateNameException ex) {
            throw new ServiceAccountKeyNameConflictException(command.name());
        }
    }

    @Override
    @Transactional
    public ServiceAccountRotatedKey rotateKey(UUID organizationId, UUID userId, UUID keyId,
                                              RotateServiceAccountKeyCommand command) {
        load(organizationId, userId);
        var old = requireOwnedKey(userId, keyId);
        if (old.bootstrapDeclared()) {
            throw new ServiceAccountKeyBootstrapDeclaredException(keyId);
        }
        if (old.revokedAt() != null) {
            throw new ServiceAccountKeyRevokedException(keyId);
        }
        var grace = command.gracePeriod() == null ? properties.rotationGrace() : command.gracePeriod();
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("Rotation grace period must be positive");
        }
        var replacement = issueKey(organizationId, userId,
                new IssueServiceAccountKeyCommand(command.name(), command.expiresAt()));
        // Expire, never revoke: the old key keeps authenticating until the window elapses, so a
        // running agent is not cut off mid-deploy. An earlier existing expiry is kept.
        var graceUntil = clock.instant().plus(grace);
        var expiresAt = old.expiresAt() != null && old.expiresAt().isBefore(graceUntil)
                ? old.expiresAt() : graceUntil;
        apiKeyService.expireAt(userId, keyId, expiresAt);
        var superseded = new ServiceAccountKeyView(old.id(), old.name(), old.keyPrefix(),
                old.bootstrapDeclared(), old.createdAt(), old.lastUsedAt(), expiresAt, old.revokedAt());
        return new ServiceAccountRotatedKey(replacement.apiKey(), replacement.rawKey(), superseded);
    }

    @Override
    @Transactional
    public void revokeKey(UUID organizationId, UUID userId, UUID keyId) {
        load(organizationId, userId);
        var key = requireOwnedKey(userId, keyId);
        if (key.bootstrapDeclared()) {
            throw new ServiceAccountKeyBootstrapDeclaredException(keyId);
        }
        apiKeyService.revoke(userId, keyId);
    }

    private ServiceAccountEntity load(UUID organizationId, UUID userId) {
        return repository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ServiceAccountNotFoundException(userId));
    }

    private UserView requireUser(UUID organizationId, UUID userId) {
        var user = userAdminService.findByIds(organizationId, List.of(userId)).get(userId);
        if (user == null) {
            throw new ServiceAccountNotFoundException(userId);
        }
        return user;
    }

    private ApiKeyView requireOwnedKey(UUID userId, UUID keyId) {
        // list() is owner-scoped, so a foreign key is indistinguishable from a missing one.
        return apiKeyService.list(userId).stream()
                .filter(key -> key.id().equals(keyId))
                .findFirst()
                .orElseThrow(() -> new ServiceAccountKeyNotFoundException(keyId));
    }

    private void requireValidOwner(UUID organizationId, UUID ownerUserId) {
        if (ownerUserId == null) {
            return;
        }
        var owner = userAdminService.findByIds(organizationId, List.of(ownerUserId)).get(ownerUserId);
        if (owner == null || !owner.active() || owner.principalType() != PrincipalType.HUMAN) {
            throw new ServiceAccountOwnerInvalidException(ownerUserId);
        }
    }

    private static void requireKnownTools(List<String> tools) {
        if (tools == null) {
            return;
        }
        for (var tool : tools) {
            if (McpToolName.fromToolName(tool).isEmpty()) {
                throw new ServiceAccountUnknownMcpToolException(tool);
            }
        }
    }

    /**
     * Bootstrap owns display name and role on a BOOTSTRAP account. Only a *change* is refused: a
     * full-form client resending the current value must not have to special-case these accounts.
     */
    private static void rejectDeclaredFieldChange(UserView current, UpdateServiceAccountCommand command) {
        if (command.displayName() != null && !command.displayName().equals(current.displayName())) {
            throw new ServiceAccountBootstrapManagedException("display_name");
        }
        if (command.roleId() != null && !command.roleId().equals(current.roleId())) {
            throw new ServiceAccountBootstrapManagedException("role_id");
        }
        if (command.roleId() == null && command.role() != null && command.role() != current.role()) {
            throw new ServiceAccountBootstrapManagedException("role");
        }
    }

    private static void applyInitialUiOwnedFields(ServiceAccountEntity entity, CreateServiceAccountCommand command) {
        entity.setDescription(command.description());
        entity.setOwnerUserId(command.ownerUserId());
        // null stays null: it means "every tool", which an empty array does not.
        entity.setMcpToolAllowList(command.mcpToolAllowList() == null
                ? null : command.mcpToolAllowList().toArray(String[]::new));
        entity.setRateLimitPerMinute(command.rateLimitPerMinute());
        entity.setRateLimitPerDay(command.rateLimitPerDay());
    }

    private ServiceAccountAdminView detail(ServiceAccountEntity entity) {
        var user = requireUser(entity.getOrganizationId(), entity.getUserId());
        return toView(entity, user, apiKeyService.list(entity.getUserId()), clock.instant(), true);
    }

    private static ServiceAccountAdminView toView(ServiceAccountEntity entity, UserView user,
                                                  List<ApiKeyView> keys, Instant now, boolean includeKeys) {
        if (user == null) {
            // The detail row cascades from users, so this is a detached-user race at worst.
            throw new ServiceAccountNotFoundException(entity.getUserId());
        }
        var active = (int) keys.stream().filter(key -> isActive(key, now)).count();
        var lastUsed = keys.stream().map(ApiKeyView::lastUsedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new ServiceAccountAdminView(
                entity.getUserId(),
                entity.getOrganizationId(),
                user.email(),
                user.displayName(),
                user.role(),
                user.roleId(),
                user.roleName(),
                user.active(),
                entity.getManagedBy(),
                entity.getDescription(),
                entity.getOwnerUserId(),
                entity.getMcpToolAllowList() == null ? null : List.of(entity.getMcpToolAllowList()),
                entity.getRateLimitPerMinute(),
                entity.getRateLimitPerDay(),
                active,
                lastUsed,
                user.lastLoginAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                includeKeys ? keys.stream().map(DefaultServiceAccountAdminService::toKeyView).toList() : List.of());
    }

    private static boolean isActive(ApiKeyView key, Instant now) {
        return key.revokedAt() == null && (key.expiresAt() == null || key.expiresAt().isAfter(now));
    }

    static ServiceAccountKeyView toKeyView(ApiKeyView key) {
        return new ServiceAccountKeyView(key.id(), key.name(), key.keyPrefix(), key.bootstrapDeclared(),
                key.createdAt(), key.lastUsedAt(), key.expiresAt(), key.revokedAt());
    }
}
