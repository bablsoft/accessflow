package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuth2MembershipResolverTest {

    @Test
    void githubReturnsOrgLogins() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.github.com/user/orgs"))
                .andExpect(header("Authorization", "Bearer ghp_xyz"))
                .andRespond(withSuccess(
                        "[{\"login\":\"bablsoft\"},{\"login\":\"acme\"}]",
                        MediaType.APPLICATION_JSON));

        var resolver = new OAuth2MembershipResolver(builder.build());

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITHUB, Map.of(), "ghp_xyz");

        assertThat(orgs).containsExactlyInAnyOrder("bablsoft", "acme");
        mockServer.verify();
    }

    @Test
    void githubReturnsEmptyWhenNoAccessToken() {
        var resolver = new OAuth2MembershipResolver();

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITHUB, Map.of(), null);

        assertThat(orgs).isEmpty();
    }

    @Test
    void githubFailsClosedOnHttpError() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.github.com/user/orgs"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var resolver = new OAuth2MembershipResolver(builder.build());

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITHUB, Map.of(), "bad");

        assertThat(orgs).isEmpty();
    }

    @Test
    void githubSkipsBlankAndNonStringLogins() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.github.com/user/orgs"))
                .andRespond(withSuccess(
                        "[{\"login\":\"\"},{\"login\":\"  \"},{\"login\":\"acme\"},{}]",
                        MediaType.APPLICATION_JSON));

        var resolver = new OAuth2MembershipResolver(builder.build());

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITHUB, Map.of(), "tok");

        assertThat(orgs).containsExactly("acme");
    }

    @Test
    void gitlabReadsGroupsClaim() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("groups", List.of("acme/team", "foo"));

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITLAB, attrs, "tok");

        assertThat(orgs).containsExactlyInAnyOrder("acme/team", "foo");
    }

    @Test
    void gitlabReturnsEmptyWhenClaimMissing() {
        var resolver = new OAuth2MembershipResolver();

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GITLAB, Map.of(), "tok");

        assertThat(orgs).isEmpty();
    }

    @Test
    void microsoftReadsGroupsClaim() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("groups", List.of("group-oid-1", "group-oid-2"));

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.MICROSOFT, attrs, "tok");

        assertThat(orgs).containsExactlyInAnyOrder("group-oid-1", "group-oid-2");
    }

    @Test
    void microsoftReturnsEmptyWhenClaimIsNotList() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("groups", "not-a-list");

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.MICROSOFT, attrs, "tok");

        assertThat(orgs).isEmpty();
    }

    @Test
    void googleAlwaysReturnsEmpty() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("hd", "example.com");

        var orgs = resolver.resolveOrganizations(OAuth2ProviderType.GOOGLE, attrs, "tok");

        assertThat(orgs).isEmpty();
    }

    @Test
    void oidcExtractsGroupsFromConfiguredClaim() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("custom_groups", List.of("group-a", "group-b"));

        var orgs = resolver.resolveOrganizations(
                OAuth2ProviderType.OIDC, attrs, "tok", "custom_groups");

        assertThat(orgs).containsExactlyInAnyOrder("group-a", "group-b");
    }

    @Test
    void oidcReturnsEmptyWhenGroupsAttributeUnset() {
        var resolver = new OAuth2MembershipResolver();
        Map<String, Object> attrs = Map.of("custom_groups", List.of("group-a"));

        var orgs = resolver.resolveOrganizations(
                OAuth2ProviderType.OIDC, attrs, "tok", null);

        assertThat(orgs).isEmpty();
    }
}
