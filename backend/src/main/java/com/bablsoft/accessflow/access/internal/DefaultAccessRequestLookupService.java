package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessRequestLookupService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAccessRequestLookupService implements AccessRequestLookupService {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessRequestViewMapper viewMapper;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccessRequestView> findById(UUID accessRequestId) {
        return requestRepository.findById(accessRequestId).map(viewMapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findReviewerRecipients(UUID accessRequestId) {
        var entity = requestRepository.findById(accessRequestId).orElse(null);
        if (entity == null) {
            return Set.of();
        }
        var plan = reviewPlanLookupService.findForDatasource(entity.getDatasourceId()).orElse(null);
        if (plan == null || plan.approvers() == null || plan.approvers().isEmpty()) {
            return Set.of();
        }
        var lowestStage = plan.approvers().stream()
                .mapToInt(ApproverRule::stage)
                .min()
                .orElse(0);
        Set<UUID> reviewers = collectReviewersAtStage(plan, lowestStage, entity);
        return applyDatasourceScope(entity.getDatasourceId(), reviewers);
    }

    private Set<UUID> collectReviewersAtStage(ReviewPlanSnapshot plan, int stage,
                                              AccessGrantRequestEntity entity) {
        Set<UUID> reviewers = new LinkedHashSet<>();
        for (ApproverRule rule : plan.approvers()) {
            if (rule.stage() != stage) {
                continue;
            }
            if (rule.userId() != null) {
                userQueryService.findById(rule.userId())
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(entity.getRequesterId()))
                        .ifPresent(u -> reviewers.add(u.id()));
            } else if (rule.role() != null) {
                userQueryService.findByOrganizationAndRole(entity.getOrganizationId(), rule.role())
                        .stream()
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(entity.getRequesterId()))
                        .forEach(u -> reviewers.add(u.id()));
            }
        }
        return reviewers;
    }

    private Set<UUID> applyDatasourceScope(UUID datasourceId, Set<UUID> reviewers) {
        return reviewerEligibilityService.findEligibleReviewerIds(datasourceId)
                .map(scoped -> {
                    var intersection = new LinkedHashSet<>(reviewers);
                    intersection.retainAll(scoped);
                    return (Set<UUID>) intersection;
                })
                .orElse(reviewers);
    }
}
