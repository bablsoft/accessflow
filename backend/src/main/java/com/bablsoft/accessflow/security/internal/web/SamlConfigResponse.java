package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;

import java.time.Instant;
import java.util.UUID;

record SamlConfigResponse(
        UUID id,
        UUID organizationId,
        String idpMetadataUrl,
        String idpEntityId,
        String spEntityId,
        String acsUrl,
        String sloUrl,
        String signingCertPem,
        String attrEmail,
        String attrDisplayName,
        String attrRole,
        UserRoleType defaultRole,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    static SamlConfigResponse from(SamlConfigView view) {
        return new SamlConfigResponse(
                view.id(),
                view.organizationId(),
                view.idpMetadataUrl(),
                view.idpEntityId(),
                view.spEntityId(),
                view.acsUrl(),
                view.sloUrl(),
                view.signingCertConfigured() ? UpdateSamlConfigCommand.MASKED_CERT : null,
                view.attrEmail(),
                view.attrDisplayName(),
                view.attrRole(),
                view.defaultRole(),
                view.active(),
                view.createdAt(),
                view.updatedAt());
    }
}
