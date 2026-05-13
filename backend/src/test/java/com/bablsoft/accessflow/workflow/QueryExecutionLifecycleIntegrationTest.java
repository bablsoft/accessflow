package com.bablsoft.accessflow.workflow;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryNotExecutableException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
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
class QueryExecutionLifecycleIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired QueryLifecycleService queryLifecycleService;
    @Autowired AuditLogService auditLogService;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;
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

        submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("alice-" + UUID.randomUUID() + "@example.com");
        submitter.setDisplayName("Alice");
        submitter.setPasswordHash("h");
        submitter.setRole(UserRoleType.ANALYST);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(organization);
        userRepository.save(submitter);

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
                        ('00000000-0000-0000-0000-000000000001', 'apple',   3),
                        ('00000000-0000-0000-0000-000000000002', 'banana',  4),
                        ('00000000-0000-0000-0000-000000000003', 'carrot',  5)""");
        }
    }

    @AfterEach
    void cleanup() {
        if (datasource != null) {
            poolManager.evict(datasource.getId());
        }
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM query_request_results");
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void executeSelectReachesExecutedAndWritesQueryExecutedAudit() {
        var queryId = persistApprovedQuery("SELECT id, name, qty FROM items ORDER BY qty",
                QueryType.SELECT);

        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(
                queryId, submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(outcome.rowsAffected()).isEqualTo(3L);
        assertThat(outcome.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);

        var reloaded = queryRequestRepository.findById(queryId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(reloaded.getRowsAffected()).isEqualTo(3L);
        assertThat(reloaded.getExecutionStartedAt()).isNotNull();
        assertThat(reloaded.getExecutionCompletedAt()).isNotNull();
        assertThat(reloaded.getExecutionDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(reloaded.getErrorMessage()).isNull();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organization.getId(),
                    new AuditLogQuery(null, AuditAction.QUERY_EXECUTED, null, queryId, null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.getTotalElements()).isEqualTo(1);
            var view = rows.getContent().get(0);
            assertThat(view.actorId()).isEqualTo(submitter.getId());
            assertThat(view.metadata())
                    .containsEntry("rows_affected", 3)
                    .containsKey("duration_ms");
        });
    }

    @Test
    void failedExecutionTransitionsToFailedAndCapturesSqlState() {
        var queryId = persistApprovedQuery("SELECT * FROM does_not_exist", QueryType.SELECT);

        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(
                queryId, submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);
        assertThat(outcome.rowsAffected()).isNull();

        var reloaded = queryRequestRepository.findById(queryId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueryStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isNotBlank();
        assertThat(reloaded.getExecutionStartedAt()).isNotNull();
        assertThat(reloaded.getExecutionCompletedAt()).isNotNull();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organization.getId(),
                    new AuditLogQuery(null, AuditAction.QUERY_FAILED, null, queryId, null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.getTotalElements()).isEqualTo(1);
            var view = rows.getContent().get(0);
            assertThat(view.metadata())
                    .containsEntry("sql_state", "42P01")
                    .containsKey("error")
                    .containsKey("vendor_code");
        });
    }

    @Test
    void executeIsRejectedWhenStatusIsNotApproved() {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        queryRequestRepository.save(query);

        assertThatThrownBy(() -> queryLifecycleService.execute(new ExecuteQueryCommand(
                query.getId(), submitter.getId(), organization.getId(), false)))
                .isInstanceOf(QueryNotExecutableException.class);

        var reloaded = queryRequestRepository.findById(query.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    private UUID persistApprovedQuery(String sql, QueryType type) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText(sql);
        query.setQueryType(type);
        query.setStatus(QueryStatus.APPROVED);
        queryRequestRepository.save(query);
        return query.getId();
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
