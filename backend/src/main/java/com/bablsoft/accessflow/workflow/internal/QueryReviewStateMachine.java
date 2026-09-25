package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.ByteSizeFormat;
import com.bablsoft.accessflow.core.api.BytesScannedCapResolutionService;
import com.bablsoft.accessflow.core.api.AppliedBytesCap;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import com.bablsoft.accessflow.workflow.internal.SqlReviewSuppression.SuppressedAutoApproval;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Applies the {@code PENDING_AI → PENDING_REVIEW | APPROVED | REJECTED} transition on the AI module's
 * completion / failure / skipped events.
 *
 * <p>Deciding lives in {@link QueryDecisionEvaluator}; this class only carries the decision out —
 * persisting the routing decision, transitioning the query and publishing the matching event. The
 * split (issue AF-859) is what lets the access simulator replay a hypothetical request through the
 * identical rules without any of these side effects.
 *
 * <p>The chain itself is unchanged: the {@code RoutingPolicyEngine} runs first and the first enabled
 * policy by ascending priority whose typed condition matches decides — {@code AUTO_APPROVE} /
 * {@code AUTO_REJECT} short-circuit, {@code REQUIRE_APPROVALS} / {@code ESCALATE} force human review
 * with an effective approval-count override persisted on {@code routing_decision}. On no match, the
 * grant-covered fast-path (#582) runs next, and only then does the query fall through to the
 * datasource's review plan.
 *
 * <p>Every entry point first reads the SQL review findings persisted at submission (#864) and hands
 * the {@code BLOCK} rule ids to the evaluator, which turns every auto-approve path into human review
 * when any are present. When that actually changed the outcome, one {@code SQL_REVIEW_BLOCKED} audit
 * row is written here — system-attributed, {@code trigger=sql_review}.
 *
 * <p>The bytes-scanned cap (#941) is resolved and compared against the persisted pre-flight estimate
 * at every entry point and handed to the evaluator; the cap, its source and the comparison are
 * stamped on the query before the decision is applied, and a {@code QUERY_BYTES_SCANNED_CAP_ENFORCED}
 * audit row is written when the cap refused the query or forced it to review.
 *
 * <p>AI failure unconditionally lands in {@code PENDING_REVIEW} so a human can inspect the query. The
 * skipped path (datasource has {@code ai_analysis_enabled = false}) runs routing with no risk signal
 * — risk-based conditions evaluate to {@code false} — and otherwise respects
 * {@code plan.requires_human_approval}, never short-circuiting via {@code auto_approve_reads}, since
 * the SELECT/low-risk fast-path needs an AI risk signal.
 */
@Component
@RequiredArgsConstructor
class QueryReviewStateMachine {

    private static final Logger log = LoggerFactory.getLogger(QueryReviewStateMachine.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryDecisionEvaluator queryDecisionEvaluator;
    private final QueryRequestStateService queryRequestStateService;
    private final RoutingDecisionService routingDecisionService;
    private final SqlReviewFindingService sqlReviewFindingService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final ApplicationEventPublisher eventPublisher;
    private final BytesScannedCapResolutionService bytesScannedCapResolutionService;
    private final QueryCostEstimateService queryCostEstimateService;
    private final QueryEstimateLookupService queryEstimateLookupService;
    private final PlatformTransactionManager transactionManager;
    private final DataBudgetStatusService dataBudgetStatusService;

    // Time-of-day / day-of-week routing conditions evaluate in the server's local zone. A field
    // (not an injected bean) so it can be overridden in tests without colliding with the proxy's
    // UTC Clock bean.
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onAiCompleted(AiAnalysisCompletedEvent event) {
        // The AI analyzer computed the estimate before publishing, so it is only read here.
        var cap = prepare(event.queryRequestId(), false);
        var query = load(event.queryRequestId(), "AiAnalysisCompletedEvent");
        if (query != null) {
            decide(query, AiOutcome.COMPLETED, event.riskLevel(), event.riskScore(), cap);
        }
    }

    @ApplicationModuleListener
    void onAiSkipped(AiAnalysisSkippedEvent event) {
        // With AI off nothing has waited for the pre-flight estimate: an independent listener
        // computes it, and routing would race it, so an estimated_rows / estimated_bytes_scanned
        // policy would silently fail closed on an estimate that was about to exist (#941).
        var cap = prepare(event.queryRequestId(), true);
        var query = load(event.queryRequestId(), "AiAnalysisSkippedEvent");
        if (query != null) {
            decide(query, AiOutcome.SKIPPED, null, -1, cap);
        }
    }

    @ApplicationModuleListener
    void onAiFailed(AiAnalysisFailedEvent event) {
        var cap = prepare(event.queryRequestId(), false);
        var query = load(event.queryRequestId(), "AiAnalysisFailedEvent");
        if (query != null) {
            decide(query, AiOutcome.FAILED, null, -1, cap);
        }
    }

    /**
     * Resolves the bytes-scanned cap (#941) and makes sure the pre-flight estimate exists — when AI
     * was skipped, or when a cap applies — <em>before</em> the decision transaction reads anything.
     *
     * <p>It runs in its own transaction on purpose. The estimate write is a check-then-insert
     * that races the independent estimate listener on a unique key, and it updates the
     * version-checked {@code query_requests} row. Inside the decision transaction a lost race would
     * mark that transaction rollback-only — and with it the transition, stranding the query in
     * {@code PENDING_AI} — while a won race would leave the decision holding a stale query row.
     * Here a lost race only fails this inner transaction; the winner's row is read afterwards.
     */
    private AppliedBytesCap prepare(UUID queryRequestId, boolean alwaysEstimate) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return template.execute(status -> {
                var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
                if (query == null || query.status() != QueryStatus.PENDING_AI) {
                    return null;
                }
                var cap = bytesScannedCapResolutionService
                        .resolve(query.datasourceId(), query.submittedByUserId())
                        .orElse(null);
                if (alwaysEstimate || cap != null) {
                    queryCostEstimateService.estimateSubmittedQuery(queryRequestId);
                }
                return cap;
            });
        } catch (RuntimeException ex) {
            // Typically the estimate listener won the insert race. The cap itself is re-resolved
            // below; a missing estimate is a missing estimate, never an error.
            log.warn("Pre-flight estimate preparation failed for query {}: {}", queryRequestId,
                    ex.getMessage());
            return resolveQuietly(queryRequestId);
        }
    }

    private AppliedBytesCap resolveQuietly(UUID queryRequestId) {
        return queryRequestLookupService.findById(queryRequestId)
                .flatMap(q -> bytesScannedCapResolutionService.resolve(q.datasourceId(),
                        q.submittedByUserId()))
                .orElse(null);
    }

    private void decide(QueryRequestSnapshot query, AiOutcome aiOutcome, RiskLevel riskLevel,
                        int riskScore, AppliedBytesCap cap) {
        var bytesCap = cap == null ? null : BytesCapCheck.of(cap, estimatedBytes(query));
        var budget = dataBudget(query);
        var decision = queryDecisionEvaluator.evaluate(query, aiOutcome, riskLevel, riskScore,
                blockingRuleIds(query), bytesCap, budget, clock);
        // #942: the reviewer is deciding on a submitter whose budget is used up — only an approval
        // given here may later run the query past the exhausted budget.
        if (budget != null && budget.forcesReview()
                && decision.nextStatus() == QueryStatus.PENDING_REVIEW) {
            queryRequestStateService.recordDataBudgetReviewForced(query.id());
        }
        if (bytesCap != null) {
            queryRequestStateService.recordBytesScannedCap(query.id(), bytesCap.limit(),
                    bytesCap.source(), bytesCap.outcome());
        }
        apply(query, decision);
    }

    /** The persisted bytes estimate; absent, failed or unsupported all read as "none". */
    private Long estimatedBytes(QueryRequestSnapshot query) {
        return queryEstimateLookupService.findByQueryRequestId(query.id())
                .filter(e -> !e.failed())
                .map(QueryEstimateSnapshot::estimatedBytesScanned)
                .orElse(null);
    }

    /** The submitter's data-budget standing (#942); reads are the only thing a budget bounds. */
    private DataBudgetCheck dataBudget(QueryRequestSnapshot query) {
        if (query.queryType() != QueryType.SELECT) {
            return null;
        }
        return DataBudgetCheck.of(dataBudgetStatusService.statusFor(query.datasourceId(),
                query.submittedByUserId()));
    }

    private List<String> blockingRuleIds(QueryRequestSnapshot query) {
        return sqlReviewFindingService.blockingRuleIds(query.id());
    }

    /** @return the query when it is present and still awaiting a decision, {@code null} otherwise. */
    private QueryRequestSnapshot load(UUID queryRequestId, String eventName) {
        var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (query == null) {
            log.warn("{} for unknown query {}", eventName, queryRequestId);
            return null;
        }
        if (query.status() != QueryStatus.PENDING_AI) {
            log.warn("{} for query {} not in PENDING_AI (status={})", eventName, query.id(),
                    query.status());
            return null;
        }
        return query;
    }

    /**
     * Carry out the decision. Every branch is one persistence call plus one event; the routing
     * branches go through {@link RoutingDecisionService#applyDecision}, which writes the
     * {@code routing_decision} row and transitions the query in a single transaction.
     */
    private void apply(QueryRequestSnapshot query, QueryDecision decision) {
        var match = decision.routingMatch();
        switch (decision.kind()) {
            case ROUTING_AUTO_APPROVE -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.APPROVED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoApprovedEvent(query.id(), match.policyId(), match.reason()));
            }
            case ROUTING_AUTO_APPROVE_SUPPRESSED -> {
                // The policy still decided and is still recorded; its effect is review at the plan's
                // default threshold (#864).
                routingDecisionService.applyDecision(query.id(), QueryStatus.PENDING_REVIEW, match,
                        null);
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id(),
                        match.policyId(), match.reason(), null));
            }
            case ROUTING_AUTO_REJECT -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.REJECTED, match, null);
                eventPublisher.publishEvent(
                        new QueryAutoRejectedEvent(query.id(), match.policyId(), match.reason()));
            }
            case ROUTING_REQUIRE_APPROVALS, ROUTING_ESCALATE -> {
                routingDecisionService.applyDecision(query.id(), QueryStatus.PENDING_REVIEW, match,
                        decision.effectiveApprovals());
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.id(),
                        match.policyId(), match.reason(), decision.effectiveApprovals()));
            }
            case GRANT_FAST_PATH -> {
                queryRequestStateService.approveByAccessGrant(query.id(), decision.grantId());
                eventPublisher.publishEvent(new QueryAutoApprovedEvent(query.id(), null,
                        grantReason(decision), decision.grantId(), decision.grantApproverEmail()));
            }
            case PLAN_APPROVED, PLAN_PENDING_REVIEW, AI_FAILED_PENDING_REVIEW -> {
                queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI,
                        decision.nextStatus());
                eventPublisher.publishEvent(decision.nextStatus() == QueryStatus.APPROVED
                        ? new QueryAutoApprovedEvent(query.id())
                        : new QueryReadyForReviewEvent(query.id()));
            }
            case BYTES_CAP_REJECTED -> {
                queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI,
                        QueryStatus.REJECTED);
                eventPublisher.publishEvent(new QueryAutoRejectedEvent(query.id(), null,
                        bytesCapReason(decision.bytesCap())));
            }
            case DATA_BUDGET_REJECTED -> {
                queryRequestStateService.transitionTo(query.id(), QueryStatus.PENDING_AI,
                        QueryStatus.REJECTED);
                eventPublisher.publishEvent(new QueryAutoRejectedEvent(query.id(), null,
                        dataBudgetReason(decision.dataBudget())));
            }
            // A switch STATEMENT over an enum is not exhaustiveness-checked, so a new kind would
            // otherwise fall through silently and strand the query in PENDING_AI forever.
            default -> throw new IllegalStateException("Unhandled decision kind " + decision.kind());
        }
        if (decision.sqlReviewSuppression() != null) {
            auditSqlReviewBlocked(query, decision);
        }
        if (decision.bytesCapChangedOutcome()) {
            auditBytesCapEnforced(query, decision);
        }
        if (decision.dataBudgetChangedOutcome()) {
            auditDataBudgetEnforced(query, decision);
        }
        // Logged after the fact: a line claiming a query was auto-approved must not outlive a
        // persistence call that then failed.
        if (match != null) {
            log.info("Query {} routed by policy {} -> {}", query.id(), match.policyId(),
                    match.action());
        } else if (decision.kind() == QueryDecisionKind.GRANT_FAST_PATH) {
            log.info("Query {} auto-approved under access grant {}", query.id(), decision.grantId());
        } else if (decision.kind() == QueryDecisionKind.PLAN_PENDING_REVIEW
                && decision.trace().steps().stream().anyMatch(
                        step -> "workflow.decision.plan.absent".equals(step.reasonKey()))) {
            // The one operational breadcrumb for a misconfigured datasource; it used to be an INFO
            // line inside the decision logic, which now also runs for simulations.
            log.info("Query {} has no review plan; routed to PENDING_REVIEW", query.id());
        }
    }

    /**
     * Exactly one row per query whose outcome the block changed. Written after the transition so it
     * never claims a suppression that did not persist; a failure here must not undo the transition,
     * hence the lifecycle service's swallow-and-log convention for audit writes.
     */
    private void auditSqlReviewBlocked(QueryRequestSnapshot query, QueryDecision decision) {
        var suppression = decision.sqlReviewSuppression();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "sql_review");
        metadata.put("blocking_rule_ids", suppression.blockingRuleIds());
        metadata.put("suppressed_paths",
                suppression.paths().stream().map(SuppressedAutoApproval::name).toList());
        if (decision.routingMatch() != null) {
            metadata.put("matched_policy_id", decision.routingMatch().policyId());
        }
        try {
            auditLogService.record(new AuditEntry(AuditAction.SQL_REVIEW_BLOCKED,
                    AuditResourceType.QUERY_REQUEST, query.id(), query.organizationId(), null,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SQL_REVIEW_BLOCKED on query {}", query.id(), ex);
        }
    }

    /**
     * One row per query whose outcome the bytes-scanned cap changed (#941) — a refusal, or an
     * automatic approval turned into review. Swallow-and-log, like the SQL review row above.
     */
    private void auditBytesCapEnforced(QueryRequestSnapshot query, QueryDecision decision) {
        var cap = decision.bytesCap();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "bytes_scanned_cap");
        metadata.put("stage", "decision");
        metadata.put("limit", cap.limit());
        metadata.put("source", cap.source().name());
        if (cap.estimatedBytes() != null) {
            metadata.put("estimated_bytes", cap.estimatedBytes());
        }
        metadata.put("outcome", cap.outcome().name());
        if (decision.routingMatch() != null) {
            metadata.put("matched_policy_id", decision.routingMatch().policyId());
        }
        try {
            auditLogService.record(new AuditEntry(AuditAction.QUERY_BYTES_SCANNED_CAP_ENFORCED,
                    AuditResourceType.QUERY_REQUEST, query.id(), query.organizationId(), null,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for QUERY_BYTES_SCANNED_CAP_ENFORCED on query {}",
                    query.id(), ex);
        }
    }

    /**
     * One row per query whose outcome an exhausted data budget changed (#942) — a refusal, or an
     * automatic approval turned into review. Swallow-and-log, like the rows above.
     */
    private void auditDataBudgetEnforced(QueryRequestSnapshot query, QueryDecision decision) {
        var budget = decision.dataBudget();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "data_budget");
        metadata.put("stage", "decision");
        metadata.put("action", budget.action().name());
        if (budget.deciding() != null) {
            metadata.put("data_budget_id", budget.deciding().budgetId());
            metadata.put("used_rows", budget.deciding().usedRows());
            metadata.put("used_bytes", budget.deciding().usedBytes());
            metadata.put("window_minutes", budget.deciding().windowMinutes());
        }
        if (decision.routingMatch() != null) {
            metadata.put("matched_policy_id", decision.routingMatch().policyId());
        }
        try {
            auditLogService.record(new AuditEntry(AuditAction.QUERY_DATA_BUDGET_ENFORCED,
                    AuditResourceType.QUERY_REQUEST, query.id(), query.organizationId(), null,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for QUERY_DATA_BUDGET_ENFORCED on query {}", query.id(),
                    ex);
        }
    }

    /** Server-default locale for the same reason as {@link #grantReason}. */
    private String dataBudgetReason(DataBudgetCheck budget) {
        var name = budget.deciding() == null ? "-" : budget.deciding().name();
        return messageSource.getMessage("workflow.data_budget.rejected", new Object[]{name},
                Locale.getDefault());
    }

    /** Server-default locale for the same reason as {@link #grantReason}. */
    private String bytesCapReason(BytesCapCheck cap) {
        var limit = ByteSizeFormat.format(cap.limit());
        if (cap.estimatedBytes() == null) {
            return messageSource.getMessage("workflow.bytes_cap.rejected_no_estimate",
                    new Object[]{limit}, Locale.getDefault());
        }
        return messageSource.getMessage("workflow.bytes_cap.rejected_exceeded",
                new Object[]{ByteSizeFormat.format(cap.estimatedBytes()), limit},
                Locale.getDefault());
    }

    /**
     * Rendered here rather than in the evaluator: there is no request locale on this asynchronous
     * path, so the reason falls back to the server default, and the evaluator stays free of
     * localization concerns it cannot resolve.
     */
    private String grantReason(QueryDecision decision) {
        var approver = decision.grantApproverEmail() != null ? decision.grantApproverEmail() : "-";
        return messageSource.getMessage("workflow.grant_pre_approval.reason",
                new Object[]{decision.grantId(), approver}, Locale.getDefault());
    }
}
