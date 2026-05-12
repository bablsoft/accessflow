package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.EditionType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutionTimeoutException;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.UpdateExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DefaultQueryExecutorPostgresIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired QueryExecutor executor;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CredentialEncryptionService encryptionService;

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
    static void startCustomerDb() {
        customerDb.start();
    }

    @AfterAll
    static void stopCustomerDb() {
        customerDb.stop();
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
        org.setSlug("primary");
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);
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
                        ('00000000-0000-0000-0000-000000000003', 'carrot',  5),
                        ('00000000-0000-0000-0000-000000000004', 'date',    6),
                        ('00000000-0000-0000-0000-000000000005', 'eggplant',7)""");
            statement.execute("DROP TABLE IF EXISTS rich_types");
            statement.execute("""
                    CREATE TABLE rich_types (
                        id          uuid PRIMARY KEY,
                        payload     jsonb NOT NULL,
                        photo       bytea NOT NULL,
                        amount      numeric(20,5) NOT NULL,
                        created_at  timestamptz NOT NULL,
                        tags        integer[] NOT NULL
                    )""");
            statement.execute("""
                    INSERT INTO rich_types VALUES (
                        '00000000-0000-0000-0000-0000000000aa',
                        '{"k":1}'::jsonb,
                        '\\xdeadbeef',
                        12345.67890,
                        '2026-05-05T12:00:00Z',
                        '{1,2,3}'
                    )""");
        }
    }

    @AfterEach
    void cleanup() {
        if (datasource != null) {
            poolManager.evict(datasource.getId());
        }
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void selectOneReturnsSingleRow() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT 1", QueryType.SELECT, null, null));

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().getFirst()).isEqualTo(1);
    }

    @Test
    void selectAllReturnsFiveRows() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT id, name, qty FROM items ORDER BY qty",
                QueryType.SELECT, null, null));

        assertThat(result.rowCount()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();
        assertThat(result.columns()).extracting("name").containsExactly("id", "name", "qty");
    }

    @Test
    void overrideMaxRowsTruncatesResult() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT id, name FROM items ORDER BY qty",
                QueryType.SELECT, 3, null));

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void insertReturnsRowsAffected() {
        var result = (UpdateExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "INSERT INTO items VALUES ('00000000-0000-0000-0000-000000000099', 'fig', 8)",
                QueryType.INSERT, null, null));

        assertThat(result.rowsAffected()).isEqualTo(1);
    }

    @Test
    void updateReturnsRowsAffected() {
        var result = (UpdateExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "UPDATE items SET qty = qty + 100 WHERE qty < 6",
                QueryType.UPDATE, null, null));

        assertThat(result.rowsAffected()).isEqualTo(3);
    }

    @Test
    void deleteReturnsRowsAffected() {
        var result = (UpdateExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "DELETE FROM items WHERE qty >= 6",
                QueryType.DELETE, null, null));

        assertThat(result.rowsAffected()).isEqualTo(2);
    }

    @Test
    void ddlReturnsZeroAffected() {
        var result = (UpdateExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "CREATE TABLE temp_t (id int)",
                QueryType.DDL, null, null));

        assertThat(result.rowsAffected()).isEqualTo(0);
    }

    @Test
    void typeFidelityForUuidJsonbBytesNumericTimestampArray() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "SELECT id, payload, photo, amount, created_at, tags FROM rich_types",
                QueryType.SELECT, null, null));

        assertThat(result.rowCount()).isEqualTo(1);
        var row = result.rows().getFirst();
        assertThat(row.get(0)).isEqualTo("00000000-0000-0000-0000-0000000000aa");
        assertThat(row.get(1)).isEqualTo("{\"k\": 1}");
        assertThat((String) row.get(2)).startsWith("base64:");
        assertThat(row.get(3)).isEqualTo(new java.math.BigDecimal("12345.67890"));
        assertThat(row.get(4).toString()).contains("2026-05-05");
        assertThat(row.get(5)).isInstanceOf(java.util.List.class);
    }

    @Test
    void timeoutOverrideForcesQueryExecutionTimeout() {
        assertThatThrownBy(() -> executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "SELECT pg_sleep(5)",
                QueryType.SELECT,
                null,
                Duration.ofSeconds(1))))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void missingTableThrowsFailedWithSqlState() {
        assertThatThrownBy(() -> executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "SELECT * FROM does_not_exist",
                QueryType.SELECT, null, null)))
                .isInstanceOf(QueryExecutionFailedException.class)
                .satisfies(ex -> assertThat(((QueryExecutionFailedException) ex).sqlState())
                        .isEqualTo("42P01"));
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
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
