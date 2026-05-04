package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.CreateUserCommand;
import com.partqam.accessflow.core.api.EmailAlreadyExistsException;
import com.partqam.accessflow.core.api.IllegalUserOperationException;
import com.partqam.accessflow.core.api.UpdateUserCommand;
import com.partqam.accessflow.core.api.UserAdminService;
import com.partqam.accessflow.core.api.UserNotFoundException;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class UserAdminServiceImpl implements UserAdminService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<UserView> listUsers(UUID organizationId, Pageable pageable) {
        return userRepository.findAllByOrganization_Id(organizationId, pageable)
                .map(this::toView);
    }

    @Override
    @Transactional
    public UserView createUser(CreateUserCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new EmailAlreadyExistsException(command.email());
        }
        var organization = organizationRepository.getReferenceById(command.organizationId());

        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setEmail(command.email());
        entity.setDisplayName(command.displayName());
        entity.setPasswordHash(command.passwordHash());
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setRole(command.role());
        entity.setActive(true);

        return toView(userRepository.save(entity));
    }

    @Override
    @Transactional
    public UserView updateUser(UUID id, UUID organizationId, UUID currentUserId,
                               UpdateUserCommand command) {
        var entity = loadInOrganization(id, organizationId);

        if (id.equals(currentUserId)) {
            if (command.role() != null && command.role() != UserRoleType.ADMIN) {
                throw new IllegalUserOperationException(
                        "Admin users cannot demote themselves from the ADMIN role");
            }
            if (Boolean.FALSE.equals(command.active())) {
                throw new IllegalUserOperationException(
                        "Admin users cannot deactivate themselves");
            }
        }

        if (command.role() != null) {
            entity.setRole(command.role());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        if (command.displayName() != null) {
            entity.setDisplayName(command.displayName());
        }
        return toView(entity);
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

    private UserEntity loadInOrganization(UUID id, UUID organizationId) {
        var entity = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new UserNotFoundException(id);
        }
        return entity;
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
                entity.getCreatedAt()
        );
    }
}
