package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.DelegatedIdentity;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewCandidate;
import com.bablsoft.accessflow.core.api.ReviewDelegationLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupNotFoundException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.GroupReviewDecisionEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.GroupReviewDecisionRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultGroupReviewService implements GroupReviewService {

    private final RequestGroupRepository groupRepository;
    private final RequestGroupItemRepository itemRepository;
    private final GroupReviewDecisionRepository decisionRepository;
    private final RequestGroupStateService stateService;
    private final GroupReviewPlanResolver reviewPlanResolver;
    private final ReviewDelegationLookupService reviewDelegationLookupService;
    private final UserQueryService userQueryService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingGroupReview> listPending(ReviewerContext context, PageRequest pageRequest) {
        var pageable = Pageable.ofSize(Math.max(1, pageRequest.size())).withPage(pageRequest.page());
        var page = groupRepository.findAll(
                RequestGroupSpecifications.forPendingReview(context.organizationId(), context.userId()),
                pageable);
        // The specification is an over-approximation — it knows nothing about approver rules, which
        // are a union over each member's review plan and cannot be expressed as a flat predicate.
        // Filtering here keeps the queue consistent with the decision guard, so a caller is never
        // shown a group they would be rejected for. Consequence: totalElements is an upper bound,
        // the same trade-off the query-review queue already makes.
        var content = hasReviewPermission(context)
                ? page.getContent().stream()
                        .filter(group -> isEligible(group, context))
                        .map(this::toPending).toList()
                : List.<PendingGroupReview>of();
        var rebased = new PageImpl<>(content, pageable, page.getTotalElements());
        return new PageResponse<>(content, rebased.getNumber(),
                rebased.getSize() <= 0 ? 1 : rebased.getSize(),
                rebased.getTotalElements(), rebased.getTotalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID requestGroupId, ReviewerContext context, String comment) {
        var pending = requirePendingReview(requestGroupId, context);
        var group = pending.group();
        var existing = decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(
                requestGroupId, context.userId(), group.getCurrentReviewStage());
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), DecisionType.APPROVED, group.getStatus(), true);
        }
        var decision = saveDecision(group, context, DecisionType.APPROVED, comment, pending.candidate());
        var approvals = decisionRepository.countByRequestGroupIdAndStageAndDecision(
                requestGroupId, group.getCurrentReviewStage(), DecisionType.APPROVED);
        var resulting = group.getStatus();
        if (approvals >= group.getRequiredApprovals()) {
            stateService.apply(group, RequestGroupStatus.APPROVED);
            resulting = RequestGroupStatus.APPROVED;
            audit(AuditAction.REQUEST_GROUP_APPROVED, group, context.userId(),
                    Map.of("approvals", approvals));
        }
        return new DecisionOutcome(decision.getId(), DecisionType.APPROVED, resulting, false);
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID requestGroupId, ReviewerContext context, String comment) {
        var pending = requirePendingReview(requestGroupId, context);
        var group = pending.group();
        var existing = decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(
                requestGroupId, context.userId(), group.getCurrentReviewStage());
        if (existing.isPresent() && existing.get().getDecision() == DecisionType.REJECTED) {
            return new DecisionOutcome(existing.get().getId(), DecisionType.REJECTED, group.getStatus(), true);
        }
        var decision = saveDecision(group, context, DecisionType.REJECTED, comment, pending.candidate());
        stateService.apply(group, RequestGroupStatus.REJECTED);
        audit(AuditAction.REQUEST_GROUP_REJECTED, group, context.userId(), Map.of());
        return new DecisionOutcome(decision.getId(), DecisionType.REJECTED, RequestGroupStatus.REJECTED, false);
    }

    private record PendingGroup(RequestGroupEntity group, ReviewCandidate candidate) {
    }

    private PendingGroup requirePendingReview(UUID requestGroupId, ReviewerContext context) {
        var group = groupRepository.findByIdAndOrganizationId(requestGroupId, context.organizationId())
                .orElseThrow(() -> new RequestGroupNotFoundException(requestGroupId));
        if (group.getStatus() != RequestGroupStatus.PENDING_REVIEW) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Group is not pending review");
        }
        if (group.getSubmittedBy().equals(context.userId())) {
            throw new SelfApprovalNotAllowedException();
        }
        return new PendingGroup(group, requireEligible(group, context));
    }

    private ReviewCandidate requireEligible(RequestGroupEntity group, ReviewerContext context) {
        if (!hasReviewPermission(context)) {
            throw new RequestGroupPermissionException("You may not review request groups");
        }
        return eligibleCandidate(group, context)
                .orElseThrow(() -> new RequestGroupPermissionException(
                        "You are not an eligible approver for this group"));
    }

    private boolean isEligible(RequestGroupEntity group, ReviewerContext context) {
        return eligibleCandidate(group, context).isPresent();
    }

    /**
     * The identity the caller may act on this bundle under, if any. Shared by the decision guard
     * and the pending queue, so the queue can never list a group the caller would be rejected for.
     */
    private Optional<ReviewCandidate> eligibleCandidate(RequestGroupEntity group,
                                                        ReviewerContext context) {
        var self = ReviewCandidate.self(context.userId(), context.roleName());
        // REVIEW_OVERRIDE holders (system ADMIN) can always act on the bundle — matches the
        // per-query review machinery.
        if (context.permissions() != null
                && context.permissions().contains(Permission.REVIEW_OVERRIDE)) {
            return Optional.of(self);
        }
        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        var resolution = reviewPlanResolver.resolve(group, items);
        if (matches(resolution, self)) {
            return Optional.of(self);
        }
        // Unscoped lookup: a bundle mixes datasources and API connectors, so each delegation is
        // matched against the members itself rather than narrowed to one resource up front.
        var decided = decisionRepository.findByRequestGroupIdAndStage(group.getId(),
                group.getCurrentReviewStage());
        for (var delegation : reviewDelegationLookupService.findActiveForDelegate(
                group.getOrganizationId(), context.userId(), null, null)) {
            // The self-approval ban covers both identities.
            if (group.getSubmittedBy().equals(delegation.delegatorUserId())) {
                continue;
            }
            if (!coversAnyMember(delegation, items)) {
                continue;
            }
            // One authority, one vote — the unique index cannot see the delegator's own vote.
            if (decided.stream().anyMatch(decision ->
                    delegation.delegatorUserId().equals(decision.getReviewerId())
                            || delegation.delegatorUserId().equals(decision.getOnBehalfOfUserId()))) {
                continue;
            }
            var borrowed = ReviewCandidate.borrowed(delegation);
            if (matches(resolution, borrowed)) {
                return Optional.of(borrowed);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(GroupReviewPlanResolver.GroupReviewResolution resolution,
                                   ReviewCandidate candidate) {
        return resolution.eligibleRoleNames().stream()
                .anyMatch(name -> name.equalsIgnoreCase(candidate.roleName()))
                || resolution.eligibleUserIds().contains(candidate.userId());
    }

    /**
     * A scoped delegation covers a bundle when its resource is one of the bundle's members —
     * consistent with the resolver's union-over-members eligibility, and never broader than what
     * the delegator could do themselves. An unscoped delegation always covers it.
     */
    private static boolean coversAnyMember(DelegatedIdentity delegation,
                                           List<RequestGroupItemEntity> items) {
        if (delegation.isUnrestricted()) {
            return true;
        }
        return items.stream().anyMatch(item ->
                delegation.covers(DelegationScopeKind.DATASOURCE, item.getDatasourceId())
                        || delegation.covers(DelegationScopeKind.API_CONNECTOR,
                                item.getApiConnectorId()));
    }

    private static boolean hasReviewPermission(ReviewerContext context) {
        return context.permissions() != null
                && (context.permissions().contains(Permission.QUERY_REVIEW)
                    || context.permissions().contains(Permission.REVIEW_OVERRIDE));
    }

    private GroupReviewDecisionEntity saveDecision(RequestGroupEntity group, ReviewerContext context,
                                                   DecisionType decision, String comment,
                                                   ReviewCandidate candidate) {
        var entity = new GroupReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setRequestGroupId(group.getId());
        entity.setReviewerId(context.userId());
        entity.setDecision(decision);
        entity.setStage(group.getCurrentReviewStage());
        entity.setComment(comment);
        // #622: reviewer_id stays the acting human; these name the borrowed authority.
        entity.setOnBehalfOfUserId(candidate.onBehalfOfUserId());
        entity.setDelegationId(candidate.delegationId());
        return decisionRepository.save(entity);
    }

    private PendingGroupReview toPending(RequestGroupEntity group) {
        var memberCount = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()).size();
        var submitterName = userQueryService.findById(group.getSubmittedBy())
                .map(u -> u.displayName()).orElse(null);
        return new PendingGroupReview(group.getId(), group.getName(), group.getSubmittedBy(),
                submitterName, memberCount, group.getAiRiskLevel(), group.getAiRiskScore(),
                group.getCurrentReviewStage(), group.getRequiredApprovals(), group.getCreatedAt());
    }

    private void audit(AuditAction action, RequestGroupEntity group, UUID actorId,
                       Map<String, Object> metadata) {
        auditLogService.record(new AuditEntry(action, AuditResourceType.REQUEST_GROUP, group.getId(),
                group.getOrganizationId(), actorId, metadata, null, null));
    }
}
