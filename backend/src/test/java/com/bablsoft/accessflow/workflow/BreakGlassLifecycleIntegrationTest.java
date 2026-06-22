package com.bablsoft.accessflow.workflow;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.workflow.api.BreakGlassAdminService;
import com.bablsoft.accessflow.workflow.api.BreakGlassNotPermittedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassInput;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import com.bablsoft.accessflow.workflow.api.SelfAcknowledgeNotAllowedException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class BreakGlassLifecycleIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired BreakGlassService breakGlassService;
    @Autowired BreakGlassAdminService breakGlassAdminService;
    @Autowired AuditLogService auditLogService;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private UserEntity admin;
    private DatasourceEntity datasource;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeAll
    static void startCustomerDb() {
        customerDb.start();
    }

    @AfterAll
    static void stopCustomerDb() {
        customerDb.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanup();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        submitter = saveUser("alice", UserRoleType.ANALYST);
        admin = saveUser("admin", UserRoleType.ADMIN);
        datasource = saveDatasource();

        try (var connection = DriverManager.getConnection(customerDb.getJdbcUrl(),
                customerDb.getUsername(), customerDb.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS items");
            statement.execute("""
                    CREATE TABLE items (
                        id   uuid PRIMARY KEY,
                        name varchar(64) NOT NULL,
                        qty  integer NOT NULL
                    )""");
            statement.execute("""
                    INSERT INTO items VALUES
                        ('00000000-0000-0000-0000-000000000001', 'apple',  3),
                        ('00000000-0000-0000-0000-000000000002', 'banana', 4)""");
        }
    }

    @AfterEach
    void cleanup() {
        if (datasource != null) {
            poolManager.evict(datasource.getId());
        }
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM query_request_results");
        jdbcTemplate.update("DELETE FROM break_glass_events");
        permissionRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void breakGlassExecutesImmediatelyOpensRetroReviewAndAuditsProminently() {
        grantBreakGlass(submitter);

        var result = breakGlassService.breakGlassExecute(new BreakGlassInput(
                datasource.getId(), "SELECT id, name, qty FROM items ORDER BY qty",
                "prod is on fire", submitter.getId(), organization.getId(), false,
                "10.0.0.1", "agent"));

        assertThat(result.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(result.rowsAffected()).isEqualTo(2L);

        var query = queryRequestRepository.findById(result.queryRequestId()).orElseThrow();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.EXECUTED);

        var event = breakGlassAdminService.get(organization.getId(), result.eventId());
        assertThat(event.status()).isEqualTo(BreakGlassStatus.PENDING_REVIEW);
        assertThat(event.justification()).isEqualTo("prod is on fire");
        assertThat(event.executionStatus()).isEqualTo(QueryStatus.EXECUTED);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organization.getId(),
                    new AuditLogQuery(null, AuditAction.QUERY_BREAK_GLASS_EXECUTED, null,
                            result.queryRequestId(), null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
            assertThat(rows.content().get(0).metadata()).containsEntry("break_glass", true);
        });
    }

    @Test
    void adminAcknowledgeClosesRetroReview() {
        grantBreakGlass(submitter);
        var result = breakGlassService.breakGlassExecute(new BreakGlassInput(
                datasource.getId(), "SELECT 1 FROM items LIMIT 1", "incident",
                submitter.getId(), organization.getId(), false, null, null));

        var reviewed = breakGlassAdminService.acknowledge(organization.getId(), result.eventId(),
                admin.getId(), "reconciled with incident #42");

        assertThat(reviewed.status()).isEqualTo(BreakGlassStatus.REVIEWED);
        assertThat(reviewed.reviewedByUserId()).isEqualTo(admin.getId());
        assertThat(reviewed.reviewComment()).isEqualTo("reconciled with incident #42");
    }

    @Test
    void submitterCannotAcknowledgeOwnEvent() {
        grantBreakGlass(submitter);
        var result = breakGlassService.breakGlassExecute(new BreakGlassInput(
                datasource.getId(), "SELECT 1 FROM items LIMIT 1", "incident",
                submitter.getId(), organization.getId(), false, null, null));

        assertThatThrownBy(() -> breakGlassAdminService.acknowledge(organization.getId(),
                result.eventId(), submitter.getId(), null))
                .isInstanceOf(SelfAcknowledgeNotAllowedException.class);

        assertThat(breakGlassAdminService.get(organization.getId(), result.eventId()).status())
                .isEqualTo(BreakGlassStatus.PENDING_REVIEW);
    }

    @Test
    void deniedWhenPermissionLacksBreakGlassFlag() {
        // submitter can read the datasource but was not granted break-glass.
        grantReadOnly(submitter);

        assertThatThrownBy(() -> breakGlassService.breakGlassExecute(new BreakGlassInput(
                datasource.getId(), "SELECT 1 FROM items", "no grant",
                submitter.getId(), organization.getId(), false, null, null)))
                .isInstanceOf(BreakGlassNotPermittedException.class);

        assertThat(queryRequestRepository.count()).isZero();
    }

    private void grantBreakGlass(UserEntity user) {
        savePermission(user, true);
    }

    private void grantReadOnly(UserEntity user) {
        savePermission(user, false);
    }

    private void savePermission(UserEntity user, boolean canBreakGlass) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(true);
        permission.setCanBreakGlass(canBreakGlass);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private UserEntity saveUser(String name, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName(name);
        user.setPasswordHash("h");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("Customer-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(5432));
        ds.setDatabaseName(customerDb.getDatabaseName());
        ds.setUsername(customerDb.getUsername());
        ds.setPasswordEncrypted(encryptionService.encrypt(customerDb.getPassword()));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(3);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }
}
