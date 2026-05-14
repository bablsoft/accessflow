package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.UserRoleType;

public record SamlSpec(
        boolean enabled,
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
        Boolean active
) {
}
