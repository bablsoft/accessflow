package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserQueryService {
    Optional<UserView> findByEmail(String email);
    Optional<UserView> findById(UUID id);

    /** Batch counterpart of {@link #findById} — one query for a page's worth of ids. */
    List<UserView> findByIds(Collection<UUID> ids);
    List<UserView> findByOrganizationAndRole(UUID organizationId, UserRoleType role);

    /**
     * Users whose effective role NAME matches, case-insensitively — a system-role name or a
     * custom role's name (AF-522). Used to expand role-targeted approver rules.
     */
    List<UserView> findByOrganizationAndRoleName(UUID organizationId, String roleName);

    /**
     * Active service accounts in the organization counted by effective role name (#1131), so the
     * review-plan editor can warn when a role-targeted approver rule would include them. Roles
     * held by no active service account are absent; keys are ordered by name.
     */
    Map<String, Long> countActiveServiceAccountsByRoleName(UUID organizationId);
}
