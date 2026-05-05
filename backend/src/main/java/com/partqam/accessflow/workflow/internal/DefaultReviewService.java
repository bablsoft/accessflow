package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.partqam.accessflow.core.api.PendingReviewView;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.RecordApprovalCommand;
import com.partqam.accessflow.core.api.RecordDecisionResult;
import com.partqam.accessflow.core.api.ReviewDecisionSnapshot;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.workflow.api.QueryNotPendingReviewException;
import com.partqam.accessflow.workflow.api.ReviewService;
import com.partqam.accessflow.workflow.api.ReviewerNotEligibleException;
import com.partqam.accessflow.workflow.events.QueryApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryRejectedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultReviewService implements ReviewService {

    private static final Set<UserRoleType> REVIEWER_ROLES = Set.of(UserRoleType.REVIEWER,
            UserRoleType.ADMIN);

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Page<PendingReview> listPendingForReviewer(ReviewerContext context, Pageable pageable) {
        if (!REVIEWER_ROLES.contains(context.role())) {
            return Page.empty(pageable);
        }
        var page = queryRequestLookupService.findPendingForReviewer(context.organizationId(),
                context.userId(), context.role(), pageable);
        var visible = page.getContent().stream()
                .filter(view -> isCurrentlyActionable(view, context))
                .map(view -> toPendingReview(view, context))
                .toList();
        return new PageImpl<>(visible, pageable, page.getTotalElements());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID queryRequestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(queryRequestId, context);
        var command = new RecordApprovalCommand(queryRequestId, context.userId(),
                prep.currentStage(),
                prep.plan().minApprovalsRequired(),
                prep.currentStage() == prep.plan().maxStage(),
                comment);
        var result = mapTransitionFailure(queryRequestId,
                () -> queryRequestStateService.recordApprovalAndAdvance(command));
        if (result.resultingStatus() == QueryStatus.APPROVED && !result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new QueryApprovedEvent(queryRequestId, context.userId()));
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
        return new DecisionOutcome(result.decisionId(), DecisionType.REQUESTED_CHANGES,
                result.resultingStatus(), result.wasIdempotentReplay());
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
        var decisions = queryRequestStateService.listDecisions(queryRequestId);
        var currentStage = currentStage(plan, decisions);
        if (!isApproverAtStage(plan, currentStage, context)) {
            throw new ReviewerNotEligibleException(context.userId(), queryRequestId);
        }
        return new DecisionPreparation(plan, currentStage);
    }

    private static int currentStage(ReviewPlanSnapshot plan,
                                    List<ReviewDecisionSnapshot> decisions) {
        var stages = plan.approvers().stream()
                .map(ApproverRule::stage)
                .distinct()
                .sorted()
                .toList();
        for (int stage : stages) {
            long approvedAtStage = decisions.stream()
                    .filter(d -> d.stage() == stage && d.decision() == DecisionType.APPROVED)
                    .count();
            if (approvedAtStage < plan.minApprovalsRequired()) {
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
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions);
        return isApproverAtStage(plan, stage, context);
    }

    private PendingReview toPendingReview(PendingReviewView view, ReviewerContext context) {
        var plan = reviewPlanLookupService.findForDatasource(view.datasourceId()).orElseThrow();
        var decisions = queryRequestStateService.listDecisions(view.queryRequestId());
        var stage = currentStage(plan, decisions);
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

    private record DecisionPreparation(ReviewPlanSnapshot plan, int currentStage) {
    }
}
