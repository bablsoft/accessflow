package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;

/** Shared {@link UserEntity} → {@link UserView} mapper (AF-522: carries roleId/roleName). */
final class UserViews {

    private UserViews() {
    }

    static UserView toView(UserEntity entity) {
        return new UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getRoleRef() != null ? entity.getRoleRef().getId() : null,
                entity.roleName(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.isPlatformAdmin(),
                entity.getCreatedAt()
        );
    }
}
