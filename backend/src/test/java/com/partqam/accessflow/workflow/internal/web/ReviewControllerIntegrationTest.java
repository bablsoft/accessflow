package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.workflow.api.QueryNotPendingReviewException;
import com.partqam.accessflow.workflow.api.ReviewService;
import com.partqam.accessflow.workflow.api.ReviewService.DecisionOutcome;
import com.partqam.accessflow.workflow.api.ReviewerNotEligibleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    @MockitoBean ReviewService reviewService;

    private MockMvcTester mvc;
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
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        reviewer = saveUser(org, "reviewer@example.com", UserRoleType.REVIEWER);
        analyst = saveUser(org, "analyst@example.com", UserRoleType.ANALYST);
        reviewerToken = generateToken(reviewer, org);
        analystToken = generateToken(analyst, org);
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listPendingReturns200ForReviewer() {
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

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
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"too risky\"}")
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("REJECTED");
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
        return jwtService.generateAccessToken(new com.partqam.accessflow.core.api.UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, Instant.now()));
    }
}
