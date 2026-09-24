package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
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
 * #939 acceptance: a user granted {@code allowed_schemas=[crm]} with {@code denied_tables=[crm.salary]}
 * can query every other table in {@code crm} — including one created after the grant — but a query
 * reaching {@code crm.salary} is refused with 403 before anything is persisted, and a more permissive
 * group grant can never lift that denial.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeniedTablesEnforcementIntegrationTest {

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

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Denied tables");
        organization.setSlug("denied-tables-" + UUID.randomUUID());
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

        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(analyst);
        permission.setCanRead(true);
        permission.setCreatedBy(analyst);
        permission.setAllowedSchemas(new String[] {"crm"});
        permission.setDeniedTables(new String[] {"crm.salary"});
        permissionRepository.save(permission);
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
            "SELECT * FROM crm.salary",
            "SELECT c.id FROM crm.customer c JOIN crm.salary s ON s.customer_id = c.id",
            "SELECT id FROM crm.customer WHERE id IN (SELECT customer_id FROM crm.salary)",
            "UPDATE crm.salary SET amount = 0"})
    void aQueryReachingADeniedTableIsRefusedBeforeAnythingIsPersisted(String sql) {
        assertThatThrownBy(() -> submit(sql))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(persistedQueries()).isZero();
    }

    @Test
    void theRefusalNamesTheDeniedTable() {
        assertThatThrownBy(() -> submit("SELECT * FROM crm.salary"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("crm.salary");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM crm.customer",
            "SELECT * FROM crm.table_created_after_the_grant",
            "SELECT c.id FROM crm.customer c JOIN crm.orders o ON o.customer_id = c.id"})
    void anAllowedSiblingOfTheDeniedTablePassesTheGate(String sql) {
        var parsed = queryParser.parse(sql, DbType.POSTGRESQL);

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
        groupPermission.setCanWrite(true);
        groupPermission.setCanDdl(true);
        groupPermissionRepository.save(groupPermission);

        // The group's empty allow-list widens the merged grant to every schema...
        var outsideCrm = queryParser.parse("SELECT * FROM hr.payroll", DbType.POSTGRESQL);
        assertThatCode(() -> permissionVerifier.verify(analyst.getId(), datasource.getId(),
                outsideCrm.type(), outsideCrm)).doesNotThrowAnyException();

        // ...but the direct grant's denial survives the merge.
        assertThatThrownBy(() -> submit("SELECT * FROM crm.salary"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("crm.salary");
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
