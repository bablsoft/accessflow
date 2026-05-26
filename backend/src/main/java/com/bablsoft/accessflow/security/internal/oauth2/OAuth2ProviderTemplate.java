package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;

import java.util.Map;
import java.util.Set;

/**
 * Per-provider OAuth2 metadata: authorization/token/userinfo URLs, default scopes,
 * the OIDC flag, and the userinfo claim names this app extracts ({@code email}, display name).
 *
 * <p>For the four built-in cloud providers (GOOGLE, GITHUB, MICROSOFT, GITLAB) the metadata is
 * static and lives in {@link #TEMPLATES}; the DB-backed config supplies only the
 * client-id/secret/tenant/scopes-override and active flag, so admin-entered URLs cannot
 * be used to redirect users elsewhere.
 *
 * <p>For the two enterprise variants (GITHUB_ENTERPRISE, GITLAB_ENTERPRISE) the URLs share
 * the same compiled sub-paths but include a {@code {base}} placeholder for the operator's
 * self-hosted origin (e.g. {@code https://github.acme.corp}). Only the origin is editable;
 * the sub-paths remain static.
 *
 * <p>For the generic {@link OAuth2ProviderType#OIDC} provider, the metadata is supplied by
 * the {@code oauth2_config} row itself; {@link #forEntity(OAuth2ConfigEntity)} builds a
 * template instance from those columns. URLs are operator-editable (admin role, audit-logged)
 * and never read from an unauthenticated request.
 */
public final class OAuth2ProviderTemplate {

