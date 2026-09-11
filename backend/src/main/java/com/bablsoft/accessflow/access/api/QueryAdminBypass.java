package com.bablsoft.accessflow.access.api;

import java.util.UUID;

/**
 * The role that carries a user's {@code QUERY_ADMIN} bypass (#968) — the system {@code ADMIN} role
 * or a custom role that includes the permission (AF-522). Present on a row only when the bypass
 * applies.
 */
public record QueryAdminBypass(UUID roleId, String roleName, boolean systemRole) {
}
