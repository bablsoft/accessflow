package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.MysqlDriverCacheTestcontainersConfig;
import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers({TestcontainersConfig.class, MysqlDriverCacheTestcontainersConfig.class})
class DefaultDatasourceConnectionPoolManagerMysqlIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static MySQLContainer customerDb = new MySQLContainer("mysql:8.0")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw")
            .withCommand("--default-authentication-plugin=mysql_native_password");

    @Autowired DatasourceConnectionPoolManager manager;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;

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
    void resolveOpensWorkingPoolAgainstCustomerMysql() throws Exception {
        var ds = saveDatasource();

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
    void evictClosesMysqlPool() {
        var ds = saveDatasource();

        var pool = manager.resolve(ds.getId());
        manager.evict(ds.getId());

        assertThat(((HikariDataSource) pool).isClosed()).isTrue();
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
