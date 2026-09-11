package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The reverse index end to end (AF-859) — group inheritance through a real membership row, the
 * QUERY_ADMIN row that appears with no permission at all, and the auditor's read-only access.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminEffectiveAccessControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/effective-access";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired AccessGrantRequestRepository accessGrantRequestRepository;
    @Autowired DatasourceGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private DatasourceEntity datasource;
    private UserGroupEntity group;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity groupMember;
    private UserEntity outsider;
    private UserEntity auditor;
    private String adminToken;
    private String analystToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Reverse " + suffix, "reverse-" + suffix);
        admin = saveUser("admin-rev-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-rev-" + suffix + "@example.com", UserRoleType.ANALYST);
        groupMember = saveUser("member-rev-" + suffix + "@example.com", UserRoleType.ANALYST);
        outsider = saveUser("outsider-rev-" + suffix + "@example.com", UserRoleType.ANALYST);
        auditor = saveUser("auditor-rev-" + suffix + "@example.com", UserRoleType.AUDITOR);
        datasource = saveDatasource("Reverse-DS-" + suffix);
        group = saveGroup("payments-oncall-" + suffix);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
        auditorToken = generateToken(auditor);
    }

    @AfterEach
    void cleanup() {
        if (datasource == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        groupPermissionRepository.deleteAll(groupPermissionRepository
                .findAllByDatasource_Id(datasource.getId()));
        permissionRepository.deleteAll(permissionRepository
                .findAllByDatasource_Id(datasource.getId()));
        jdbcTemplate.update("delete from access_grant_request where datasource_id = ?",
                datasource.getId());
        membershipRepository.deleteAll(membershipRepository.findAllByGroup_Id(group.getId()));
        userGroupRepository.deleteById(group.getId());
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId(), groupMember.getId(),
                outsider.getId(), auditor.getId()));
        organizationRepository.deleteById(org.getId());
        datasource = null;
    }

    private String uri(String table, String capability) {
        return BASE + "?datasource_id=" + datasource.getId() + "&table=" + table
                + "&capability=" + capability;
    }

    @Test
    void aDirectWriterAndAGroupWriterBothAppearWithTheirProvenance() {
        givenDirect(analyst, false, true, null, new String[] {"public.payments"}, false);
        givenGroupPermission(false, true, new String[] {"public"}, null);
        givenMembership(groupMember);

        var result = mvc.get().uri(uri("public.payments", "WRITE"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // The admin is here too — QUERY_ADMIN, with no permission row.
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(3);
        assertThat(result).bodyJson().extractingPath(
                        "$.content[?(@.email == '" + analyst.getEmail() + "')].sources[0].kind")
                .asArray().containsExactly("DIRECT_PERMISSION");
        assertThat(result).bodyJson().extractingPath(
                        "$.content[?(@.email == '" + groupMember.getEmail() + "')].sources[0].kind")
                .asArray().containsExactly("GROUP_PERMISSION");
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                        + groupMember.getEmail() + "')].sources[0].covering_allow_list_entry")
                .asArray().containsExactly("public");
    }

    @Test
    void aQueryAdminHolderWithZeroPermissionRowsIsListedForATableTheyCanWrite() {
        var result = mvc.get().uri(uri("public.payments", "WRITE"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(permissionRepository.findAllByDatasource_Id(datasource.getId())).isEmpty();
        assertThat(result).bodyJson().extractingPath("$.content[0].email").asString()
                .isEqualTo(admin.getEmail());
        assertThat(result).bodyJson().extractingPath("$.content[0].sources[0].kind").asString()
                .isEqualTo("QUERY_ADMIN_BYPASS");
        assertThat(result).bodyJson().extractingPath("$.content[0].table_scope").asString()
                .isEqualTo("ALL_TABLES");
    }

    @Test
    void aUserWhoCannotReachTheTableIsAbsent() {
        givenDirect(analyst, true, false, null, new String[] {"orders"}, false);

        var result = mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).bodyJson().extractingPath(
                "$.content[?(@.email == '" + analyst.getEmail() + "')]").asArray().isEmpty();
    }

    @Test
    void breakGlassIsReportedWithoutGrantingOrdinaryAccess() {
        givenDirect(analyst, true, false, new String[] {"public"}, null, true);

        var result = mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                + analyst.getEmail() + "')].can_break_glass").asArray().containsExactly(true);
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                        + analyst.getEmail() + "')].sources[1].kind")
                .asArray().containsExactly("BREAK_GLASS");
    }

    @Test
    void aJitMaterialisedRowIsLabelledFromTheForeignKey() {
        var grantId = givenApprovedGrant(analyst, true);
        givenJitRow(analyst, grantId);

        var result = mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                + analyst.getEmail() + "')].sources[0].kind").asArray().containsExactly("JIT_GRANT");
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                        + analyst.getEmail() + "')].sources[0].pre_approve_queries")
                .asArray().containsExactly(true);
    }

    @Test
    void aJitRowWhoseGrantDoesNotPreApproveIsStillJitButDoesNotPreApprove() {
        var grantId = givenApprovedGrant(analyst, false);
        givenJitRow(analyst, grantId);

        var result = mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                + analyst.getEmail() + "')].sources[0].kind").asArray().containsExactly("JIT_GRANT");
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                        + analyst.getEmail() + "')].sources[0].pre_approve_queries")
                .asArray().containsExactly(false);
    }

    @Test
    void aTimeBoxedAdminRowNextToAnUnrelatedActiveGrantStaysADirectPermission() {
        // Same user, same datasource, an active pre-approving grant — but the row was not
        // materialised from it, so the pre-#969 correlation would have mislabelled it.
        givenApprovedGrant(analyst, true);
        givenDirect(analyst, true, false, new String[] {"public"}, null, false);

        var result = mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[?(@.email == '"
                        + analyst.getEmail() + "')].sources[0].kind")
                .asArray().containsExactly("DIRECT_PERMISSION");
    }

    @Test
    void everyReadWritesAnAuditRow() {
        mvc.get().uri(uri("public.payments", "WRITE"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        var rows = jdbcTemplate.queryForList(
                "select metadata::text from audit_log where organization_id = ? and action = ?",
                org.getId(), AuditAction.ACCESS_SIMULATION_RUN.name());
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("metadata")))
                .contains("public.payments").contains("WRITE");
    }

    @Test
    void anAuditorCanReadTheReport() {
        assertThat(mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange()).hasStatus(200);
    }

    @Test
    void anAnalystIsForbidden() {
        assertThat(mvc.get().uri(uri("public.payments", "READ"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThat(mvc.get().uri(uri("public.payments", "READ")).exchange()).hasStatus(401);
    }

    @Test
    void anUnknownDatasourceIsNotFound() {
        assertThat(mvc.get()
                .uri(BASE + "?datasource_id=" + UUID.randomUUID()
                        + "&table=public.payments&capability=READ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(404);
    }

    @Test
    void aMisspelledCapabilityIsAClientErrorNotAServerOne() {
        assertThat(mvc.get().uri(uri("public.payments", "WRIET"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(400);
    }

    @Test
    void aTableThatNormalizesAwayIsRejected() {
        // Passed as a parameter rather than inlined in the URI so no encoding sits between the
        // test and the normalization rule it is exercising.
        assertThat(mvc.get().uri(BASE)
                .param("datasource_id", datasource.getId().toString())
                .param("table", "[]")
                .param("capability", "READ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(400);
    }

    @Test
    void aMissingRequiredParameterIsAClientErrorNotAServerOne() {
        // The endpoint advertises 400 for this; nothing maps it globally, so without the local
        // handler the security module's Exception catch-all would answer 500.
        for (var query : List.of(
                "?table=public.payments&capability=READ",
                "?datasource_id=" + datasource.getId() + "&capability=READ",
                "?datasource_id=" + datasource.getId() + "&table=public.payments")) {
            assertThat(mvc.get().uri(BASE + query)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .exchange()).hasStatus(400);
        }
    }

    @Test
    void aMalformedDatasourceIdIsAClientErrorNotAServerOne() {
        assertThat(mvc.get()
                .uri(BASE + "?datasource_id=not-a-uuid&table=public.payments&capability=READ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(400);
    }

    private void givenDirect(UserEntity user, boolean read, boolean write, String[] schemas,
                             String[] tables, boolean breakGlass) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(read);
        permission.setCanWrite(write);
        permission.setCanDdl(false);
        permission.setCanBreakGlass(breakGlass);
        permission.setAllowedSchemas(schemas);
        permission.setAllowedTables(tables);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    /** A time-boxed direct row stamped with the JIT request it materialises (#969). */
    private void givenJitRow(UserEntity user, UUID accessGrantRequestId) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCanWrite(false);
        permission.setCanDdl(false);
        permission.setCanBreakGlass(false);
        permission.setAllowedSchemas(new String[] {"public"});
        permission.setExpiresAt(Instant.now().plusSeconds(3600));
        permission.setAccessGrantRequestId(accessGrantRequestId);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private UUID givenApprovedGrant(UserEntity requester, boolean preApproveQueries) {
        var request = new AccessGrantRequestEntity();
        request.setId(UUID.randomUUID());
        request.setOrganizationId(org.getId());
        request.setRequesterId(requester.getId());
        request.setDatasourceId(datasource.getId());
        request.setCanRead(true);
        request.setAllowedSchemas(new String[] {"public"});
        request.setPreApproveQueries(preApproveQueries);
        request.setRequestedDuration("PT4H");
        request.setJustification("effective-access JIT case");
        request.setStatus(AccessGrantStatus.APPROVED);
        request.setExpiresAt(Instant.now().plusSeconds(3600));
        return accessGrantRequestRepository.save(request).getId();
    }

    private void givenGroupPermission(boolean read, boolean write, String[] schemas,
                                      String[] tables) {
        var permission = new DatasourceGroupPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setOrganizationId(org.getId());
        permission.setDatasource(datasource);
        permission.setGroup(group);
        permission.setCanRead(read);
        permission.setCanWrite(write);
        permission.setCanDdl(false);
        permission.setAllowedSchemas(schemas);
        permission.setAllowedTables(tables);
        permission.setCreatedBy(admin);
        groupPermissionRepository.save(permission);
    }

    private void givenMembership(UserEntity user) {
        var membership = new UserGroupMembershipEntity();
        membership.setUser(user);
        membership.setGroup(group);
        membershipRepository.save(membership);
    }

    private UserGroupEntity saveGroup(String name) {
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        return userGroupRepository.save(entity);
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
