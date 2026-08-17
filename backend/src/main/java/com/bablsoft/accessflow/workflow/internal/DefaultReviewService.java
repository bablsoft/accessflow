package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApprovalPredictionLookupService;
import com.bablsoft.accessflow.core.api.ApprovalPredictionSnapshot;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.DelegatedIdentity;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordApprovalCommand;
import com.bablsoft.accessflow.core.api.ReviewCandidate;
import com.bablsoft.accessflow.core.api.ReviewDecisionSnapshot;
import com.bablsoft.accessflow.core.api.ReviewDelegationLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewStages;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.workflow.api.QueryNotPendingReviewException;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewerNotEligibleException;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
import com.bablsoft.accessflow.workflow.events.ReviewDecisionMadeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultReviewService implements ReviewService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final ReviewDelegationLookupService reviewDelegationLookupService;
    private final RoutingDecisionService routingDecisionService;
    private final ApprovalPredictionLookupService approvalPredictionLookupService;
    private final com.bablsoft.accessflow.core.api.UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingReview> listPendingForReviewer(ReviewerContext context,
                                                              PageRequest pageRequest) {
        if (!hasReviewPermission(context)) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        // Resolved once for the whole page. A per-row lookup here would be an N+1 on every queue
        // render, the same reason approvalProbabilities is batched below. Unscoped at this point
        // because the page spans datasources; each row filters to its own below.
        var delegations = reviewDelegationLookupService.findActiveForDelegate(
                context.organizationId(), context.userId(), null, null);
        var principalIds = new ArrayList<UUID>();
        principalIds.add(context.userId());
        delegations.stream().map(DelegatedIdentity::delegatorUserId).forEach(principalIds::add);
        var roleNames = new ArrayList<String>();
        if (context.roleName() != null) {
            roleNames.add(context.roleName().toLowerCase(Locale.ROOT));
        }
        delegations.stream()
                .map(DelegatedIdentity::delegatorRoleName)
                .filter(Objects::nonNull)
                .map(role -> role.toLowerCase(Locale.ROOT))
                .forEach(roleNames::add);
        var page = queryRequestLookupService.findPendingForReviewer(context.organizationId(),
                context.userId(), principalIds, roleNames, pageRequest);
        var actionable = new ArrayList<PendingReviewView>();
        var matchedIdentity = new HashMap<UUID, ReviewCandidate>();
        for (var view : page.content()) {
            actionableAs(view, context, delegations).ifPresent(candidate -> {
                actionable.add(view);
                matchedIdentity.put(view.queryRequestId(), candidate);
            });
        }
        var probabilities = approvalProbabilities(actionable);
        var delegators = delegatorRefs(matchedIdentity.values());
        var visible = actionable.stream()
                .map(view -> toPendingReview(view, context,
                        probabilities.get(view.queryRequestId()),
                        matchedIdentity.get(view.queryRequestId()), delegators))
                .toList();
        return new PageResponse<>(visible, page.page(), page.size(), page.totalElements(),
                page.totalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID queryRequestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(queryRequestId, context);
        var command = new RecordApprovalCommand(queryRequestId, context.userId(),
                prep.currentStage(),
                prep.effectiveMinApprovals(),
                prep.currentStage() == prep.plan().maxStage(),
                comment,
                prep.onBehalfOfUserId(),
                prep.delegationId());
        var result = mapTransitionFailure(queryRequestId,
                () -> queryRequestStateService.recordApprovalAndAdvance(command));
        if (result.resultingStatus() == QueryStatus.APPROVED && !result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new QueryApprovedEvent(queryRequestId, context.userId()));
        }
        if (!result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new ReviewDecisionMadeEvent(queryRequestId,
                    prep.submitterId(), context.userId(), DecisionType.APPROVED, comment));
        }
        return new DecisionOutcome(result.decisionId(), DecisionType.APPROVED,
                result.resultingStatus(), result.wasIdempotentReplay());
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID queryRequestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(queryRequestId, context);
        var result = mapTransitionFailure(queryRequestId,
                () -> queryRequestStateService.recordRejection(queryRequestId, context.userId(),
                        prep.currentStage(), comment, prep.onBehalfOfUserId(),
                        prep.delegationId()));
        if (result.resultingStatus() == QueryStatus.REJECTED && !result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new QueryRejectedEvent(queryRequestId, context.userId()));
        }
        if (!result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new ReviewDecisionMadeEvent(queryRequestId,
                    prep.submitterId(), context.userId(), DecisionType.REJECTED, comment));
        }
        return new DecisionOutcome(result.decisionId(), DecisionType.REJECTED,
                result.resultingStatus(), result.wasIdempotentReplay());
    }

    @Override
    @Transactional
    public DecisionOutcome requestChanges(UUID queryRequestId, ReviewerContext context,
                                          String comment) {
        var prep = prepareDecision(queryRequestId, context);
        var result = mapTransitionFailure(queryRequestId,
                () -> queryRequestStateService.recordChangesRequested(queryRequestId,
                        context.userId(), prep.currentStage(), comment, prep.onBehalfOfUserId(),
                        prep.delegationId()));
        if (!result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new ReviewDecisionMadeEvent(queryRequestId,
                    prep.submitterId(), context.userId(), DecisionType.REQUESTED_CHANGES,
                    comment));
        }
        return new DecisionOutcome(result.decisionId(), DecisionType.REQUESTED_CHANGES,
                result.resultingStatus(), result.wasIdempotentReplay());
    }

    @Override
    public BulkDecisionOutcome bulkDecide(List<UUID> queryRequestIds, DecisionType decision,
                                          ReviewerContext context, String comment) {
        // Intentionally NOT @Transactional. Each row delegates to the single-row entry
        // point; the actual database write inside (QueryRequestStateService) is its own
        // bean and starts its own transaction, so a per-row failure cannot poison a
        // successful peer.
        var rows = new ArrayList<RowOutcome>(queryRequestIds.size());
        for (UUID queryRequestId : queryRequestIds) {
            rows.add(decideOne(queryRequestId, decision, context, comment));
        }
        return new BulkDecisionOutcome(List.copyOf(rows));
    }

    // Per-row dispatch. Each branch delegates to the single-row entry point so semantics,
    // events, and persistence stay identical. Per-row failures are mapped to a RowStatus so
    // they do not roll back successful peers.
    private RowOutcome decideOne(UUID queryRequestId, DecisionType decision,
                                 ReviewerContext context, String comment) {
        try {
            var outcome = switch (decision) {
                case APPROVED -> approve(queryRequestId, context, comment);
                case REJECTED -> reject(queryRequestId, context, comment);
                case REQUESTED_CHANGES -> requestChanges(queryRequestId, context, comment);
            };
            return RowOutcome.success(queryRequestId, outcome);
        } catch (QueryRequestNotFoundException ex) {
            return RowOutcome.failure(queryRequestId, RowStatus.NOT_FOUND,
                    "QUERY_REQUEST_NOT_FOUND", msg("error.query_request_not_found"));
        } catch (AccessDeniedException ex) {
            return RowOutcome.failure(queryRequestId, RowStatus.FORBIDDEN,
                    "FORBIDDEN",
                    ex.getMessage() != null ? ex.getMessage() : msg("error.forbidden"));
        } catch (ReviewerNotEligibleException ex) {
            return RowOutcome.failure(queryRequestId, RowStatus.FORBIDDEN,
                    "REVIEWER_NOT_ELIGIBLE", msg("error.reviewer_not_eligible"));
        } catch (QueryNotPendingReviewException ex) {
            return RowOutcome.failure(queryRequestId, RowStatus.INVALID_STATE,
                    "QUERY_NOT_PENDING_REVIEW", msg("error.query_not_pending_review"));
        } catch (RuntimeException ex) {
            // Server-side bug, not a per-row business outcome — log and bubble up so the
            // batch fails fast rather than silently swallowing the error.
            log.error("Unexpected error during bulk decision for query {}", queryRequestId, ex);
            throw ex;
        }
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private DecisionPreparation prepareDecision(UUID queryRequestId, ReviewerContext context) {
        var view = queryRequestLookupService.findPendingReview(queryRequestId)
                .orElseThrow(() -> new QueryRequestNotFoundException(queryRequestId));
        if (!view.organizationId().equals(context.organizationId())) {
            throw new QueryRequestNotFoundException(queryRequestId);
        }
        if (view.status() != QueryStatus.PENDING_REVIEW) {
            throw new QueryNotPendingReviewException(queryRequestId, view.status());
        }
        if (view.submittedByUserId().equals(context.userId())) {
            throw new AccessDeniedException("A reviewer cannot review their own query request");
        }
        // Deliberately before delegation resolution: a delegation widens which requests an
        // already-permitted reviewer may act on, and can never confer the permission itself.
        if (!hasReviewPermission(context)) {
            throw new ReviewerNotEligibleException(context.userId(), queryRequestId);
        }
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId())
                .orElseThrow(() -> new ReviewerNotEligibleException(context.userId(),
                        queryRequestId));
        if (!plan.organizationId().equals(view.organizationId())) {
            throw new ReviewerNotEligibleException(context.userId(), queryRequestId);
        }
        var effectiveMin = effectiveMinApprovals(queryRequestId, plan);
        var decisions = queryRequestStateService.listDecisions(queryRequestId);
        var currentStage = currentStage(plan, decisions, effectiveMin);
        var match = candidates(context, view, decisions, currentStage).stream()
                // Both predicates must be satisfied by the SAME identity. Matching the approver
                // rule as delegator A and the datasource scope as delegator B would synthesize an
                // identity neither of them holds.
                .filter(candidate -> isApproverAtStage(plan, currentStage, candidate))
                .filter(candidate -> isInDatasourceScope(view.datasourceId(), candidate.userId()))
                .findFirst()
                .orElseThrow(() -> new ReviewerNotEligibleException(context.userId(),
                        queryRequestId));
        return new DecisionPreparation(plan, currentStage, effectiveMin, view.submittedByUserId(),
                match.onBehalfOfUserId(), match.delegationId());
    }

    /**
     * The identities the caller may be evaluated under: their own first, then any borrowed through
     * an active out-of-office delegation (#622), ordered deterministically so a replayed decision
     * records the same provenance.
     */
    private List<ReviewCandidate> candidates(ReviewerContext context, PendingReviewView view,
                                             List<ReviewDecisionSnapshot> decisions,
                                             int currentStage) {
        var delegations = reviewDelegationLookupService.findActiveForDelegate(
                context.organizationId(), context.userId(), DelegationScopeKind.DATASOURCE,
                view.datasourceId());
        return candidates(context, view.submittedByUserId(), delegations, decisions, currentStage);
    }

    private static List<ReviewCandidate> candidates(ReviewerContext context, UUID submitterId,
                                                    List<DelegatedIdentity> delegations,
                                                    List<ReviewDecisionSnapshot> decisions,
                                                    int currentStage) {
        var candidates = new ArrayList<ReviewCandidate>();
        candidates.add(ReviewCandidate.self(context.userId(), context.roleName()));
        delegations.stream().map(ReviewCandidate::borrowed).forEach(candidates::add);
        // The self-approval ban covers both identities: a delegate may not act on a request the
        // delegator submitted. Drop that candidate rather than reject outright — the delegate may
        // still qualify in their own right, or through a different delegator.
        candidates.removeIf(candidate -> candidate.isDelegated()
                && submitterId.equals(candidate.onBehalfOfUserId()));
        // One authority, one vote. The unique index only stops the acting user voting twice; it
        // cannot see that a delegator already voted personally, or that a different delegate
        // already voted for them.
        //
        // Decisions the acting user cast themselves are excluded from this guard: that is a
        // replay, which the state service answers idempotently downstream. Without the exclusion a
        // retry — or any bulk re-run — would be rejected as ineligible instead, breaking the
        // documented wasIdempotentReplay contract for exactly the multi-approver plans where a
        // second attempt is reachable.
        candidates.removeIf(candidate ->
                votedByAnotherActorAtStage(decisions, currentStage, candidate.userId(),
                        context.userId()));
        return candidates;
    }

    private static boolean votedByAnotherActorAtStage(List<ReviewDecisionSnapshot> decisions,
                                                      int stage, UUID authorityUserId,
                                                      UUID actingUserId) {
        return decisions.stream()
                .filter(decision -> decision.stage() == stage)
                .filter(decision -> !actingUserId.equals(decision.reviewerId()))
                .anyMatch(decision -> authorityUserId.equals(decision.reviewerId())
                        || authorityUserId.equals(decision.onBehalfOfUserId()));
    }

    /**
     * The effective minimum approvals for a query — a routing-policy override (ESCALATE /
     * REQUIRE_APPROVALS) when one was recorded, otherwise the review plan's value.
     */
    private int effectiveMinApprovals(UUID queryRequestId, ReviewPlanSnapshot plan) {
        return routingDecisionService.findEffectiveMinApprovals(queryRequestId)
                .orElseGet(plan::minApprovalsRequired);
    }

    private boolean isInDatasourceScope(UUID datasourceId, UUID userId) {
        var eligible = reviewerEligibilityService.findEligibleReviewerIds(datasourceId);
        return eligible.map(set -> set.contains(userId)).orElse(true);
    }

    private static int currentStage(ReviewPlanSnapshot plan,
                                    List<ReviewDecisionSnapshot> decisions,
                                    int minApprovalsRequired) {
        // Shared with the notification layer (#622) so who may act and who is told cannot drift.
        return ReviewStages.current(plan, decisions, minApprovalsRequired);
    }

    private static boolean isApproverAtStage(ReviewPlanSnapshot plan, int stage,
                                             ReviewCandidate candidate) {
        return plan.approvers().stream()
                .filter(rule -> rule.stage() == stage)
                .anyMatch(rule -> matchesUser(rule, candidate) || matchesRole(rule, candidate));
    }

    private static boolean matchesUser(ApproverRule rule, ReviewCandidate candidate) {
        return rule.userId() != null && rule.userId().equals(candidate.userId());
    }

    private static boolean matchesRole(ApproverRule rule, ReviewCandidate candidate) {
        return rule.role() != null && rule.role().equalsIgnoreCase(candidate.roleName());
    }

    private static boolean hasReviewPermission(ReviewerContext context) {
        return context.permissions() != null
                && context.permissions().contains(Permission.QUERY_REVIEW);
    }

    /**
     * Exact per-row re-check behind the deliberately over-approximating queue query, which flattens
     * delegated identities into one id list and so cannot tell which identity matched what.
     *
     * <p>Returns the matching identity rather than a boolean so the caller gets the queue filter and
     * the "delegated" badge from one pass — recomputing the plan, decisions, routing override and
     * datasource scope a second time per row would be a needless N+1 on every queue render.
     */
    private Optional<ReviewCandidate> actionableAs(PendingReviewView view, ReviewerContext context,
                                                   List<DelegatedIdentity> delegations) {
        if (view.submittedByUserId().equals(context.userId())) {
            return Optional.empty();
        }
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId()).orElse(null);
        if (plan == null) {
            return Optional.empty();
        }
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions,
                effectiveMinApprovals(view.queryRequestId(), plan));
        var applicable = delegations.stream()
                .filter(identity -> identity.covers(DelegationScopeKind.DATASOURCE,
                        view.datasourceId()))
                .toList();
        return candidates(context, view.submittedByUserId(), applicable, decisions, stage).stream()
                .filter(candidate -> isApproverAtStage(plan, stage, candidate))
                .filter(candidate -> isInDatasourceScope(view.datasourceId(), candidate.userId()))
                .findFirst();
    }

    /**
     * One batch lookup for the whole page — a per-row call here would be an N+1 on every queue
     * render. Sentinel rows (skipped / failed) carry no probability and are simply absent from the
     * map, which {@code Collectors.toMap} would reject as a null value anyway. No merge function:
     * {@code approval_predictions.query_request_id} is UNIQUE, so a duplicate key is a broken
     * invariant that should throw rather than be silently resolved.
     */
    private Map<UUID, Double> approvalProbabilities(List<PendingReviewView> views) {
        if (views.isEmpty()) {
            return Map.of();
        }
        var queryRequestIds = views.stream().map(PendingReviewView::queryRequestId).toList();
        return approvalPredictionLookupService.findByQueryRequestIds(queryRequestIds).stream()
                .filter(snapshot -> snapshot.probability() != null)
                .collect(Collectors.toMap(ApprovalPredictionSnapshot::queryRequestId,
                        ApprovalPredictionSnapshot::probability));
    }

    /** One batch lookup for the delegators named across the page — not one per row. */
    private Map<UUID, com.bablsoft.accessflow.core.api.UserView> delegatorRefs(
            Collection<ReviewCandidate> matched) {
        var ids = matched.stream()
                .map(ReviewCandidate::onBehalfOfUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userQueryService.findByIds(ids).stream()
                .collect(Collectors.toMap(com.bablsoft.accessflow.core.api.UserView::id,
                        user -> user));
    }

    private PendingReview toPendingReview(PendingReviewView view, ReviewerContext context,
                                          Double approvalProbability, ReviewCandidate matched,
                                          Map<UUID, com.bablsoft.accessflow.core.api.UserView> delegators) {
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId()).orElseThrow();
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions,
                effectiveMinApprovals(view.queryRequestId(), plan));
        var delegator = matched.onBehalfOfUserId() == null
                ? null : delegators.get(matched.onBehalfOfUserId());
        return new PendingReview(
                view.queryRequestId(),
                view.datasourceId(),
                view.datasourceName(),
                view.submittedByUserId(),
                view.submittedByEmail(),
                view.sqlText(),
                view.queryType(),
                view.justification(),
                view.aiAnalysisId(),
                view.aiRiskLevel(),
                view.aiRiskScore(),
                view.aiSummary(),
                approvalProbability,
                stage,
                view.createdAt(),
                matched.onBehalfOfUserId(),
                delegator == null ? null : delegator.email(),
                delegator == null ? null : delegator.displayName());
    }

    private static <T> T mapTransitionFailure(UUID queryRequestId,
                                              java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalQueryStatusTransitionException ex) {
            throw new QueryNotPendingReviewException(queryRequestId, ex.actual());
        }
    }

    /**
     * The resolved decision context. {@code onBehalfOfUserId} / {@code delegationId} are null
     * unless the reviewer qualified only through an out-of-office delegation (#622).
     */
    private record DecisionPreparation(ReviewPlanSnapshot plan, int currentStage,
                                       int effectiveMinApprovals, UUID submitterId,
                                       UUID onBehalfOfUserId, UUID delegationId) {
    }
}
