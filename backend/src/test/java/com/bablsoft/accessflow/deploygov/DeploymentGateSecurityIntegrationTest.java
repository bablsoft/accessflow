package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Proves the #693 machine endpoints ride the API-key channel with zero auth code of their own: a
 * raw {@code af_…} key reaches the domain layer (a domain status, never a 401), and an anonymous
 * call is rejected by the security chain on all three endpoints.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentGateSecurityIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ApiKeyService apiKeyService;

    private MockMvcTester mvc;
    private String rawKey;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        // No deleteAll cleanup: other integration tests sharing the container may have left
        // FK-bearing rows (deploygov group grants → users) that a blanket wipe trips over.
        // Every identifier here is randomized instead, so leftovers never collide.
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Acme-" + UUID.randomUUID());
        org.setSlug("acme-" + UUID.randomUUID());
        organizationRepository.save(org);

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("CI");
        user.setPasswordHash("x");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);

        rawKey = apiKeyService.issue(user.getId(), org.getId(), "ci", null).rawKey();
    }

    @Test
    void apiKeyHeaderReachesTheGateDomainLayer() {
        // 404 (unknown pipeline), not 401: the key authenticated and the domain answered.
        var result = mvc.get()
                .uri("/api/v1/deployment-gate?pipeline=ghost&version=1.0.0&environment=production")
                .header(API_KEY_HEADER, rawKey)
                .exchange();
        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_PIPELINE_NOT_FOUND");
    }

    @Test
    void authorizationApiKeySchemeAlsoAuthenticates() {
        var result = mvc.get()
                .uri("/api/v1/deployment-gate?pipeline=ghost&version=1.0.0&environment=production")
                .header("Authorization", "ApiKey " + rawKey)
                .exchange();
        assertThat(result).hasStatus(404);
    }

    @Test
    void gateValidatesTheParameterCombinationBehindTheKey() {
        var result = mvc.get().uri("/api/v1/deployment-gate?pipeline=only-one")
                .header(API_KEY_HEADER, rawKey)
                .exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_GATE_QUERY_INVALID");
    }

    @Test
    void confirmExecutionAcceptsTheApiKeyChannel() {
        var result = mvc.post()
                .uri("/api/v1/deployment-requests/" + UUID.randomUUID() + "/confirm-execution")
                .header(API_KEY_HEADER, rawKey)
                .exchange();
        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_REQUEST_NOT_FOUND");
    }

    @Test
    void outcomeAcceptsTheApiKeyChannel() {
        var result = mvc.post()
                .uri("/api/v1/deployment-requests/" + UUID.randomUUID() + "/outcome")
                .header(API_KEY_HEADER, rawKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\",\"detail\":\"green\"}")
                .exchange();
        assertThat(result).hasStatus(404);
    }

    @Test
    void anonymousCallsAreRejectedOnAllThreeEndpoints() {
        assertThat(mvc.get()
                .uri("/api/v1/deployment-gate?pipeline=p&version=1&environment=production")
                .exchange()).hasStatus(401);
        assertThat(mvc.post()
                .uri("/api/v1/deployment-requests/" + UUID.randomUUID() + "/confirm-execution")
                .exchange()).hasStatus(401);
        assertThat(mvc.post()
                .uri("/api/v1/deployment-requests/" + UUID.randomUUID() + "/outcome")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\"}")
                .exchange()).hasStatus(401);
    }
}
