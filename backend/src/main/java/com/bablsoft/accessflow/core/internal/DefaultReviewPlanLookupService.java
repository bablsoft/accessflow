package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultReviewPlanLookupService implements ReviewPlanLookupService {

    private final DatasourceRepository datasourceRepository;
    private final ReviewPlanApproverRepository reviewPlanApproverRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewPlanSnapshot> findForDatasource(UUID datasourceId) {
        return datasourceRepository.findById(datasourceId)
                .map(d -> d.getReviewPlan())
                .map(this::toSnapshot);
    }

    private ReviewPlanSnapshot toSnapshot(ReviewPlanEntity plan) {
        var approverEntities = reviewPlanApproverRepository
                .findAllByReviewPlan_IdOrderByStageAsc(plan.getId());
        var approvers = approverEntities.stream()
                .map(a -> new ApproverRule(
                        a.getUser() != null ? a.getUser().getId() : null,
                        a.getRole(),
                        a.getStage()))
                .toList();
        var maxStage = approvers.stream()
                .map(ApproverRule::stage)
                .max(Comparator.naturalOrder())
                .orElse(0);
        return new ReviewPlanSnapshot(
                plan.getId(),
                plan.getOrganization().getId(),
                plan.isRequiresAiReview(),
                plan.isRequiresHumanApproval(),
                plan.getMinApprovalsRequired(),
                plan.isAutoApproveReads(),
                maxStage,
                List.copyOf(approvers),
                parseNotifyChannelIds(plan.getNotifyChannels()));
    }

    private List<UUID> parseNotifyChannelIds(String[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        return Arrays.stream(raw)
                .filter(s -> s != null && !s.isBlank())
                .map(this::parseUuidOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping malformed notify_channels entry: {}", value);
            return null;
        }
    }
}
