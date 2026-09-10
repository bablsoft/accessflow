package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * End to end over the real pipeline (#776): approved {@code query_requests} rows go in, the
 * aggregation mines them with the real canonicalizer and the real JSqlParser, and the endpoint
 * serves what each viewer is allowed to see.
 *
 * <p>Deliberately not mocking the service: the enum {@code @Param} binding on the corpus query, the
 * {@code text[]} / {@code uuid[]} round-trip and the {@code query_type} PG enum mapping only fail
 * against a real Postgres.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QuerySuggestionControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired QuerySuggestionRepository suggestionRepository;
    @Autowired QuerySuggestionAggregationService aggregationService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private DatasourceEntity datasource;
    private UserEntity admin;
    private UserEntity analyst;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Suggestions " + suffix, "sugg-" + suffix);
        admin = saveUser("admin-sugg-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-sugg-" + suffix + "@example.com", UserRoleType.ANALYST);
        datasource = saveDatasource("Suggestions-DS-" + suffix);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    /**
     * Scoped to this test's own datasource. A blanket {@code deleteAll} on tables shared with every
     * other integration class fails on an FK violation the moment another class has left a child
     * row ({@code ai_analyses}, {@code review_decisions}, snapshots) pointing at its rows.
     */
    @AfterEach
    void cleanup() {
        if (datasource == null) {
            return;
        }
        suggestionRepository.deleteAll(
                suggestionRepository.findByDatasourceIdOrderByApprovedCountDescLastSubmittedAtDesc(
                        datasource.getId()));
        queryRequestRepository.deleteAll(
                queryRequestRepository.findAll().stream()
                        .filter(q -> datasource.getId().equals(q.getDatasource().getId()))
                        .toList());
        permissionRepository.deleteAll(
                permissionRepository.findAll().stream()
                        .filter(p -> datasource.getId().equals(p.getDatasource().getId()))
                        .toList());
        datasourceRepository.deleteById(datasource.getId());
        datasource = null;
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/query-suggestions";
    }

    @Test
    void approvedHistoryBecomesARankedRailTheAnalystCanSee() {
        saveApproved("SELECT id FROM orders", analyst, 3);
        saveApproved("SELECT total FROM refunds", admin, 2);
        givenGrant(analyst, true, false, false, null);

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.suggestions.length()").asNumber()
                .isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.suggestions[0].approved_count").asNumber()
                .isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.suggestions[0].referenced_tables[0]")
                .asString().isEqualTo("orders");
        assertThat(result).bodyJson().extractingPath("$.suggestions[0].query_type").asString()
                .isEqualTo("SELECT");
    }

    @Test
    void breakGlassAndRecurringHistoryNeverEntersTheCorpus() {
        saveApproved("SELECT id FROM orders", analyst, 2);
        save("SELECT secret FROM payroll", analyst, QueryStatus.EXECUTED,
                SubmissionReason.EMERGENCY_ACCESS, null, 2);
        save("SELECT id FROM nightly", analyst, QueryStatus.EXECUTED, SubmissionReason.RECURRING,
                null, 2);
        save("SELECT id FROM series", analyst, QueryStatus.APPROVED,
                SubmissionReason.USER_SUBMITTED, "PT15M", 2);
        givenGrant(analyst, true, false, false, null);

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.suggestions.length()").asNumber()
                .isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.suggestions[0].referenced_tables[0]")
                .asString().isEqualTo("orders");
    }

    @Test
    void rejectedAndPendingHistoryIsNotAModelOfSafe() {
        save("SELECT id FROM orders", analyst, QueryStatus.REJECTED,
                SubmissionReason.USER_SUBMITTED, null, 3);
        save("SELECT id FROM refunds", analyst, QueryStatus.PENDING_REVIEW,
                SubmissionReason.USER_SUBMITTED, null, 3);
        givenGrant(analyst, true, false, false, null);

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void theAllowListHidesSuggestionsForTablesTheViewerCannotReach() {
        saveApproved("SELECT id FROM orders", analyst, 2);
        saveApproved("SELECT amount FROM payroll", admin, 5);
        givenGrant(analyst, true, false, false, new String[]{"orders"});

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.suggestions.length()").asNumber()
                .isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.suggestions[0].referenced_tables[0]")
                .asString().isEqualTo("orders");
        // The payroll suggestion exists — it is simply not this viewer's to see.
        assertThat(suggestionRepository.count()).isEqualTo(2);
    }

    /**
     * Datasource visibility is itself grant-based ({@code DatasourceRepository.existsVisibleToUser}
     * requires an unexpired direct or group grant), so a viewer with no grant never reaches the
     * rail — they are told the datasource does not exist, exactly as every other per-datasource
     * endpoint tells them. The service's empty-rail branch is the fail-closed guard behind that.
     */
    @Test
    void aViewerWithNoGrantCannotSeeTheDatasourceAtAll() {
        saveApproved("SELECT id FROM orders", admin, 2);

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void queryAdminSeesEveryDatasourceSuggestionWithoutAGrant() {
        saveApproved("SELECT amount FROM payroll", analyst, 2);

        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.suggestions.length()").asNumber()
                .isEqualTo(1);
    }

    @Test
    void limitCapsTheRail() {
        saveApproved("SELECT id FROM orders", analyst, 4);
        saveApproved("SELECT total FROM refunds", analyst, 3);
        givenGrant(analyst, true, false, false, null);
        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        var result = mvc.get().uri(base() + "?limit=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.suggestions.length()").asNumber()
                .isEqualTo(1);
    }

    @Test
    void outOfRangeLimitIsRejected() {
        assertThat(mvc.get().uri(base() + "?limit=51")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(400);
        assertThat(mvc.get().uri(base() + "?limit=-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(400);
    }

    @Test
    void anUnknownDatasourceIsNotFound() {
        var result = mvc.get()
                .uri("/api/v1/datasources/" + UUID.randomUUID() + "/query-suggestions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void recomputeIsAcceptedForAnAdminAndForbiddenForAnAnalyst() {
        assertThat(mvc.post().uri(base() + "/recompute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(202);
        assertThat(mvc.post().uri(base() + "/recompute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThat(mvc.get().uri(base()).exchange()).hasStatus(401);
    }

    @Test
    void aShapeThatDropsOutOfTheHistoryIsSweptOnTheNextPass() {
        saveApproved("SELECT id FROM orders", analyst, 2);
        aggregationService.aggregateDatasource(org.getId(), datasource.getId());
        assertThat(suggestionRepository.count()).isEqualTo(1);

        queryRequestRepository.deleteAll();
        aggregationService.aggregateDatasource(org.getId(), datasource.getId());

        assertThat(suggestionRepository.count()).isZero();
    }

    private void saveApproved(String sql, UserEntity submitter, int times) {
        save(sql, submitter, QueryStatus.EXECUTED, SubmissionReason.USER_SUBMITTED, null, times);
    }

    private void save(String sql, UserEntity submitter, QueryStatus status,
                      SubmissionReason reason, String recurrenceRule, int times) {
        for (int i = 0; i < times; i++) {
            var entity = new QueryRequestEntity();
            entity.setId(UUID.randomUUID());
            entity.setDatasource(datasource);
            entity.setSubmittedBy(submitter);
            entity.setSqlText(sql);
            entity.setQueryType(QueryType.SELECT);
            entity.setStatus(status);
            entity.setSubmissionReason(reason);
            entity.setRecurrenceRule(recurrenceRule);
            entity.setCreatedAt(Instant.now().minus(i + 1L, ChronoUnit.HOURS));
            queryRequestRepository.save(entity);
        }
    }

    private void givenGrant(UserEntity user, boolean read, boolean write, boolean ddl,
                            String[] allowedTables) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(read);
        permission.setCanWrite(write);
        permission.setCanDdl(ddl);
        permission.setAllowedTables(allowedTables);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
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
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource(String name) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
