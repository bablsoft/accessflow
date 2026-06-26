package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.BulkItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.RowOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewerNotEligibleException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AttestationReviewControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean AttestationReviewService reviewService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private UserEntity reviewer;
    private String reviewerToken;
    private String analystToken;

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
        org.setSlug("primary-" + UUID.randomUUID());
        organization = organizationRepository.save(org);
        reviewer = saveUser(org, "reviewer@example.com", UserRoleType.REVIEWER);
        reviewerToken = token(reviewer, org);
        analystToken = token(saveUser(org, "analyst@example.com", UserRoleType.ANALYST), org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listItemsReturns200ForReviewer() {
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(new PageResponse<>(List.of(itemView()), 0, 20, 1, 1));
        var res = mvc.get().uri("/api/v1/reviews/attestations/items")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content[0].subject_user_email").asString()
                .isEqualTo("subject@example.com");
    }

    @Test
    void listItemsReturns403ForAnalyst() {
        var res = mvc.get().uri("/api/v1/reviews/attestations/items")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();
        assertThat(res).hasStatus(403);
    }

    @Test
    void certifyReturns200AndWritesAudit() {
        var itemId = UUID.randomUUID();
        when(reviewService.certify(eq(itemId), any(), any()))
                .thenReturn(new ItemDecisionOutcome(itemId, AttestationItemDecision.CERTIFIED, false));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/certify", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"still needed\"}")
                .exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.decision").asString().isEqualTo("CERTIFIED");
        assertThat(res).bodyJson().extractingPath("$.was_idempotent_replay").asBoolean().isFalse();

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo(AuditAction.ATTESTATION_ITEM_CERTIFIED);
        assertThat(audits.get(0).resourceId()).isEqualTo(itemId);
        assertThat(audits.get(0).metadata()).containsEntry("comment", "still needed");
    }

    @Test
    void certifyIdempotentReplayWritesNoAudit() {
        var itemId = UUID.randomUUID();
        when(reviewService.certify(eq(itemId), any(), any()))
                .thenReturn(new ItemDecisionOutcome(itemId, AttestationItemDecision.CERTIFIED, true));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/certify", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(res).hasStatus(200);
        assertThat(auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content()).isEmpty();
    }

    @Test
    void revokeReturns400WhenCommentBlank() {
        var itemId = UUID.randomUUID();
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/revoke", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"\"}").exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void revokeReturns200AndWritesAudit() {
        var itemId = UUID.randomUUID();
        when(reviewService.revoke(eq(itemId), any(), any()))
                .thenReturn(new ItemDecisionOutcome(itemId, AttestationItemDecision.REVOKED, false));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/revoke", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"no longer needed\"}")
                .exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.decision").asString().isEqualTo("REVOKED");
        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo(AuditAction.ATTESTATION_ITEM_REVOKED);
    }

    @Test
    void revokeReturns403WhenSelfReview() {
        var itemId = UUID.randomUUID();
        when(reviewService.revoke(eq(itemId), any(), any())).thenThrow(
                new AttestationReviewerNotEligibleException(reviewer.getId(), itemId));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/revoke", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"x\"}").exchange();
        assertThat(res).hasStatus(403);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ATTESTATION_REVIEWER_NOT_ELIGIBLE");
    }

    @Test
    void certifyReturns404WhenItemMissing() {
        var itemId = UUID.randomUUID();
        when(reviewService.certify(eq(itemId), any(), any()))
                .thenThrow(new AttestationItemNotFoundException(itemId));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/certify", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(res).hasStatus(404);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ATTESTATION_ITEM_NOT_FOUND");
    }

    @Test
    void certifyReturns409WhenCampaignNotOpen() {
        var itemId = UUID.randomUUID();
        when(reviewService.certify(eq(itemId), any(), any())).thenThrow(
                new IllegalAttestationCampaignTransitionException(null, "campaign not open"));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/{id}/certify", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(res).hasStatus(409);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ATTESTATION_CAMPAIGN_INVALID_STATE");
    }

    @Test
    void bulkReturns200WithPerRowOutcomesAndAuditsSuccesses() {
        var ok = UUID.randomUUID();
        var missing = UUID.randomUUID();
        when(reviewService.bulkDecide(any(), eq(AttestationItemDecision.CERTIFIED), any(), any()))
                .thenReturn(new BulkItemDecisionOutcome(List.of(
                        RowOutcome.success(ok,
                                new ItemDecisionOutcome(ok, AttestationItemDecision.CERTIFIED, false)),
                        RowOutcome.failure(missing, AttestationReviewService.RowStatus.NOT_FOUND,
                                "ATTESTATION_ITEM_NOT_FOUND", "not found"))));
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"item_ids":["%s","%s"],"decision":"CERTIFIED","comment":"bulk ok"}
                        """.formatted(ok, missing))
                .exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.results").asArray().hasSize(2);
        // Only the successful, non-replay row is audited.
        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo(AuditAction.ATTESTATION_ITEM_CERTIFIED);
        assertThat(audits.get(0).resourceId()).isEqualTo(ok);
    }

    @Test
    void bulkReturns400WhenDecisionNotTerminal() {
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"item_ids":["%s"],"decision":"PENDING"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void bulkReturns400WhenItemIdsEmpty() {
        var res = mvc.post().uri("/api/v1/reviews/attestations/items/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"item_ids\":[],\"decision\":\"CERTIFIED\"}")
                .exchange();
        assertThat(res).hasStatus(400);
    }

    private AttestationItemView itemView() {
        return new AttestationItemView(UUID.randomUUID(), UUID.randomUUID(), organization.getId(),
                UUID.randomUUID(), UUID.randomUUID(), "Production", UUID.randomUUID(),
                "subject@example.com", "Subject", true, false, false, false, null, null,
                AttestationItemDecision.PENDING, null, null, null, null,
                Instant.parse("2026-07-01T00:00:00Z"));
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
        return jwtService.generateAccessToken(new UserView(user.getId(), user.getEmail(),
                user.getDisplayName(), user.getRole(), org.getId(), true, AuthProviderType.LOCAL,
                user.getPasswordHash(), null, null, false, Instant.now()));
    }
}
