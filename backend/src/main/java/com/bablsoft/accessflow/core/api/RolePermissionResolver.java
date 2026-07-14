package com.bablsoft.accessflow.core.api;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the effective permission set of a user's role (AF-522). System roles are answered from
 * {@link SystemRolePermissions} (the code map is authoritative); custom roles from their
 * {@code role_permissions} rows. When {@code roleId} is null (pre-migration data or legacy
 * callers), the fallback system role decides; when both are null the set is empty.
 */
public interface RolePermissionResolver {

    Set<Permission> resolve(UUID roleId, UserRoleType fallbackSystemRole);
}
