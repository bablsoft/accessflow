package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupNotFoundException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.GroupReviewDecisionEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.GroupReviewDecisionRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserQueryService userQueryService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingGroupReview> listPending(ReviewerContext context, PageRequest pageRequest) {
        var pageable = Pageable.ofSize(Math.max(1, pageRequest.size())).withPage(pageRequest.page());
        var page = groupRepository.findAll(
                RequestGroupSpecifications.forPendingReview(context.organizationId(), context.userId()),
                pageable);
        var content = page.getContent().stream().map(this::toPending).toList();
        var rebased = new PageImpl<>(content, pageable, page.getTotalElements());
        return new PageResponse<>(content, rebased.getNumber(),
                rebased.getSize() <= 0 ? 1 : rebased.getSize(),
                rebased.getTotalElements(), rebased.getTotalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID requestGroupId, ReviewerContext context, String comment) {
        var group = requirePendingReview(requestGroupId, context);
        var existing = decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(
                requestGroupId, context.userId(), group.getCurrentReviewStage());
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), DecisionType.APPROVED, group.getStatus(), true);
        }
        var decision = saveDecision(group, context, DecisionType.APPROVED, comment);
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
        var group = requirePendingReview(requestGroupId, context);
        var existing = decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(
                requestGroupId, context.userId(), group.getCurrentReviewStage());
        if (existing.isPresent() && existing.get().getDecision() == DecisionType.REJECTED) {
            return new DecisionOutcome(existing.get().getId(), DecisionType.REJECTED, group.getStatus(), true);
        }
        var decision = saveDecision(group, context, DecisionType.REJECTED, comment);
        stateService.apply(group, RequestGroupStatus.REJECTED);
        audit(AuditAction.REQUEST_GROUP_REJECTED, group, context.userId(), Map.of());
        return new DecisionOutcome(decision.getId(), DecisionType.REJECTED, RequestGroupStatus.REJECTED, false);
    }

    private RequestGroupEntity requirePendingReview(UUID requestGroupId, ReviewerContext context) {
        var group = groupRepository.findByIdAndOrganizationId(requestGroupId, context.organizationId())
                .orElseThrow(() -> new RequestGroupNotFoundException(requestGroupId));
        if (group.getStatus() != RequestGroupStatus.PENDING_REVIEW) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Group is not pending review");
        }
        if (group.getSubmittedBy().equals(context.userId())) {
            throw new SelfApprovalNotAllowedException();
        }
        requireEligible(group, context);
        return group;
    }

    private void requireEligible(RequestGroupEntity group, ReviewerContext context) {
        // REVIEW_OVERRIDE holders (system ADMIN) can always act on the bundle — matches the
        // per-query review machinery.
        if (context.permissions() != null
                && context.permissions().contains(Permission.REVIEW_OVERRIDE)) {
            return;
        }
        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        var resolution = reviewPlanResolver.resolve(group, items);
        var eligible = resolution.eligibleRoleNames().stream()
                .anyMatch(name -> name.equalsIgnoreCase(context.roleName()))
                || resolution.eligibleUserIds().contains(context.userId());
        if (!eligible) {
            throw new RequestGroupPermissionException("You are not an eligible approver for this group");
        }
    }

    private GroupReviewDecisionEntity saveDecision(RequestGroupEntity group, ReviewerContext context,
                                                   DecisionType decision, String comment) {
        var entity = new GroupReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setRequestGroupId(group.getId());
        entity.setReviewerId(context.userId());
        entity.setDecision(decision);
        entity.setStage(group.getCurrentReviewStage());
        entity.setComment(comment);
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
