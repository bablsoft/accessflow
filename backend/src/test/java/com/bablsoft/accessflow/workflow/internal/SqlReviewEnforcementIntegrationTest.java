package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.workflow.api.AccessSimulationInput;
import com.bablsoft.accessflow.workflow.api.AccessSimulationService;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #864 end to end: findings are written at submission and a {@code BLOCK} suppresses every
 * auto-approve path — routing {@code AUTO_APPROVE}, the grant fast path (#582), and both plan fast
 * paths — while never touching {@code AUTO_REJECT}, never gating a non-relational datasource, and
 * writing exactly one {@code SQL_REVIEW_BLOCKED} audit row per query whose outcome it changed.
 *
 * <p>The skipped path submits through {@link QuerySubmissionService} with
 * {@code ai_analysis_enabled=false}, so the real {@code AiAnalysisSkippedEvent} drives the state
 * machine. The completed path persists a {@code PENDING_AI} row, records findings the way the
 * submission service does, and publishes the completion event — the AI provider is not involved.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SqlReviewEnforcementIntegrationTest {

    private static final String SELECT_STAR = "SELECT * FROM orders";
    private static final String NARROW_SELECT = "SELECT id FROM orders";

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QuerySubmissionService querySubmissionService;
    @Autowired SqlReviewService sqlReviewService;
    @Autowired SqlReviewFindingService sqlReviewFindingService;
    @Autowired AccessSimulationService accessSimulationService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired RoutingPolicyRepository routingPolicyRepository;
    @Autowired RoutingDecisionRepository routingDecisionRepository;
    @Autowired RoutingConditionCodec routingConditionCodec;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private UserEntity approver;

    @BeforeEach
    void setUp() {
        cleanup();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        submitter = persistUser("submitter", UserRoleType.ANALYST);
        approver = persistUser("approver", UserRoleType.REVIEWER);
    }

    @AfterEach
    void cleanup() {
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM sql_review_rulesets WHERE organization_id = ?",
                    organization.getId());
        }
        routingDecisionRepository.deleteAll();
        routingPolicyRepository.deleteAll();
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        jdbcTemplate.update("DELETE FROM access_grant_decision");
        jdbcTemplate.update("DELETE FROM access_grant_request");
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    // ── Submission persists findings ──────────────────────────────────────────

    @Test
    void submissionPersistsFindingsBeforeTheAiIsAskedAndABlockForcesReviewOnTheSkippedPath() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(false, false), false, DbType.POSTGRESQL);

        var result = submit(datasource, SELECT_STAR);

        // Findings exist from the moment the row does — the AI never ran here at all.
        var findings = sqlReviewFindingService.findByQueryRequest(result.id());
        assertThat(findings).extracting("ruleId").contains("select_star");
        assertThat(findings).anyMatch(f -> f.ruleId().equals("select_star") && f.isBlocking());
        awaitStatus(result.id(), QueryStatus.PENDING_REVIEW);
        assertThat(blockedAuditRows(result.id())).isEqualTo(1);
    }

    @Test
    void warnFindingsChangeNothing() {
        seedRuleset("select_star", SqlReviewSeverity.WARN);
        var datasource = persistDatasource(persistPlan(false, false), false, DbType.POSTGRESQL);

        var result = submit(datasource, SELECT_STAR);

        assertThat(sqlReviewFindingService.findByQueryRequest(result.id()))
                .anyMatch(f -> f.ruleId().equals("select_star")
                        && f.severity() == SqlReviewSeverity.WARN);
        awaitStatus(result.id(), QueryStatus.APPROVED);
        assertThat(blockedAuditRows(result.id())).isZero();
    }

    @Test
    void offRulesProduceNoFindingsAtAll() {
        seedRuleset("select_star", SqlReviewSeverity.OFF);
        var datasource = persistDatasource(persistPlan(false, false), false, DbType.POSTGRESQL);

        var result = submit(datasource, SELECT_STAR);

        awaitStatus(result.id(), QueryStatus.APPROVED);
        assertThat(sqlReviewFindingService.findByQueryRequest(result.id()))
                .noneMatch(f -> f.ruleId().equals("select_star"));
        assertThat(sqlReviewFindingService.blockingRuleIds(result.id())).isEmpty();
    }

    // ── The three auto-approve paths ──────────────────────────────────────────

    @Test
    void blockBeatsRoutingAutoApproveAndThePolicyIsStillRecorded() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(true, false), true, DbType.POSTGRESQL);
        var policy = persistPolicy(RoutingAction.AUTO_APPROVE,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)));
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, SELECT_STAR);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        var decision = routingDecisionRepository.findByQueryRequestId(query.getId()).orElseThrow();
        assertThat(decision.getMatchedPolicyId()).isEqualTo(policy.getId());
        assertThat(decision.getAction()).isEqualTo(RoutingAction.AUTO_APPROVE);
        assertThat(blockedAuditRows(query.getId())).isEqualTo(1);
        assertThat(blockedAuditMetadata(query.getId()))
                .contains("\"trigger\": \"sql_review\"")
                .contains("ROUTING_AUTO_APPROVE")
                .contains("select_star")
                .contains(policy.getId().toString());
    }

    @Test
    void routingAutoRejectStillRejectsInThePresenceOfABlock() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(true, false), true, DbType.POSTGRESQL);
        persistPolicy(RoutingAction.AUTO_REJECT,
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT)));
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, SELECT_STAR);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.REJECTED);
        assertThat(blockedAuditRows(query.getId())).isZero();
    }

    @Test
    void blockBeatsTheGrantFastPath() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(true, false), true, DbType.POSTGRESQL);
        var grantId = persistPreApprovedGrant(datasource);
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, SELECT_STAR);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(queryRequestRepository.findById(query.getId()).orElseThrow().getApprovedByGrantId())
                .isNull();
        assertThat(blockedAuditRows(query.getId())).isEqualTo(1);
        assertThat(blockedAuditMetadata(query.getId())).contains("GRANT_FAST_PATH");
        // Control: the same grant still fast-paths a query with no blocking finding.
        var clean = persistPendingAiQuery(datasource, QueryType.SELECT, NARROW_SELECT);
        publish(new AiAnalysisCompletedEvent(clean.getId(), null, RiskLevel.LOW, 5));
        awaitStatus(clean.getId(), QueryStatus.APPROVED);
        assertThat(queryRequestRepository.findById(clean.getId()).orElseThrow().getApprovedByGrantId())
                .isEqualTo(grantId);
    }

    @Test
    void blockBeatsRequiresHumanApprovalFalse() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(false, false), true, DbType.POSTGRESQL);
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, SELECT_STAR);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(blockedAuditMetadata(query.getId())).contains("REVIEW_PLAN");
    }

    @Test
    void blockBeatsAutoApproveReads() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(true, true), true, DbType.POSTGRESQL);
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, SELECT_STAR);

        publish(new AiAnalysisCompletedEvent(query.getId(), null, RiskLevel.LOW, 5));

        awaitStatus(query.getId(), QueryStatus.PENDING_REVIEW);
        assertThat(blockedAuditRows(query.getId())).isEqualTo(1);
    }

    // ── The other entry points ────────────────────────────────────────────────

    @Test
    void aiFailedStillEvaluatesAndLandsInReviewWithoutASuppressionRow() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        // ai_analysis_enabled=true with no ai_config bound: the AI module publishes a real
        // AiAnalysisFailedEvent, which already forces review on its own.
        var datasource = persistDatasource(persistPlan(false, false), true, DbType.POSTGRESQL);

        var result = submit(datasource, SELECT_STAR);

        awaitStatus(result.id(), QueryStatus.PENDING_REVIEW);
        assertThat(sqlReviewFindingService.blockingRuleIds(result.id())).contains("select_star");
        assertThat(blockedAuditRows(result.id())).isZero();
    }

    @Test
    void aNonRelationalDatasourceIsEntirelyUnaffected() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(false, false), false, DbType.MONGODB);
        var query = persistPendingAiQuery(datasource, QueryType.SELECT, "{ find: 'orders' }");

        publish(new AiAnalysisSkippedEvent(query.getId(), "ai_analysis_enabled=false"));

        awaitStatus(query.getId(), QueryStatus.APPROVED);
        assertThat(sqlReviewFindingService.findByQueryRequest(query.getId())).isEmpty();
        assertThat(blockedAuditRows(query.getId())).isZero();
    }

    @Test
    void theAccessSimulatorReportsTheSameBlockTheLivePathEnforces() {
        seedRuleset("select_star", SqlReviewSeverity.BLOCK);
        var datasource = persistDatasource(persistPlan(false, false), false, DbType.POSTGRESQL);
        grantRead(datasource);

        var simulation = accessSimulationService.simulate(organization.getId(),
                new AccessSimulationInput(submitter.getId(), datasource.getId(), SELECT_STAR,
                        AiOutcome.SKIPPED, null, null));

        assertThat(simulation.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        var sqlReview = simulation.steps().stream()
                .filter(s -> s.step() == QueryDecisionStepKind.SQL_REVIEW).findFirst().orElseThrow();
        assertThat(sqlReview.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(sqlReview.details()).containsEntry("blocking_rule_ids", List.of("select_star"));
        var plan = simulation.steps().stream()
                .filter(s -> s.step() == QueryDecisionStepKind.REVIEW_PLAN).findFirst().orElseThrow();
        assertThat(plan.reasonKey()).isEqualTo("workflow.decision.plan.suppressed_sql_review");
        // Nothing persisted, nothing audited: the simulator only reads.
        assertThat(queryRequestRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'SQL_REVIEW_BLOCKED'", Long.class))
                .isZero();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private QuerySubmissionService.QuerySubmissionResult submit(DatasourceEntity datasource,
                                                                String sql) {
        return querySubmissionService.submit(new SubmissionInput(datasource.getId(), sql, "j",
                submitter.getId(), organization.getId(), true, null, null, null, null, false, null,
                null));
    }

    private void awaitStatus(UUID queryId, QueryStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queryRequestRepository.findById(queryId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private int blockedAuditRows(UUID queryId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log "
                + "WHERE resource_id = ? AND action = 'SQL_REVIEW_BLOCKED'", Integer.class, queryId);
    }

    private String blockedAuditMetadata(UUID queryId) {
        var rows = jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log "
                + "WHERE resource_id = ? AND action = 'SQL_REVIEW_BLOCKED'", String.class, queryId);
        assertThat(rows).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT actor_id FROM audit_log "
                + "WHERE resource_id = ? AND action = 'SQL_REVIEW_BLOCKED'", UUID.class, queryId))
                .isNull();
        return rows.get(0);
    }

    /** One organization-wide default ruleset with exactly one rule configured. */
    private void seedRuleset(String ruleId, SqlReviewSeverity severity) {
        var rulesetId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO sql_review_rulesets (id, organization_id, name, environment, "
                + "enabled) VALUES (?, ?, ?, NULL, true)", rulesetId, organization.getId(),
                "Default " + rulesetId);
        jdbcTemplate.update("INSERT INTO sql_review_rule_configs (id, ruleset_id, rule_id, severity, "
                + "params) VALUES (?, ?, ?, ?::sql_review_severity, NULL)", UUID.randomUUID(),
                rulesetId, ruleId, severity.name());
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private UserEntity persistUser(String prefix, UserRoleType role) {
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

    private void grantRead(DatasourceEntity datasource) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(submitter);
        permission.setCanRead(true);
        permission.setCreatedBy(approver);
        permissionRepository.save(permission);
    }

    private ReviewPlanEntity persistPlan(boolean requiresHumanApproval, boolean autoApproveReads) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(requiresHumanApproval);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(autoApproveReads);
        reviewPlanRepository.save(plan);

        var rule = new ReviewPlanApproverEntity();
        rule.setId(UUID.randomUUID());
        rule.setReviewPlan(plan);
        rule.setRole("REVIEWER");
        rule.setStage(1);
        reviewPlanApproverRepository.save(rule);
        return plan;
    }

    private DatasourceEntity persistDatasource(ReviewPlanEntity plan, boolean aiEnabled,
                                               DbType dbType) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("DS-" + UUID.randomUUID());
        ds.setDbType(dbType);
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
        ds.setAiAnalysisEnabled(aiEnabled);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    /**
     * A {@code PENDING_AI} row plus the findings the submission service would have recorded for it,
     * for the tests that need to drive the completion event by hand.
     */
    private QueryRequestEntity persistPendingAiQuery(DatasourceEntity ds, QueryType type,
                                                     String sql) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText(sql);
        query.setQueryType(type);
        query.setStatus(QueryStatus.PENDING_AI);
        var saved = queryRequestRepository.save(query);
        sqlReviewFindingService.recordForQuery(saved.getId(),
                sqlReviewService.evaluate(organization.getId(), ds.getId(), sql));
        return saved;
    }

    private RoutingPolicyEntity persistPolicy(RoutingAction action, ConditionNode condition) {
        var policy = new RoutingPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setOrganizationId(organization.getId());
        policy.setName("policy-" + UUID.randomUUID());
        policy.setPriority(1);
        policy.setEnabled(true);
        policy.setAction(action);
        policy.setRequiredApprovals(null);
        policy.setConditionJson(routingConditionCodec.encode(condition));
        return routingPolicyRepository.save(policy);
    }

    /** An APPROVED pre-approving grant scoped to {@code orders} (#582), via plain SQL — the access
     * module's entities are module-private. */
    private UUID persistPreApprovedGrant(DatasourceEntity datasource) {
        var grantId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO access_grant_request (id, organization_id, requester_id, datasource_id,
                    can_read, can_write, can_ddl, allowed_schemas, allowed_tables,
                    requested_duration, justification, status, expires_at, pre_approve_queries,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, false, false, NULL, ARRAY['orders'], 'PT4H', 'j',
                    'APPROVED'::access_grant_status, ?, true, 0, now(), now())
                """, grantId, organization.getId(), submitter.getId(), datasource.getId(),
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(4))));
        jdbcTemplate.update("""
                INSERT INTO access_grant_decision (id, access_grant_request_id, reviewer_id,
                    decision, stage, comment, decided_at)
                VALUES (?, ?, ?, 'APPROVED'::decision, 1, NULL, now())
                """, UUID.randomUUID(), grantId, approver.getId());
        return grantId;
    }
}
