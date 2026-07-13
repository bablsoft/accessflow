package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestNotPendingException;
import com.bablsoft.accessflow.access.api.AccessReviewService;
import com.bablsoft.accessflow.access.api.AccessReviewerNotEligibleException;
import com.bablsoft.accessflow.access.events.AccessRequestApprovedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestRejectedEvent;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAccessReviewService implements AccessReviewService {

    private static final Set<UserRoleType> REVIEWER_ROLES =
            Set.of(UserRoleType.REVIEWER, UserRoleType.ADMIN);

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final AccessGrantMaterializer materializer;
    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingAccessRequest> listPendingForReviewer(ReviewerContext context,
                                                                     PageRequest pageRequest) {
        if (!REVIEWER_ROLES.contains(context.role())) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var actionable = requestRepository
                .findAllByOrganizationIdAndStatusOrderByCreatedAtAsc(
                        context.organizationId(), AccessGrantStatus.PENDING)
                .stream()
                .filter(entity -> isCurrentlyActionable(entity, context))
                .map(this::toPendingAccessRequest)
                .toList();
        return paginate(actionable, pageRequest);
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID accessRequestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(accessRequestId, context);
        // An admin acting outside the datasource's review plan (no plan, or not a named approver)
        // finalises the request with a single approval; a plan-eligible reviewer follows the
        // multi-stage chain so only the final stage materialises the grant.
        var command = prep.adminOverride()
                ? new RecordAccessApprovalCommand(accessRequestId, context.userId(),
                        prep.currentStage(), 1, true, comment)
                : new RecordAccessApprovalCommand(accessRequestId, context.userId(),
                        prep.currentStage(), prep.plan().minApprovalsRequired(),
                        prep.currentStage() == prep.plan().maxStage(), comment);
        var result = stateService.recordApprovalAndAdvance(command);
        if (result.resultingStatus() == AccessGrantStatus.APPROVED && !result.wasIdempotentReplay()) {
            materializer.materialize(accessRequestId, context.userId());
            eventPublisher.publishEvent(
                    new AccessRequestApprovedEvent(accessRequestId, context.userId()));
        }
        return new DecisionOutcome(result.decisionId(), DecisionType.APPROVED,
                result.resultingStatus(), result.wasIdempotentReplay());
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID accessRequestId, ReviewerContext context, String comment) {
        var prep = prepareDecision(accessRequestId, context);
        var result = stateService.recordRejection(accessRequestId, context.userId(),
                prep.currentStage(), comment);
        if (!result.wasIdempotentReplay()) {
            eventPublisher.publishEvent(
                    new AccessRequestRejectedEvent(accessRequestId, context.userId()));
        }
        return new DecisionOutcome(result.decisionId(), DecisionType.REJECTED,
                result.resultingStatus(), result.wasIdempotentReplay());
    }

    @Override
    @Transactional
    public RevocationOutcome revoke(UUID accessRequestId, ReviewerContext context, String comment) {
        var entity = requestRepository.findById(accessRequestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(accessRequestId));
        if (!entity.getOrganizationId().equals(context.organizationId())) {
            throw new AccessRequestNotFoundException(accessRequestId);
        }
        var revoked = stateService.revoke(accessRequestId, context.userId());
        if (revoked) {
            return new RevocationOutcome(AccessGrantStatus.REVOKED, false);
        }
        var current = requestRepository.findById(accessRequestId)
                .map(AccessGrantRequestEntity::getStatus)
                .orElse(entity.getStatus());
        return new RevocationOutcome(current, true);
    }

    private DecisionPreparation prepareDecision(UUID accessRequestId, ReviewerContext context) {
        var entity = requestRepository.findById(accessRequestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(accessRequestId));
        if (!entity.getOrganizationId().equals(context.organizationId())) {
            throw new AccessRequestNotFoundException(accessRequestId);
        }
        if (entity.getStatus() != AccessGrantStatus.PENDING) {
            throw new AccessRequestNotPendingException(accessRequestId, entity.getStatus());
        }
        if (entity.getRequesterId().equals(context.userId())) {
            throw new AccessDeniedException(msg("error.access_self_approval"));
        }
        if (!REVIEWER_ROLES.contains(context.role())) {
            throw new AccessReviewerNotEligibleException(context.userId(), accessRequestId);
        }
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        var sameOrgPlan = plan != null && plan.organizationId().equals(entity.getOrganizationId());
        var currentStage = sameOrgPlan
                ? currentStage(plan, stateService.listDecisions(accessRequestId))
                : 0;
        var planEligible = sameOrgPlan
                && isInDatasourceScope(entity.getDatasourceId(), context.userId())
                && isApproverAtStage(plan, currentStage, context);
        if (planEligible) {
            return new DecisionPreparation(plan, currentStage, entity.getRequesterId(), false);
        }
        // Admins are the backstop approver: when the datasource's plan does not route the
        // request to them (no plan, foreign-org plan, out of scope, or not a named approver)
        // they may still decide it. Non-admin reviewers stay strictly plan-gated.
        if (context.role() == UserRoleType.ADMIN) {
            return new DecisionPreparation(sameOrgPlan ? plan : null, currentStage,
                    entity.getRequesterId(), true);
        }
        throw new AccessReviewerNotEligibleException(context.userId(), accessRequestId);
    }

    private boolean isCurrentlyActionable(AccessGrantRequestEntity entity, ReviewerContext context) {
        if (entity.getRequesterId().equals(context.userId())) {
            return false;
        }
        if (context.role() == UserRoleType.ADMIN) {
            return true;
        }
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        if (plan == null || !plan.organizationId().equals(entity.getOrganizationId())) {
            return false;
        }
        if (!isInDatasourceScope(entity.getDatasourceId(), context.userId())) {
            return false;
        }
        var decisions = stateService.listDecisions(entity.getId());
        var stage = currentStage(plan, decisions);
        return isApproverAtStage(plan, stage, context);
    }

    private boolean isInDatasourceScope(UUID datasourceId, UUID userId) {
        return reviewerEligibilityService.findEligibleReviewerIds(datasourceId)
                .map(set -> set.contains(userId))
                .orElse(true);
    }

    private static int currentStage(ReviewPlanSnapshot plan, List<AccessDecisionSnapshot> decisions) {
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

    private PendingAccessRequest toPendingAccessRequest(AccessGrantRequestEntity entity) {
        // The datasource may have no review plan (admins still see such requests via the
        // fallback) — report stage 0 rather than throwing.
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        var decisions = stateService.listDecisions(entity.getId());
        var stage = plan != null ? currentStage(plan, decisions) : 0;
        var requesterEmail = userQueryService.findById(entity.getRequesterId())
                .map(UserView::email).orElse(null);
        var datasourceName = datasourceLookupService.findRef(entity.getDatasourceId())
                .map(DatasourceRef::name).orElse(null);
        return new PendingAccessRequest(
                entity.getId(),
                entity.getDatasourceId(),
                datasourceName,
                entity.getRequesterId(),
                requesterEmail,
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                AccessRequestViewMapper.toList(entity.getAllowedSchemas()),
                AccessRequestViewMapper.toList(entity.getAllowedTables()),
                entity.getRequestedDuration(),
                entity.getJustification(),
                entity.isPreApproveQueries(),
                stage,
                entity.getCreatedAt());
    }

    private static PageResponse<PendingAccessRequest> paginate(List<PendingAccessRequest> all,
                                                               PageRequest pageRequest) {
        int page = pageRequest.page();
        int size = pageRequest.size();
        int from = Math.min((int) ((long) page * size), all.size());
        int to = (int) Math.min((long) from + size, all.size());
        var content = all.subList(from, to);
        int totalPages = (int) Math.ceil((double) all.size() / size);
        return new PageResponse<>(List.copyOf(content), page, size, all.size(), totalPages);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private record DecisionPreparation(ReviewPlanSnapshot plan, int currentStage, UUID requesterId,
                                       boolean adminOverride) {
    }
}
