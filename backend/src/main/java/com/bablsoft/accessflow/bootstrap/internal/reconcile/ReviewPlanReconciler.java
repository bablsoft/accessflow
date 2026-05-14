package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.ReviewPlanSpec;
import com.bablsoft.accessflow.core.api.CreateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.ReviewPlanAdminService;
import com.bablsoft.accessflow.core.api.ReviewPlanView;
import com.bablsoft.accessflow.core.api.UpdateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewPlanReconciler {

    private final ReviewPlanAdminService reviewPlanAdminService;
    private final AdminUserReconciler adminUserReconciler;

    public Map<String, UUID> reconcile(UUID organizationId,
                                       List<ReviewPlanSpec> specs,
                                       Map<String, UUID> notificationChannelsByName) {
        var byName = new HashMap<String, UUID>();
        for (var spec : specs) {
            var id = applyOne(organizationId, spec, notificationChannelsByName);
            byName.put(spec.name(), id);
        }
        return Map.copyOf(byName);
    }

    private UUID applyOne(UUID organizationId, ReviewPlanSpec spec,
                          Map<String, UUID> notificationChannelsByName) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("Review plan spec is missing 'name'");
        }

        var approverRules = spec.approverEmails().stream()
                .map(email -> new ReviewPlanView.ApproverRule(
                        adminUserReconciler.lookupId(organizationId, email),
                        UserRoleType.ADMIN,
                        1))
                .toList();

        var notifyChannelIds = spec.notifyChannelNames().stream()
                .map(channelName -> {
                    var id = notificationChannelsByName.get(channelName);
                    if (id == null) {
                        throw new IllegalStateException(
                                "Review plan '%s' references unknown notification channel '%s'"
                                        .formatted(spec.name(), channelName));
                    }
                    return id.toString();
                })
                .toList();

        var existing = findByName(organizationId, spec.name());
        if (existing.isPresent()) {
            var view = existing.get();
            var updated = reviewPlanAdminService.update(view.id(), organizationId,
                    new UpdateReviewPlanCommand(
                            spec.name(),
                            spec.description(),
                            spec.requiresAiReview(),
                            spec.requiresHumanApproval(),
                            spec.minApprovalsRequired(),
                            spec.approvalTimeoutHours(),
                            spec.autoApproveReads(),
                            notifyChannelIds,
                            approverRules));
            log.info("Bootstrap: updated review plan '{}' (id={})", spec.name(), updated.id());
            return updated.id();
        }

        var created = reviewPlanAdminService.create(new CreateReviewPlanCommand(
                organizationId,
                spec.name(),
                spec.description(),
                spec.requiresAiReview(),
                spec.requiresHumanApproval(),
                spec.minApprovalsRequired(),
                spec.approvalTimeoutHours(),
                spec.autoApproveReads(),
                notifyChannelIds,
                approverRules));
        log.info("Bootstrap: created review plan '{}' (id={})", spec.name(), created.id());
        return created.id();
    }

    private Optional<ReviewPlanView> findByName(UUID organizationId, String name) {
        return reviewPlanAdminService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }
}
