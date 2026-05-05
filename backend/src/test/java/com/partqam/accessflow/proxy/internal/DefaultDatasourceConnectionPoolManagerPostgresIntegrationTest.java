package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.PoolInitializationException;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DefaultDatasourceConnectionPoolManagerPostgresIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired DatasourceConnectionPoolManager manager;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;

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
        registry.add("accessflow.proxy.connection-timeout", () -> "2s");
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
    void setUp() {
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
    }

    @AfterEach
    void cleanup() {
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void resolveOpensWorkingPoolAgainstCustomerPostgres() throws Exception {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(), true);

        var pool = manager.resolve(ds.getId());

        try (var connection = pool.getConnection();
             var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        manager.evict(ds.getId());
    }

    @Test
    void resolveCachesPoolForRepeatedCalls() {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(), true);

        var first = manager.resolve(ds.getId());
        var second = manager.resolve(ds.getId());

        assertThat(second).isSameAs(first);
        manager.evict(ds.getId());
    }

    @Test
    void evictClosesPoolAndForcesRecreation() {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(), true);

        var first = manager.resolve(ds.getId());
        manager.evict(ds.getId());
        var second = manager.resolve(ds.getId());

        assertThat(((HikariDataSource) first).isClosed()).isTrue();
        assertThat(second).isNotSameAs(first);
        manager.evict(ds.getId());
    }

    @Test
    void resolveThrowsForInactiveDatasource() {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(), false);

        assertThatThrownBy(() -> manager.resolve(ds.getId()))
                .isInstanceOf(DatasourceUnavailableException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void resolveThrowsForMissingDatasource() {
        assertThatThrownBy(() -> manager.resolve(UUID.randomUUID()))
                .isInstanceOf(DatasourceUnavailableException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveThrowsPoolInitializationExceptionForBadCredentials() {
        var ds = saveDatasource(customerDb.getUsername(), "wrong-password", true);

        assertThatThrownBy(() -> manager.resolve(ds.getId()))
                .isInstanceOf(PoolInitializationException.class);
    }

    private DatasourceEntity saveDatasource(String username, String password, boolean active) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Customer-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(5432));
        ds.setDatabaseName(customerDb.getDatabaseName());
        ds.setUsername(username);
        ds.setPasswordEncrypted(encryptionService.encrypt(password));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(3);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(active);
        return datasourceRepository.save(ds);
    }
}
