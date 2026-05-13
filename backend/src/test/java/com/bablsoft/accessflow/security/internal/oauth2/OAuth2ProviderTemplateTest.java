package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void allProvidersExposeDisplayName() {
        for (var provider : OAuth2ProviderType.values()) {
            var t = OAuth2ProviderTemplate.forProvider(provider);
            assertThat(t.displayName()).isNotBlank();
            assertThat(t.provider()).isEqualTo(provider);
        }
    }

}
