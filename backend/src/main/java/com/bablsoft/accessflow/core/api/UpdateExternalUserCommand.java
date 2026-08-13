package com.bablsoft.accessflow.core.api;

/**
 * Partial update for an externally managed (SCIM, #621) user. Null fields are left unchanged.
 * Deliberately excludes everything SCIM does not own: password, role, platform_admin, TOTP,
 * row-security attributes, auth provider.
 */
public record UpdateExternalUserCommand(
        String email,
        String displayName,
        String scimExternalId,
        Boolean active
) {
}
