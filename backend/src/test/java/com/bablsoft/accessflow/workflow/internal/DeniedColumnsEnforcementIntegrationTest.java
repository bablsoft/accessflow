package com.bablsoft.accessflow.workflow.internal;

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
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #935 acceptance: a user denied {@code customer.national_id} cannot submit a query that selects
 * it, filters on it, or reaches it through {@code *} — each is refused with 403 before anything is
 * persisted — while a query that stays off the column passes the same gate.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeniedColumnsEnforcementIntegrationTest {

    @Autowired QuerySubmissionService querySubmissionService;
    @Autowired DatasourcePermissionVerifier permissionVerifier;
    @Autowired QueryParser queryParser;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity analyst;
    private DatasourceEntity datasource;

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Denied columns");
        organization.setSlug("denied-columns-" + UUID.randomUUID());
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
        permission.setRestrictedColumns(new String[] {"public.customer.email"});
        permission.setDeniedColumns(new String[] {"public.customer.national_id"});
        permissionRepository.save(permission);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM query_requests WHERE datasource_id = ?", datasource.getId());
        jdbcTemplate.update("DELETE FROM datasource_user_permissions WHERE datasource_id = ?",
                datasource.getId());
        jdbcTemplate.update("DELETE FROM datasources WHERE id = ?", datasource.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", analyst.getId());
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organization.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT national_id FROM customer",
            "SELECT id FROM public.customer WHERE national_id = '1'",
            "SELECT * FROM customer",
            "SELECT c.* FROM customer c JOIN orders o ON o.customer_id = c.id",
            "SELECT x FROM (SELECT national_id AS x FROM customer) t"})
    void aQueryReachingADeniedColumnIsRefusedBeforeAnythingIsPersisted(String sql) {
        assertThatThrownBy(() -> submit(sql))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("public.customer.national_id");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM query_requests WHERE datasource_id = ?", Integer.class,
                datasource.getId());
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id, email FROM customer",
            "SELECT national_id FROM orders",
            "SELECT count(*) FROM customer"})
    void aQueryThatStaysOffTheDeniedColumnPassesTheGate(String sql) {
        var parsed = queryParser.parse(sql, DbType.POSTGRESQL);

        assertThatCode(() -> permissionVerifier.verify(analyst.getId(), datasource.getId(),
                parsed.type(), parsed)).doesNotThrowAnyException();
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
