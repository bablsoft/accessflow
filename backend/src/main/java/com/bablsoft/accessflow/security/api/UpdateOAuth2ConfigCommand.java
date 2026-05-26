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
 *
 * <p>The {@code displayName}, URL, and attribute-name fields are persisted only for the
 * generic {@link OAuth2ProviderType#OIDC} provider; the four built-in cloud providers ignore
 * them and pick up their templates from {@code OAuth2ProviderTemplate}. When the row's
 * provider is {@code OIDC} and {@code active=true}, the five URL fields and
 * {@code displayName} must be present (validated by {@code DefaultOAuth2ConfigService}).
 * Attribute-name fields are optional and fall back to standard OIDC claim names
 * ({@code sub}, {@code email}, {@code email_verified}, {@code name}). {@code groupsAttribute}
 * is the claim used by {@code OAuth2MembershipResolver} when an OIDC allowlist is
 * configured.
 *
 * <p>{@code baseUrl} is persisted only for {@link OAuth2ProviderType#GITHUB_ENTERPRISE} and
 * {@link OAuth2ProviderType#GITLAB_ENTERPRISE}. It carries the origin of the self-hosted
 * instance (e.g. {@code https://github.acme.corp}). When the row's provider is one of those
 * and {@code active=true}, {@code baseUrl} must be present, {@code https://}, and an origin
 * with no path / query / fragment.
 */
public record UpdateOAuth2ConfigCommand(
        String clientId,
        String clientSecret,
        String scopesOverride,
        String tenantId,
        String displayName,
        String authorizationUri,
        String tokenUri,
        String userInfoUri,
        String jwkSetUri,
        String issuerUri,
        String userNameAttribute,
        String emailAttribute,
        String emailVerifiedAttribute,
        String displayNameAttribute,
        String groupsAttribute,
        String baseUrl,
        List<String> allowedOrganizations,
        List<String> allowedEmailDomains,
        UserRoleType defaultRole,
        Boolean active) {

    public static final String MASKED_SECRET = "********";
}
