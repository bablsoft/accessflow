package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

/**
 * Mutable OAuth2-config fields. {@code clientSecret} semantics mirror SAML signing-cert:
 * <ul>
 *     <li>{@code null} or literal {@code "********"} — leave the existing secret unchanged.</li>
 *     <li>blank string — clear the stored secret (forces the row inactive).</li>
 *     <li>any other value — replace the secret.</li>
 * </ul>
 */
public record UpdateOAuth2ConfigCommand(
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
        UserRoleType defaultRole,
        Boolean active) {

    public static final String MASKED_SECRET = "********";
}
