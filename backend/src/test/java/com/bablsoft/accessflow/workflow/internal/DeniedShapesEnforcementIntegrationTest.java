package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryShape;
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
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #940 acceptance: a grant denying {@code JOIN} refuses a joined query at submission with 403 before
 * anything is persisted — also inside a transactional batch and through a subquery — while a simple
 * query passes, a grant with no denied shapes is unaffected, and a permissive group grant can never
 * lift the denial.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeniedShapesEnforcementIntegrationTest {

    @Autowired QuerySubmissionService querySubmissionService;
    @Autowired DatasourcePermissionVerifier permissionVerifier;
    @Autowired QueryParser queryParser;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired DatasourceGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity analyst;
    private DatasourceEntity datasource;
    private DatasourceUserPermissionEntity permission;

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Denied shapes");
        organization.setSlug("denied-shapes-" + UUID.randomUUID());
        organizationRepository.save(organization);
        analyst = persistUser();

        datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setOrganization(organization);
        datasource.setName("DS-" + UUID.randomUUID());
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost("nope.invalid");
        datasource.setPort(65000);
        datasource.setDatabaseName("db");
        datasource.setUsername("u");
        datasource.setPasswordEncrypted(encryptionService.encrypt("p"));
        datasource.setSslMode(SslMode.DISABLE);
        datasource.setConnectionPoolSize(5);
        datasource.setMaxRowsPerQuery(1000);
        datasource.setActive(true);
        datasourceRepository.save(datasource);

        permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(analyst);
        permission.setCanRead(true);
        permission.setCanWrite(true);
        permission.setCreatedBy(analyst);
        permission.setDeniedShapes(new String[] {"JOIN"});
        permission = permissionRepository.save(permission);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM query_requests WHERE datasource_id = ?", datasource.getId());
        jdbcTemplate.update("DELETE FROM datasource_user_permissions WHERE datasource_id = ?",
                datasource.getId());
        jdbcTemplate.update("DELETE FROM datasource_group_permissions WHERE datasource_id = ?",
                datasource.getId());
        jdbcTemplate.update("DELETE FROM user_group_memberships WHERE user_id = ?", analyst.getId());
        jdbcTemplate.update("DELETE FROM user_groups WHERE organization_id = ?",
                organization.getId());
        jdbcTemplate.update("DELETE FROM datasources WHERE id = ?", datasource.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", analyst.getId());
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organization.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT c.id FROM customer c JOIN orders o ON o.customer_id = c.id",
            "SELECT * FROM customer c, orders o WHERE o.customer_id = c.id",
            "SELECT id FROM customer WHERE id IN (SELECT o.customer_id FROM orders o JOIN items i ON i.order_id = o.id)",
            "BEGIN; UPDATE customer SET active = false WHERE id = 1; DELETE FROM orders USING customer WHERE orders.customer_id = customer.id; COMMIT;"})
    void aQueryWithADeniedShapeIsRefusedBeforeAnythingIsPersisted(String sql) {
        assertThatThrownBy(() -> submit(sql))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("JOIN");

        assertThat(persistedQueries()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id, name FROM customer WHERE id = 1 ORDER BY name",
            "SELECT count(*) FROM customer",
            "SELECT id FROM customer WHERE id IN (SELECT customer_id FROM orders)"})
    void aQueryWithoutTheDeniedShapePassesTheGate(String sql) {
        var parsed = queryParser.parse(sql, DbType.POSTGRESQL);

        assertThat(parsed.shapesAnalyzed()).isTrue();
        assertThatCode(() -> permissionVerifier.verify(analyst.getId(), datasource.getId(),
                parsed.type(), parsed)).doesNotThrowAnyException();
    }

    @Test
    void aGrantWithNoDeniedShapesLeavesAJoinedQueryAlone() {
        permission.setDeniedShapes(null);
        permissionRepository.save(permission);
        var parsed = queryParser.parse("SELECT c.id FROM customer c JOIN orders o ON o.customer_id = c.id",
                DbType.POSTGRESQL);

        assertThat(parsed.shapes()).contains(QueryShape.JOIN);
        assertThatCode(() -> permissionVerifier.verify(analyst.getId(), datasource.getId(),
                parsed.type(), parsed)).doesNotThrowAnyException();
    }

    @Test
    void aPermissiveGroupGrantCannotLiftTheDirectDenial() {
        var group = new UserGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganization(organization);
        group.setName("everything-" + UUID.randomUUID());
        userGroupRepository.save(group);
        var membership = new UserGroupMembershipEntity();
        membership.setId(new UserGroupMembershipEntity.Id(analyst.getId(), group.getId()));
        membership.setUser(analyst);
        membership.setGroup(group);
        membershipRepository.save(membership);
        var groupPermission = new DatasourceGroupPermissionEntity();
        groupPermission.setId(UUID.randomUUID());
        groupPermission.setOrganizationId(organization.getId());
        groupPermission.setDatasource(datasource);
        groupPermission.setGroup(group);
        groupPermission.setCreatedBy(analyst);
        groupPermission.setCanRead(true);
        groupPermission.setDeniedShapes(new String[] {"UNION"});
        groupPermissionRepository.save(groupPermission);

        assertThatThrownBy(() -> submit("SELECT c.id FROM customer c JOIN orders o ON o.customer_id = c.id"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("JOIN");
        // The group's own denial joins the union too.
        assertThatThrownBy(() -> submit("SELECT id FROM customer UNION SELECT id FROM orders"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("UNION");
        assertThat(persistedQueries()).isZero();
    }

    private int persistedQueries() {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM query_requests WHERE datasource_id = ?", Integer.class,
                datasource.getId());
        return rows == null ? 0 : rows;
    }

    private void submit(String sql) {
        querySubmissionService.submit(new SubmissionInput(datasource.getId(), sql, "j",
                analyst.getId(), organization.getId(), false, null, null, null, null, false));
    }

    private UserEntity persistUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("analyst-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("analyst");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }
}
