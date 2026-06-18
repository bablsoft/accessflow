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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryRequestRepositoryLastApprovalIntegrationTest {

    private static final List<QueryStatus> APPROVED =
            List.of(QueryStatus.APPROVED, QueryStatus.EXECUTED);

    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private DatasourceEntity otherDatasource;

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
        var plan = savePlan();
        datasource = saveDatasource(plan);
        otherDatasource = saveDatasource(plan);
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
    void returnsApprovalInstantForApprovedQueryOnDatasource() {
        var approved = saveQuery(datasource, QueryStatus.APPROVED);
        var persisted = queryRequestRepository.findById(approved.getId()).orElseThrow();

        var result = queryRequestRepository.findLastApprovalInstant(organization.getId(),
                submitter.getId(), datasource.getId(), APPROVED, null);

        assertThat(result).contains(persisted.getUpdatedAt());
    }

    @Test
    void executedCountsAsApproval() {
        saveQuery(datasource, QueryStatus.EXECUTED);

        var result = queryRequestRepository.findLastApprovalInstant(organization.getId(),
                submitter.getId(), datasource.getId(), APPROVED, null);

        assertThat(result).isPresent();
    }

    @Test
    void emptyWhenNoApprovedQuery() {
        saveQuery(datasource, QueryStatus.PENDING_REVIEW);
        saveQuery(datasource, QueryStatus.REJECTED);

        var result = queryRequestRepository.findLastApprovalInstant(organization.getId(),
                submitter.getId(), datasource.getId(), APPROVED, null);

        assertThat(result).isEmpty();
    }

    @Test
    void excludesTheGivenQueryId() {
        var only = saveQuery(datasource, QueryStatus.APPROVED);

        var result = queryRequestRepository.findLastApprovalInstant(organization.getId(),
                submitter.getId(), datasource.getId(), APPROVED, only.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void scopedToTheDatasource() {
        saveQuery(otherDatasource, QueryStatus.APPROVED);

        var result = queryRequestRepository.findLastApprovalInstant(organization.getId(),
                submitter.getId(), datasource.getId(), APPROVED, null);

        assertThat(result).isEmpty();
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

    private QueryRequestEntity saveQuery(DatasourceEntity ds, QueryStatus status) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(status);
        return queryRequestRepository.save(query);
    }
}
