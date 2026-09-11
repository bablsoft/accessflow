package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Seeding and cleanup shared by the sqlreview web integration tests. Every identifier is randomised
 * and cleanup is scoped to the test's own organization, so the class never interferes with the other
 * classes sharing the Testcontainers database.
 */
abstract class SqlReviewIntegrationTestSupport {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    protected MockMvcTester mvc;
    protected OrganizationEntity org;
    protected final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdDatasourceIds = new ArrayList<>();
    private final List<UUID> createdRoleIds = new ArrayList<>();

    protected void seedOrganization() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("SQL review " + suffix);
        entity.setSlug("sqlreview-" + suffix);
        org = organizationRepository.save(entity);
    }

    protected void cleanupOrganization() {
        if (org == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        jdbcTemplate.update("delete from sql_review_rulesets where organization_id = ?", org.getId());
        for (UUID datasourceId : createdDatasourceIds) {
            permissionRepository.deleteAll(permissionRepository.findAllByDatasource_Id(datasourceId));
        }
        datasourceRepository.deleteAllById(createdDatasourceIds);
        userRepository.deleteAllById(createdUserIds);
        for (UUID roleId : createdRoleIds) {
            rolePermissionRepository.deleteAll(rolePermissionRepository.findAllByRole_Id(roleId));
        }
        roleRepository.deleteAllById(createdRoleIds);
        organizationRepository.deleteById(org.getId());
        org = null;
    }

    protected RoleEntity saveCustomRole(String name, Permission permission) {
        var role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setOrganization(org);
        role.setName(name + " " + suffix);
        role.setSystem(false);
        roleRepository.save(role);
        rolePermissionRepository.save(new RolePermissionEntity(role, permission));
        createdRoleIds.add(role.getId());
        return role;
    }

    protected UserEntity saveUser(String prefix, UserRoleType role, RoleEntity roleRef) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(prefix + "-" + suffix + "@example.com");
        user.setDisplayName(prefix);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setRoleRef(roleRef);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        createdUserIds.add(user.getId());
        userRepository.save(user);
        // The detached instance keeps the real RoleEntity (not a lazy proxy) so generateToken can
        // read the custom role's name outside a session.
        return user;
    }

    protected DatasourceEntity saveDatasource(String name, DbType dbType, DatasourceEnvironment environment) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name + "-" + suffix);
        ds.setDbType(dbType);
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
        ds.setEnvironment(environment);
        createdDatasourceIds.add(ds.getId());
        return datasourceRepository.save(ds);
    }

    protected void grantRead(UserEntity user, DatasourceEntity datasource, UserEntity grantedBy) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCreatedBy(grantedBy);
        permissionRepository.save(permission);
    }

    protected String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getRoleRef() == null ? null : entity.getRoleRef().getId(),
                entity.roleName(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.isPlatformAdmin(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }

    protected long auditRows(String action) {
        var count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where organization_id = ? and action = ?",
                Long.class, org.getId(), action);
        return count == null ? 0 : count;
    }

    protected long auditRows() {
        var count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where organization_id = ?", Long.class, org.getId());
        return count == null ? 0 : count;
    }
}
