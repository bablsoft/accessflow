package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The privileged-access report end to end (#968), against the real service: the admin with no
 * permission row at all, a custom role carrying QUERY_ADMIN, direct and group break-glass grants
 * with their expiry, the reader who must not appear, the evidence aggregate, and the audit row.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class PrivilegedAccessControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/privileged-access";
    private static final Instant EXPIRY = Instant.parse("2099-10-01T00:00:00Z");

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired DatasourceGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<UUID> createdQueryIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private DatasourceEntity payments;
    private DatasourceEntity analytics;
    private UserGroupEntity oncall;
    private RoleEntity steward;
    private UserEntity admin;
    private UserEntity auditor;
    private UserEntity customAdmin;
    private UserEntity breakGlassAnalyst;
    private UserEntity groupMember;
    private UserEntity reader;
    private String adminToken;
    private String auditorToken;
    private String readerToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Privileged " + suffix, "privileged-" + suffix);
        steward = saveCustomRole("Data steward " + suffix, Permission.QUERY_ADMIN);
        admin = saveUser("admin-priv-" + suffix + "@example.com", UserRoleType.ADMIN, null);
        auditor = saveUser("auditor-priv-" + suffix + "@example.com", UserRoleType.AUDITOR, null);
        customAdmin = saveUser("steward-priv-" + suffix + "@example.com", null, steward);
        breakGlassAnalyst = saveUser("bg-priv-" + suffix + "@example.com", UserRoleType.ANALYST, null);
        groupMember = saveUser("member-priv-" + suffix + "@example.com", UserRoleType.ANALYST, null);
        reader = saveUser("reader-priv-" + suffix + "@example.com", UserRoleType.ANALYST, null);
        payments = saveDatasource("payments-" + suffix);
        analytics = saveDatasource("analytics-" + suffix);
        oncall = saveGroup("oncall-" + suffix);

        givenDirect(breakGlassAnalyst, payments, true, EXPIRY);
        givenDirect(reader, payments, false, null);
        givenGroupPermission(analytics, true);
        givenMembership(groupMember);
        seedQuery(payments, admin, SubmissionReason.USER_SUBMITTED, "2026-07-01T10:00:00Z");
        seedQuery(payments, admin, SubmissionReason.USER_SUBMITTED, "2026-07-03T10:00:00Z");
        seedQuery(payments, breakGlassAnalyst, SubmissionReason.EMERGENCY_ACCESS, "2026-07-02T10:00:00Z");

        adminToken = generateToken(admin);
        auditorToken = generateToken(auditor);
        readerToken = generateToken(reader);
    }

    @AfterEach
    void cleanup() {
        if (org == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        queryRequestRepository.deleteAllById(createdQueryIds);
        groupPermissionRepository.deleteAll(groupPermissionRepository
                .findAllByDatasource_Id(analytics.getId()));
        permissionRepository.deleteAll(permissionRepository.findAllByDatasource_Id(payments.getId()));
        membershipRepository.deleteAll(membershipRepository.findAllByGroup_Id(oncall.getId()));
        userGroupRepository.deleteById(oncall.getId());
        datasourceRepository.deleteAllById(List.of(payments.getId(), analytics.getId()));
        userRepository.deleteAllById(createdUserIds);
        rolePermissionRepository.deleteAll(rolePermissionRepository.findAllByRole_Id(steward.getId()));
        roleRepository.deleteById(steward.getId());
        organizationRepository.deleteById(org.getId());
        org = null;
    }

    @Test
    void everyBypassHolderAppearsOnceAndTheOrdinaryReaderNever() {
        var result = get(BASE, adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(4);
        assertThat(result).bodyJson().extractingPath("$.content[*].email").asArray()
                .containsExactly(admin.getEmail(), breakGlassAnalyst.getEmail(),
                        groupMember.getEmail(), customAdmin.getEmail())
                .doesNotContain(reader.getEmail(), auditor.getEmail());
    }

    @Test
    void theAdminAppearsAsAQueryAdminBypassWithNoPermissionRowAndItsEvidence() {
        var result = get(BASE + "?user_id=" + admin.getId(), adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].bypass_kinds").asArray()
                .containsExactly("QUERY_ADMIN");
        assertThat(result).bodyJson().extractingPath("$.content[0].query_admin.role_name")
                .isEqualTo("ADMIN");
        assertThat(result).bodyJson().extractingPath("$.content[0].query_admin.system_role")
                .isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.content[0].system_role").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants").asArray()
                .isEmpty();
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.submitted_query_count")
                .asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.last_submitted_at")
                .isEqualTo("2026-07-03T10:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.break_glass_execution_count")
                .asNumber().isEqualTo(0);
        // Explicit null, not an omitted key.
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.last_break_glass_at")
                .isNull();
    }

    @Test
    void aCustomRoleCarryingQueryAdminResolvesLikeTheSystemAdminRole() {
        var result = get(BASE + "?user_id=" + customAdmin.getId(), adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].bypass_kinds").asArray()
                .containsExactly("QUERY_ADMIN");
        assertThat(result).bodyJson().extractingPath("$.content[0].role_name")
                .isEqualTo(steward.getName());
        assertThat(result).bodyJson().extractingPath("$.content[0].system_role").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.content[0].query_admin.role_id")
                .isEqualTo(steward.getId().toString());
        assertThat(result).bodyJson().extractingPath("$.content[0].query_admin.system_role")
                .isEqualTo(false);
    }

    @Test
    void aDirectBreakGlassHolderAppearsWithTheDatasourceExpiryAndBreakGlassEvidence() {
        var result = get(BASE + "?user_id=" + breakGlassAnalyst.getId(), adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].bypass_kinds").asArray()
                .containsExactly("BREAK_GLASS");
        assertThat(result).bodyJson().extractingPath("$.content[0].query_admin").isNull();
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].datasource_name")
                .isEqualTo(payments.getName());
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].source_kind")
                .isEqualTo("DIRECT");
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].group_id")
                .isNull();
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].expires_at")
                .isEqualTo("2099-10-01T00:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.break_glass_execution_count")
                .asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].evidence.last_break_glass_at")
                .isEqualTo("2026-07-02T10:00:00Z");
    }

    @Test
    void aGroupBreakGlassGrantIsReportedThroughTheMemberWithItsGroup() {
        var result = get(BASE + "?user_id=" + groupMember.getId(), adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].datasource_name")
                .isEqualTo(analytics.getName());
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].source_kind")
                .isEqualTo("GROUP");
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].group_name")
                .isEqualTo(oncall.getName());
        assertThat(result).bodyJson().extractingPath("$.content[0].break_glass_grants[0].expires_at")
                .isNull();
    }

    @Test
    void kindFilterSelectsRows() {
        var result = get(BASE + "?kind=BREAK_GLASS", adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].email").asArray()
                .containsExactly(breakGlassAnalyst.getEmail(), groupMember.getEmail());
    }

    @Test
    void aUserWithNoBypassIsAnEmptyPageNotAnError() {
        var result = get(BASE + "?user_id=" + reader.getId(), adminToken);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(0);
    }

    @Test
    void everyReadWritesAnAuditRowWithTheFiltersAndTotal() {
        get(BASE + "?kind=QUERY_ADMIN&user_id=" + admin.getId(), adminToken);

        var rows = jdbcTemplate.queryForList(
                "select metadata::text, resource_id::text as resource_id from audit_log "
                        + "where organization_id = ? and action = ?",
                org.getId(), AuditAction.PRIVILEGED_ACCESS_REPORT_VIEWED.name());
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("metadata")))
                .contains("\"row_count\": 1").contains("QUERY_ADMIN").contains(admin.getId().toString())
                .doesNotContain(admin.getEmail());
        assertThat(rows.get(0).get("resource_id")).isEqualTo(org.getId().toString());
    }

    @Test
    void anAuditorCanReadTheReport() {
        assertThat(get(BASE, auditorToken)).hasStatus(200);
    }

    @Test
    void anAnalystIsForbidden() {
        assertThat(get(BASE, readerToken)).hasStatus(403);
    }

    @Test
    void anonymousIsUnauthorized() {
        assertThat(mvc.get().uri(BASE).exchange()).hasStatus(401);
    }

    @Test
    void aMisspelledKindOrMalformedUserIdIsAClientErrorNotAServerOne() {
        assertThat(get(BASE + "?kind=SUPERUSER", adminToken)).hasStatus(400);
        assertThat(get(BASE + "?user_id=not-a-uuid", adminToken)).hasStatus(400);
    }

    private MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private void givenDirect(UserEntity user, DatasourceEntity ds, boolean breakGlass, Instant expiresAt) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(ds);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCanBreakGlass(breakGlass);
        permission.setExpiresAt(expiresAt);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private void givenGroupPermission(DatasourceEntity ds, boolean breakGlass) {
        var permission = new DatasourceGroupPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setOrganizationId(org.getId());
        permission.setDatasource(ds);
        permission.setGroup(oncall);
        permission.setCanRead(true);
        permission.setCanBreakGlass(breakGlass);
        permission.setCreatedBy(admin);
        groupPermissionRepository.save(permission);
    }

    private void givenMembership(UserEntity user) {
        var membership = new UserGroupMembershipEntity();
        membership.setUser(user);
        membership.setGroup(oncall);
        membershipRepository.save(membership);
    }

    private void seedQuery(DatasourceEntity ds, UserEntity submitter, SubmissionReason reason,
                           String when) {
        var qr = new QueryRequestEntity();
        qr.setId(UUID.randomUUID());
        qr.setDatasource(ds);
        qr.setSubmittedBy(submitter);
        qr.setSqlText("SELECT 1");
        qr.setQueryType(QueryType.SELECT);
        qr.setStatus(QueryStatus.EXECUTED);
        qr.setSubmissionReason(reason);
        qr.setCreatedAt(Instant.parse(when));
        qr.setUpdatedAt(Instant.parse(when));
        createdQueryIds.add(qr.getId());
        queryRequestRepository.save(qr);
    }

    private UserGroupEntity saveGroup(String name) {
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        return userGroupRepository.save(entity);
    }

    private RoleEntity saveCustomRole(String name, Permission permission) {
        var role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setOrganization(org);
        role.setName(name);
        role.setSystem(false);
        roleRepository.save(role);
        rolePermissionRepository.save(new RolePermissionEntity(role, permission));
        return role;
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email, UserRoleType role, RoleEntity roleRef) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setRoleRef(roleRef);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        createdUserIds.add(user.getId());
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
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getRoleRef() == null ? null : entity.getRoleRef().getId(),
                entity.roleName(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.isPlatformAdmin(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
