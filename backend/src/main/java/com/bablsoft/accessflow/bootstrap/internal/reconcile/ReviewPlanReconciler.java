package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
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
import java.util.LinkedHashMap;
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
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

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

        var specMap = specFields(spec, approverRules, notifyChannelIds);
        var specFingerprint = fingerprinter.fingerprint(specMap);

        var existing = findByName(organizationId, spec.name());
        if (existing.isEmpty()) {
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
            stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.REVIEW_PLAN,
                    created.id(), specFingerprint,
                    new BootstrapResourceUpsertedEvent(
                            organizationId,
                            BootstrapResourceType.REVIEW_PLAN,
                            created.id(),
                            BootstrapChangeKind.CREATE,
                            List.of(),
                            Map.of("name", created.name())));
            return created.id();
        }

        var view = existing.get();
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.REVIEW_PLAN, view.id())
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: review plan '{}' unchanged, skipping update", spec.name());
            return view.id();
        }

        var viewMap = viewFields(view);
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
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.REVIEW_PLAN,
                updated.id(), specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.REVIEW_PLAN,
                        updated.id(),
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(viewMap, specMap),
                        Map.of("name", updated.name())));
        return updated.id();
    }

    private Optional<ReviewPlanView> findByName(UUID organizationId, String name) {
        return reviewPlanAdminService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private static Map<String, Object> specFields(ReviewPlanSpec spec,
                                                  List<ReviewPlanView.ApproverRule> approverRules,
                                                  List<String> notifyChannelIds) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", spec.name());
        map.put("description", spec.description());
        map.put("requires_ai_review", spec.requiresAiReview());
        map.put("requires_human_approval", spec.requiresHumanApproval());
        map.put("min_approvals_required", spec.minApprovalsRequired());
        map.put("approval_timeout_hours", spec.approvalTimeoutHours());
        map.put("auto_approve_reads", spec.autoApproveReads());
        map.put("notify_channels", notifyChannelIds);
        map.put("approvers", approverRules.stream()
                .map(rule -> Map.of(
                        "user_id", rule.userId().toString(),
                        "role", rule.role().name(),
                        "stage", rule.stage()))
                .toList());
        return map;
    }

    private static Map<String, Object> viewFields(ReviewPlanView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", view.name());
        map.put("description", view.description());
        map.put("requires_ai_review", view.requiresAiReview());
        map.put("requires_human_approval", view.requiresHumanApproval());
        map.put("min_approvals_required", view.minApprovalsRequired());
        map.put("approval_timeout_hours", view.approvalTimeoutHours());
        map.put("auto_approve_reads", view.autoApproveReads());
        map.put("notify_channels", view.notifyChannels());
        map.put("approvers", view.approvers().stream()
                .map(rule -> Map.of(
                        "user_id", rule.userId().toString(),
                        "role", rule.role().name(),
                        "stage", rule.stage()))
                .toList());
        return map;
    }
}
