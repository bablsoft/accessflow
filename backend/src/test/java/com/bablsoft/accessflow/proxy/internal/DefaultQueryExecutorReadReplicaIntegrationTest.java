package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of multi-replica read routing (AF-457) using three real PostgreSQL containers
 * (primary + two replicas). Each is seeded with a distinct row so the test can prove which
 * database answered a SELECT: reads round-robin across the replicas, a downed replica is skipped
 * in favour of its sibling, INSERT/UPDATE go to the primary, and only when every replica is down
 * does the read fall back to the primary with a
 * {@link AuditAction#DATASOURCE_REPLICA_FALLBACK} audit row.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DefaultQueryExecutorReadReplicaIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer primaryDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("primary_db")
            .withUsername("p_user")
            .withPassword("p-pw");

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer replicaDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("replica_db")
            .withUsername("r_user")
            .withPassword("r-pw");

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer replicaDb2 = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("replica2_db")
            .withUsername("r2_user")
            .withPassword("r2-pw");

    @Autowired QueryExecutor executor;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired AuditLogService auditLogService;

    private OrganizationEntity org;
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
    static void startContainers() {
        primaryDb.start();
        replicaDb.start();
        replicaDb2.start();
    }

    @AfterAll
    static void stopContainers() {
        if (primaryDb.isRunning()) {
            primaryDb.stop();
        }
        if (replicaDb.isRunning()) {
            replicaDb.stop();
        }
        if (replicaDb2.isRunning()) {
            replicaDb2.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);
        datasource = saveDatasource();

        seed(primaryDb, "primary-row");
        seed(replicaDb, "replica-a-row");
        seed(replicaDb2, "replica-b-row");
    }

    @SuppressWarnings("rawtypes")
    private void seed(PostgreSQLContainer container, String label) throws Exception {
        try (var conn = DriverManager.getConnection(container.getJdbcUrl(),
                container.getUsername(), container.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS items");
            stmt.execute("CREATE TABLE items (id uuid PRIMARY KEY, label varchar(64) NOT NULL)");
            stmt.execute("INSERT INTO items VALUES ('00000000-0000-0000-0000-000000000001', '"
                    + label + "')");
        }
    }

    @Test
    void selectsRoundRobinAcrossBothReplicas() {
        var request = new QueryExecutionRequest(datasource.getId(),
                "SELECT label FROM items", QueryType.SELECT, null, null);

        var first = (SelectExecutionResult) executor.execute(request);
        var second = (SelectExecutionResult) executor.execute(request);

        var labels = java.util.Set.of(
                (String) first.rows().get(0).get(0),
                (String) second.rows().get(0).get(0));
        // Consecutive SELECTs alternate between the two replicas; neither hits the primary.
        assertThat(labels).containsExactlyInAnyOrder("replica-a-row", "replica-b-row");
    }

    @Test
    void downedReplicaIsSkippedInFavourOfSibling() {
        // Warm both replica pools.
        var request = new QueryExecutionRequest(datasource.getId(),
                "SELECT label FROM items", QueryType.SELECT, null, null);
        executor.execute(request);
        executor.execute(request);

        replicaDb2.stop();
        // Evict cached pools so the downed endpoint fails fast at pool init instead of waiting
        // out the Hikari connection timeout on a dead pooled connection.
        poolManager.evict(datasource.getId());
        try {
            for (int i = 0; i < 3; i++) {
                var result = (SelectExecutionResult) executor.execute(request);
                assertThat(result.rows().get(0)).containsExactly("replica-a-row");
            }
        } finally {
            replicaDb2.start();
        }
    }

    @Test
    void insertGoesToPrimaryEvenWhenReplicaConfigured() {
        var insertId = UUID.randomUUID();
        var request = new QueryExecutionRequest(datasource.getId(),
                "INSERT INTO items VALUES ('" + insertId + "', 'written-to-primary')",
                QueryType.INSERT, null, null);

        executor.execute(request);

        // The replica was never touched — assert it still shows only the seeded replica row.
        var replicaSelect = new QueryExecutionRequest(datasource.getId(),
                "SELECT label FROM items WHERE id = '" + insertId + "'",
                QueryType.SELECT, null, null);
        var replicaResult = (SelectExecutionResult) executor.execute(replicaSelect);
        assertThat(replicaResult.rows()).isEmpty();
    }

    @Test
    void selectFallsBackToPrimaryWhenAllReplicasUnreachable() {
        // Warm the primary pool so the fallback path isn't constructing a pool from scratch.
        var warmup = new QueryExecutionRequest(datasource.getId(),
                "SELECT label FROM items", QueryType.SELECT, null, null);
        executor.execute(warmup);

        replicaDb.stop();
        replicaDb2.stop();
        poolManager.evict(datasource.getId());
        try {
            // Reload primary pool only; every replica resolve fails after eviction.
            var fallback = new QueryExecutionRequest(datasource.getId(),
                    "SELECT label FROM items", QueryType.SELECT, null, null);
            var result = (SelectExecutionResult) executor.execute(fallback);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).containsExactly("primary-row");

            var audits = auditLogService.query(org.getId(),
                    new AuditLogQuery(null, AuditAction.DATASOURCE_REPLICA_FALLBACK,
                            null, null, null, null),
                    new PageRequest(0, 10, null));
            assertThat(audits.content()).isNotEmpty();
        } finally {
            replicaDb.start();
            replicaDb2.start();
        }
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("With-Replica-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(primaryDb.getHost());
        ds.setPort(primaryDb.getMappedPort(5432));
        ds.setDatabaseName(primaryDb.getDatabaseName());
        ds.setUsername(primaryDb.getUsername());
        ds.setPasswordEncrypted(encryptionService.encrypt(primaryDb.getPassword()));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(3);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        addReplica(ds, replicaDb.getJdbcUrl(), replicaDb.getUsername(),
                encryptionService.encrypt(replicaDb.getPassword()));
        addReplica(ds, replicaDb2.getJdbcUrl(), replicaDb2.getUsername(),
                encryptionService.encrypt(replicaDb2.getPassword()));
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private static void addReplica(DatasourceEntity ds, String jdbcUrl, String username,
                                   String passwordEncrypted) {
        var replica = new com.bablsoft.accessflow.core.internal.persistence.entity
                .DatasourceReadReplicaEntity();
        replica.setId(UUID.randomUUID());
        replica.setDatasource(ds);
        replica.setJdbcUrl(jdbcUrl);
        replica.setUsername(username);
        replica.setPasswordEncrypted(passwordEncrypted);
        replica.setPosition(ds.getReadReplicas().size());
        ds.getReadReplicas().add(replica);
    }
}
