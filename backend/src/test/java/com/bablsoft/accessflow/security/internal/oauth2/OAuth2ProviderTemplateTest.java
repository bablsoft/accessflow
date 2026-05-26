package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2ProviderTemplateTest {

    @Test
    void googleTemplateUsesOidcUrls() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GOOGLE);
        assertThat(t.authorizationUri(null)).startsWith("https://accounts.google.com/");
        assertThat(t.tokenUri(null)).contains("oauth2.googleapis.com");
        assertThat(t.userInfoUri()).contains("openidconnect.googleapis.com");
        assertThat(t.defaultScopes()).contains("openid", "email", "profile");
        assertThat(t.isOidc()).isTrue();
    }

    @Test
    void githubTemplateUsesNonOidcUrls() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITHUB);
        assertThat(t.authorizationUri(null)).startsWith("https://github.com/");
        assertThat(t.userInfoUri()).isEqualTo("https://api.github.com/user");
        assertThat(t.defaultScopes()).contains("read:user", "user:email");
        assertThat(t.isOidc()).isFalse();
        assertThat(t.jwkSetUri(null)).isNull();
    }

    @Test
    void microsoftTemplateSubstitutesTenant() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.MICROSOFT);
        assertThat(t.authorizationUri("contoso"))
                .isEqualTo("https://login.microsoftonline.com/contoso/oauth2/v2.0/authorize");
        assertThat(t.tokenUri("contoso")).contains("/contoso/");
        assertThat(t.issuerUri("contoso")).contains("/contoso/v2.0");
    }

    @Test
    void microsoftTemplateFallsBackToCommonTenant() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.MICROSOFT);
        assertThat(t.authorizationUri(null)).contains("/common/");
        assertThat(t.authorizationUri("  ")).contains("/common/");
    }

    @Test
    void gitlabTemplateExposesOidcEndpoints() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITLAB);
        assertThat(t.authorizationUri(null)).contains("gitlab.com");
        assertThat(t.userInfoUri()).contains("gitlab.com/oauth/userinfo");
        assertThat(t.isOidc()).isTrue();
    }

    @Test
    void allFixedProvidersExposeDisplayName() {
        for (var provider : OAuth2ProviderType.values()) {
            if (provider == OAuth2ProviderType.OIDC) continue;
            var t = OAuth2ProviderTemplate.forProvider(provider);
            assertThat(t.displayName()).isNotBlank();
            assertThat(t.provider()).isEqualTo(provider);
        }
    }

    @Test
    void githubEnterpriseSubstitutesBaseUrl() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITHUB_ENTERPRISE);
        assertThat(t.isOidc()).isFalse();
        assertThat(t.defaultScopes()).contains("read:user", "user:email");
        assertThat(t.authorizationUri(null, "https://gh.acme.corp"))
                .isEqualTo("https://gh.acme.corp/login/oauth/authorize");
        assertThat(t.tokenUri(null, "https://gh.acme.corp"))
                .isEqualTo("https://gh.acme.corp/login/oauth/access_token");
        assertThat(t.userInfoUri("https://gh.acme.corp"))
                .isEqualTo("https://gh.acme.corp/api/v3/user");
        assertThat(t.jwkSetUri(null, "https://gh.acme.corp")).isNull();
        assertThat(t.issuerUri(null, "https://gh.acme.corp")).isNull();
    }

    @Test
    void githubEnterpriseTrimsTrailingSlash() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITHUB_ENTERPRISE);
        assertThat(t.authorizationUri(null, "https://gh.acme.corp/"))
                .isEqualTo("https://gh.acme.corp/login/oauth/authorize");
        assertThat(t.authorizationUri(null, "https://gh.acme.corp///"))
                .isEqualTo("https://gh.acme.corp/login/oauth/authorize");
    }

    @Test
    void githubEnterprisePreservesCustomPort() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITHUB_ENTERPRISE);
        assertThat(t.authorizationUri(null, "https://gh.acme.corp:8443"))
                .isEqualTo("https://gh.acme.corp:8443/login/oauth/authorize");
    }

    @Test
    void gitlabEnterpriseSubstitutesBaseUrlForAllUris() {
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITLAB_ENTERPRISE);
        assertThat(t.isOidc()).isTrue();
        assertThat(t.defaultScopes()).contains("openid", "email", "profile");
        assertThat(t.authorizationUri(null, "https://gl.acme.corp"))
                .isEqualTo("https://gl.acme.corp/oauth/authorize");
        assertThat(t.tokenUri(null, "https://gl.acme.corp"))
                .isEqualTo("https://gl.acme.corp/oauth/token");
        assertThat(t.userInfoUri("https://gl.acme.corp"))
                .isEqualTo("https://gl.acme.corp/oauth/userinfo");
        assertThat(t.jwkSetUri(null, "https://gl.acme.corp"))
                .isEqualTo("https://gl.acme.corp/oauth/discovery/keys");
        assertThat(t.issuerUri(null, "https://gl.acme.corp"))
                .isEqualTo("https://gl.acme.corp");
    }

    @Test
    void enterpriseTemplateSingleArgAccessorsLeaveBasePlaceholder() {
        // Defensive: the single-arg overloads pass null baseUrl, so {base} stays unresolved.
        // Production code calls the two-arg overloads via DynamicClientRegistrationRepository.
        var t = OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.GITHUB_ENTERPRISE);
        assertThat(t.authorizationUri(null)).isEqualTo("/login/oauth/authorize");
    }

    @Test
    void forProviderRejectsOidcSinceItHasNoStaticDefaults() {
        assertThatThrownBy(() -> OAuth2ProviderTemplate.forProvider(OAuth2ProviderType.OIDC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forEntityBuildsOidcTemplateFromColumns() {
        var entity = oidcEntity();
        entity.setUserNameAttribute("uid");
        entity.setEmailAttribute("upn");
        entity.setEmailVerifiedAttribute("verified_flag");
        entity.setDisplayNameAttribute("preferred_username");
        entity.setGroupsAttribute("roles");

        var t = OAuth2ProviderTemplate.forEntity(entity);

        assertThat(t.provider()).isEqualTo(OAuth2ProviderType.OIDC);
        assertThat(t.displayName()).isEqualTo("Mock IdP");
        assertThat(t.authorizationUri(null)).isEqualTo("http://idp/authorize");
        assertThat(t.tokenUri(null)).isEqualTo("http://idp/token");
        assertThat(t.userInfoUri()).isEqualTo("http://idp/userinfo");
        assertThat(t.jwkSetUri(null)).isEqualTo("http://idp/jwks");
        assertThat(t.issuerUri(null)).isEqualTo("http://idp");
        assertThat(t.isOidc()).isTrue();
        assertThat(t.defaultScopes()).contains("openid", "email", "profile");
        assertThat(t.userNameAttributeName()).isEqualTo("uid");
        assertThat(t.emailAttributeName()).isEqualTo("upn");
        assertThat(t.emailVerifiedAttributeName()).isEqualTo("verified_flag");
        assertThat(t.displayNameAttributeName()).isEqualTo("preferred_username");
        assertThat(t.groupsAttributeName()).isEqualTo("roles");
    }

    @Test
    void forEntityOidcAppliesStandardAttributeDefaultsWhenBlank() {
        var entity = oidcEntity();

        var t = OAuth2ProviderTemplate.forEntity(entity);

        assertThat(t.userNameAttributeName()).isEqualTo("sub");
        assertThat(t.emailAttributeName()).isEqualTo("email");
        assertThat(t.emailVerifiedAttributeName()).isEqualTo("email_verified");
        assertThat(t.displayNameAttributeName()).isEqualTo("name");
        assertThat(t.groupsAttributeName()).isNull();
    }

    @Test
    void forEntityDelegatesToStaticTemplateForFixedProviders() {
        var entity = new OAuth2ConfigEntity();
        entity.setProvider(OAuth2ProviderType.GOOGLE);

        var t = OAuth2ProviderTemplate.forEntity(entity);

        assertThat(t.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
        assertThat(t.authorizationUri(null)).startsWith("https://accounts.google.com/");
    }

    private static OAuth2ConfigEntity oidcEntity() {
        var entity = new OAuth2ConfigEntity();
        entity.setProvider(OAuth2ProviderType.OIDC);
        entity.setDisplayName("Mock IdP");
        entity.setAuthorizationUri("http://idp/authorize");
        entity.setTokenUri("http://idp/token");
        entity.setUserInfoUri("http://idp/userinfo");
        entity.setJwkSetUri("http://idp/jwks");
        entity.setIssuerUri("http://idp");
        return entity;
    }

}
