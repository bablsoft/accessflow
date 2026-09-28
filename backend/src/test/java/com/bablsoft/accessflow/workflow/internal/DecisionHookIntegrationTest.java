package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
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
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.CreateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionHookScopeConflictException;
import com.bablsoft.accessflow.workflow.api.DecisionHookService;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingDecisionSource;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookResultRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingDecisionRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * #945 end to end: a real signed HTTP endpoint answers for queries leaving {@code PENDING_AI}. The
 * acceptance criteria are pinned here — a reject rejects, an escalation raises the approval count,
 * a timeout / bad signature / approval attempt all land in human review and never in
 * {@code APPROVED} — against a review plan that would otherwise auto-approve everything.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@TestPropertySource(properties = {
        "accessflow.workflow.decision-hook.allow-private-network=true",
        "accessflow.workflow.decision-hook.circuit-failure-threshold=1000"})
class DecisionHookIntegrationTest {

    private static final String SQL = "SELECT * FROM orders";
    private static final String SECRET = "integration-secret-0123456789abcdef";

    @MockitoBean QueryExecutor queryExecutor;
    @MockitoBean QueryParser queryParser;

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
    @Autowired DecisionHookResultRepository decisionHookResultRepository;
    @Autowired RoutingConditionCodec routingConditionCodec;
    @Autowired DecisionHookService decisionHookService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private HttpServer server;
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private ReviewPlanEntity plan;

