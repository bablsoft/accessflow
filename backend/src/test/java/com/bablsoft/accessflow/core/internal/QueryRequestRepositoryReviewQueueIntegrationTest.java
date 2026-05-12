package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.EditionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryRequestRepositoryReviewQueueIntegrationTest {

    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository approverRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private UserEntity reviewer;
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
        submitter = saveUser("submitter", UserRoleType.ANALYST);
        reviewer = saveUser("reviewer", UserRoleType.REVIEWER);
        plan = savePlan();
        datasource = saveDatasource(plan);
    }

    @AfterEach
    void cleanup() {
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        approverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void findPendingForReviewerReturnsRequestForRoleApprover() {
        saveApprover(plan, null, UserRoleType.REVIEWER, 1);
        var pending = saveQuery(QueryStatus.PENDING_REVIEW, submitter);

        var page = queryRequestRepository.findPendingForReviewer(
                organization.getId(), reviewer.getId(),
                UserRoleType.REVIEWER, QueryStatus.PENDING_REVIEW,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(QueryRequestEntity::getId)
                .containsExactly(pending.getId());
    }

    @Test
    void findPendingForReviewerReturnsRequestForUserApprover() {
        saveApprover(plan, reviewer, null, 1);
        var pending = saveQuery(QueryStatus.PENDING_REVIEW, submitter);

        var page = queryRequestRepository.findPendingForReviewer(
                organization.getId(), reviewer.getId(),
                UserRoleType.REVIEWER, QueryStatus.PENDING_REVIEW,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(QueryRequestEntity::getId)
                .containsExactly(pending.getId());
    }

    @Test
    void findPendingForReviewerExcludesQueriesSubmittedByTheReviewer() {
        saveApprover(plan, null, UserRoleType.REVIEWER, 1);
        saveQuery(QueryStatus.PENDING_REVIEW, reviewer);

        var page = queryRequestRepository.findPendingForReviewer(
                organization.getId(), reviewer.getId(),
                UserRoleType.REVIEWER, QueryStatus.PENDING_REVIEW,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void findPendingForReviewerExcludesNonPendingReviewStatuses() {
        saveApprover(plan, null, UserRoleType.REVIEWER, 1);
        var pending = saveQuery(QueryStatus.PENDING_REVIEW, submitter);
        saveQuery(QueryStatus.APPROVED, submitter);

        var page = queryRequestRepository.findPendingForReviewer(
                organization.getId(), reviewer.getId(),
                UserRoleType.REVIEWER, QueryStatus.PENDING_REVIEW,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(QueryRequestEntity::getId)
                .containsExactly(pending.getId());
    }

    private OrganizationEntity saveOrganization() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(String prefix, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName(prefix);
        user.setPasswordHash("hash");
        user.setRole(role);
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
        entity.setRequiresAiReview(true);
        entity.setRequiresHumanApproval(true);
        entity.setMinApprovalsRequired(1);
        entity.setApprovalTimeoutHours(24);
        entity.setAutoApproveReads(false);
        return reviewPlanRepository.save(entity);
    }

    private ReviewPlanApproverEntity saveApprover(ReviewPlanEntity reviewPlan, UserEntity user,
                                                  UserRoleType role, int stage) {
        var entity = new ReviewPlanApproverEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewPlan(reviewPlan);
        entity.setUser(user);
        entity.setRole(role);
        entity.setStage(stage);
        return approverRepository.save(entity);
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
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        ds.setReviewPlan(reviewPlan);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity saveQuery(QueryStatus status, UserEntity submittedBy) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submittedBy);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(status);
        return queryRequestRepository.save(query);
    }
}
