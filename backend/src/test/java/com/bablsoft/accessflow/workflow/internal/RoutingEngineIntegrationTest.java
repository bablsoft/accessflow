package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
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
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingDecisionRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RoutingEngineIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired RoutingPolicyRepository routingPolicyRepository;
    @Autowired RoutingDecisionRepository routingDecisionRepository;
    @Autowired RoutingConditionCodec routingConditionCodec;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;

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
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        submitter.setDisplayName("Submitter");
        submitter.setPasswordHash("hash");
        submitter.setRole(UserRoleType.ANALYST);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(organization);
        userRepository.save(submitter);
    }

    @AfterEach
    void cleanup() {
        routingDecisionRepository.deleteAll();
        routingPolicyRepository.deleteAll();
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void autoRejectPolicyTransitionsQueryToRejectedAndRecordsDecision() {
        var plan = persistPlan();
        var datasource = persistDatasource(plan);
        var query = persistPendingAiQuery(datasource, QueryType.DELETE);
        var policy = persistPolicy(1, RoutingAction.AUTO_REJECT, null,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.HIGH, 90));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.REJECTED);
            var decision = routingDecisionRepository.findByQueryRequestId(query.getId());
            assertThat(decision).isPresent();
            assertThat(decision.get().getMatchedPolicyId()).isEqualTo(policy.getId());
            assertThat(decision.get().getAction()).isEqualTo(RoutingAction.AUTO_REJECT);
        });
    }

    @Test
    void escalatePolicyRoutesToReviewWithRaisedApprovalThreshold() {
        var plan = persistPlan(); // minApprovalsRequired = 1
        var datasource = persistDatasource(plan);
        var query = persistPendingAiQuery(datasource, QueryType.UPDATE);
        persistPolicy(1, RoutingAction.ESCALATE, 2,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.UPDATE)));

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.HIGH, 80));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
            var decision = routingDecisionRepository.findByQueryRequestId(query.getId());
            assertThat(decision).isPresent();
            // plan min (1) + escalate delta (2) = 3
            assertThat(decision.get().getEffectiveMinApprovals()).isEqualTo(3);
        });
    }

    @Test
    void noMatchingPolicyFallsThroughToPlanRouting() {
        var plan = persistPlan();
        var datasource = persistDatasource(plan);
        var query = persistPendingAiQuery(datasource, QueryType.SELECT);
        persistPolicy(1, RoutingAction.AUTO_REJECT, null,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE)));

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.HIGH, 70));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
            assertThat(routingDecisionRepository.findByQueryRequestId(query.getId())).isEmpty();
        });
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private ReviewPlanEntity persistPlan() {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        reviewPlanRepository.save(plan);

        var approver = new ReviewPlanApproverEntity();
        approver.setId(UUID.randomUUID());
        approver.setReviewPlan(plan);
        approver.setRole(UserRoleType.REVIEWER);
        approver.setStage(1);
        reviewPlanApproverRepository.save(approver);
        return plan;
    }

    private DatasourceEntity persistDatasource(ReviewPlanEntity plan) {
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
        ds.setReviewPlan(plan);
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity persistPendingAiQuery(DatasourceEntity ds, QueryType type) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(type);
        query.setStatus(QueryStatus.PENDING_AI);
        return queryRequestRepository.save(query);
    }

    private RoutingPolicyEntity persistPolicy(int priority, RoutingAction action,
                                              Integer requiredApprovals, ConditionNode condition) {
        var policy = new RoutingPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setOrganizationId(organization.getId());
        policy.setName("policy-" + priority);
        policy.setPriority(priority);
        policy.setEnabled(true);
        policy.setAction(action);
        policy.setRequiredApprovals(requiredApprovals);
        policy.setConditionJson(routingConditionCodec.encode(condition));
        return routingPolicyRepository.save(policy);
    }
}
