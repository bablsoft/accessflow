package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

/**
 * Mutable SAML-config fields. {@code signingCertPem} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing certificate unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing certificate unchanged.</li>
 *     <li>blank string — clear the stored certificate.</li>
 *     <li>any other value — replace the certificate.</li>
 * </ul>
 */
public record UpdateSamlConfigCommand(
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
        Boolean active) {

    public static final String MASKED_CERT = "********";
}
