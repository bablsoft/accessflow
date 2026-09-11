package com.bablsoft.accessflow.core.internal.persistence;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V169's backfill (#969) is what makes the new foreign key true for tenants that already hold
 * materialised JIT rows: it copies the exact forward link the materializer always wrote
 * ({@code access_grant_request.granted_permission_id}) onto the permission row, and must leave an
 * admin-created row — and a row a connector request happens to point at — untouched. Fresh
 * containers migrate an empty schema, so this test seeds the pre-migration shape and replays the
 * migration's own UPDATE verbatim, read from the migration file.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class PermissionAccessGrantRequestBackfillIntegrationTest {

    private static final String MIGRATION =
            "db/migration/V169__permission_access_grant_request.sql";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity requester;
    private DatasourceEntity datasource;
    private UUID jitPermissionId;
    private UUID adminPermissionId;
    private UUID connectorLinkedPermissionId;
    private UUID datasourceRequestId;
    private UUID connectorRequestId;

    @BeforeEach
    void seed() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Backfill " + suffix, "backfill-" + suffix);
        admin = saveUser("admin-bf-" + suffix + "@example.com", UserRoleType.ADMIN);
        requester = saveUser("req-bf-" + suffix + "@example.com", UserRoleType.ANALYST);
        var other = saveUser("other-bf-" + suffix + "@example.com", UserRoleType.ANALYST);
        var third = saveUser("third-bf-" + suffix + "@example.com", UserRoleType.ANALYST);
        datasource = saveDatasource("Backfill-DS-" + suffix);

        // Every row starts null, the state the migration's UPDATE finds on an upgraded tenant.
        jitPermissionId = savePermission(requester);
        adminPermissionId = savePermission(other);
        connectorLinkedPermissionId = savePermission(third);

        datasourceRequestId = seedDatasourceRequest(requester.getId(), jitPermissionId);
        // A connector request's granted_permission_id names an api_connector_user_permissions row;
        // pointing one at a datasource permission id proves the datasource_id guard holds.
        connectorRequestId = seedConnectorRequest(third.getId(), connectorLinkedPermissionId);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM access_grant_request WHERE id IN (?, ?)",
                datasourceRequestId, connectorRequestId);
        permissionRepository.deleteAll(permissionRepository
                .findAllByDatasource_Id(datasource.getId()));
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteAll(userRepository.findAllByOrganization_Id(org.getId()));
        organizationRepository.deleteById(org.getId());
    }

    @Test
    void backfillsOnlyTheRowsADatasourceRequestMaterialised() throws IOException {
        // Scoped to the three seeded rows: the shipped statement has no per-row filter (correct
        // for a one-shot migration), but this replay runs in the shared Testcontainers database
        // and must not rewrite other test classes' rows. The join — the part under test — is
        // still taken verbatim, so the scope is appended with AND rather than a second WHERE.
        jdbcTemplate.update(backfillStatement().replace(";", " AND p.id IN (?, ?, ?);"),
                jitPermissionId, adminPermissionId, connectorLinkedPermissionId);

        assertThat(linkOf(jitPermissionId)).isEqualTo(datasourceRequestId);
        assertThat(linkOf(adminPermissionId)).isNull();
        assertThat(linkOf(connectorLinkedPermissionId)).isNull();
    }

    @Test
    void deletingTheRequestClearsTheLinkInsteadOfBlocking() {
        jdbcTemplate.update(
                "UPDATE datasource_user_permissions SET access_grant_request_id = ? WHERE id = ?",
                datasourceRequestId, jitPermissionId);

        jdbcTemplate.update("DELETE FROM access_grant_request WHERE id = ?", datasourceRequestId);

        assertThat(linkOf(jitPermissionId)).isNull();
        assertThat(permissionRepository.findById(jitPermissionId)).isPresent();
    }

    /** The UPDATE from V169, taken verbatim so the test cannot drift from the shipped statement. */
    private static String backfillStatement() throws IOException {
        var sql = new String(new ClassPathResource(MIGRATION).getContentAsByteArray(),
                StandardCharsets.UTF_8);
        // Guard the extraction: it takes the FIRST UPDATE up to the FIRST semicolon, so a
        // migration later split into two statements would silently under-cover and stay green.
        assertThat(sql.split("UPDATE datasource_user_permissions", -1).length - 1)
                .as("V169 must contain exactly one backfill UPDATE — "
                        + "this test replays only the first")
                .isEqualTo(1);
        var start = sql.indexOf("UPDATE datasource_user_permissions");
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private UUID linkOf(UUID permissionId) {
        return jdbcTemplate.queryForObject(
                "SELECT access_grant_request_id FROM datasource_user_permissions WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, UUID.class), permissionId);
    }

    private UUID seedDatasourceRequest(UUID requesterId, UUID grantedPermissionId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_grant_request (id, organization_id, requester_id, datasource_id,
                    can_read, requested_duration, status, expires_at, granted_permission_id)
                VALUES (?, ?, ?, ?, true, 'PT4H', 'APPROVED'::access_grant_status,
                    now() + interval '1 hour', ?)
                """, id, org.getId(), requesterId, datasource.getId(), grantedPermissionId);
        return id;
    }

    private UUID seedConnectorRequest(UUID requesterId, UUID grantedPermissionId) {
        var id = UUID.randomUUID();
        // connector_id is a bare UUID (V113), so no api_connectors row is needed.
        jdbcTemplate.update("""
                INSERT INTO access_grant_request (id, organization_id, requester_id, connector_id,
                    can_read, requested_duration, status, expires_at, granted_permission_id)
                VALUES (?, ?, ?, ?, true, 'PT4H', 'APPROVED'::access_grant_status,
                    now() + interval '1 hour', ?)
                """, id, org.getId(), requesterId, UUID.randomUUID(), grantedPermissionId);
        return id;
    }

    private UUID savePermission(UserEntity user) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCanWrite(false);
        permission.setCanDdl(false);
        permission.setCanBreakGlass(false);
        permission.setExpiresAt(Instant.now().plusSeconds(3600));
        permission.setCreatedBy(admin);
        return permissionRepository.save(permission).getId();
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
        user.setPasswordHash("not-a-real-hash");
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
}
