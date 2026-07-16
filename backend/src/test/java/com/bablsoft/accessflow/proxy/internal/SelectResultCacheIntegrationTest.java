package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the opt-in SELECT result cache (AF-457) against real PostgreSQL and Redis
 * containers. A cache hit is proven by mutating the table <em>behind the proxy's back</em> (plain
 * JDBC) and observing the stale cached rows; a write through the proxy then invalidates the
 * cached entry and the next read sees fresh data.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SelectResultCacheIntegrationTest {

    @Autowired QueryExecutor executor;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

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

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS cache_items");
        jdbcTemplate.execute(
                "CREATE TABLE cache_items (id int PRIMARY KEY, label varchar(64) NOT NULL)");
        jdbcTemplate.update("INSERT INTO cache_items VALUES (1, 'v1')");

        datasourceRepository.deleteAll();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("CacheOrg");
        org.setSlug("cache-" + UUID.randomUUID());
        organizationRepository.save(org);

        var pg = TestcontainersConfig.postgres;
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Cached-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(pg.getHost());
        ds.setPort(pg.getMappedPort(5432));
        ds.setDatabaseName(pg.getDatabaseName());
        ds.setUsername(pg.getUsername());
        ds.setPasswordEncrypted(encryptionService.encrypt(pg.getPassword()));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(2);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setResultCacheEnabled(true);
        ds.setResultCacheTtlSeconds(300);
        ds.setActive(true);
        datasource = datasourceRepository.save(ds);
    }

    private QueryExecutionRequest select() {
        return new QueryExecutionRequest(datasource.getId(),
                "SELECT label FROM cache_items ORDER BY id", QueryType.SELECT, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), false, null,
                java.util.List.of(), Set.of("cache_items"));
    }

    @Test
    void cachedSelectServesStaleRowsUntilWriteInvalidates() {
        var first = (SelectExecutionResult) executor.execute(select());
        assertThat(first.rows().get(0)).containsExactly("v1");

        // Mutate behind the proxy's back — the cached entry must still serve the old value.
        jdbcTemplate.update("UPDATE cache_items SET label = 'v2' WHERE id = 1");
        var cached = (SelectExecutionResult) executor.execute(select());
        assertThat(cached.rows().get(0)).containsExactly("v1");

        // A write through the proxy to the referenced table invalidates the cached entry.
        executor.execute(new QueryExecutionRequest(datasource.getId(),
                "UPDATE cache_items SET label = 'v3' WHERE id = 1", QueryType.UPDATE, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), false, null,
                java.util.List.of(), Set.of("cache_items")));
        var fresh = (SelectExecutionResult) executor.execute(select());
        assertThat(fresh.rows().get(0)).containsExactly("v3");
    }

    @Test
    void selectWithoutOptInIsNeverCached() {
        datasource.setResultCacheEnabled(false);
        datasourceRepository.save(datasource);

        var first = (SelectExecutionResult) executor.execute(select());
        assertThat(first.rows().get(0)).containsExactly("v1");

        jdbcTemplate.update("UPDATE cache_items SET label = 'v2' WHERE id = 1");
        var second = (SelectExecutionResult) executor.execute(select());
        assertThat(second.rows().get(0)).containsExactly("v2");
    }
}
