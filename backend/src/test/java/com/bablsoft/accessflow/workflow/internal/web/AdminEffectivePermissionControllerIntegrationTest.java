package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
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
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The explorer end to end (#946): the row cap it reports is the one the executor's clamp produces
 * over the real merge, the group grant is named, the read is audited, and it is admin-only.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminEffectivePermissionControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired DatasourceGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired DatasourceUserPermissionLookupService permissionLookupService;
    @Autowired RowCapResolver rowCapResolver;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private OrganizationEntity org;
    private DatasourceEntity datasource;
    private UserGroupEntity group;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity auditor;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Explorer " + suffix, "explorer-" + suffix);
        admin = saveUser(org, "admin-exp-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser(org, "analyst-exp-" + suffix + "@example.com", UserRoleType.ANALYST);
        auditor = saveUser(org, "auditor-exp-" + suffix + "@example.com", UserRoleType.AUDITOR);
        datasource = saveDatasource("Explorer-DS-" + suffix);
        group = saveGroup("analysts-" + suffix);
    }

    @AfterEach
    void cleanup() {
        for (var orgId : orgIds) {
            jdbcTemplate.update("delete from audit_log where organization_id = ?", orgId);
        }
        groupPermissionRepository.deleteAll(groupPermissionRepository
                .findAllByDatasource_Id(datasource.getId()));
        permissionRepository.deleteAll(permissionRepository
                .findAllByDatasource_Id(datasource.getId()));
        membershipRepository.deleteAll(membershipRepository.findAllByGroup_Id(group.getId()));
        userGroupRepository.deleteById(group.getId());
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteAllById(userIds);
        organizationRepository.deleteAllById(orgIds);
    }

    private String uri(UUID userId) {
        return "/api/v1/admin/effective-access/users/" + userId + "/datasources/"
                + datasource.getId();
    }

    @Test
    void theSmallestOverrideWinsAndIsTracedToItsGroup() {
        givenDirect(analyst, 5000);
        var groupGrant = givenGroupPermission(50);
        givenMembership(analyst);

        var result = mvc.get().uri(uri(analyst.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                .exchange();

        assertThat(result).hasStatus(200);
        var enforced = rowCapResolver.resolve(permissionLookupService
                .findFor(analyst.getId(), datasource.getId()).orElseThrow().rowLimitOverride(),
                datasource.getMaxRowsPerQuery()).value();
        assertThat(enforced).isEqualTo(50);
        assertThat(result).bodyJson().extractingPath("$.row_cap.value").asNumber()
                .isEqualTo(enforced);
        assertThat(result).bodyJson().extractingPath("$.row_cap.source").isEqualTo("OVERRIDE");
        assertThat(result).bodyJson().extractingPath("$.row_cap.grant_ids[0]")
                .isEqualTo(groupGrant.toString());
        assertThat(result).bodyJson().extractingPath("$.grants.length()").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.has_grant").isEqualTo(true);
        var audited = jdbcTemplate.queryForList(
                "select metadata::text from audit_log where organization_id = ? and action = ?",
                String.class, org.getId(), AuditAction.EFFECTIVE_PERMISSION_VIEWED.name());
        assertThat(audited).singleElement().asString().contains(analyst.getId().toString());
    }

    @Test
    void anOverrideAboveTheDatasourceCapIsReportedAsClamped() {
        givenDirect(analyst, 5000);

        var result = mvc.get().uri(uri(analyst.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.row_cap.value").asNumber().isEqualTo(1000);
        assertThat(result).bodyJson().extractingPath("$.row_cap.source")
                .isEqualTo("DATASOURCE_CAP");
        assertThat(result).bodyJson().extractingPath("$.row_cap.override").asNumber()
                .isEqualTo(5000);
    }

    @Test
    void neitherAnAnalystNorAnAuditorMayReadIt() {
        assertThat(mvc.get().uri(uri(analyst.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(analyst))
                .exchange()).hasStatus(403);
        assertThat(mvc.get().uri(uri(analyst.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(auditor))
                .exchange()).hasStatus(403);
    }

    @Test
    void aUserInAnotherOrganizationIsNotFound() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var otherOrg = saveOrg("Other " + suffix, "other-" + suffix);
        var stranger = saveUser(otherOrg, "stranger-" + suffix + "@example.com",
                UserRoleType.ANALYST);

        assertThat(mvc.get().uri(uri(stranger.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                .exchange()).hasStatus(404);
    }

    private void givenDirect(UserEntity user, Integer rowLimit) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCanWrite(false);
        permission.setCanDdl(false);
        permission.setCanBreakGlass(false);
        permission.setRowLimitOverride(rowLimit);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private UUID givenGroupPermission(Integer rowLimit) {
        var permission = new DatasourceGroupPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setOrganizationId(org.getId());
        permission.setDatasource(datasource);
        permission.setGroup(group);
        permission.setCanRead(true);
        permission.setCanWrite(false);
        permission.setCanDdl(false);
        permission.setRowLimitOverride(rowLimit);
        permission.setCreatedBy(admin);
        return groupPermissionRepository.save(permission).getId();
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
        var saved = organizationRepository.save(entity);
        orgIds.add(saved.getId());
        return saved;
    }

    private UserEntity saveUser(OrganizationEntity organization, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        var saved = userRepository.save(user);
        userIds.add(saved.getId());
        return saved;
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

    private String token(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
