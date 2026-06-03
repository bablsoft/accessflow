package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserAdminService {

    PageResponse<UserView> listUsers(UUID organizationId, PageRequest pageRequest);

    UserView createUser(CreateUserCommand command);

    UserView updateUser(UUID id, UUID organizationId, UUID currentUserId, UpdateUserCommand command);

    /**
     * Returns the admin-set attribute map for a user, resolvable in row-security predicates as
     * {@code :user.<key>}. Empty when none are set. Throws {@link UserNotFoundException} when the
     * user is not in {@code organizationId}.
     */
    Map<String, String> getUserAttributes(UUID id, UUID organizationId);

    UserView deactivateUser(UUID id, UUID organizationId, UUID currentUserId);

    Map<UUID, UserView> findByIds(UUID organizationId, Collection<UUID> ids);
}
