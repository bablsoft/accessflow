package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateExternalUserCommand;
import com.bablsoft.accessflow.core.api.DirectoryPage;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalIdAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalUserDirectoryService;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.SessionRevocationService;
import com.bablsoft.accessflow.core.api.UpdateExternalUserCommand;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultExternalUserDirectoryService implements ExternalUserDirectoryService {

    private static final Sort STABLE_ORDER = Sort.by("createdAt", "id").ascending();

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;
    private final QuotaService quotaService;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionRevocationService sessionRevocationService;

    @Override
    @Transactional
    public UserView createExternal(CreateExternalUserCommand command) {
        var email = normalizeEmail(command.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        if (command.scimExternalId() != null) {
            userRepository.findByOrganization_IdAndScimExternalId(
                            command.organizationId(), command.scimExternalId())
                    .ifPresent(existing -> {
                        throw new ExternalIdAlreadyExistsException(command.scimExternalId());
                    });
        }
        quotaService.checkUserQuota(command.organizationId());

        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organizationRepository.getReferenceById(command.organizationId()));
        entity.setEmail(email);
        entity.setDisplayName(command.displayName());
        entity.setAuthProvider(AuthProviderType.SCIM);
        entity.setScimExternalId(command.scimExternalId());
        entity.setActive(true);
        applySystemRole(entity, command.defaultRole());
        return UserViews.toView(userRepository.save(entity));
    }

    @Override
    @Transactional
    public UserView updateExternal(UUID organizationId, UUID userId,
                                   UpdateExternalUserCommand command) {
        var entity = userRepository.findByOrganization_IdAndId(organizationId, userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (command.email() != null) {
            var email = normalizeEmail(command.email());
            if (!email.equals(entity.getEmail()) && userRepository.existsByEmail(email)) {
                throw new EmailAlreadyExistsException(email);
            }
            entity.setEmail(email);
        }
        if (command.displayName() != null) {
            entity.setDisplayName(command.displayName());
        }
        if (command.scimExternalId() != null
                && !command.scimExternalId().equals(entity.getScimExternalId())) {
            userRepository.findByOrganization_IdAndScimExternalId(
                            organizationId, command.scimExternalId())
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new ExternalIdAlreadyExistsException(command.scimExternalId());
                    });
            entity.setScimExternalId(command.scimExternalId());
        }
        if (command.active() != null) {
            applyActive(entity, organizationId, command.active());
        }
        // The @PreUpdate bump only runs at flush — stamp updatedAt now so the returned view
        // (and the SCIM meta.lastModified built from it) reflects this write.
        entity.setUpdatedAt(Instant.now());
        return UserViews.toView(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID organizationId, UUID userId) {
        return userRepository.findByOrganization_IdAndId(organizationId, userId)
                .map(UserViews::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findByEmail(UUID organizationId, String email) {
        return userRepository.findByOrganization_IdAndEmail(organizationId, normalizeEmail(email))
                .map(UserViews::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findByExternalId(UUID organizationId, String scimExternalId) {
        return userRepository.findByOrganization_IdAndScimExternalId(organizationId, scimExternalId)
                .map(UserViews::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public DirectoryPage<UserView> list(UUID organizationId, int offset, int limit) {
        var page = userRepository.findAllByOrganization_Id(
                organizationId, new OffsetPageable(offset, limit, STABLE_ORDER));
        return new DirectoryPage<>(
                page.getContent().stream().map(UserViews::toView).toList(),
                page.getTotalElements());
    }

    private void applyActive(UserEntity entity, UUID organizationId, boolean active) {
        if (entity.isActive() == active) {
            return;
        }
        if (active) {
            // Reactivation counts against the org's user quota, exactly like a create.
            quotaService.checkUserQuota(organizationId);
            entity.setActive(true);
            return;
        }
        entity.setActive(false);
        // Refresh tokens are revoked synchronously (a failed revocation must surface); the
        // event fans the remaining side-effects (JIT-grant revocation) out to listeners.
        sessionRevocationService.revokeAllSessions(entity.getId());
        eventPublisher.publishEvent(new UserDeactivatedEvent(entity.getId(), organizationId));
    }

    /**
     * Mirrors {@code UserAdminServiceImpl.applyRole}'s system-role path: link the system-role row
     * and keep the legacy enum column in sync; fall back to the enum alone when the row is missing
     * (pre-V114 data mid-deploy).
     */
    private void applySystemRole(UserEntity entity, UserRoleType role) {
        var roleRef = roleRepository.findByNameAndSystemTrue(role.name()).orElse(null);
        entity.setRoleRef(roleRef);
        entity.setRole(role);
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
