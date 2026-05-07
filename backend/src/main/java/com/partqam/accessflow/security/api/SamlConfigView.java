package com.partqam.accessflow.security.api;

import com.partqam.accessflow.core.api.UserRoleType;

import java.time.Instant;
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
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
