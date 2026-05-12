package com.bablsoft.accessflow.core.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserAdminService {

    Page<UserView> listUsers(UUID organizationId, Pageable pageable);

    UserView createUser(CreateUserCommand command);

    UserView updateUser(UUID id, UUID organizationId, UUID currentUserId, UpdateUserCommand command);

    UserView deactivateUser(UUID id, UUID organizationId, UUID currentUserId);

    Map<UUID, UserView> findByIds(UUID organizationId, Collection<UUID> ids);
}
