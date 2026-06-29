package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConnectorAuthApplierTest {

    private final ApiConnectorAuthApplier applier = new ApiConnectorAuthApplier();

    @Test
    void noneYieldsNoHeaders() {
        assertThat(applier.authHeaders(ApiAuthMethod.NONE, Map.of(), 1000)).isEmpty();
        assertThat(applier.authHeaders(null, null, 1000)).isEmpty();
    }

    @Test
    void bearerTokenSetsAuthorization() {
        var headers = applier.authHeaders(ApiAuthMethod.BEARER_TOKEN, Map.of("token", "abc"), 1000);
        assertThat(headers).containsEntry("Authorization", "Bearer abc");
    }

    @Test
    void basicEncodesCredentials() {
        var headers = applier.authHeaders(ApiAuthMethod.BASIC, Map.of("username", "u", "password", "p"), 1000);
        assertThat(headers.get("Authorization")).startsWith("Basic ");
    }

    @Test
    void apiKeyUsesConfiguredHeaderName() {
        var headers = applier.authHeaders(ApiAuthMethod.API_KEY,
                Map.of("header-name", "X-Key", "api-key", "secret"), 1000);
        assertThat(headers).containsEntry("X-Key", "secret");
    }

    @Test
    void customHeaderSetsConfiguredHeader() {
        var headers = applier.authHeaders(ApiAuthMethod.CUSTOM_HEADER,
                Map.of("header-name", "X-Auth", "header-value", "v"), 1000);
        assertThat(headers).containsEntry("X-Auth", "v");
    }

    @Test
    void mtlsYieldsNoHeaders() {
        assertThat(applier.authHeaders(ApiAuthMethod.MTLS, Map.of(), 1000)).isEmpty();
    }

    @Test
    void oauth2YieldsNoHeadersHereHandledByTokenService() {
        // OAuth2 token sourcing moved to ConnectorOAuth2TokenService (#506); the applier no longer
        // emits an Authorization header for OAUTH2_CLIENT_CREDENTIALS.
        assertThat(applier.authHeaders(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS,
                Map.of("token-url", "http://x"), 1000)).isEmpty();
    }
}
