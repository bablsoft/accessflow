package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

import java.util.Map;
import java.util.Set;

/**
 * Static, per-provider OAuth2 metadata: authorization/token/userinfo URLs, default scopes,
 * the OIDC flag, and the userinfo claim names this app extracts ({@code email}, display name).
 * The DB-backed config supplies only the client-id/secret/tenant/scopes-override and active flag;
 * everything provider-specific lives here so we never trust admin-entered URLs at runtime.
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
                    "name"));

    public static OAuth2ProviderTemplate forProvider(OAuth2ProviderType provider) {
        var template = TEMPLATES.get(provider);
        if (template == null) {
            throw new IllegalArgumentException("No template registered for " + provider);
        }
        return template;
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
    }

    public OAuth2ProviderType provider() {
        return provider;
    }

    public String displayName() {
        return displayName;
    }

    public String authorizationUri(String tenantId) {
        return substituteTenant(authorizationUri, tenantId);
    }

    public String tokenUri(String tenantId) {
        return substituteTenant(tokenUri, tenantId);
    }

    public String userInfoUri() {
        return userInfoUri;
    }

    public String jwkSetUri(String tenantId) {
        return jwkSetUri == null ? null : substituteTenant(jwkSetUri, tenantId);
    }

    public String issuerUri(String tenantId) {
        return issuerUri == null ? null : substituteTenant(issuerUri, tenantId);
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

    private static String substituteTenant(String uri, String tenantId) {
        if (!uri.contains("{tenant}")) {
            return uri;
        }
        var tenant = (tenantId == null || tenantId.isBlank()) ? "common" : tenantId.trim();
        return uri.replace("{tenant}", tenant);
    }
}
