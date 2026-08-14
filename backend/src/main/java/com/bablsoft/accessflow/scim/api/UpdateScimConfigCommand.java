package com.bablsoft.accessflow.scim.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

/** Partial update / upsert for the org's SCIM config (#621); null fields are left unchanged. */
public record UpdateScimConfigCommand(
        Boolean enabled,
        String attrEmail,
        String attrDisplayName,
        UserRoleType defaultRole
) {
}
