package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordApprovalCommand;
import com.bablsoft.accessflow.core.api.RecordDecisionResult;
import com.bablsoft.accessflow.core.api.ReviewDecisionSnapshot;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserRoleType;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultReviewService implements ReviewService {

    private static final Set<UserRoleType> REVIEWER_ROLES = Set.of(UserRoleType.REVIEWER,
            UserRoleType.ADMIN);

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final RoutingDecisionService routingDecisionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingReview> listPendingForReviewer(ReviewerContext context,
                                                              PageRequest pageRequest) {
        if (!REVIEWER_ROLES.contains(context.role())) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var page = queryRequestLookupService.findPendingForReviewer(context.organizationId(),
                context.userId(), context.role(), pageRequest);
        var visible = page.content().stream()
                .filter(view -> isCurrentlyActionable(view, context))
                .map(view -> toPendingReview(view, context))
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
                comment);
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
                        prep.currentStage(), comment));
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
                        context.userId(), prep.currentStage(), comment));
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
        if (!REVIEWER_ROLES.contains(context.role())) {
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
        if (!isApproverAtStage(plan, currentStage, context)) {
            throw new ReviewerNotEligibleException(context.userId(), queryRequestId);
        }
        if (!isInDatasourceScope(view.datasourceId(), context.userId())) {
            throw new ReviewerNotEligibleException(context.userId(), queryRequestId);
        }
        return new DecisionPreparation(plan, currentStage, effectiveMin, view.submittedByUserId());
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
        var stages = plan.approvers().stream()
                .map(ApproverRule::stage)
                .distinct()
                .sorted()
                .toList();
        for (int stage : stages) {
            long approvedAtStage = decisions.stream()
                    .filter(d -> d.stage() == stage && d.decision() == DecisionType.APPROVED)
                    .count();
            if (approvedAtStage < minApprovalsRequired) {
                return stage;
            }
        }
        // All stages have met threshold — defensive: fall back to max stage so the caller's
        // PENDING_REVIEW guard surfaces an illegal-transition error.
        return plan.maxStage();
    }

    private static boolean isApproverAtStage(ReviewPlanSnapshot plan, int stage,
                                             ReviewerContext context) {
        return plan.approvers().stream()
                .filter(rule -> rule.stage() == stage)
                .anyMatch(rule -> matchesUser(rule, context) || matchesRole(rule, context));
    }

    private static boolean matchesUser(ApproverRule rule, ReviewerContext context) {
        return rule.userId() != null && rule.userId().equals(context.userId());
    }

    private static boolean matchesRole(ApproverRule rule, ReviewerContext context) {
        return rule.role() != null && rule.role() == context.role();
    }

    private boolean isCurrentlyActionable(PendingReviewView view, ReviewerContext context) {
        if (view.submittedByUserId().equals(context.userId())) {
            return false;
        }
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId()).orElse(null);
        if (plan == null) {
            return false;
        }
        if (!isInDatasourceScope(view.datasourceId(), context.userId())) {
            return false;
        }
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions,
                effectiveMinApprovals(view.queryRequestId(), plan));
        return isApproverAtStage(plan, stage, context);
    }

    private PendingReview toPendingReview(PendingReviewView view, ReviewerContext context) {
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId()).orElseThrow();
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions,
                effectiveMinApprovals(view.queryRequestId(), plan));
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
                stage,
                view.createdAt());
    }

    private static <T> T mapTransitionFailure(UUID queryRequestId,
                                              java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalQueryStatusTransitionException ex) {
            throw new QueryNotPendingReviewException(queryRequestId, ex.actual());
        }
    }

    private record DecisionPreparation(ReviewPlanSnapshot plan, int currentStage,
                                       int effectiveMinApprovals, UUID submitterId) {
    }
}
