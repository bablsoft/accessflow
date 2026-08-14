package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.scim.api.ScimConfigView;

import java.time.Instant;
import java.util.UUID;

record ScimConfigResponse(
        UUID id,
        UUID organizationId,
        boolean enabled,
        String attrEmail,
        String attrDisplayName,
        UserRoleType defaultRole,
        Instant createdAt,
        Instant updatedAt) {

    static ScimConfigResponse from(ScimConfigView view) {
        return new ScimConfigResponse(
                view.id(),
                view.organizationId(),
                view.enabled(),
                view.attrEmail(),
                view.attrDisplayName(),
                view.defaultRole(),
                view.createdAt(),
                view.updatedAt());
    }
}
