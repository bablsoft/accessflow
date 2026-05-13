package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.InactiveUserException;
import com.bablsoft.accessflow.core.api.UserProvisioningService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultUserProvisioningService implements UserProvisioningService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional
    public UserView findOrProvision(UUID organizationId,
                                    String email,
                                    String displayName,
                                    AuthProviderType authProvider,
                                    UserRoleType defaultRole) {
        var normalizedEmail = email.trim().toLowerCase();
        var existing = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                throw new InactiveUserException(normalizedEmail);
            }
            if (existing.getAuthProvider() == AuthProviderType.LOCAL
                    && existing.getPasswordHash() != null) {
                throw new ExternalLocalAccountConflictException(normalizedEmail);
            }
            return toView(existing);
        }

        var organization = organizationRepository.getReferenceById(organizationId);
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setEmail(normalizedEmail);
        entity.setDisplayName(displayName);
        entity.setAuthProvider(authProvider);
        entity.setRole(defaultRole);
        entity.setActive(true);
        return toView(userRepository.save(entity));
    }

    private UserView toView(UserEntity entity) {
        return new UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt()
        );
    }
}
