package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserQueryService {
    Optional<UserView> findByEmail(String email);
    Optional<UserView> findById(UUID id);
    List<UserView> findByOrganizationAndRole(UUID organizationId, UserRoleType role);

    /**
     * Users whose effective role NAME matches, case-insensitively — a system-role name or a
     * custom role's name (AF-522). Used to expand role-targeted approver rules.
     */
    List<UserView> findByOrganizationAndRoleName(UUID organizationId, String roleName);
}
