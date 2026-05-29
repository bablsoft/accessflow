package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceQueryStatsLookupService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DatasourceQueryStatsAggregateIntegrationTest {

    @Autowired DatasourceQueryStatsLookupService lookupService;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity dsA;
    private DatasourceEntity dsB;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        organization = saveOrganization();
        submitter = saveUser();
        dsA = saveDatasource();
        dsB = saveDatasource();
    }

    @AfterEach
    void cleanup() {
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void aggregatesCountsErrorsAndPercentilesWithinTheWindow() {
        var now = Instant.now();
        var since = now.minus(24, ChronoUnit.HOURS);
        // dsA: three recent EXECUTED rows (durations 10/30/50) + one recent FAILED (no duration).
        saveQuery(dsA, QueryStatus.EXECUTED, now.minusSeconds(60), 10);
        saveQuery(dsA, QueryStatus.EXECUTED, now.minusSeconds(60), 30);
        saveQuery(dsA, QueryStatus.EXECUTED, now.minusSeconds(60), 50);
        saveQuery(dsA, QueryStatus.FAILED, now.minusSeconds(60), null);
        // dsA: one stale EXECUTED row outside the window — must be excluded.
        saveQuery(dsA, QueryStatus.EXECUTED, now.minus(2, ChronoUnit.DAYS), 9999);
        // dsB: a single recent EXECUTED row.
        saveQuery(dsB, QueryStatus.EXECUTED, now.minusSeconds(30), 20);

        var stats = lookupService.statsFor(List.of(dsA.getId(), dsB.getId()), since);

        var a = stats.get(dsA.getId());
        assertThat(a.queriesLast24h()).isEqualTo(4L);
        assertThat(a.errorsLast24h()).isEqualTo(1L);
        // percentile_cont over [10,30,50]: p50 = 30, p95 = 30 + 0.9*(50-30) = 48.
        assertThat(a.executionMsP50()).isEqualTo(30.0);
        assertThat(a.executionMsP95()).isEqualTo(48.0);

        var b = stats.get(dsB.getId());
        assertThat(b.queriesLast24h()).isEqualTo(1L);
        assertThat(b.errorsLast24h()).isZero();
        assertThat(b.executionMsP50()).isEqualTo(20.0);
    }

    @Test
    void datasourceWithOnlyStaleRowsIsAbsentFromResult() {
        var now = Instant.now();
        saveQuery(dsA, QueryStatus.EXECUTED, now.minus(3, ChronoUnit.DAYS), 100);

        var stats = lookupService.statsFor(List.of(dsA.getId(), dsB.getId()),
                now.minus(24, ChronoUnit.HOURS));

        assertThat(stats).doesNotContainKey(dsA.getId());
        assertThat(stats).doesNotContainKey(dsB.getId());
    }

    private OrganizationEntity saveOrganization() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("DS-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("db");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private void saveQuery(DatasourceEntity datasource, QueryStatus status, Instant createdAt,
                           Integer durationMs) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(status);
        query.setExecutionDurationMs(durationMs);
        query.setCreatedAt(createdAt);
        queryRequestRepository.save(query);
    }
}
