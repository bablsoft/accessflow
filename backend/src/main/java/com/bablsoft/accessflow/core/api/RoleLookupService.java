package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side role resolution for other modules (AF-522): find a role visible to an organization
 * (global system role or the org's custom role) by id or by case-insensitive name. Used to
 * validate role-name strings stored in policy rows (masking reveal-to-roles, row-security
 * applies-to-roles, routing conditions, review-plan approver rules).
 */
public interface RoleLookupService {

    Optional<RoleView> findById(UUID roleId, UUID organizationId);

    Optional<RoleView> findByNameInScope(UUID organizationId, String name);
}
