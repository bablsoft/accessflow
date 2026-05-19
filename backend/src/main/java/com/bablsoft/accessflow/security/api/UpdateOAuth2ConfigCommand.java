package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.List;

/**
 * Mutable OAuth2-config fields. {@code clientSecret} semantics mirror SAML signing-cert:
 * <ul>
 *     <li>{@code null} or literal {@code "********"} — leave the existing secret unchanged.</li>
 *     <li>blank string — clear the stored secret (forces the row inactive).</li>
 *     <li>any other value — replace the secret.</li>
 * </ul>
 *
 * <p>{@code allowedOrganizations} / {@code allowedEmailDomains} are tri-state:
 * <ul>
 *     <li>{@code null} — leave the existing list unchanged.</li>
 *     <li>empty list — clear the restriction (any login allowed).</li>
 *     <li>non-empty list — replace the stored list.</li>
 * </ul>
 */
public record UpdateOAuth2ConfigCommand(
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        UserRoleType defaultRole,
        Boolean active) {

    public static final String MASKED_SECRET = "********";
}
