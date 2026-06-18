package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
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
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QuerySnapshotRepositoryIntegrationTest {

    @Autowired QuerySnapshotRepository snapshotRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private QueryRequestEntity query;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pk = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        organization = organizationRepository.save(newOrg());
        submitter = userRepository.save(newUser());
        datasource = datasourceRepository.save(newDatasource());
        query = queryRequestRepository.save(newQuery());
    }

    @AfterEach
    void cleanup() {
        snapshotRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void persistsArraysEnumsAndJsonAndReadsBack() {
        var snapshot = newSnapshot();
        snapshot.setReferencedTables(new String[]{"public.users", "public.orders"});
        snapshot.setAiAnalysisJson("{\"risk\":\"LOW\"}");
        snapshot.setReviewDecisionsJson("[{\"stage\":1}]");
        snapshotRepository.save(snapshot);

        var loaded = snapshotRepository.findById(snapshot.getId()).orElseThrow();
        assertThat(loaded.getReferencedTables()).containsExactly("public.users", "public.orders");
        assertThat(loaded.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(loaded.getDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(loaded.getAiAnalysisJson()).contains("LOW");
        assertThat(loaded.getReviewDecisionsJson()).contains("stage");
    }

    @Test
    void existsAndFindByQueryRequestId() {
        snapshotRepository.save(newSnapshot());

        assertThat(snapshotRepository.existsByQueryRequestId(query.getId())).isTrue();
        assertThat(snapshotRepository.findByQueryRequestId(query.getId())).isPresent();
        assertThat(snapshotRepository.findByQueryRequestIdAndOrganizationId(
                query.getId(), organization.getId())).isPresent();
        assertThat(snapshotRepository.findByQueryRequestIdAndOrganizationId(
                query.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void uniqueQueryRequestIdViolation() {
        snapshotRepository.save(newSnapshot());
        snapshotRepository.flush();

        var duplicate = newSnapshot();
        assertThatThrownBy(() -> {
            snapshotRepository.save(duplicate);
            snapshotRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingQueryCascadesSnapshot() {
        snapshotRepository.save(newSnapshot());
        snapshotRepository.flush();

        // Re-read the parent so its @Version (updated_at) carries the DB-stored,
        // microsecond-truncated timestamp. Deleting the entity loaded in @BeforeEach
        // directly trips ObjectOptimisticLockingFailure on platforms whose
        // Instant.now() has sub-microsecond precision (Linux CI) — the in-memory
        // nanos no longer match the truncated value in the row.
        var managed = queryRequestRepository.findById(query.getId()).orElseThrow();
        queryRequestRepository.delete(managed);
        queryRequestRepository.flush();

        assertThat(snapshotRepository.findByQueryRequestId(query.getId())).isEmpty();
    }

    private QuerySnapshotEntity newSnapshot() {
        var snapshot = new QuerySnapshotEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setQueryRequestId(query.getId());
        snapshot.setOrganizationId(organization.getId());
        snapshot.setDatasourceId(datasource.getId());
        snapshot.setSubmittedBy(submitter.getId());
        snapshot.setSqlText("SELECT * FROM users");
        snapshot.setQueryType(QueryType.SELECT);
        snapshot.setDbType(DbType.POSTGRESQL);
        snapshot.setReferencedTables(new String[0]);
        snapshot.setReviewDecisionsJson("[]");
        snapshot.setExecutedAt(Instant.now());
        return snapshot;
    }

    private QueryRequestEntity newQuery() {
        var entity = new QueryRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText("SELECT * FROM users");
        entity.setQueryType(QueryType.SELECT);
        entity.setStatus(QueryStatus.EXECUTED);
        return entity;
    }

    private OrganizationEntity newOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        return org;
    }

    private UserEntity newUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return user;
    }

    private DatasourceEntity newDatasource() {
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
        return ds;
    }
}
