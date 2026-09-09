package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminGovernanceDomainsControllerIntegrationTest {

    private static final String URI = "/api/v1/admin/governance-domains";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private OrganizationEntity otherOrg;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = saveOrg("Primary");
        otherOrg = saveOrg("Other");
        otherOrg.setGovernsApis(true);
        otherOrg.setGovernsDeployments(true);
        organizationRepository.save(otherOrg);

        adminToken = generateToken(saveUser("admin@example.com", UserRoleType.ADMIN));
        analystToken = generateToken(saveUser("analyst@example.com", UserRoleType.ANALYST));
    }

    @Test
    void returnsBothFlagsForTheCallersOrganization() {
        org.setGovernsApis(true);
        organizationRepository.save(org);

        var result = mvc.get().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.governs_apis").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.governs_deployments").asBoolean().isFalse();
    }

    @Test
    void updatesBothFlagsAndPersistsThemOnTheCallersOwnOrganization() {
        var result = mvc.put().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"governs_apis\":true,\"governs_deployments\":true}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.governs_apis").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.governs_deployments").asBoolean().isTrue();
        var reloaded = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(reloaded.isGovernsApis()).isTrue();
        assertThat(reloaded.isGovernsDeployments()).isTrue();
        // There is no id in the path, so another tenant cannot be reached from this endpoint.
        assertThat(organizationRepository.findById(otherOrg.getId()).orElseThrow().isGovernsApis())
                .isTrue();
    }

    @Test
    void turnsADomainBackOff() {
        org.setGovernsApis(true);
        org.setGovernsDeployments(true);
        organizationRepository.save(org);

        var result = mvc.put().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"governs_apis\":false,\"governs_deployments\":false}")
                .exchange();

        assertThat(result).hasStatus(200);
        var reloaded = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(reloaded.isGovernsApis()).isFalse();
        assertThat(reloaded.isGovernsDeployments()).isFalse();
    }

    @Test
    void rejectsAMissingFlag() {
        var result = mvc.put().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"governs_apis\":true}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void forbidsNonAdminCallers() {
        assertThat(mvc.get().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
        assertThat(mvc.put().uri(URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"governs_apis\":true,\"governs_deployments\":true}")
                .exchange()).hasStatus(403);
    }

    @Test
    void rejectsUnauthenticatedCallers() {
        assertThat(mvc.get().uri(URI).exchange()).hasStatus(401);
    }

    private OrganizationEntity saveOrg(String name) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(name.toLowerCase() + "-" + UUID.randomUUID());
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
