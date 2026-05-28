package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SamlConfigView(
        UUID id,
        UUID organizationId,
        String idpMetadataUrl,
        String idpEntityId,
        String spEntityId,
        String acsUrl,
        String sloUrl,
        boolean signingCertConfigured,
        String attrEmail,
        String attrDisplayName,
        String attrRole,
        String attrGroups,
        Map<String, String> groupMappings,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
