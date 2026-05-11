package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
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
