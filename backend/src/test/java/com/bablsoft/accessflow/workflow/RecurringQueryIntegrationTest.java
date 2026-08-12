package com.bablsoft.accessflow.workflow;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
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
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end occurrence loop for recurring approved queries (#627), against a real customer
 * Postgres: the parent stays APPROVED while child occurrence rows execute through the full proxy
 * pipeline, results and snapshots are recorded per occurrence, and the fail-closed halt fires
 * when the submitter has no live permission. Snapshot/result assertions are synchronous —
 * {@code QueryExecutedEvent} is published outside a transaction, so its listeners run inline.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RecurringQueryIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired QueryLifecycleService queryLifecycleService;
    @Autowired com.bablsoft.accessflow.core.api.QueryRequestLookupService queryRequestLookupService;
    @Autowired QuerySnapshotService querySnapshotService;
    @Autowired QueryResultPersistenceService queryResultPersistenceService;
    @Autowired AuditLogService auditLogService;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity adminSubmitter;
    private UserEntity analystWithoutPermission;
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
        // These tests drive executeRecurringOccurrence by hand on rows with an already-due
        // cursor. The real RecurringQueryRunJob also runs in this context and would race the
        // manual calls (an extra child appears whenever a tick lands mid-test), so push its
        // cadence far beyond the test's lifetime. The lone context-startup tick fires before
        // any test row exists.
        registry.add("accessflow.workflow.recurring-run-poll-interval", () -> "PT10H");
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

        adminSubmitter = saveUser("admin", UserRoleType.ADMIN);
        analystWithoutPermission = saveUser("analyst", UserRoleType.ANALYST);
        datasource = saveDatasource();

        try (var connection = DriverManager.getConnection(customerDb.getJdbcUrl(),
                customerDb.getUsername(), customerDb.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS items");
            statement.execute("""
                    CREATE TABLE items (
                        id   uuid PRIMARY KEY,
                        name varchar(64) NOT NULL
                    )""");
            statement.execute("""
                    INSERT INTO items VALUES
                        ('00000000-0000-0000-0000-000000000001', 'apple'),
                        ('00000000-0000-0000-0000-000000000002', 'banana')""");
        }
    }

    @AfterEach
    void cleanup() {
        if (datasource != null) {
            poolManager.evict(datasource.getId());
        }
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM query_request_results");
        // Children reference their parent via recurring_parent_id — delete them first.
        jdbcTemplate.update("DELETE FROM query_requests WHERE recurring_parent_id IS NOT NULL");
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void occurrenceExecutesAsChildWhileParentStaysApproved() {
        var parentId = persistRecurringParent(adminSubmitter, "PT6H",
                Instant.now().plus(Duration.ofDays(1)), Instant.now().minusSeconds(5));

        queryLifecycleService.executeRecurringOccurrence(parentId);

        var parent = queryRequestRepository.findById(parentId).orElseThrow();
        assertThat(parent.getStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(parent.getRecurrenceNextRunAt()).isAfter(Instant.now());
        assertThat(parent.getRecurrenceHaltedReason()).isNull();

        var children = queryRequestRepository.findAll().stream()
                .filter(q -> parentId.equals(q.getRecurringParentId()))
                .toList();
        assertThat(children).hasSize(1);
        var child = children.get(0);
        assertThat(child.getStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(child.getSubmissionReason()).isEqualTo(SubmissionReason.RECURRING);
        assertThat(child.getSqlText()).isEqualTo("SELECT id, name FROM items ORDER BY name");
        assertThat(child.getRowsAffected()).isEqualTo(2L);

        // Per-occurrence artifacts: the results snapshot and the immutable query snapshot both
        // key off the CHILD id, so history never overwrites itself.
        assertThat(queryResultPersistenceService.find(child.getId())).isPresent();
        assertThat(querySnapshotService.find(child.getId(), organization.getId())).isPresent();

        // The occurrence read path runs its real JPQL: org-scoped, newest first.
        var occurrences = queryRequestLookupService.findOccurrences(parentId,
                organization.getId(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 10));
        assertThat(occurrences.totalElements()).isEqualTo(1);
        assertThat(occurrences.content().get(0).id()).isEqualTo(child.getId());
        assertThat(queryRequestLookupService.findOccurrences(parentId, UUID.randomUUID(),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 10)).totalElements()).isZero();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organization.getId(),
                    new AuditLogQuery(null, AuditAction.QUERY_EXECUTED, null, child.getId(),
                            null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
            assertThat(rows.content().get(0).metadata()).containsEntry("trigger", "recurring");
        });
    }

    @Test
    void secondOccurrenceGetsItsOwnChildRow() {
        var parentId = persistRecurringParent(adminSubmitter, "PT6H",
                Instant.now().plus(Duration.ofDays(1)), Instant.now().minusSeconds(5));

        queryLifecycleService.executeRecurringOccurrence(parentId);
        // Force the cursor due again — the job would do this six hours later.
        var parent = queryRequestRepository.findById(parentId).orElseThrow();
        parent.setRecurrenceNextRunAt(Instant.now().minusSeconds(1));
        queryRequestRepository.save(parent);
        queryLifecycleService.executeRecurringOccurrence(parentId);

        var children = queryRequestRepository.findAll().stream()
                .filter(q -> parentId.equals(q.getRecurringParentId()))
                .toList();
        assertThat(children).hasSize(2);
        assertThat(children).allSatisfy(child -> {
            assertThat(child.getStatus()).isEqualTo(QueryStatus.EXECUTED);
            assertThat(queryResultPersistenceService.find(child.getId())).isPresent();
        });
        assertThat(queryRequestRepository.findById(parentId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void haltsFailClosedWhenSubmitterHasNoPermission() {
        var parentId = persistRecurringParent(analystWithoutPermission, "PT6H",
                Instant.now().plus(Duration.ofDays(1)), Instant.now().minusSeconds(5));

        queryLifecycleService.executeRecurringOccurrence(parentId);

        var parent = queryRequestRepository.findById(parentId).orElseThrow();
        assertThat(parent.getStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(parent.getRecurrenceNextRunAt()).isNull();
        assertThat(parent.getRecurrenceHaltedReason()).isNotBlank();
        assertThat(queryRequestRepository.findAll().stream()
                .filter(q -> parentId.equals(q.getRecurringParentId()))).isEmpty();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organization.getId(),
                    new AuditLogQuery(null, AuditAction.RECURRING_SERIES_HALTED, null, parentId,
                            null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
        });
    }

    @Test
    void seriesCompletesQuietlyOncePastRecurrenceUntil() {
        var parentId = persistRecurringParent(adminSubmitter, "PT6H",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(30));

        queryLifecycleService.executeRecurringOccurrence(parentId);

        var parent = queryRequestRepository.findById(parentId).orElseThrow();
        assertThat(parent.getStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(parent.getRecurrenceNextRunAt()).isNull();
        assertThat(parent.getRecurrenceHaltedReason()).isNull();
        assertThat(queryRequestRepository.findAll().stream()
                .filter(q -> parentId.equals(q.getRecurringParentId()))).isEmpty();
    }

    private UUID persistRecurringParent(UserEntity submitter, String rule, Instant until,
                                        Instant nextRunAt) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT id, name FROM items ORDER BY name");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.APPROVED);
        query.setRecurrenceRule(rule);
        query.setRecurrenceUntil(until);
        query.setRecurrenceNextRunAt(nextRunAt);
        queryRequestRepository.save(query);
        return query.getId();
    }

    private UserEntity saveUser(String label, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(label + "-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName(label);
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
