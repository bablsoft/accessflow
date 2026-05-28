package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.Map;

/**
 * Mutable SAML-config fields. {@code signingCertPem} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing certificate unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing certificate unchanged.</li>
 *     <li>blank string — clear the stored certificate.</li>
 *     <li>any other value — replace the certificate.</li>
 * </ul>
 *
 * {@code groupMappings} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing mappings unchanged.</li>
 *     <li>empty map — clear all mappings.</li>
 *     <li>otherwise — replace the entire map.</li>
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
        String attrGroups,
        Map<String, String> groupMappings,
        UserRoleType defaultRole,
        Boolean active) {

    public static final String MASKED_CERT = "********";
}
