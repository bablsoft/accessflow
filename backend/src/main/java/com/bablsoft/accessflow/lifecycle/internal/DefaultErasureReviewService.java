package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureDecision;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewerNotEligibleException;
import com.bablsoft.accessflow.lifecycle.api.ErasureSelfApprovalException;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestApprovedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestRejectedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Review-plan-based erasure review (AF-519). Mirrors {@code DefaultAccessReviewService}: resolves the
 * datasource's {@link ReviewPlanSnapshot}, computes the current stage from recorded decisions, checks
 * the caller is a plan approver at that stage and within the datasource's scoped-reviewer set, and
 * treats any ADMIN as a backstop approver. The submitter can never decide their own request
 * (self-approval guard preserved). Only the final stage transitions a request to {@code APPROVED};
 * the existing {@code ErasureExecutionJob} then executes it.
 */
@Service
@RequiredArgsConstructor
class DefaultErasureReviewService implements ErasureReviewService {


    private final DeletionRequestRepository repository;
    private final ErasureRequestStateService stateService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final ErasureRequestViewMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ErasureRequestView> listPending(ReviewerContext context,
                                                        PageRequest pageRequest) {
        if (!has(context, Permission.ERASURE_REVIEW)) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var actionable = repository
                .findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
                        context.organizationId(), ErasureStatus.PENDING_REVIEW)
                .stream()
                .filter(entity -> isCurrentlyActionable(entity, context))
                .map(mapper::toView)
                .toList();
        return paginate(actionable, pageRequest);
    }

    @Override
    @Transactional
    public ErasureRequestView approve(UUID requestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(requestId, context);
        var command = prep.adminOverride()
                ? new RecordErasureApprovalCommand(requestId, context.reviewerId(),
                        prep.currentStage(), 1, true, comment)
                : new RecordErasureApprovalCommand(requestId, context.reviewerId(),
                        prep.currentStage(), prep.plan().minApprovalsRequired(),
                        prep.currentStage() == prep.plan().maxStage(), comment);
        var result = stateService.recordApprovalAndAdvance(command);
        if (result.resultingStatus() == ErasureStatus.APPROVED && !result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new ErasureRequestApprovedEvent(
                    requestId, context.organizationId(), context.reviewerId(), prep.requestedBy()));
        }
        return mapper.toView(reload(requestId));
    }

    @Override
    @Transactional
    public ErasureRequestView reject(UUID requestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(requestId, context);
        var result = stateService.recordRejection(requestId, context.reviewerId(),
                prep.currentStage(), comment);
        if (!result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(new ErasureRequestRejectedEvent(
                    requestId, context.organizationId(), context.reviewerId()));
        }
        return mapper.toView(reload(requestId));
    }

    private DecisionPreparation prepareDecision(UUID requestId, ReviewerContext context) {
        var entity = repository.findByIdForUpdate(requestId)
                .filter(e -> e.getOrganizationId().equals(context.organizationId()))
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
        if (entity.getStatus() != ErasureStatus.PENDING_REVIEW) {
            throw new DeletionRequestInvalidStateException(entity.getStatus());
        }
        if (entity.getRequestedBy().equals(context.reviewerId())) {
            throw new ErasureSelfApprovalException();
        }
        if (!has(context, Permission.ERASURE_REVIEW)) {
            throw new ErasureReviewerNotEligibleException(context.reviewerId(), requestId);
        }
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        var sameOrgPlan = plan != null && plan.organizationId().equals(entity.getOrganizationId());
        var currentStage = sameOrgPlan
                ? currentStage(plan, stateService.listDecisions(requestId))
                : 0;
        var planEligible = sameOrgPlan
                && isInDatasourceScope(entity.getDatasourceId(), context.reviewerId())
                && isApproverAtStage(plan, currentStage, context);
        if (planEligible) {
            return new DecisionPreparation(plan, currentStage, entity.getRequestedBy(), false);
        }
        // Admin backstop: when the datasource's plan does not route the request to the caller (no
        // plan, foreign-org plan, out of scope, or not a named approver) an ADMIN may still decide
        // it. Non-admin reviewers stay strictly plan-gated.
        if (has(context, Permission.REVIEW_OVERRIDE)) {
            return new DecisionPreparation(sameOrgPlan ? plan : null, currentStage,
                    entity.getRequestedBy(), true);
        }
        throw new ErasureReviewerNotEligibleException(context.reviewerId(), requestId);
    }

    private boolean isCurrentlyActionable(DeletionRequestEntity entity, ReviewerContext context) {
        if (entity.getRequestedBy().equals(context.reviewerId())) {
            return false;
        }
        if (has(context, Permission.REVIEW_OVERRIDE)) {
            return true;
        }
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        if (plan == null || !plan.organizationId().equals(entity.getOrganizationId())) {
            return false;
        }
        if (!isInDatasourceScope(entity.getDatasourceId(), context.reviewerId())) {
            return false;
        }
        var stage = currentStage(plan, stateService.listDecisions(entity.getId()));
        return isApproverAtStage(plan, stage, context);
    }

    private boolean isInDatasourceScope(UUID datasourceId, UUID userId) {
        return reviewerEligibilityService.findEligibleReviewerIds(datasourceId)
                .map(set -> set.contains(userId))
                .orElse(true);
    }

    private DeletionRequestEntity reload(UUID requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
    }

    private static int currentStage(ReviewPlanSnapshot plan, List<ErasureDecisionSnapshot> decisions) {
        var stages = plan.approvers().stream()
                .map(ApproverRule::stage)
                .distinct()
                .sorted()
                .toList();
        for (int stage : stages) {
            long approvedAtStage = decisions.stream()
                    .filter(d -> d.stage() == stage && d.decision() == ErasureDecision.APPROVED)
                    .count();
            if (approvedAtStage < plan.minApprovalsRequired()) {
                return stage;
            }
        }
        return plan.maxStage();
    }

    private static boolean isApproverAtStage(ReviewPlanSnapshot plan, int stage,
                                             ReviewerContext context) {
        return plan.approvers().stream()
                .filter(rule -> rule.stage() == stage)
                .anyMatch(rule -> matchesUser(rule, context) || matchesRole(rule, context));
    }

    private static boolean matchesUser(ApproverRule rule, ReviewerContext context) {
        return rule.userId() != null && rule.userId().equals(context.reviewerId());
    }

    private static boolean matchesRole(ApproverRule rule, ReviewerContext context) {
        return rule.role() != null && rule.role().equalsIgnoreCase(context.roleName());
    }

    private static boolean has(ReviewerContext context, Permission permission) {
        return context.permissions() != null && context.permissions().contains(permission);
    }

    private static PageResponse<ErasureRequestView> paginate(List<ErasureRequestView> all,
                                                             PageRequest pageRequest) {
        int page = pageRequest.page();
        int size = pageRequest.size();
        int from = Math.min((int) ((long) page * size), all.size());
        int to = (int) Math.min((long) from + size, all.size());
        var content = all.subList(from, to);
        int totalPages = (int) Math.ceil((double) all.size() / size);
        return new PageResponse<>(List.copyOf(content), page, size, all.size(), totalPages);
    }

    private record DecisionPreparation(ReviewPlanSnapshot plan, int currentStage, UUID requestedBy,
                                       boolean adminOverride) {
    }
}
