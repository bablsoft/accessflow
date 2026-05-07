package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.partqam.accessflow.proxy.api.QueryExecutionRequest;
import com.partqam.accessflow.proxy.api.QueryExecutor;
import com.partqam.accessflow.proxy.api.SelectExecutionResult;
import com.partqam.accessflow.proxy.api.UpdateExecutionResult;
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
import org.testcontainers.mysql.MySQLContainer;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DefaultQueryExecutorMysqlIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static MySQLContainer customerDb = new MySQLContainer("mysql:8.0")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw")
            .withCommand("--default-authentication-plugin=mysql_native_password");

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
        var cacheDir = com.partqam.accessflow.proxy.internal.driver
                .DriverCacheTestSupport.prepareCacheWithMysql();
        registry.add("accessflow.drivers.cache-dir", cacheDir::toString);
        registry.add("accessflow.drivers.offline", () -> "true");
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
            statement.execute("DROP TABLE IF EXISTS rich");
            statement.execute("""
                    CREATE TABLE rich (
                        id      BINARY(16) PRIMARY KEY,
                        payload JSON NOT NULL,
                        photo   BLOB NOT NULL,
                        qty     INT NOT NULL
                    )""");
            statement.execute("""
                    INSERT INTO rich VALUES (
                        UNHEX(REPLACE('11111111-1111-1111-1111-111111111111','-','')),
                        JSON_OBJECT('k', 1),
                        UNHEX('DEADBEEF'),
                        42
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
    void selectOneAgainstMysql() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT 1", QueryType.SELECT, null, null));

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(((Number) result.rows().getFirst().getFirst()).longValue()).isEqualTo(1L);
    }

    @Test
    void insertReturnsRowsAffectedAgainstMysql() {
        var result = (UpdateExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "INSERT INTO rich VALUES ("
                        + "UNHEX(REPLACE('22222222-2222-2222-2222-222222222222','-','')),"
                        + "JSON_OBJECT('k', 2), UNHEX('CAFEBABE'), 7)",
                QueryType.INSERT, null, null));

        assertThat(result.rowsAffected()).isEqualTo(1);
    }

    @Test
    void selectMapsBlobToBase64AndJsonAsString() {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT payload, photo, qty FROM rich",
                QueryType.SELECT, null, null));

        assertThat(result.rowCount()).isEqualTo(1);
        var row = result.rows().getFirst();
        assertThat(row.get(0).toString()).contains("\"k\":");
        assertThat((String) row.get(1)).startsWith("base64:");
        assertThat(row.get(2)).isEqualTo(42);
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Customer-" + UUID.randomUUID());
        ds.setDbType(DbType.MYSQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(3306));
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
