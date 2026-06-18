package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.CollaboratorIdentity;
import com.bablsoft.accessflow.workflow.api.QueryCollaborationAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for collaboration eligibility, shared by the comment service and the
 * realtime relay. Centralizes the reviewer-eligibility logic that the review path and the realtime
 * dispatcher each computed independently.
 */
@Service
@RequiredArgsConstructor
class DefaultQueryCollaborationAccessService implements QueryCollaborationAccessService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public boolean canCollaborate(UUID queryRequestId, UUID userId, UUID organizationId,
                                  UserRoleType role) {
        var snapshot = scopedSnapshot(queryRequestId, organizationId).orElse(null);
        if (snapshot == null) {
            return false;
        }
        var isSubmitter = snapshot.submittedByUserId().equals(userId);
        if (isSubmitter) {
            // The submitter may co-author while AI is still running and once it is under review.
            return snapshot.status() == QueryStatus.PENDING_AI
                    || snapshot.status() == QueryStatus.PENDING_REVIEW;
        }
        // Everyone else co-authors only while the query is under review.
        if (snapshot.status() != QueryStatus.PENDING_REVIEW) {
            return false;
        }
        // Admins oversee any in-review query in their org; reviewers must be assigned to it.
        return role == UserRoleType.ADMIN || isEligibleReviewer(snapshot, userId, role);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollaboratorIdentity> resolveParticipant(UUID queryRequestId, UUID userId,
                                                             UUID organizationId,
                                                             UserRoleType role) {
        if (!canCollaborate(queryRequestId, userId, organizationId, role)) {
            return Optional.empty();
        }
        var displayName = userQueryService.findById(userId)
                .map(u -> u.displayName() != null ? u.displayName() : u.email())
                .orElse(null);
        return Optional.of(new CollaboratorIdentity(userId, displayName));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> collaboratorIds(UUID queryRequestId, UUID organizationId) {
        var snapshot = scopedSnapshot(queryRequestId, organizationId).orElse(null);
        if (snapshot == null) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(snapshot.submittedByUserId());
        ids.addAll(eligibleReviewerIds(snapshot));
        return ids;
    }

    private Optional<QueryRequestSnapshot> scopedSnapshot(UUID queryRequestId, UUID organizationId) {
        return queryRequestLookupService.findById(queryRequestId)
                .filter(s -> s.organizationId().equals(organizationId));
    }

    private boolean isEligibleReviewer(QueryRequestSnapshot snapshot, UUID userId,
                                       UserRoleType role) {
        if (role != UserRoleType.REVIEWER && role != UserRoleType.ADMIN) {
            return false;
        }
        var plan = reviewPlanLookupService.findForDatasource(snapshot.datasourceId()).orElse(null);
        if (plan == null || !plan.organizationId().equals(snapshot.organizationId())) {
            return false;
        }
        var isApprover = plan.approvers().stream()
                .anyMatch(rule -> matchesUser(rule, userId) || matchesRole(rule, role));
        return isApprover && isInDatasourceScope(snapshot.datasourceId(), userId);
    }

    /** All active eligible reviewer user ids for the query (any stage), excluding the submitter. */
    private Set<UUID> eligibleReviewerIds(QueryRequestSnapshot snapshot) {
        var plan = reviewPlanLookupService.findForDatasource(snapshot.datasourceId()).orElse(null);
        if (plan == null || !plan.organizationId().equals(snapshot.organizationId())) {
            return Set.of();
        }
        Set<UUID> reviewers = new LinkedHashSet<>();
        for (ApproverRule rule : plan.approvers()) {
            if (rule.userId() != null) {
                userQueryService.findById(rule.userId())
                        .filter(UserView::active)
                        .ifPresent(u -> reviewers.add(u.id()));
            } else if (rule.role() != null) {
                userQueryService.findByOrganizationAndRole(snapshot.organizationId(), rule.role())
                        .stream()
                        .filter(UserView::active)
                        .forEach(u -> reviewers.add(u.id()));
            }
        }
        reviewers.remove(snapshot.submittedByUserId());
        return filterToDatasourceScope(snapshot.datasourceId(), reviewers);
    }

    private Set<UUID> filterToDatasourceScope(UUID datasourceId, Set<UUID> candidates) {
        var scoped = reviewerEligibilityService.findEligibleReviewerIds(datasourceId).orElse(null);
        if (scoped == null) {
            return candidates;
        }
        var result = new LinkedHashSet<UUID>();
        for (var id : candidates) {
            if (scoped.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private boolean isInDatasourceScope(UUID datasourceId, UUID userId) {
        return reviewerEligibilityService.findEligibleReviewerIds(datasourceId)
                .map(set -> set.contains(userId))
                .orElse(true);
    }

    private static boolean matchesUser(ApproverRule rule, UUID userId) {
        return rule.userId() != null && rule.userId().equals(userId);
    }

    private static boolean matchesRole(ApproverRule rule, UserRoleType role) {
        return rule.role() != null && rule.role() == role;
    }
}
