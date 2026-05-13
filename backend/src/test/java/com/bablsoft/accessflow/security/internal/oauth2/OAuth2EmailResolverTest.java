package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuth2EmailResolverTest {

    @Test
    void resolvesGoogleEmailFromClaims() {
        var resolver = new OAuth2EmailResolver();
        var attrs = Map.<String, Object>of(
                "sub", "1",
                "email", "alice@example.com",
                "email_verified", Boolean.TRUE,
                "name", "Alice");

        var resolved = resolver.resolve(OAuth2ProviderType.GOOGLE, attrs, "token");

        assertThat(resolved.email()).isEqualTo("alice@example.com");
        assertThat(resolved.displayName()).isEqualTo("Alice");
        assertThat(resolved.emailVerified()).isTrue();
    }

    @Test
    void googleUnverifiedEmailReportedAsUnverified() {
        var resolver = new OAuth2EmailResolver();
        var attrs = Map.<String, Object>of(
                "sub", "1",
                "email", "alice@example.com",
                "email_verified", Boolean.FALSE,
                "name", "Alice");

        var resolved = resolver.resolve(OAuth2ProviderType.GOOGLE, attrs, "token");

        assertThat(resolved.emailVerified()).isFalse();
    }

    @Test
    void microsoftEmailVerifiedAcceptsStringTrue() {
        var resolver = new OAuth2EmailResolver();
        var attrs = Map.<String, Object>of(
                "sub", "1",
                "email", "bob@example.com",
                "email_verified", "true",
                "name", "Bob");

        var resolved = resolver.resolve(OAuth2ProviderType.MICROSOFT, attrs, "token");

        assertThat(resolved.emailVerified()).isTrue();
    }

    @Test
    void githubFallsBackToEmailsEndpointForPrimaryVerified() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.github.com/user/emails"))
                .andExpect(header("Authorization", "Bearer ghp_abc"))
                .andRespond(withSuccess(
                        "[{\"email\":\"secondary@example.com\",\"primary\":false,\"verified\":true},"
                                + "{\"email\":\"primary@example.com\",\"primary\":true,\"verified\":true}]",
                        MediaType.APPLICATION_JSON));

        var resolver = new OAuth2EmailResolver(builder.build());
        var attrs = Map.<String, Object>of(
                "id", 1,
                "name", "Carol",
                "login", "carol");

        var resolved = resolver.resolve(OAuth2ProviderType.GITHUB, attrs, "ghp_abc");

        assertThat(resolved.email()).isEqualTo("primary@example.com");
        assertThat(resolved.emailVerified()).isTrue();
        mockServer.verify();
    }

    @Test
    void githubMissingTokenLeavesEmailUnresolved() {
        var resolver = new OAuth2EmailResolver();
        var attrs = Map.<String, Object>of("id", 1, "name", "Dave");

        var resolved = resolver.resolve(OAuth2ProviderType.GITHUB, attrs, null);

        assertThat(resolved.email()).isNull();
    }

    @Test
    void githubEmailsEndpointFailureFallsBackToUserClaimEmail() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.github.com/user/emails"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var resolver = new OAuth2EmailResolver(builder.build());
        var attrs = Map.<String, Object>of(
                "id", 1,
                "name", "Eve",
                "email", "eve@example.com");

        var resolved = resolver.resolve(OAuth2ProviderType.GITHUB, attrs, "bad");

        assertThat(resolved.email()).isEqualTo("eve@example.com");
        // We couldn't independently verify — be conservative.
        assertThat(resolved.emailVerified()).isFalse();
    }
}
