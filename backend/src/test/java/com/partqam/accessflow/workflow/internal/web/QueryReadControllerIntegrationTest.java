package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryDetailView;
import com.partqam.accessflow.core.api.QueryListItemView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryResultPersistenceService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.workflow.api.QueryLifecycleService;
import com.partqam.accessflow.workflow.api.QueryLifecycleService.ExecutionOutcome;
import com.partqam.accessflow.workflow.api.QueryNotCancellableException;
import com.partqam.accessflow.workflow.api.QueryNotExecutableException;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryReadControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean QueryRequestLookupService queryRequestLookupService;
    @MockitoBean QueryLifecycleService queryLifecycleService;
    @MockitoBean QueryResultPersistenceService queryResultPersistenceService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity analyst;
    private UserEntity admin;
    private String analystToken;
    private String adminToken;

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

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        analyst = makeUser("analyst@example.com", "Analyst", UserRoleType.ANALYST);
        admin = makeUser("admin@example.com", "Admin", UserRoleType.ADMIN);
        analystToken = tokenFor(analyst);
        adminToken = tokenFor(admin);
    }

    private UserEntity makeUser(String email, String name, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(name);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String tokenFor(UserEntity user) {
        return jwtService.generateAccessToken(new UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, Instant.now()));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    // ── GET /api/v1/queries ─────────────────────────────────────────────────────

    @Test
    void listReturnsPageScopedToCallerAsNonAdmin() {
        var qid = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var item = new QueryListItemView(qid, dsId, "Prod PG", analyst.getId(),
                analyst.getEmail(), analyst.getDisplayName(),
                QueryType.SELECT, QueryStatus.PENDING_AI, RiskLevel.LOW, 12,
                Instant.parse("2026-05-01T10:00:00Z"));
        Page<QueryListItemView> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        when(queryRequestLookupService.findForOrganization(any(), any())).thenReturn(page);

        var response = mvc.get().uri("/api/v1/queries?status=PENDING_AI&size=20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.total_elements").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.content[0].id").asString()
                .isEqualTo(qid.toString());
        assertThat(response).bodyJson().extractingPath("$.content[0].status").asString()
                .isEqualTo("PENDING_AI");
        assertThat(response).bodyJson().extractingPath("$.content[0].submitted_by.email")
                .asString().isEqualTo(analyst.getEmail());
        assertThat(response).bodyJson().extractingPath("$.content[0].datasource.name")
                .asString().isEqualTo("Prod PG");
    }

    @Test
    void listReturns400WhenSizeExceedsMax() {
        var response = mvc.get().uri("/api/v1/queries?size=500")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("BAD_QUERY_LIST");
    }

    @Test
    void listReturns401WithoutToken() {
        var response = mvc.get().uri("/api/v1/queries").exchange();
        assertThat(response).hasStatus(401);
    }

    // ── GET /api/v1/queries/{id} ────────────────────────────────────────────────

    @Test
    void getReturnsDetailForSubmitter() {
        var qid = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var detail = new QueryDetailView(qid, dsId, "Prod PG", org.getId(),
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(),
                "SELECT 1", QueryType.SELECT, QueryStatus.EXECUTED,
                "ticket-42", null, 0L, 12, null,
                "Prod plan", 24,
                List.of(),
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-01T10:00:30Z"));
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));

        var response = mvc.get().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.id").asString().isEqualTo(qid.toString());
        assertThat(response).bodyJson().extractingPath("$.sql_text").asString().isEqualTo("SELECT 1");
        assertThat(response).bodyJson().extractingPath("$.status").asString().isEqualTo("EXECUTED");
        assertThat(response).bodyJson().extractingPath("$.rows_affected").isEqualTo(0);
        assertThat(response).bodyJson().extractingPath("$.review_plan_name").asString()
                .isEqualTo("Prod plan");
        assertThat(response).bodyJson().extractingPath("$.approval_timeout_hours").isEqualTo(24);
    }

    @Test
    void getReturns404WhenNonAdminCallerIsNotSubmitter() {
        var qid = UUID.randomUUID();
        var detail = new QueryDetailView(qid, UUID.randomUUID(), "Prod PG", org.getId(),
                admin.getId(), admin.getEmail(), admin.getDisplayName(),
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI, "x", null,
                null, null, null, null, null, List.of(), Instant.now(), Instant.now());
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));

        var response = mvc.get().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_REQUEST_NOT_FOUND");
    }

    @Test
    void getReturnsDetailForAdminEvenIfNotSubmitter() {
        var qid = UUID.randomUUID();
        var detail = new QueryDetailView(qid, UUID.randomUUID(), "Prod PG", org.getId(),
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(),
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_AI, "x", null,
                null, null, null, null, null, List.of(), Instant.now(), Instant.now());
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));

        var response = mvc.get().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(response).hasStatus(200);
    }

    // ── DELETE /api/v1/queries/{id} ─────────────────────────────────────────────

    @Test
    void cancelReturns204OnSuccess() {
        var qid = UUID.randomUUID();
        doNothing().when(queryLifecycleService).cancel(any());

        var response = mvc.delete().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(204);
    }

    @Test
    void cancelReturns403WhenNotSubmitter() {
        var qid = UUID.randomUUID();
        doThrow(new AccessDeniedException("not submitter"))
                .when(queryLifecycleService).cancel(any());

        var response = mvc.delete().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(403);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void cancelReturns409WhenAlreadyExecuted() {
        var qid = UUID.randomUUID();
        doThrow(new QueryNotCancellableException(qid, QueryStatus.EXECUTED))
                .when(queryLifecycleService).cancel(any());

        var response = mvc.delete().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(409);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_NOT_CANCELLABLE");
    }

    @Test
    void cancelReturns404WhenQueryMissing() {
        var qid = UUID.randomUUID();
        doThrow(new QueryRequestNotFoundException(qid))
                .when(queryLifecycleService).cancel(any());

        var response = mvc.delete().uri("/api/v1/queries/" + qid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_REQUEST_NOT_FOUND");
    }

    // ── POST /api/v1/queries/{id}/execute ───────────────────────────────────────

    @Test
    void executeReturns202WithExecutedStatus() {
        var qid = UUID.randomUUID();
        when(queryLifecycleService.execute(any()))
                .thenReturn(new ExecutionOutcome(qid, QueryStatus.EXECUTED, 5L, 42));

        var response = mvc.post().uri("/api/v1/queries/" + qid + "/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(202);
        assertThat(response).bodyJson().extractingPath("$.status").asString().isEqualTo("EXECUTED");
        assertThat(response).bodyJson().extractingPath("$.rows_affected").isEqualTo(5);
        assertThat(response).bodyJson().extractingPath("$.duration_ms").isEqualTo(42);
    }

    @Test
    void executeReturns409WhenNotApproved() {
        var qid = UUID.randomUUID();
        doThrow(new QueryNotExecutableException(qid, QueryStatus.PENDING_REVIEW))
                .when(queryLifecycleService).execute(any());

        var response = mvc.post().uri("/api/v1/queries/" + qid + "/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(409);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_NOT_EXECUTABLE");
    }

    // ── GET /api/v1/queries/{id}/results ────────────────────────────────────────

    @Test
    void resultsReturnsSlicedRows() {
        var qid = UUID.randomUUID();
        var detail = new QueryDetailView(qid, UUID.randomUUID(), "Prod PG", org.getId(),
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(),
                "SELECT id,name FROM users", QueryType.SELECT, QueryStatus.EXECUTED,
                "x", null, 3L, 12, null, null, null,
                List.of(), Instant.now(), Instant.now());
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));

        var snapshot = new QueryResultPersistenceService.QueryResultSnapshot(
                qid,
                "[{\"name\":\"id\",\"type\":\"int4\"},{\"name\":\"name\",\"type\":\"text\"}]",
                "[[1,\"a\"],[2,\"b\"],[3,\"c\"]]",
                3L, false, 12);
        when(queryResultPersistenceService.find(qid)).thenReturn(Optional.of(snapshot));

        var response = mvc.get().uri("/api/v1/queries/" + qid + "/results?page=0&size=2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.row_count").isEqualTo(3);
        // size=2 + page=0 → only first two rows.
        assertThat(response).bodyJson().extractingPath("$.rows.length()").isEqualTo(2);
        assertThat(response).bodyJson().extractingPath("$.rows[0][0]").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.rows[0][1]").asString().isEqualTo("a");
        assertThat(response).bodyJson().extractingPath("$.rows[1][1]").asString().isEqualTo("b");
    }

    @Test
    void resultsReturns404WhenNoStoredResults() {
        var qid = UUID.randomUUID();
        var detail = new QueryDetailView(qid, UUID.randomUUID(), "Prod PG", org.getId(),
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(),
                "SELECT 1", QueryType.SELECT, QueryStatus.EXECUTED,
                "x", null, null, null, null, null, null,
                List.of(), Instant.now(), Instant.now());
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));
        when(queryResultPersistenceService.find(qid)).thenReturn(Optional.empty());

        var response = mvc.get().uri("/api/v1/queries/" + qid + "/results")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(404);
    }

    @Test
    void resultsReturns422WhenQueryIsNotSelect() {
        var qid = UUID.randomUUID();
        var detail = new QueryDetailView(qid, UUID.randomUUID(), "Prod PG", org.getId(),
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(),
                "UPDATE x SET y=1", QueryType.UPDATE, QueryStatus.EXECUTED,
                "x", null, 1L, 5, null, null, null,
                List.of(), Instant.now(), Instant.now());
        when(queryRequestLookupService.findDetailById(qid, org.getId()))
                .thenReturn(Optional.of(detail));

        var response = mvc.get().uri("/api/v1/queries/" + qid + "/results")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("RESULTS_NOT_AVAILABLE");
    }

}
