package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.workflow.api.CollaborationNotPermittedException;
import com.bablsoft.accessflow.workflow.api.CollaboratorRef;
import com.bablsoft.accessflow.workflow.api.CommentStatus;
import com.bablsoft.accessflow.workflow.api.QueryCommentNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryCommentService;
import com.bablsoft.accessflow.workflow.api.QueryCommentThreadView;
import com.bablsoft.accessflow.workflow.api.QueryCommentView;
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
class QueryCommentControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean QueryCommentService queryCommentService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private UserEntity reviewer;
    private String reviewerToken;
    private final UUID queryId = UUID.randomUUID();

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
        reviewerToken = generateToken(reviewer, org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listReturns200() {
        when(queryCommentService.listThreads(eq(queryId), any()))
                .thenReturn(List.of(new QueryCommentThreadView(view(CommentStatus.OPEN), List.of())));

        var response = mvc.get().uri("/api/v1/queries/{id}/comments", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$[0].root.body").asString()
                .isEqualTo("needs an index");
    }

    @Test
    void listReturns401WithoutToken() {
        assertThat(mvc.get().uri("/api/v1/queries/{id}/comments", queryId).exchange())
                .hasStatus(401);
    }

    @Test
    void createReturns201AndWritesAudit() {
        var created = view(CommentStatus.OPEN);
        when(queryCommentService.addComment(eq(queryId), any(), any())).thenReturn(created);

        var response = mvc.post().uri("/api/v1/queries/{id}/comments", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .header("X-Forwarded-For", "203.0.113.10")
                .header(HttpHeaders.USER_AGENT, "junit-ua/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchor_start_line\":2,\"anchor_end_line\":4,\"body\":\"needs an index\"}")
                .exchange();

        assertThat(response).hasStatus(201);
        assertThat(response).bodyJson().extractingPath("$.body").asString()
                .isEqualTo("needs an index");

        var rows = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).action()).isEqualTo(AuditAction.QUERY_COMMENT_ADDED);
        assertThat(rows.get(0).resourceType()).isEqualTo(AuditResourceType.QUERY_COMMENT);
        assertThat(rows.get(0).resourceId()).isEqualTo(created.id());
        assertThat(rows.get(0).ipAddress()).isEqualTo("203.0.113.10");
        assertThat(rows.get(0).metadata()).containsEntry("query_id", queryId.toString());
    }

    @Test
    void createValidationErrorReturns400() {
        var response = mvc.post().uri("/api/v1/queries/{id}/comments", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchor_start_line\":2,\"anchor_end_line\":4,\"body\":\"  \"}")
                .exchange();

        assertThat(response).hasStatus(400);
    }

    @Test
    void createForbiddenReturns403() {
        when(queryCommentService.addComment(eq(queryId), any(), any()))
                .thenThrow(new CollaborationNotPermittedException(queryId));

        var response = mvc.post().uri("/api/v1/queries/{id}/comments", queryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchor_start_line\":1,\"anchor_end_line\":1,\"body\":\"x\"}")
                .exchange();

        assertThat(response).hasStatus(403);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("COLLABORATION_FORBIDDEN");
    }

    @Test
    void replyReturns201() {
        var commentId = UUID.randomUUID();
        when(queryCommentService.reply(eq(queryId), eq(commentId), any(), any()))
                .thenReturn(view(CommentStatus.OPEN));

        var response = mvc.post().uri("/api/v1/queries/{id}/comments/{c}/replies", queryId, commentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"agreed\"}")
                .exchange();

        assertThat(response).hasStatus(201);
    }

    @Test
    void resolveReturns200AndWritesAudit() {
        var commentId = UUID.randomUUID();
        when(queryCommentService.resolve(eq(queryId), eq(commentId), any()))
                .thenReturn(view(CommentStatus.RESOLVED));

        var response = mvc.post().uri("/api/v1/queries/{id}/comments/{c}/resolve", queryId, commentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange();

        assertThat(response).hasStatus(200);
        var rows = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(rows.get(0).action()).isEqualTo(AuditAction.QUERY_COMMENT_RESOLVED);
    }

    @Test
    void reopenReturns200() {
        var commentId = UUID.randomUUID();
        when(queryCommentService.reopen(eq(queryId), eq(commentId), any()))
                .thenReturn(view(CommentStatus.OPEN));

        var response = mvc.post().uri("/api/v1/queries/{id}/comments/{c}/reopen", queryId, commentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange();

        assertThat(response).hasStatus(200);
    }

    @Test
    void resolveMissingCommentReturns404() {
        var commentId = UUID.randomUUID();
        when(queryCommentService.resolve(eq(queryId), eq(commentId), any()))
                .thenThrow(new QueryCommentNotFoundException(commentId));

        var response = mvc.post().uri("/api/v1/queries/{id}/comments/{c}/resolve", queryId, commentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_COMMENT_NOT_FOUND");
    }

    private QueryCommentView view(CommentStatus status) {
        return new QueryCommentView(UUID.randomUUID(), queryId, null,
                new CollaboratorRef(reviewer.getId(), "reviewer@example.com", "reviewer@example.com"),
                2, 4, "SELECT 1", "needs an index", status,
                status == CommentStatus.RESOLVED
                        ? new CollaboratorRef(reviewer.getId(), "reviewer@example.com", "reviewer@example.com")
                        : null,
                status == CommentStatus.RESOLVED ? Instant.now() : null,
                Instant.now(), Instant.now());
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
