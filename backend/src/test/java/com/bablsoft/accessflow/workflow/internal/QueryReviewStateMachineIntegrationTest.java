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
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryReviewStateMachineIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
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
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        jdbcTemplate.update("DELETE FROM access_grant_decision");
        jdbcTemplate.update("DELETE FROM access_grant_request");
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void aiCompletedTransitionsPendingAiToPendingReview() {
        var plan = persistPlan(true, true, false);
        var query = persistPendingAiQuery(persistDatasource(plan), QueryType.UPDATE);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.MEDIUM));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        });
    }

    @Test
    void aiCompletedAutoApprovesSelectWithLowRisk() {
        var plan = persistPlan(true, true, true);
        var query = persistPendingAiQuery(persistDatasource(plan), QueryType.SELECT);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.APPROVED);
        });
    }

    @Test
    void aiCompletedDoesNotAutoApproveSelectWithHighRisk() {
        var plan = persistPlan(true, true, true);
        var query = persistPendingAiQuery(persistDatasource(plan), QueryType.SELECT);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.HIGH));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        });
    }

    @Test
    void aiCompletedTransitionsToApprovedWhenHumanApprovalNotRequired() {
        var plan = persistPlan(true, false, false);
        var query = persistPendingAiQuery(persistDatasource(plan), QueryType.SELECT);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.HIGH));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.APPROVED);
        });
    }

    @Test
    void aiFailedTransitionsToPendingReviewEvenWhenHumanApprovalNotRequired() {
        var plan = persistPlan(true, false, false);
        var query = persistPendingAiQuery(persistDatasource(plan), QueryType.SELECT);

        publish(new AiAnalysisFailedEvent(query.getId(), "boom"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        });
    }

    @Test
    void datasourceWithoutReviewPlanRoutesToPendingReview() {
        var query = persistPendingAiQuery(persistDatasource(null), QueryType.SELECT);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        });
    }

    @Test
    void grantFastPathAutoApprovesCoveredQueryAndRecordsProvenance() {
        var plan = persistPlan(true, true, false);
        var datasource = persistDatasource(plan);
        var approver = persistApprover();
        var grantId = persistPreApprovedGrant(datasource, approver,
                Instant.now().plus(Duration.ofHours(4)));
        var query = persistPendingAiQuery(datasource, QueryType.SELECT,
                "SELECT * FROM orders");

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.APPROVED);
            assertThat(loaded.getApprovedByGrantId()).isEqualTo(grantId);
        });
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var metadata = jdbcTemplate.queryForList(
                    "SELECT metadata::text FROM audit_log "
                            + "WHERE resource_id = ? AND action::text = 'QUERY_APPROVED'",
                    String.class, query.getId());
            assertThat(metadata).isNotEmpty();
            assertThat(metadata.get(0)).contains("\"source\": \"ACCESS_GRANT\"")
                    .contains(grantId.toString())
                    .contains(approver.getEmail());
        });
    }

    @Test
    void expiredGrantDoesNotFastPath() {
        var plan = persistPlan(true, true, false);
        var datasource = persistDatasource(plan);
        var approver = persistApprover();
        persistPreApprovedGrant(datasource, approver, Instant.now().minus(Duration.ofMinutes(1)));
        var query = persistPendingAiQuery(datasource, QueryType.SELECT,
                "SELECT * FROM orders");

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
            assertThat(loaded.getApprovedByGrantId()).isNull();
        });
    }

    @Test
    void grantScopeMissRoutesToReview() {
        var plan = persistPlan(true, true, false);
        var datasource = persistDatasource(plan);
        var approver = persistApprover();
        persistPreApprovedGrant(datasource, approver, Instant.now().plus(Duration.ofHours(4)));
        var query = persistPendingAiQuery(datasource, QueryType.SELECT,
                "SELECT * FROM payroll");

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var loaded = queryRequestRepository.findById(query.getId()).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
            assertThat(loaded.getApprovedByGrantId()).isNull();
        });
    }

    private UserEntity persistApprover() {
        var approver = new UserEntity();
        approver.setId(UUID.randomUUID());
        approver.setEmail("approver-" + UUID.randomUUID() + "@example.com");
        approver.setDisplayName("Approver");
        approver.setPasswordHash("hash");
        approver.setRole(UserRoleType.REVIEWER);
        approver.setAuthProvider(AuthProviderType.LOCAL);
        approver.setActive(true);
        approver.setOrganization(organization);
        return userRepository.save(approver);
    }

    /** Seeds an APPROVED pre-approving grant scoped to the {@code orders} table via plain SQL —
     * the access module's entities are module-private to {@code access.internal}. */
    private UUID persistPreApprovedGrant(DatasourceEntity datasource, UserEntity approver,
                                         Instant expiresAt) {
        var grantId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_grant_request (id, organization_id, requester_id, datasource_id,
                    can_read, can_write, can_ddl, allowed_schemas, allowed_tables,
                    requested_duration, justification, status, expires_at, pre_approve_queries,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, false, false, NULL, ARRAY['orders'], 'PT4H', 'j',
                    'APPROVED'::access_grant_status, ?, true, 0, now(), now())
                """, grantId, organization.getId(), submitter.getId(), datasource.getId(),
                java.sql.Timestamp.from(expiresAt));
        jdbcTemplate.update("""
                INSERT INTO access_grant_decision (id, access_grant_request_id, reviewer_id,
                    decision, stage, comment, decided_at)
                VALUES (?, ?, ?, 'APPROVED'::decision, 1, NULL, now())
                """, UUID.randomUUID(), grantId, approver.getId());
        return grantId;
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private ReviewPlanEntity persistPlan(boolean requiresAi, boolean requiresHumanApproval,
                                         boolean autoApproveReads) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(requiresAi);
        plan.setRequiresHumanApproval(requiresHumanApproval);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(autoApproveReads);
        reviewPlanRepository.save(plan);

        var approver = new ReviewPlanApproverEntity();
        approver.setId(UUID.randomUUID());
        approver.setReviewPlan(plan);
        approver.setRole("REVIEWER");
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
        return persistPendingAiQuery(ds, type, "SELECT 1");
    }

    private QueryRequestEntity persistPendingAiQuery(DatasourceEntity ds, QueryType type,
                                                     String sql) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText(sql);
        query.setQueryType(type);
        query.setStatus(QueryStatus.PENDING_AI);
        return queryRequestRepository.save(query);
    }
}
