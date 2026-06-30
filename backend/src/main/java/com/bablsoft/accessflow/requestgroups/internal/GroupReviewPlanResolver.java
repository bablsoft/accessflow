package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates the {@link ReviewPlanSnapshot} of every distinct member target into a single group-level
 * review resolution. The eligible approvers are the <strong>union</strong> across all member plans;
 * the group requires human approval if <em>any</em> member plan does, and the per-stage threshold is
 * the maximum across member plans so that <em>every</em> member plan is satisfied — no member's
 * policy is weakened by bundling.
 */
@Component
@RequiredArgsConstructor
public class GroupReviewPlanResolver {

    private static final Logger log = LoggerFactory.getLogger(GroupReviewPlanResolver.class);

    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final ApiConnectorAdminService apiConnectorAdminService;

    /** The aggregated review requirement for a group (single approval stage = 1). */
    public record GroupReviewResolution(
            boolean requiresHumanApproval,
            int requiredApprovals,
            Set<UUID> eligibleUserIds,
            Set<UserRoleType> eligibleRoles) {
    }

    public GroupReviewResolution resolve(RequestGroupEntity group, List<RequestGroupItemEntity> items) {
        boolean requiresHuman = false;
        int requiredApprovals = 0;
        Set<UUID> eligibleUserIds = new HashSet<>();
        Set<UserRoleType> eligibleRoles = new HashSet<>();

        for (RequestGroupItemEntity item : items) {
            var plan = planFor(group, item);
            if (plan.isEmpty()) {
                continue;
            }
            var snapshot = plan.get();
            if (snapshot.requiresHumanApproval()) {
                requiresHuman = true;
                requiredApprovals = Math.max(requiredApprovals, snapshot.minApprovalsRequired());
            }
            for (ApproverRule rule : snapshot.approvers()) {
                if (rule.userId() != null) {
                    eligibleUserIds.add(rule.userId());
                }
                if (rule.role() != null) {
                    eligibleRoles.add(rule.role());
                }
            }
            if (item.getTargetKind() == RequestGroupTargetKind.QUERY && item.getDatasourceId() != null) {
                reviewerEligibilityService.findEligibleReviewerIds(item.getDatasourceId())
                        .ifPresent(eligibleUserIds::addAll);
            }
        }
        // ADMIN can always act on the bundle (matches the per-query review machinery).
        eligibleRoles.add(UserRoleType.ADMIN);
        return new GroupReviewResolution(requiresHuman, Math.max(requiredApprovals, requiresHuman ? 1 : 0),
                Set.copyOf(eligibleUserIds), Set.copyOf(eligibleRoles));
    }

    private Optional<ReviewPlanSnapshot> planFor(RequestGroupEntity group, RequestGroupItemEntity item) {
        try {
            if (item.getTargetKind() == RequestGroupTargetKind.QUERY) {
                return reviewPlanLookupService.findForDatasource(item.getDatasourceId());
            }
            var connector = apiConnectorAdminService.getForAdmin(item.getApiConnectorId(),
                    group.getOrganizationId());
            return connector.reviewPlanId() == null ? Optional.empty()
                    : reviewPlanLookupService.findById(connector.reviewPlanId());
        } catch (RuntimeException ex) {
            // Resolution must fail safe toward review: an unresolvable plan should never silently
            // drop the human-approval requirement, so we treat it as "needs review" upstream.
            log.warn("Failed to resolve review plan for group {} item {}: {}",
                    group.getId(), item.getId(), ex.getMessage());
            throw ex;
        }
    }
}
