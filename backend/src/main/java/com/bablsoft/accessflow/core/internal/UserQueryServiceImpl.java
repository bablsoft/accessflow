package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class UserQueryServiceImpl implements UserQueryService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID id) {
        return userRepository.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserView> findByOrganizationAndRole(UUID organizationId, UserRoleType role) {
        return userRepository.findAllByOrganization_IdAndRole(organizationId, role).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserView> findByOrganizationAndRoleName(UUID organizationId, String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        var systemRole = systemRoleOrNull(roleName);
        return userRepository.findAllByOrganizationAndRoleName(organizationId, roleName.trim(),
                        systemRole).stream()
                .map(this::toView)
                .toList();
    }

    private static UserRoleType systemRoleOrNull(String roleName) {
        try {
            return UserRoleType.valueOf(roleName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UserView toView(UserEntity entity) {
        return UserViews.toView(entity);
    }
}