    /** What the stub endpoint does with the next request. */
    private record Answer(String decision, Integer approvals, String secret, long delayMs) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/decide", exchange -> {
            calls.incrementAndGet();
            var request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            var current = answer.get();
            sleep(current.delayMs());
            var body = new StringBuilder("{\"request_id\":\"")
                    .append(request.get("request_id").asString())
                    .append("\",\"decision\":\"").append(current.decision()).append('"');
            if (current.approvals() != null) {
                body.append(",\"approvals\":").append(current.approvals());
            }
            var bytes = body.append(",\"reason\":\"stub says so\"}").toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-AccessFlow-Signature", sign(bytes, current.secret()));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Hook org");
        organization.setSlug("hook-" + UUID.randomUUID());
        organizationRepository.save(organization);
        submitter = persistUser();
        plan = persistPlan();
        datasource = persistDatasource(plan);
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, SQL));
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("postgresql"));
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        var orgId = organization.getId();
        var dsId = datasource.getId();
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", orgId);
        jdbcTemplate.update("DELETE FROM user_notifications WHERE user_id = ?", submitter.getId());
        jdbcTemplate.update("DELETE FROM routing_decision WHERE query_request_id IN "
                + "(SELECT id FROM query_requests WHERE datasource_id = ?)", dsId);
        jdbcTemplate.update("DELETE FROM routing_policy WHERE organization_id = ?", orgId);
        jdbcTemplate.update("DELETE FROM decision_hooks WHERE organization_id = ?", orgId);
        jdbcTemplate.update("UPDATE query_requests SET query_estimate_id = NULL WHERE datasource_id = ?",
                dsId);
        jdbcTemplate.update("DELETE FROM query_estimates WHERE query_request_id IN "
                + "(SELECT id FROM query_requests WHERE datasource_id = ?)", dsId);
        jdbcTemplate.update("DELETE FROM query_requests WHERE datasource_id = ?", dsId);
        datasourceRepository.deleteById(dsId);
        jdbcTemplate.update("DELETE FROM review_plan_approvers WHERE review_plan_id = ?", plan.getId());
        reviewPlanRepository.deleteById(plan.getId());
        userRepository.deleteById(submitter.getId());
        organizationRepository.deleteById(orgId);
    }

    @Test
    void aHookThatRejectsRejectsTheQueryAndRecordsItsProvenance() {
        var hookId = createHook(2000);
        answer.set(new Answer("REJECT", null, SECRET, 0));
        var query = submit();

        awaitStatus(query, QueryStatus.REJECTED);

        var decision = routingDecisionRepository.findByQueryRequestId(query).orElseThrow();
        assertThat(decision.getSource()).isEqualTo(RoutingDecisionSource.DECISION_HOOK);
        assertThat(decision.getDecisionHookId()).isEqualTo(hookId);
        assertThat(decision.getMatchedPolicyId()).isNull();
        assertThat(decision.getAction()).isEqualTo(RoutingAction.AUTO_REJECT);
        assertThat(decision.getReason()).isEqualTo("stub says so");
        assertThat(result(query).getOutcome()).isEqualTo(DecisionHookOutcome.REJECT);
        assertThat(auditMetadata(query, "QUERY_DECISION_HOOK_EVALUATED"))
                .contains("\"outcome\": \"REJECT\"");
        assertThat(auditMetadata(query, "QUERY_REJECTED"))
                .contains("\"source\": \"DECISION_HOOK\"");
    }

    @Test
    void aHookThatEscalatesRaisesTheApprovalCount() {
        createHook(2000);
        answer.set(new Answer("ESCALATE", 2, SECRET, 0));
        var query = submit();

        awaitStatus(query, QueryStatus.PENDING_REVIEW);

        var decision = routingDecisionRepository.findByQueryRequestId(query).orElseThrow();
        assertThat(decision.getAction()).isEqualTo(RoutingAction.ESCALATE);
        assertThat(decision.getEffectiveMinApprovals()).isEqualTo(3);
    }

    @Test
    void aHookThatTimesOutSendsTheQueryToHumanReview() {
        createHook(200);
        answer.set(new Answer("ALLOW", null, SECRET, 1500));
        var query = submit();

        awaitStatus(query, QueryStatus.PENDING_REVIEW);

        var result = result(query);
        assertThat(result.getOutcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(result.getFailure()).isEqualTo(DecisionHookFailure.TIMEOUT);
        assertThat(routingDecisionRepository.findByQueryRequestId(query)).isEmpty();
    }

    @Test
    void aHookWhoseSignatureFailsSendsTheQueryToHumanReview() {
        createHook(2000);
        answer.set(new Answer("ALLOW", null, "somebody-elses-secret-0123456789", 0));
        var query = submit();

        awaitStatus(query, QueryStatus.PENDING_REVIEW);

        assertThat(result(query).getFailure()).isEqualTo(DecisionHookFailure.SIGNATURE_MISMATCH);
    }

    @Test
    void aHookCannotApprove() {
        createHook(2000);
        answer.set(new Answer("AUTO_APPROVE", null, SECRET, 0));
        var query = submit();

        awaitStatus(query, QueryStatus.PENDING_REVIEW);

        assertThat(result(query).getFailure()).isEqualTo(DecisionHookFailure.INVALID_DECISION);
    }

    @Test
    void aHookThatAllowsLeavesThePlanToApprove() {
        createHook(2000);
        answer.set(new Answer("ALLOW", null, SECRET, 0));
        var query = submit();

        awaitStatus(query, QueryStatus.APPROVED);

        assertThat(result(query).getOutcome()).isEqualTo(DecisionHookOutcome.ALLOW);
    }

    @Test
    void aMatchingPolicyDecidesWithoutConsultingTheHook() {
        createHook(2000);
        answer.set(new Answer("REJECT", null, SECRET, 0));
        persistPolicy();
        var query = submit();

        awaitStatus(query, QueryStatus.PENDING_REVIEW);

        assertThat(calls).hasValue(0);
        assertThat(routingDecisionRepository.findByQueryRequestId(query).orElseThrow().getSource())
                .isEqualTo(RoutingDecisionSource.POLICY);
        assertThat(decisionHookResultRepository.findByQueryRequestId(query)).isEmpty();
    }

    @Test
    void aSecondOrganizationDefaultIsRefused() {
        createHook(2000);

        assertThatThrownBy(() -> createHook(2000))
                .isInstanceOf(DecisionHookScopeConflictException.class);
    }

    @Test
    void theStoredSecretIsEncryptedAndTheTestEndpointVerifiesIt() {
        var hookId = createHook(2000);
        answer.set(new Answer("ALLOW", null, SECRET, 0));

        var stored = jdbcTemplate.queryForObject(
                "SELECT secret_encrypted FROM decision_hooks WHERE id = ?", String.class, hookId);
        assertThat(stored).isNotEqualTo(SECRET);
        assertThat(encryptionService.decrypt(stored)).isEqualTo(SECRET);
        var result = decisionHookService.test(organization.getId(), hookId);
        assertThat(result.outcome()).isEqualTo(DecisionHookOutcome.ALLOW);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID createHook(int timeoutMs) {
        return decisionHookService.create(new CreateDecisionHookCommand(organization.getId(), null,
                "Stub", "http://127.0.0.1:" + server.getAddress().getPort() + "/decide", timeoutMs,
                SECRET, false, true)).id();
    }

    private UUID submit() {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText(SQL);
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_AI);
        queryRequestRepository.save(query);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new AiAnalysisSkippedEvent(query.getId(),
                        "ai_analysis_enabled=false")));
        return query.getId();
    }

    private void awaitStatus(UUID queryId, QueryStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(queryRequestRepository.findById(queryId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookResultEntity
            result(UUID queryId) {
        return decisionHookResultRepository.findByQueryRequestId(queryId).orElseThrow();
    }

    private String auditMetadata(UUID queryId, String action) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !jdbcTemplate.queryForList(
                "SELECT metadata::text FROM audit_log WHERE resource_id = ? AND action = ?",
                String.class, queryId, action).isEmpty());
        var rows = jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log WHERE "
                + "resource_id = ? AND action = ?", String.class, queryId, action);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String sign(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private UserEntity persistUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("hook-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    /** Auto-approves everything, so any PENDING_REVIEW below is the hook's doing. */
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
        ds.setName("HOOK-" + UUID.randomUUID());
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

    private void persistPolicy() {
        var policy = new RoutingPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setOrganizationId(organization.getId());
        policy.setName("policy-" + UUID.randomUUID());
        policy.setPriority(1);
        policy.setEnabled(true);
        policy.setAction(RoutingAction.ESCALATE);
        policy.setRequiredApprovals(1);
        policy.setConditionJson(routingConditionCodec.encode(
                new ConditionNode.QueryTypeIn(Set.of(QueryType.SELECT))));
        routingPolicyRepository.save(policy);
    }
}
