package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ErasureRequestControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DeletionRequestRepository deletionRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private UUID datasourceId;
    private String adminToken;
    private String userToken;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        cleanup();
        datasourceId = UUID.randomUUID();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org = organizationRepository.save(org);
        adminToken = token(saveUser(org, "admin@example.com", UserRoleType.ADMIN), org);
        userToken = token(saveUser(org, "user@example.com", UserRoleType.ANALYST), org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM deletion_request_decisions");
        jdbcTemplate.update("DELETE FROM deletion_requests");
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private UUID submitAndAwaitReview(String token, String subject) {
        var res = mvc.post().uri("/api/v1/lifecycle/erasure-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"datasource_id":"%s","subject_type":"EMAIL","subject_identifier":"%s",
                         "reason":"GDPR"}
                        """.formatted(datasourceId, subject))
                .exchange();
        assertThat(res).hasStatus(202);
        var id = deletionRequestRepository.findAll().stream()
                .filter(d -> d.getSubjectIdentifier().equals(subject)).findFirst().orElseThrow()
                .getId();
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(deletionRequestRepository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(ErasureStatus.PENDING_REVIEW));
        return id;
    }

    @Test
    void submitAdvancesToReviewThenAdminApproves() {
        var id = submitAndAwaitReview(userToken, "approve@example.com");
        var res = mvc.post().uri("/api/v1/lifecycle/erasure-reviews/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"approved\"}")
                .exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.status").asString().isEqualTo("APPROVED");
        assertThat(res).bodyJson().extractingPath("$.scope_snapshot").asString()
                .contains("approve@example.com");
    }

    @Test
    void adminRejects() {
        var id = submitAndAwaitReview(userToken, "reject@example.com");
        var res = mvc.post().uri("/api/v1/lifecycle/erasure-reviews/{id}/reject", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"not valid\"}")
                .exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.status").asString().isEqualTo("REJECTED");
    }

    @Test
    void submitterCannotApproveOwnRequest() {
        var id = submitAndAwaitReview(adminToken, "self@example.com");
        var res = mvc.post().uri("/api/v1/lifecycle/erasure-reviews/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();
        assertThat(res).hasStatus(403);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DELETION_REQUEST_SELF_APPROVAL");
    }

    @Test
    void listPendingExcludesOwnAndAdminSees() {
        submitAndAwaitReview(userToken, "queue@example.com");
        var res = mvc.get().uri("/api/v1/lifecycle/erasure-reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void listMineReturnsCallerRequests() {
        submitAndAwaitReview(userToken, "mine@example.com");
        var res = mvc.get().uri("/api/v1/lifecycle/erasure-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void cancelReturns204ThenApprovedCannotCancel() {
        var cancelId = submitAndAwaitReview(userToken, "cancel@example.com");
        assertThat(mvc.post().uri("/api/v1/lifecycle/erasure-requests/{id}/cancel", cancelId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()).hasStatus(204);

        var approvedId = submitAndAwaitReview(userToken, "locked@example.com");
        mvc.post().uri("/api/v1/lifecycle/erasure-reviews/{id}/approve", approvedId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        var res = mvc.post().uri("/api/v1/lifecycle/erasure-requests/{id}/cancel", approvedId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
        assertThat(res).hasStatus(409);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DELETION_REQUEST_INVALID_STATE");
    }

    @Test
    void getReturns404WhenMissing() {
        var res = mvc.get().uri("/api/v1/lifecycle/erasure-requests/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
        assertThat(res).hasStatus(404);
    }

    @Test
    void approveReturns403ForNonAdmin() {
        var id = submitAndAwaitReview(userToken, "forbidden@example.com");
        assertThat(mvc.post().uri("/api/v1/lifecycle/erasure-reviews/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(403);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String token(UserEntity user, OrganizationEntity org) {
        return jwtService.generateAccessToken(new UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, null, false, Instant.now()));
    }
}
