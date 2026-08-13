package com.bablsoft.accessflow.scim.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.UUID;

/**
 * The organization's SCIM 2.0 provisioning settings (#621). {@code attrEmail} and
 * {@code attrDisplayName} name the SCIM attribute the corresponding user field is read from —
 * see {@link ScimAttributeMapping} for the allowed values.
 */
public record ScimConfigView(
        UUID id,
        UUID organizationId,
        boolean enabled,
        String attrEmail,
        String attrDisplayName,
        UserRoleType defaultRole,
        Instant createdAt,
        Instant updatedAt
) {
}
