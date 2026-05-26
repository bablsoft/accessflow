package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.workflow.api.QueryNotPendingReviewException;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.BulkDecisionOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowStatus;
import com.bablsoft.accessflow.workflow.api.ReviewerNotEligibleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
class ReviewControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean ReviewService reviewService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private UserEntity reviewer;
    private UserEntity analyst;
    private String reviewerToken;
    private String analystToken;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
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
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        organization = organizationRepository.save(org);

        reviewer = saveUser(org, "reviewer@example.com", UserRoleType.REVIEWER);
        analyst = saveUser(org, "analyst@example.com", UserRoleType.ANALYST);
        reviewerToken = generateToken(reviewer, org);
        analystToken = generateToken(analyst, org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listPendingReturns200ForReviewer() {
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(com.bablsoft.accessflow.core.api.PageResponse.empty(0, 20));

        var response = mvc.get().uri("/api/v1/reviews/pending")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.content").asArray().isEmpty();
    }

    @Test
    void listPendingReturns403ForAnalyst() {
        var response = mvc.get().uri("/api/v1/reviews/pending")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(403);
    }

    @Test
    void listPendingReturns401WithoutToken() {
        var response = mvc.get().uri("/api/v1/reviews/pending").exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void approveReturns200WithDecisionPayload() {
        var queryId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenReturn(new DecisionOutcome(decisionId, DecisionType.APPROVED,
                        QueryStatus.APPROVED, false));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .header("X-Forwarded-For", "203.0.113.10")
                .header(HttpHeaders.USER_AGENT, "junit-ua/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"looks good\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.query_request_id").asString()
                .isEqualTo(queryId.toString());
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("APPROVED");
        assertThat(response).bodyJson().extractingPath("$.decision").asString()
                .isEqualTo("APPROVED");

        var auditRows = auditLogService.query(organization.getId(),
                AuditLogQuery.empty(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 10)).content();
        assertThat(auditRows).hasSize(1);
        var row = auditRows.get(0);
        assertThat(row.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(row.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(row.resourceId()).isEqualTo(queryId);
        assertThat(row.actorId()).isEqualTo(reviewer.getId());
        assertThat(row.organizationId()).isEqualTo(organization.getId());
        assertThat(row.ipAddress()).isEqualTo("203.0.113.10");
        assertThat(row.userAgent()).isEqualTo("junit-ua/1");
        assertThat(row.metadata())
                .containsEntry("comment", "looks good")
                .containsEntry("resulting_status", "APPROVED");
    }

    @Test
    void approveReturns403ForAnalyst() {
        var queryId = UUID.randomUUID();
        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"x\"}")
                .exchange();

        assertThat(response).hasStatus(403);
    }

    @Test
    void approveReturns403WhenSelfApproving() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new AccessDeniedException("A reviewer cannot review their own query request"));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(response).hasStatus(403);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void approveReturns403WhenReviewerNotEligible() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new ReviewerNotEligibleException(reviewer.getId(), queryId));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(response).hasStatus(403);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REVIEWER_NOT_ELIGIBLE");
    }

    @Test
    void approveReturns404WhenQueryMissing() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new QueryRequestNotFoundException(queryId));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_REQUEST_NOT_FOUND");
    }

    @Test
    void approveReturns409WhenQueryNotPendingReview() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new QueryNotPendingReviewException(queryId, QueryStatus.APPROVED));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(response).hasStatus(409);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_NOT_PENDING_REVIEW");
    }

    @Test
    void rejectReturns200WithDecisionPayload() {
        var queryId = UUID.randomUUID();
        when(reviewService.reject(eq(queryId), any(), any()))
                .thenReturn(new DecisionOutcome(UUID.randomUUID(), DecisionType.REJECTED,
                        QueryStatus.REJECTED, false));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/reject", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .header("X-Forwarded-For", "198.51.100.7")
                .header(HttpHeaders.USER_AGENT, "junit-ua/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"too risky\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("REJECTED");

        var auditRows = auditLogService.query(organization.getId(),
                AuditLogQuery.empty(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 10)).content();
        assertThat(auditRows).hasSize(1);
        var row = auditRows.get(0);
        assertThat(row.action()).isEqualTo(AuditAction.QUERY_REJECTED);
        assertThat(row.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(row.resourceId()).isEqualTo(queryId);
        assertThat(row.actorId()).isEqualTo(reviewer.getId());
        assertThat(row.organizationId()).isEqualTo(organization.getId());
        assertThat(row.ipAddress()).isEqualTo("198.51.100.7");
        assertThat(row.userAgent()).isEqualTo("junit-ua/2");
        assertThat(row.metadata())
                .containsEntry("comment", "too risky")
                .containsEntry("resulting_status", "REJECTED");
    }

    @Test
    void rejectReturns400WhenCommentBlank() {
        var queryId = UUID.randomUUID();
        var response = mvc.post().uri("/api/v1/reviews/{queryId}/reject", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectReturns400WhenCommentMissing() {
        var queryId = UUID.randomUUID();
        var response = mvc.post().uri("/api/v1/reviews/{queryId}/reject", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void approveIdempotentReplayWritesNoAuditRow() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenReturn(new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                        QueryStatus.APPROVED, true));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/approve", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"replay\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(auditLogService.query(organization.getId(),
                AuditLogQuery.empty(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 10)).totalElements()).isZero();
    }

    @Test
    void requestChangesReturns200WithDecisionPayload() {
        var queryId = UUID.randomUUID();
        when(reviewService.requestChanges(eq(queryId), any(), any()))
                .thenReturn(new DecisionOutcome(UUID.randomUUID(), DecisionType.REQUESTED_CHANGES,
                        QueryStatus.PENDING_REVIEW, false));

        var response = mvc.post().uri("/api/v1/reviews/{queryId}/request-changes", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"please add a more specific WHERE\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.decision").asString()
                .isEqualTo("REQUESTED_CHANGES");
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void requestChangesReturns400WhenCommentBlank() {
        var queryId = UUID.randomUUID();
        var response = mvc.post().uri("/api/v1/reviews/{queryId}/request-changes", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bulkDecideReturns200WithMixedPerRowOutcomes() {
        var okId = UUID.randomUUID();
        var forbiddenId = UUID.randomUUID();
        var notFoundId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        when(reviewService.bulkDecide(any(), eq(DecisionType.APPROVED), any(), any()))
                .thenReturn(new BulkDecisionOutcome(List.of(
                        RowOutcome.success(okId, new DecisionOutcome(decisionId,
                                DecisionType.APPROVED, QueryStatus.APPROVED, false)),
                        RowOutcome.failure(forbiddenId, RowStatus.FORBIDDEN,
                                "REVIEWER_NOT_ELIGIBLE", "Not eligible"),
                        RowOutcome.failure(notFoundId, RowStatus.NOT_FOUND,
                                "QUERY_REQUEST_NOT_FOUND", "Not found"))));

        var body = "{\"query_ids\":[\"" + okId + "\",\"" + forbiddenId + "\",\""
                + notFoundId + "\"],\"decision\":\"APPROVED\",\"comment\":\"ok\"}";
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .header("X-Forwarded-For", "203.0.113.20")
                .header(HttpHeaders.USER_AGENT, "junit-bulk/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.results[0].status").asString()
                .isEqualTo("SUCCESS");
        assertThat(response).bodyJson().extractingPath("$.results[0].decision").asString()
                .isEqualTo("APPROVED");
        assertThat(response).bodyJson().extractingPath("$.results[1].status").asString()
                .isEqualTo("FORBIDDEN");
        assertThat(response).bodyJson().extractingPath("$.results[1].error_code").asString()
                .isEqualTo("REVIEWER_NOT_ELIGIBLE");
        assertThat(response).bodyJson().extractingPath("$.results[2].status").asString()
                .isEqualTo("NOT_FOUND");

        // Audit log: only the SUCCESS row writes a row.
        var auditRows = auditLogService.query(organization.getId(),
                AuditLogQuery.empty(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 10))
                .content();
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(auditRows.get(0).resourceId()).isEqualTo(okId);
        assertThat(auditRows.get(0).metadata())
                .containsEntry("comment", "ok")
                .containsEntry("resulting_status", "APPROVED");
    }

    @Test
    void bulkDecideReturns400OnEmptyQueryIds() {
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[],\"decision\":\"APPROVED\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bulkDecideReturns400WhenRejectMissingComment() {
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[\"" + UUID.randomUUID()
                        + "\"],\"decision\":\"REJECTED\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bulkDecideReturns400WhenRequestChangesMissingComment() {
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[\"" + UUID.randomUUID()
                        + "\"],\"decision\":\"REQUESTED_CHANGES\",\"comment\":\"   \"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bulkDecideReturns403ForAnalyst() {
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[\"" + UUID.randomUUID()
                        + "\"],\"decision\":\"APPROVED\"}")
                .exchange();

        assertThat(response).hasStatus(403);
    }

    @Test
    void bulkDecideReturns401WithoutToken() {
        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[\"" + UUID.randomUUID()
                        + "\"],\"decision\":\"APPROVED\"}")
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void bulkDecideRequestChangesDoesNotAudit() {
        var id = UUID.randomUUID();
        when(reviewService.bulkDecide(any(), eq(DecisionType.REQUESTED_CHANGES), any(), any()))
                .thenReturn(new BulkDecisionOutcome(List.of(
                        RowOutcome.success(id, new DecisionOutcome(UUID.randomUUID(),
                                DecisionType.REQUESTED_CHANGES, QueryStatus.PENDING_REVIEW,
                                false)))));

        var response = mvc.post().uri("/api/v1/reviews/bulk")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_ids\":[\"" + id + "\"],\"decision\":\"REQUESTED_CHANGES\","
                        + "\"comment\":\"narrower WHERE please\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 10)).totalElements()).isZero();
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

    private String generateToken(UserEntity user, OrganizationEntity org) {
        return jwtService.generateAccessToken(new com.bablsoft.accessflow.core.api.UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, null, false, Instant.now()));
    }
}
