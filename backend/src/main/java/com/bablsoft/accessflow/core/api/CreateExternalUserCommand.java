package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Create command for an externally provisioned (SCIM, #621) user: no password, provider
 * {@link AuthProviderType#SCIM}, role fixed to the org's configured default system role.
 */
public record CreateExternalUserCommand(
        UUID organizationId,
        String email,
        String displayName,
        String scimExternalId,
        UserRoleType defaultRole
) {
}