    private static final Map<OAuth2ProviderType, OAuth2ProviderTemplate> TEMPLATES = Map.of(
            OAuth2ProviderType.GOOGLE, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.GOOGLE,
                    "Google",
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://openidconnect.googleapis.com/v1/userinfo",
                    "https://www.googleapis.com/oauth2/v3/certs",
                    "https://accounts.google.com",
                    Set.of("openid", "email", "profile"),
                    true,
                    "sub",
                    "email",
                    "email_verified",
                    "name"),
            OAuth2ProviderType.GITHUB, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.GITHUB,
                    "GitHub",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    "https://api.github.com/user",
                    null,
                    null,
                    Set.of("read:user", "user:email"),
                    false,
                    "id",
                    "email",
                    null,
                    "name"),
            OAuth2ProviderType.MICROSOFT, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.MICROSOFT,
                    "Microsoft",
                    "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
                    "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
                    "https://graph.microsoft.com/oidc/userinfo",
                    "https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys",
                    "https://login.microsoftonline.com/{tenant}/v2.0",
                    Set.of("openid", "email", "profile"),
                    true,
                    "sub",
                    "email",
                    "email_verified",
                    "name"),
            OAuth2ProviderType.GITLAB, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.GITLAB,
                    "GitLab",
                    "https://gitlab.com/oauth/authorize",
                    "https://gitlab.com/oauth/token",
                    "https://gitlab.com/oauth/userinfo",
                    "https://gitlab.com/oauth/discovery/keys",
                    "https://gitlab.com",
                    Set.of("openid", "email", "profile"),
                    true,
                    "sub",
                    "email",
                    "email_verified",
                    "name"),
            OAuth2ProviderType.GITHUB_ENTERPRISE, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.GITHUB_ENTERPRISE,
                    "GitHub Enterprise",
                    "{base}/login/oauth/authorize",
                    "{base}/login/oauth/access_token",
                    "{base}/api/v3/user",
                    null,
                    null,
                    Set.of("read:user", "user:email"),
                    false,
                    "id",
                    "email",
                    null,
                    "name"),
            OAuth2ProviderType.GITLAB_ENTERPRISE, new OAuth2ProviderTemplate(
                    OAuth2ProviderType.GITLAB_ENTERPRISE,
                    "GitLab (self-managed)",
                    "{base}/oauth/authorize",
                    "{base}/oauth/token",
                    "{base}/oauth/userinfo",
                    "{base}/oauth/discovery/keys",
                    "{base}",
                    Set.of("openid", "email", "profile"),
                    true,
                    "sub",
                    "email",
                    "email_verified",
                    "name"));

    public static OAuth2ProviderTemplate forProvider(OAuth2ProviderType provider) {
        var template = TEMPLATES.get(provider);
        if (template == null) {
            throw new IllegalArgumentException("No template registered for " + provider);
        }
        return template;
    }

    /**
     * Returns a template for the given {@code oauth2_config} row. For the four built-in
     * cloud providers and the two enterprise variants this is equivalent to
     * {@link #forProvider(OAuth2ProviderType)}; for {@link OAuth2ProviderType#OIDC} it
     * builds the template from the entity's URL and attribute-name columns, applying
     * defaults for any attribute name left null.
     */
    public static OAuth2ProviderTemplate forEntity(OAuth2ConfigEntity entity) {
        if (entity.getProvider() != OAuth2ProviderType.OIDC) {
            return forProvider(entity.getProvider());
        }
        return new OAuth2ProviderTemplate(
                OAuth2ProviderType.OIDC,
                entity.getDisplayName(),
                entity.getAuthorizationUri(),
                entity.getTokenUri(),
                entity.getUserInfoUri(),
                entity.getJwkSetUri(),
                entity.getIssuerUri(),
                Set.of("openid", "email", "profile"),
                true,
                blankOrDefault(entity.getUserNameAttribute(), "sub"),
                blankOrDefault(entity.getEmailAttribute(), "email"),
                blankOrDefault(entity.getEmailVerifiedAttribute(), "email_verified"),
                blankOrDefault(entity.getDisplayNameAttribute(), "name"),
                blankToNull(entity.getGroupsAttribute()));
    }

    private static String blankOrDefault(String value, String fallback) {
        if (value == null) return fallback;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private final OAuth2ProviderType provider;
    private final String displayName;
    private final String authorizationUri;
    private final String tokenUri;
    private final String userInfoUri;
    private final String jwkSetUri;
    private final String issuerUri;
    private final Set<String> defaultScopes;
    private final boolean oidc;
    private final String userNameAttributeName;
    private final String emailAttributeName;
    private final String emailVerifiedAttributeName;
    private final String displayNameAttributeName;
    private final String groupsAttributeName;

    private OAuth2ProviderTemplate(OAuth2ProviderType provider,
                                   String displayName,
                                   String authorizationUri,
                                   String tokenUri,
                                   String userInfoUri,
                                   String jwkSetUri,
                                   String issuerUri,
                                   Set<String> defaultScopes,
                                   boolean oidc,
                                   String userNameAttributeName,
                                   String emailAttributeName,
                                   String emailVerifiedAttributeName,
                                   String displayNameAttributeName) {
        this(provider, displayName, authorizationUri, tokenUri, userInfoUri, jwkSetUri, issuerUri,
                defaultScopes, oidc, userNameAttributeName, emailAttributeName,
                emailVerifiedAttributeName, displayNameAttributeName, null);
    }

    private OAuth2ProviderTemplate(OAuth2ProviderType provider,
                                   String displayName,
                                   String authorizationUri,
                                   String tokenUri,
                                   String userInfoUri,
                                   String jwkSetUri,
                                   String issuerUri,
                                   Set<String> defaultScopes,
                                   boolean oidc,
                                   String userNameAttributeName,
                                   String emailAttributeName,
                                   String emailVerifiedAttributeName,
                                   String displayNameAttributeName,
                                   String groupsAttributeName) {
        this.provider = provider;
        this.displayName = displayName;
        this.authorizationUri = authorizationUri;
        this.tokenUri = tokenUri;
        this.userInfoUri = userInfoUri;
        this.jwkSetUri = jwkSetUri;
        this.issuerUri = issuerUri;
        this.defaultScopes = Set.copyOf(defaultScopes);
        this.oidc = oidc;
        this.userNameAttributeName = userNameAttributeName;
        this.emailAttributeName = emailAttributeName;
        this.emailVerifiedAttributeName = emailVerifiedAttributeName;
        this.displayNameAttributeName = displayNameAttributeName;
        this.groupsAttributeName = groupsAttributeName;
    }

    public OAuth2ProviderType provider() {
        return provider;
    }

    public String displayName() {
        return displayName;
    }

    public String authorizationUri(String tenantId) {
        return authorizationUri(tenantId, null);
    }

    public String authorizationUri(String tenantId, String baseUrl) {
        return substitute(authorizationUri, tenantId, baseUrl);
    }

    public String tokenUri(String tenantId) {
        return tokenUri(tenantId, null);
    }

    public String tokenUri(String tenantId, String baseUrl) {
        return substitute(tokenUri, tenantId, baseUrl);
    }

    public String userInfoUri() {
        return userInfoUri(null);
    }

    public String userInfoUri(String baseUrl) {
        return substitute(userInfoUri, null, baseUrl);
    }

    public String jwkSetUri(String tenantId) {
        return jwkSetUri(tenantId, null);
    }

    public String jwkSetUri(String tenantId, String baseUrl) {
        return jwkSetUri == null ? null : substitute(jwkSetUri, tenantId, baseUrl);
    }

    public String issuerUri(String tenantId) {
        return issuerUri(tenantId, null);
    }

    public String issuerUri(String tenantId, String baseUrl) {
        return issuerUri == null ? null : substitute(issuerUri, tenantId, baseUrl);
    }

    public Set<String> defaultScopes() {
        return defaultScopes;
    }

    public boolean isOidc() {
        return oidc;
    }

    public String userNameAttributeName() {
        return userNameAttributeName;
    }

    public String emailAttributeName() {
        return emailAttributeName;
    }

    public String emailVerifiedAttributeName() {
        return emailVerifiedAttributeName;
    }

    public String displayNameAttributeName() {
        return displayNameAttributeName;
    }

    public String groupsAttributeName() {
        return groupsAttributeName;
    }

    private static String substitute(String uri, String tenantId, String baseUrl) {
        if (uri == null) return null;
        var out = uri;
        if (out.contains("{tenant}")) {
            var tenant = (tenantId == null || tenantId.isBlank()) ? "common" : tenantId.trim();
            out = out.replace("{tenant}", tenant);
        }
        if (out.contains("{base}")) {
            var base = (baseUrl == null) ? "" : trimTrailingSlash(baseUrl.trim());
            out = out.replace("{base}", base);
        }
        return out;
    }

    private static String trimTrailingSlash(String value) {
        var end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
