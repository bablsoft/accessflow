package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetUsageSource;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetUsageEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #942 end to end over the real ledger: an exhausted budget refuses or escalates a SELECT when it
 * leaves {@code PENDING_AI}, a budget with allowance left caps the execution and is charged for
 * what was delivered, and a {@code data_budget_used_percent} routing policy sees the live share.
 * No customer database is contacted — {@link QueryExecutor} is mocked.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DataBudgetEnforcementIntegrationTest {

    private static final String SQL = "SELECT * FROM events";

    @MockitoBean QueryExecutor queryExecutor;
    @MockitoBean QueryParser queryParser;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QueryLifecycleService queryLifecycleService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired RoutingPolicyRepository routingPolicyRepository;
    @Autowired RoutingDecisionRepository routingDecisionRepository;
    @Autowired RoutingConditionCodec routingConditionCodec;
    @Autowired DataBudgetRepository dataBudgetRepository;
    @Autowired DataBudgetUsageRepository usageRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private ReviewPlanEntity plan;

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Budget org");
        organization.setSlug("budget-" + UUID.randomUUID());
        organizationRepository.save(organization);
        submitter = persistUser();
        plan = persistPlan();
        datasource = persistDatasource(plan);
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, SQL));
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("postgresql"));
    }

    @AfterEach
    void cleanup() {
        var orgId = organization.getId();
        var dsId = datasource.getId();
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", orgId);
        jdbcTemplate.update("DELETE FROM user_notifications WHERE user_id = ?", submitter.getId());
        jdbcTemplate.update("DELETE FROM routing_decision WHERE query_request_id IN "
                + "(SELECT id FROM query_requests WHERE datasource_id = ?)", dsId);
        jdbcTemplate.update("DELETE FROM routing_policy WHERE organization_id = ?", orgId);
        jdbcTemplate.update("UPDATE query_requests SET query_estimate_id = NULL WHERE datasource_id = ?",
                dsId);
        jdbcTemplate.update("DELETE FROM query_estimates WHERE query_request_id IN "
                + "(SELECT id FROM query_requests WHERE datasource_id = ?)", dsId);
        jdbcTemplate.update("DELETE FROM query_requests WHERE datasource_id = ?", dsId);
        jdbcTemplate.update("DELETE FROM data_budget_usage WHERE datasource_id = ?", dsId);
        jdbcTemplate.update("DELETE FROM data_budgets WHERE datasource_id = ?", dsId);
        datasourceRepository.deleteById(dsId);
        jdbcTemplate.update("DELETE FROM review_plan_approvers WHERE review_plan_id = ?", plan.getId());
        reviewPlanRepository.deleteById(plan.getId());
        userRepository.deleteById(submitter.getId());
        organizationRepository.deleteById(orgId);
    }

    @Test
    void anExhaustedRejectBudgetRejectsTheQueryWhenItLeavesPendingAi() {
        persistBudget(DataBudgetBreachAction.REJECT, 100L);
        spend(100);
        var query = persistPendingAiQuery();

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.REJECTED);
        assertThat(budgetAuditMetadata(query.getId()))
                .contains("\"stage\": \"decision\"")
                .contains("\"action\": \"REJECT\"");
    }

    @Test
    void anExhaustedReviewBudgetHoldsAnAutoApprovalForAPerson() {
        persistBudget(DataBudgetBreachAction.REQUIRE_REVIEW, 100L);
        spend(250);
        var query = persistPendingAiQuery();

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(budgetAuditMetadata(query.getId())).contains("REQUIRE_REVIEW");
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow()
                .isDataBudgetReviewForced()).isTrue();
    }

    @Test
    void theRemainingAllowanceCapsTheExecutionAndTheDeliveredRowsAreCharged() {
        persistBudget(DataBudgetBreachAction.REJECT, 100L);
        spend(97);
        var query = persistPendingAiQuery();
        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));
        awaitStatus(query.getId(), QueryStatus.APPROVED);
        List<List<Object>> rows = IntStream.range(0, 3).<List<Object>>mapToObj(List::of).toList();
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4")), rows, 3L, true, Duration.ofMillis(3),
                Set.of(), Set.of(), SelectExecutionResult.TRUNCATED_ROW_LIMIT, null, 120L));

        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(query.getId(),
                submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        var request = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(request.capture());
        assertThat(request.getValue().maxRowsOverride()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT truncated_reason FROM query_request_results "
                + "WHERE query_request_id = ?", String.class, query.getId()))
                .isEqualTo(SelectExecutionResult.TRUNCATED_DATA_BUDGET);
        var charged = usageRepository.findAll().stream()
                .filter(u -> query.getId().equals(u.getQueryRequestId())).toList();
        assertThat(charged).singleElement().satisfies(u -> {
            assertThat(u.getRowsRead()).isEqualTo(3);
            assertThat(u.getBytesRead()).isEqualTo(120);
            assertThat(u.getSource()).isEqualTo(DataBudgetUsageSource.QUERY);
        });
    }

    @Test
    void aBudgetExhaustedAfterApprovalFailsTheExecution() {
        persistBudget(DataBudgetBreachAction.REJECT, 100L);
        var query = persistPendingAiQuery();
        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));
        awaitStatus(query.getId(), QueryStatus.APPROVED);
        spend(100);

        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(query.getId(),
                submitter.getId(), organization.getId(), false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);
        verify(queryExecutor, never()).execute(any());
        assertThat(budgetAuditMetadata(query.getId())).contains("\"stage\": \"execution\"");
    }

    @Test
    void aUsedPercentPolicyEscalatesAHeavyReader() {
        persistBudget(DataBudgetBreachAction.REJECT, 100L);
        spend(85);
        var policy = persistPolicy(new ConditionNode.DataBudgetUsedPercent(ComparisonOperator.GTE,
                80));
        var query = persistPendingAiQuery();

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(routingDecisionRepository.findByQueryRequestId(query.getId()).orElseThrow()
                .getMatchedPolicyId()).isEqualTo(policy.getId());
    }

    @Test
    void noBudgetLeavesBehaviourUnchangedAndWritesNoLedgerRow() {
        var query = persistPendingAiQuery();
        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));
        awaitStatus(query.getId(), QueryStatus.APPROVED);
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(List.of(),
                List.of(), 0L, false, Duration.ofMillis(3)));

        queryLifecycleService.execute(new ExecuteQueryCommand(query.getId(), submitter.getId(),
                organization.getId(), false));

        var request = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(request.capture());
        assertThat(request.getValue().maxResultBytesOverride()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM data_budget_usage "
                + "WHERE datasource_id = ?", Integer.class, datasource.getId())).isZero();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private void awaitStatus(UUID queryId, QueryStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queryRequestRepository.findById(queryId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private String budgetAuditMetadata(UUID queryId) {
        var rows = jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log WHERE "
                + "resource_id = ? AND action = 'QUERY_DATA_BUDGET_ENFORCED'", String.class,
                queryId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void spend(long rows) {
        var entry = new DataBudgetUsageEntity();
        entry.setId(UUID.randomUUID());
        entry.setOrganizationId(organization.getId());
        entry.setUserId(submitter.getId());
        entry.setDatasourceId(datasource.getId());
        entry.setRowsRead(rows);
        entry.setBytesRead(rows);
        entry.setSource(DataBudgetUsageSource.QUERY);
        entry.setOccurredAt(Instant.now().minusSeconds(30));
        usageRepository.save(entry);
    }

    private void persistBudget(DataBudgetBreachAction action, Long maxRows) {
        var budget = new DataBudgetEntity();
        budget.setId(UUID.randomUUID());
        budget.setOrganizationId(organization.getId());
        budget.setDatasourceId(datasource.getId());
        budget.setName("Daily");
        budget.setMaxRows(maxRows);
        budget.setWindowMinutes(1440);
        budget.setBreachAction(action);
        budget.setEnabled(true);
        dataBudgetRepository.save(budget);
    }

    private UserEntity persistUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("budget-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private ReviewPlanEntity persistPlan() {
        var reviewPlan = new ReviewPlanEntity();
        reviewPlan.setId(UUID.randomUUID());
        reviewPlan.setOrganization(organization);
        reviewPlan.setName("plan-" + UUID.randomUUID());
        reviewPlan.setRequiresAiReview(false);
        reviewPlan.setRequiresHumanApproval(false);
        reviewPlan.setMinApprovalsRequired(1);
        reviewPlan.setApprovalTimeoutHours(24);
        reviewPlan.setAutoApproveReads(false);
        reviewPlanRepository.save(reviewPlan);
        var rule = new ReviewPlanApproverEntity();
        rule.setId(UUID.randomUUID());
        rule.setReviewPlan(reviewPlan);
        rule.setRole("REVIEWER");
        rule.setStage(1);
        reviewPlanApproverRepository.save(rule);
        return reviewPlan;
    }

    private DatasourceEntity persistDatasource(ReviewPlanEntity reviewPlan) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("BUDGET-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setReviewPlan(reviewPlan);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity persistPendingAiQuery() {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
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
