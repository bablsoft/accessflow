package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DataBudgetUsageSource;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetUsageEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * #942: admin CRUD, the caller's own standing and the admin per-user view, end to end over the
 * real ledger. Identifiers are randomized and cleanup is scoped to what this class created, so it
 * never trips over another class's rows in the shared container.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DataBudgetControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired DataBudgetRepository dataBudgetRepository;
    @Autowired DataBudgetUsageRepository usageRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final List<UUID> createdPermissions = new ArrayList<>();
    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity stranger;
    private DatasourceEntity datasource;
    private DatasourceEntity hidden;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        primaryOrg = saveOrg("Primary", "primary-db-" + suffix);
        otherOrg = saveOrg("Other", "other-db-" + suffix);
        admin = saveUser(primaryOrg, "admin-db-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst-db-" + suffix + "@example.com",
                UserRoleType.ANALYST);
        stranger = saveUser(otherOrg, "stranger-db-" + suffix + "@example.com",
                UserRoleType.ANALYST);
        datasource = saveDatasource(primaryOrg, "DB-DS-" + suffix);
        hidden = saveDatasource(primaryOrg, "DB-HIDDEN-" + suffix);
        grantRead(analyst, datasource);
        adminToken = token(admin);
        analystToken = token(analyst);
    }

    @AfterEach
    void cleanup() {
        var datasourceIds = List.of(datasource.getId(), hidden.getId());
        usageRepository.deleteAll(usageRepository.findAll().stream()
                .filter(u -> datasourceIds.contains(u.getDatasourceId())).toList());
        dataBudgetRepository.deleteAll(dataBudgetRepository.findAll().stream()
                .filter(b -> datasourceIds.contains(b.getDatasourceId())).toList());
        permissionRepository.deleteAllById(createdPermissions);
        datasourceRepository.deleteAllById(datasourceIds);
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId(), stranger.getId()));
        organizationRepository.deleteAllById(List.of(primaryOrg.getId(), otherOrg.getId()));
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/data-budgets";
    }

    @Test
    void createReturns201WithDefaults() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Analysts daily","max_rows":1000,"applies_to_roles":["ANALYST"],
                         "enabled":true}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).contains("/data-budgets/");
        assertThat(result).bodyJson().extractingPath("$.window_minutes").asNumber()
                .isEqualTo(1440);
        assertThat(result).bodyJson().extractingPath("$.breach_action").asString()
                .isEqualTo("REQUIRE_REVIEW");
        assertThat(result).bodyJson().extractingPath("$.applies_to_roles").asArray()
                .containsExactly("ANALYST");
    }

    @Test
    void listUpdateAndDeleteRoundTrip() {
        var id = createBudget("{\"name\":\"B\",\"max_rows\":10,\"enabled\":true}");

        var list = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(list).hasStatus(200);
        assertThat(list).bodyJson().extractingPath("$.content[*].name").asArray()
                .containsExactly("B");

        var update = mvc.put().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"B2","max_bytes":2048,"window_minutes":60,
                         "breach_action":"REJECT","warn_threshold_percent":75,"enabled":false}
                        """)
                .exchange();
        assertThat(update).hasStatus(200);
        assertThat(update).bodyJson().extractingPath("$.breach_action").asString()
                .isEqualTo("REJECT");
        assertThat(update).bodyJson().extractingPath("$.warn_threshold_percent").asNumber()
                .isEqualTo(75);

        var delete = mvc.delete().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(delete).hasStatus(204);
        assertThat(dataBudgetRepository.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void anAnalystCannotManageBudgets() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"B\",\"max_rows\":10}")
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void beanValidationFailuresReturn400() {
        assertThat(post("{\"name\":\"\",\"max_rows\":10}")).hasStatus(400);
        assertThat(post("{\"name\":\"B\",\"max_rows\":0}")).hasStatus(400);
        assertThat(post("{\"name\":\"B\",\"max_rows\":10,\"window_minutes\":30}")).hasStatus(400);
        assertThat(post("{\"name\":\"B\",\"max_rows\":10,\"warn_threshold_percent\":100}"))
                .hasStatus(400);
    }

    @Test
    void aBudgetWithoutAnyLimitReturns422() {
        var result = post("{\"name\":\"B\"}");

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_DATA_BUDGET");
    }

    @Test
    void anAppliesToUserOfAnotherOrgReturns422() {
        var result = post("{\"name\":\"B\",\"max_rows\":10,\"applies_to_user_ids\":[\""
                + stranger.getId() + "\"]}");

        assertThat(result).hasStatus(422);
    }

    @Test
    void anUnknownBudgetReturns404() {
        var result = mvc.put().uri(base() + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"B\",\"max_rows\":10}")
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATA_BUDGET_NOT_FOUND");
    }

    @Test
    void myStandingReflectsTheLedgerOverTheWindow() {
        createBudget("{\"name\":\"Daily\",\"max_rows\":100,\"window_minutes\":60,"
                + "\"breach_action\":\"REJECT\",\"enabled\":true}");
        ledger(analyst, 30, Instant.now().minusSeconds(60));
        // Outside the one-hour window: must not count.
        ledger(analyst, 50, Instant.now().minusSeconds(7_200));

        var result = mvc.get().uri(base() + "/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.remaining_rows").asNumber().isEqualTo(70);
        assertThat(result).bodyJson().extractingPath("$.used_percent").asNumber().isEqualTo(30);
        assertThat(result).bodyJson().extractingPath("$.exhausted").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.budgets[0].used_rows").asNumber()
                .isEqualTo(30);
    }

    @Test
    void myStandingIsEmptyWithoutABudget() {
        var result = mvc.get().uri(base() + "/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.budgets").asArray().isEmpty();
    }

    @Test
    void myStandingOnAnInvisibleDatasourceIs404() {
        var result = mvc.get()
                .uri("/api/v1/datasources/" + hidden.getId() + "/data-budgets/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void anAdminSeesAUsersStandingAcrossDatasources() {
        createBudget("{\"name\":\"Daily\",\"max_rows\":10,\"breach_action\":\"REJECT\","
                + "\"enabled\":true}");
        ledger(analyst, 10, Instant.now().minusSeconds(60));

        var result = mvc.get()
                .uri("/api/v1/admin/users/" + analyst.getId() + "/data-budget-usage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].datasource_name").asString()
                .isEqualTo(datasource.getName());
        assertThat(result).bodyJson().extractingPath("$.content[0].exhausted").asBoolean()
                .isTrue();
        assertThat(result).bodyJson().extractingPath("$.content[0].breach_action").asString()
                .isEqualTo("REJECT");
    }

    @Test
    void theAdminViewHidesUsersOfOtherOrganizationsAndAnalysts() {
        var foreign = mvc.get()
                .uri("/api/v1/admin/users/" + stranger.getId() + "/data-budget-usage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        var forbidden = mvc.get()
                .uri("/api/v1/admin/users/" + analyst.getId() + "/data-budget-usage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();

        assertThat(foreign).hasStatus(404);
        assertThat(forbidden).hasStatus(403);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String body) {
        return mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String createBudget(String body) {
        var result = post(body);
        assertThat(result).hasStatus(201);
        return dataBudgetRepository.findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                primaryOrg.getId(), datasource.getId()).getLast().getId().toString();
    }

    private void ledger(UserEntity user, long rows, Instant at) {
        var entry = new DataBudgetUsageEntity();
        entry.setId(UUID.randomUUID());
        entry.setOrganizationId(primaryOrg.getId());
        entry.setUserId(user.getId());
        entry.setDatasourceId(datasource.getId());
        entry.setRowsRead(rows);
        entry.setBytesRead(rows * 10);
        entry.setSource(DataBudgetUsageSource.QUERY);
        entry.setOccurredAt(at);
        usageRepository.save(entry);
    }

    private void grantRead(UserEntity user, DatasourceEntity ds) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(ds);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCreatedBy(admin);
        createdPermissions.add(permissionRepository.save(permission).getId());
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName(name + " " + suffix);
        org.setSlug(slug);
        return organizationRepository.save(org);
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

    private DatasourceEntity saveDatasource(OrganizationEntity org, String name) {
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

    private String token(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
