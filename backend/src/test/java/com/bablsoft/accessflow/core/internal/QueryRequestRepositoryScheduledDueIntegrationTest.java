package com.bablsoft.accessflow.core.internal;

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
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryRequestRepositoryScheduledDueIntegrationTest {

    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private ReviewPlanEntity plan;
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
        cleanup();
        organization = saveOrganization();
        submitter = saveUser();
        plan = savePlan();
        datasource = saveDatasource(plan);
    }

    @AfterEach
    void cleanup() {
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void findScheduledDueIdsReturnsApprovedRowsWhoseScheduledForIsAtOrBeforeNow() {
        var due = saveQuery(QueryStatus.APPROVED, Instant.now().minusSeconds(60));

        var ids = queryRequestRepository.findScheduledDueIds(Instant.now());

        assertThat(ids).containsExactly(due.getId());
    }

    @Test
    void findScheduledDueIdsSkipsFutureRows() {
        saveQuery(QueryStatus.APPROVED, Instant.now().plusSeconds(3600));

        var ids = queryRequestRepository.findScheduledDueIds(Instant.now());

        assertThat(ids).isEmpty();
    }

    @Test
    void findScheduledDueIdsSkipsNonApprovedRows() {
        saveQuery(QueryStatus.PENDING_REVIEW, Instant.now().minusSeconds(60));
        saveQuery(QueryStatus.EXECUTED, Instant.now().minusSeconds(60));
        saveQuery(QueryStatus.FAILED, Instant.now().minusSeconds(60));

        var ids = queryRequestRepository.findScheduledDueIds(Instant.now());

        assertThat(ids).isEmpty();
    }

    @Test
    void findScheduledDueIdsSkipsApprovedRowsWithoutSchedule() {
        saveQuery(QueryStatus.APPROVED, null);

        var ids = queryRequestRepository.findScheduledDueIds(Instant.now());

        assertThat(ids).isEmpty();
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

    private ReviewPlanEntity savePlan() {
        var entity = new ReviewPlanEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setName("Plan-" + UUID.randomUUID());
        entity.setRequiresAiReview(false);
        entity.setRequiresHumanApproval(true);
        entity.setMinApprovalsRequired(1);
        entity.setApprovalTimeoutHours(24);
        entity.setAutoApproveReads(false);
        return reviewPlanRepository.save(entity);
    }

    private DatasourceEntity saveDatasource(ReviewPlanEntity reviewPlan) {
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
        ds.setReviewPlan(reviewPlan);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity saveQuery(QueryStatus status, Instant scheduledFor) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(status);
        query.setScheduledFor(scheduledFor);
        return queryRequestRepository.save(query);
    }
}
