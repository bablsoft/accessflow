package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotCancellableException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestNotPendingException;
import com.bablsoft.accessflow.access.api.AccessRequestService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.AccessReviewService;
import com.bablsoft.accessflow.access.api.AccessReviewService.DecisionOutcome;
import com.bablsoft.accessflow.access.api.AccessReviewService.RevocationOutcome;
import com.bablsoft.accessflow.access.api.AccessReviewerNotEligibleException;
import com.bablsoft.accessflow.access.api.InvalidAccessDurationException;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AccessRequestControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean AccessRequestService accessRequestService;
    @MockitoBean AccessReviewService accessReviewService;
    @MockitoBean AuditLogService auditLogService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private String analystToken;
    private String reviewerToken;
    private String adminToken;

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
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        organization = organizationRepository.save(org);
        analystToken = token(saveUser("analyst@x.io", UserRoleType.ANALYST));
        reviewerToken = token(saveUser("reviewer@x.io", UserRoleType.REVIEWER));
        adminToken = token(saveUser("admin@x.io", UserRoleType.ADMIN));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private AccessRequestView view(UUID id, AccessGrantStatus status) {
        return new AccessRequestView(id, organization.getId(), UUID.randomUUID(), "u@x.io",
                UUID.randomUUID(), "db", true, false, false, List.of("public"), null, "PT4H",
                "j", status, null, null, Instant.now(), Instant.now());
    }

    @Test
    void submitReturns201() {
        var id = UUID.randomUUID();
        when(accessRequestService.submit(any())).thenReturn(view(id, AccessGrantStatus.PENDING));

        var response = mvc.post().uri("/api/v1/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"" + UUID.randomUUID()
                        + "\",\"can_read\":true,\"requested_duration\":\"PT4H\","
                        + "\"justification\":\"need access\"}")
                .exchange();

        assertThat(response).hasStatus(201);
        assertThat(response).bodyJson().extractingPath("$.status").asString().isEqualTo("PENDING");
    }

    @Test
    void submitRejectsBodyWithNoCapability() {
        var response = mvc.post().uri("/api/v1/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"" + UUID.randomUUID()
                        + "\",\"requested_duration\":\"PT4H\",\"justification\":\"j\"}")
                .exchange();

        assertThat(response).hasStatus(400);
    }

    @Test
    void submitMapsInvalidDurationTo422() {
        doThrow(new InvalidAccessDurationException("bad")).when(accessRequestService).submit(any());

        var response = mvc.post().uri("/api/v1/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"" + UUID.randomUUID()
                        + "\",\"can_read\":true,\"requested_duration\":\"PT1S\",\"justification\":\"j\"}")
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("INVALID_ACCESS_DURATION");
    }

    @Test
    void listMineReturns200() {
        when(accessRequestService.listMine(any(), any(), any(), any()))
                .thenReturn(PageResponse.empty(0, 20));
        var response = mvc.get().uri("/api/v1/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(response).hasStatus(200);
    }

    @Test
    void listRequestableDatasourcesReturns200() {
        when(accessRequestService.listRequestableDatasources(any())).thenReturn(List.of());
        var response = mvc.get().uri("/api/v1/access-requests/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(response).hasStatus(200);
    }

    @Test
    void cancelReturns204() {
        var response = mvc.delete().uri("/api/v1/access-requests/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(response).hasStatus(204);
    }

    @Test
    void cancelMapsNotFoundTo404() {
        var id = UUID.randomUUID();
        doThrow(new AccessRequestNotFoundException(id))
                .when(accessRequestService).cancel(eq(id), any(), any());
        var response = mvc.delete().uri("/api/v1/access-requests/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(response).hasStatus(404);
    }

    @Test
    void cancelMapsNotCancellableTo409() {
        var id = UUID.randomUUID();
        doThrow(new AccessRequestNotCancellableException(id, AccessGrantStatus.APPROVED))
                .when(accessRequestService).cancel(eq(id), any(), any());
        var response = mvc.delete().uri("/api/v1/access-requests/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(response).hasStatus(409);
    }

    @Test
    void adminListReturns200ForReviewerAnd403ForAnalyst() {
        when(accessReviewService.listPendingForReviewer(any(), any()))
                .thenReturn(PageResponse.empty(0, 20));
        assertThat(mvc.get().uri("/api/v1/admin/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken).exchange())
                .hasStatus(200);
        assertThat(mvc.get().uri("/api/v1/admin/access-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange())
                .hasStatus(403);
        assertThat(mvc.get().uri("/api/v1/admin/access-requests").exchange()).hasStatus(401);
    }

    @Test
    void approveReturns200() {
        var id = UUID.randomUUID();
        when(accessReviewService.approve(eq(id), any(), any())).thenReturn(
                new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                        AccessGrantStatus.APPROVED, false));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}")
                .exchange();
        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("APPROVED");
    }

    @Test
    void approveIdempotentReplaySkipsAuditAndReturns200() {
        var id = UUID.randomUUID();
        when(accessReviewService.approve(eq(id), any(), any())).thenReturn(
                new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                        AccessGrantStatus.APPROVED, true));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}")
                .exchange();
        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.idempotent_replay").isEqualTo(true);
    }

    @Test
    void rejectReturns200() {
        var id = UUID.randomUUID();
        when(accessReviewService.reject(eq(id), any(), any())).thenReturn(
                new DecisionOutcome(UUID.randomUUID(), DecisionType.REJECTED,
                        AccessGrantStatus.REJECTED, false));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/reject", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"denied\"}")
                .exchange();
        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("REJECTED");
    }

    @Test
    void rejectRequiresComment() {
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/reject", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"\"}")
                .exchange();
        assertThat(response).hasStatus(400);
    }

    @Test
    void revokeNoOpSkipsAuditAndReturns200() {
        var id = UUID.randomUUID();
        when(accessReviewService.revoke(eq(id), any(), any()))
                .thenReturn(new RevocationOutcome(AccessGrantStatus.EXPIRED, true));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/revoke", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .exchange();
        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.no_op").isEqualTo(true);
    }

    @Test
    void approveMapsNotPendingTo409() {
        var id = UUID.randomUUID();
        when(accessReviewService.approve(eq(id), any(), any()))
                .thenThrow(new AccessRequestNotPendingException(id, AccessGrantStatus.REJECTED));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}")
                .exchange();
        assertThat(response).hasStatus(409);
    }

    @Test
    void approveMapsNotEligibleTo403() {
        var id = UUID.randomUUID();
        when(accessReviewService.approve(eq(id), any(), any()))
                .thenThrow(new AccessReviewerNotEligibleException(UUID.randomUUID(), id));
        var response = mvc.post().uri("/api/v1/admin/access-requests/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}")
                .exchange();
        assertThat(response).hasStatus(403);
    }

    @Test
    void revokeReturns200ForAdminAnd403ForReviewer() {
        var id = UUID.randomUUID();
        when(accessReviewService.revoke(eq(id), any(), any()))
                .thenReturn(new RevocationOutcome(AccessGrantStatus.REVOKED, false));
        assertThat(mvc.post().uri("/api/v1/admin/access-requests/{id}/revoke", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(200);
        assertThat(mvc.post().uri("/api/v1/admin/access-requests/{id}/revoke", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(403);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private String token(UserEntity user) {
        return jwtService.generateAccessToken(new com.bablsoft.accessflow.core.api.UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                organization.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, null, false, Instant.now()));
    }
}
