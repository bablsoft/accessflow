package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Inverts the permission catalog: who in an organization holds a given {@link Permission}
 * (issue AF-859).
 *
 * <p>Every other lookup answers "what does this user hold", because that is what a request needs.
 * The reverse question has no answer anywhere else, and it is the one that matters for
 * {@code QUERY_ADMIN}: its holders skip the per-datasource permission gate entirely, so they appear
 * in no permission table while being able to reach everything.
 *
 * <p>Both role kinds are resolved: a system role through {@link SystemRolePermissions}, which is the
 * authoritative map, and a custom role (AF-522) through its own {@code role_permissions} rows.
 */
public interface RolePermissionHolderLookupService {

    /** Active users in the organization whose effective role grants {@code permission}. */
    List<UUID> findUserIdsWithPermission(UUID organizationId, Permission permission);
}
