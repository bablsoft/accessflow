package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.scim.api.ScimTokenService;
import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimConfigEntity;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimConfigRepository;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimTokenRepository;
import com.bablsoft.accessflow.security.internal.token.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Full-stack SCIM 2.0 protocol tests (#621): bearer-token chain, Okta/Entra-shaped payloads,
 * deactivation fan-out, group membership provenance.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ScimEndpointsIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ScimConfigRepository scimConfigRepository;
    @Autowired ScimTokenRepository scimTokenRepository;
    @Autowired ScimTokenService scimTokenService;
    @Autowired UserGroupService userGroupService;
    @Autowired RefreshTokenStore refreshTokenStore;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private String rawToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        scimTokenRepository.deleteAll();
        scimConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        var config = new ScimConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(org.getId());
        config.setEnabled(true);
        scimConfigRepository.save(config);

        rawToken = scimTokenService.create(org.getId(), "it-token", null).rawToken();
    }

    @Test
    void missingTokenReturns401ScimEnvelope() {
        var result = mvc.get().uri("/scim/v2/Users").exchange();

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.schemas[0]").asString()
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("401");
    }

    @Test
    void revokedTokenReturns401() {
        var tokenId = scimTokenRepository.findAllByOrganizationIdOrderByCreatedAtDesc(org.getId())
                .get(0).getId();
        scimTokenService.revoke(org.getId(), tokenId);

        var result = scimGet("/scim/v2/Users");

        assertThat(result).hasStatus(401);
    }

    @Test
    void disabledConfigReturns401() {
        var config = scimConfigRepository.findByOrganizationId(org.getId()).orElseThrow();
        config.setEnabled(false);
        scimConfigRepository.save(config);

        var result = scimGet("/scim/v2/ServiceProviderConfig");

        assertThat(result).hasStatus(401);
    }

    @Test
    void discoveryEndpointsAnswerWithScimMediaType() {
        var result = scimGet("/scim/v2/ServiceProviderConfig");

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.patch.supported").asBoolean().isTrue();
        assertThat(scimGet("/scim/v2/ResourceTypes")).hasStatus(200);
        assertThat(scimGet("/scim/v2/Schemas")).hasStatus(200);
    }

    @Test
    void oktaShapedCreateProvisionsScimUser() throws Exception {
        var result = scimPost("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"jane@example.com",
                 "name":{"givenName":"Jane","familyName":"Doe","formatted":"Jane Doe"},
                 "displayName":"Jane Doe",
                 "emails":[{"primary":true,"value":"jane@example.com","type":"work"}],
                 "password":"should-be-ignored",
                 "externalId":"00u1abcd",
                 "active":true}
                """);

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.userName").asString()
                .isEqualTo("jane@example.com");
        assertThat(result).bodyJson().extractingPath("$.externalId").asString()
                .isEqualTo("00u1abcd");
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");

        var stored = userRepository.findByEmail("jane@example.com").orElseThrow();
        assertThat(stored.getAuthProvider()).isEqualTo(AuthProviderType.SCIM);
        assertThat(stored.getPasswordHash()).isNull();
        assertThat(stored.getScimExternalId()).isEqualTo("00u1abcd");
        assertThat(stored.getRole()).isEqualTo(UserRoleType.ANALYST);
    }

    @Test
    void duplicateEmailReturns409Uniqueness() {
        seedScimUser("dup@example.com");

        var result = scimPost("/scim/v2/Users",
                "{\"userName\":\"dup@example.com\",\"active\":true}");

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.scimType").asString()
                .isEqualTo("uniqueness");
    }

    @Test
    void filterByUserNameFindsTheUser() {
        seedScimUser("findme@example.com");

        var result = mvc.get().uri("/scim/v2/Users")
                .param("filter", "userName eq \"findme@example.com\"")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.totalResults").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.Resources[0].userName").asString()
                .isEqualTo("findme@example.com");
    }

    @Test
    void unsupportedFilterReturnsInvalidFilterEnvelope() {
        var result = mvc.get().uri("/scim/v2/Users")
                .param("filter", "title co \"boss\"")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.scimType").asString()
                .isEqualTo("invalidFilter");
    }

    @Test
    void entraShapedPatchDeactivatesAndRevokesRefreshTokens() {
        var user = seedScimUser("leaver@example.com");
        refreshTokenStore.store("rt-leaver", user.getId().toString(), 3600);

        var result = mvc.patch().uri("/scim/v2/Users/" + user.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .contentType(MediaType.parseMediaType("application/scim+json"))
                .content("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"Replace","path":"active","value":"False"}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.active").asBoolean().isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(refreshTokenStore.isRevoked("rt-leaver")).isTrue());
    }

    @Test
    void deleteDeactivatesIdempotently() {
        var user = seedScimUser("gone@example.com");

        assertThat(scimDelete("/scim/v2/Users/" + user.getId())).hasStatus(204);
        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
        assertThat(scimDelete("/scim/v2/Users/" + user.getId())).hasStatus(204);
        assertThat(scimDelete("/scim/v2/Users/" + UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void groupLifecycleKeepsManualMembershipsIntact() {
        var scimUser = seedScimUser("member@example.com");
        var manualUser = seedScimUser("manual@example.com");

        // Create the group over SCIM with one member.
        var created = scimPost("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"Engineers","externalId":"grp-1",
                 "members":[{"value":"%s"}]}
                """.formatted(scimUser.getId()));
        assertThat(created).hasStatus(201);
        var groupId = userGroupService.listAll(org.getId()).stream()
                .filter(g -> "Engineers".equals(g.name()))
                .findFirst().orElseThrow().id();

        // An admin adds a MANUAL member out-of-band.
        userGroupService.addMember(groupId, manualUser.getId(), org.getId(),
                UserGroupMembershipSourceType.MANUAL);

        // SCIM replaces its member set with empty — the MANUAL row must survive.
        var patch = mvc.patch().uri("/scim/v2/Groups/" + groupId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"Operations":[{"op":"replace","path":"members","value":[]}]}
                        """)
                .exchange();
        assertThat(patch).hasStatus(200);

        var members = userGroupService.listMembers(groupId, org.getId());
        assertThat(members).hasSize(1);
        assertThat(members.get(0).userId()).isEqualTo(manualUser.getId());
        assertThat(members.get(0).source()).isEqualTo(UserGroupMembershipSourceType.MANUAL);
    }

    @Test
    void groupFilterByDisplayName() {
        scimPost("/scim/v2/Groups", "{\"displayName\":\"Platform\"}");

        var result = mvc.get().uri("/scim/v2/Groups")
                .param("filter", "displayName eq \"platform\"")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.totalResults").asNumber().isEqualTo(1);
    }

    private UserEntity seedScimUser(String email) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setAuthProvider(AuthProviderType.SCIM);
        user.setRole(UserRoleType.ANALYST);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult scimGet(String uri) {
        return mvc.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult scimPost(String uri,
                                                                                String body) {
        return mvc.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .contentType(MediaType.parseMediaType("application/scim+json"))
                .content(body)
                .exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult scimDelete(String uri) {
        return mvc.delete().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .exchange();
    }
}
