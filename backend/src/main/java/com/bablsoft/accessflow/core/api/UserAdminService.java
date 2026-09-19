package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserAdminService {

    default PageResponse<UserView> listUsers(UUID organizationId, PageRequest pageRequest) {
        return listUsers(organizationId, null, pageRequest);
    }

    /** Lists the organization's users, optionally only one {@link PrincipalType} (#875). */
    PageResponse<UserView> listUsers(UUID organizationId, PrincipalType principalType, PageRequest pageRequest);

    UserView createUser(CreateUserCommand command);

    UserView updateUser(UUID id, UUID organizationId, UUID currentUserId, UpdateUserCommand command);

    /**
     * Returns the admin-set attribute map for a user, resolvable in row-security predicates as
     * {@code :user.<key>}. Empty when none are set. Throws {@link UserNotFoundException} when the
     * user is not in {@code organizationId}.
     */
    Map<String, String> getUserAttributes(UUID id, UUID organizationId);

    UserView deactivateUser(UUID id, UUID organizationId, UUID currentUserId);

    /**
     * Sets the orthogonal platform-admin capability on a user (AF-456). Used by bootstrap to
     * promote a pre-existing admin to a platform admin on a re-run. Throws
     * {@link UserNotFoundException} when the user does not exist.
     */
    UserView setPlatformAdmin(UUID id, boolean platformAdmin);

    /**
     * Sets the person-vs-service-account discriminator (#868). The {@code serviceaccounts} module
     * is the only intended caller: it pairs this with the {@code service_accounts} detail row in one
     * transaction so the two can never drift; {@code PrincipalTypeChokepointTest} (ArchUnit) fails
     * the build on any other caller. Throws
     * {@link UserNotFoundException} when the user is not in {@code organizationId}.
     */
    UserView setPrincipalType(UUID id, UUID organizationId, PrincipalType principalType);

    Map<UUID, UserView> findByIds(UUID organizationId, Collection<UUID> ids);
}
