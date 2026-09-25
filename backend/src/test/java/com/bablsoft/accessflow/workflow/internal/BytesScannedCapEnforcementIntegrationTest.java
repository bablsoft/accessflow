package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction;
import com.bablsoft.accessflow.core.api.BytesScannedCapOutcome;
import com.bablsoft.accessflow.core.api.BytesScannedCapSource;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryEstimateRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.ComparisonOperator;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #941 end to end: the warehouse's pre-flight bytes estimate is persisted with the query's
 * estimate, a bytes-scanned cap refuses the query when it leaves {@code PENDING_AI}, a missing
 * estimate under {@code REQUIRE_REVIEW} holds an automatic approval for a person, the cap is
 * re-checked just before execution, and an {@code estimated_bytes_scanned} routing policy sees the
 * estimate on the AI-skipped path, where nothing else waits for it.
 *
 * <p>No warehouse is contacted: {@link QueryExecutor} is mocked, so its {@code dryRun} supplies the
 * estimate the BigQuery engine would report.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class BytesScannedCapEnforcementIntegrationTest {

    private static final String SQL = "SELECT * FROM events";
    private static final long ONE_TB = 1_000_000_000_000L;

    @MockitoBean QueryExecutor queryExecutor;
    // No BigQuery engine plugin is loaded in tests; the parser is the plugin's to supply.
    @MockitoBean QueryParser queryParser;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QueryLifecycleService queryLifecycleService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired QueryEstimateRepository queryEstimateRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired RoutingPolicyRepository routingPolicyRepository;
    @Autowired RoutingDecisionRepository routingDecisionRepository;
    @Autowired RoutingConditionCodec routingConditionCodec;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;

    @BeforeEach
    void setUp() {
        cleanup();
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);
        submitter = persistUser();
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, SQL));
    }

    @AfterEach
    void cleanup() {
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization.getId());
        }
        routingDecisionRepository.deleteAll();
        routingPolicyRepository.deleteAll();
        jdbcTemplate.update("UPDATE query_requests SET query_estimate_id = NULL");
        queryEstimateRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void anEstimateOverTheCapIsRejectedWhenTheQueryLeavesPendingAi() {
        givenBytesEstimate(2 * ONE_TB);
        var datasource = persistDatasource(persistPlan(false), ONE_TB,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        var query = persistPendingAiQuery(datasource);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.REJECTED);
        var row = queryRequestRepository.findById(query.getId()).orElseThrow();
        assertThat(row.getBytesScannedCap()).isEqualTo(ONE_TB);
        assertThat(row.getBytesScannedCapSource()).isEqualTo(BytesScannedCapSource.DATASOURCE);
        assertThat(row.getBytesScannedCapOutcome()).isEqualTo(BytesScannedCapOutcome.EXCEEDED);
        assertThat(queryEstimateRepository.findByQueryRequestId(query.getId()).orElseThrow()
                .getEstimatedBytesScanned()).isEqualTo(2 * ONE_TB);
        assertThat(capAuditMetadata(query.getId()))
                .contains("\"stage\": \"decision\"")
                .contains("\"outcome\": \"EXCEEDED\"");
    }

    @Test
    void aMissingEstimateUnderRequireReviewHoldsAnAutoApprovalForAPerson() {
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("bigquery"));
        var datasource = persistDatasource(persistPlan(false), ONE_TB,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        var query = persistPendingAiQuery(datasource);

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow()
                .getBytesScannedCapOutcome()).isEqualTo(BytesScannedCapOutcome.NO_ESTIMATE_REVIEW);
        assertThat(capAuditMetadata(query.getId())).contains("NO_ESTIMATE_REVIEW");
    }

    @Test
    void aCapLoweredAfterApprovalFailsTheExecutionBeforeTheWarehouseRunsIt() {
        givenBytesEstimate(500_000_000_000L);
        var datasource = persistDatasource(persistPlan(false), ONE_TB,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        var query = persistPendingAiQuery(datasource);
        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));
        awaitStatus(query.getId(), QueryStatus.APPROVED);

        datasource.setMaxBytesScannedPerQuery(100_000_000_000L);
        datasourceRepository.save(datasource);
        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(query.getId(),
                submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);
        verify(queryParser, never()).parse(any(), any());
        verify(queryExecutor, never()).execute(any());
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow().getErrorMessage())
                .contains("500 GB").contains("100 GB");
        assertThat(capAuditMetadata(query.getId())).contains("\"stage\": \"execution\"");
    }

    @Test
    void anEstimateWithinTheCapExecutes() {
        givenBytesEstimate(10L);
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(List.of(),
                List.of(), 0L, false, Duration.ofMillis(3)));
        var datasource = persistDatasource(persistPlan(false), ONE_TB,
                BytesCapMissingEstimateAction.REJECT);
        var query = persistPendingAiQuery(datasource);
        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));
        awaitStatus(query.getId(), QueryStatus.APPROVED);

        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(query.getId(),
                submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow()
                .getBytesScannedCapOutcome()).isEqualTo(BytesScannedCapOutcome.WITHIN);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? "
                + "AND action = 'QUERY_BYTES_SCANNED_CAP_ENFORCED'", Integer.class, query.getId()))
                .isZero();
    }

    @Test
    void anEstimatedBytesPolicyEscalatesOnTheSkippedPathWithoutAnyCap() {
        givenBytesEstimate(2 * ONE_TB);
        var datasource = persistDatasource(persistPlan(false), null,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        var policy = persistPolicy(new ConditionNode.EstimatedBytesScanned(ComparisonOperator.GT,
                ONE_TB));
        var query = persistPendingAiQuery(datasource);

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(routingDecisionRepository.findByQueryRequestId(query.getId()).orElseThrow()
                .getMatchedPolicyId()).isEqualTo(policy.getId());
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow()
                .getBytesScannedCap()).isNull();
    }

    @Test
    void anEstimatedBytesPolicyDoesNotFireWithoutAnEstimate() {
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("bigquery"));
        var datasource = persistDatasource(persistPlan(false), null,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        persistPolicy(new ConditionNode.EstimatedBytesScanned(ComparisonOperator.GTE, 0));
        var query = persistPendingAiQuery(datasource);

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.APPROVED);
        assertThat(routingDecisionRepository.findByQueryRequestId(query.getId())).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void givenBytesEstimate(long bytes) {
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of("bigquery",
                QueryType.SELECT, null, null, null, Set.of(), Duration.ofMillis(2))
                .withEstimatedBytesScanned(bytes));
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private void awaitStatus(UUID queryId, QueryStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queryRequestRepository.findById(queryId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private String capAuditMetadata(UUID queryId) {
        var rows = jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log WHERE "
                + "resource_id = ? AND action = 'QUERY_BYTES_SCANNED_CAP_ENFORCED'", String.class,
                queryId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private UserEntity persistUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private ReviewPlanEntity persistPlan(boolean requiresHumanApproval) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(false);
        plan.setRequiresHumanApproval(requiresHumanApproval);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        reviewPlanRepository.save(plan);
        var rule = new ReviewPlanApproverEntity();
        rule.setId(UUID.randomUUID());
        rule.setReviewPlan(plan);
        rule.setRole("REVIEWER");
        rule.setStage(1);
        reviewPlanApproverRepository.save(rule);
        return plan;
    }

    private DatasourceEntity persistDatasource(ReviewPlanEntity plan, Long cap,
                                               BytesCapMissingEstimateAction missing) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("WH-" + UUID.randomUUID());
        ds.setDbType(DbType.BIGQUERY);
        ds.setHost("bigquery.invalid");
        ds.setPort(443);
        ds.setDatabaseName("analytics");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("{}"));
        ds.setSslMode(SslMode.REQUIRE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setReviewPlan(plan);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        ds.setMaxBytesScannedPerQuery(cap);
        ds.setBytesCapMissingEstimate(missing);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity persistPendingAiQuery(DatasourceEntity ds) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText(SQL);
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_AI);
        return queryRequestRepository.save(query);
    }

    private RoutingPolicyEntity persistPolicy(ConditionNode condition) {
        var policy = new RoutingPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setOrganizationId(organization.getId());
        policy.setName("policy-" + UUID.randomUUID());
        policy.setPriority(1);
        policy.setEnabled(true);
        policy.setAction(RoutingAction.ESCALATE);
        policy.setRequiredApprovals(1);
        policy.setConditionJson(routingConditionCodec.encode(condition));
        return routingPolicyRepository.save(policy);
    }
}
