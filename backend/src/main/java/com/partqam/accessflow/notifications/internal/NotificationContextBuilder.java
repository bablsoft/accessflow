package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.AiAnalysisLookupService;
import com.partqam.accessflow.core.api.AiAnalysisSummaryView;
import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.config.NotificationsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a {@link NotificationContext} from a query request id by composing the public-facing
 * facades exposed by the {@code core} module. Never reaches into {@code core/internal}.
 */
@Component
@RequiredArgsConstructor
class NotificationContextBuilder {

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final UserQueryService userQueryService;
    private final NotificationsProperties properties;

    List<UUID> lookupPlanChannelIds(UUID datasourceId) {
        return reviewPlanLookupService.findForDatasource(datasourceId)
                .map(ReviewPlanSnapshot::notifyChannelIds)
                .orElse(List.of());
    }

    Optional<NotificationContext> build(NotificationEventType eventType, UUID queryRequestId,
                                        UUID reviewerUserId, String reviewerComment) {
        var snapshot = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        var datasource = datasourceAdminService.getForAdmin(
                snapshot.datasourceId(), snapshot.organizationId());
        var submitter = userQueryService.findById(snapshot.submittedByUserId()).orElse(null);
        var reviewer = reviewerUserId != null
                ? userQueryService.findById(reviewerUserId).orElse(null)
                : null;
        var ai = aiAnalysisLookupService.findByQueryRequestId(queryRequestId).orElse(null);
        var plan = reviewPlanLookupService.findForDatasource(snapshot.datasourceId()).orElse(null);
        var recipients = resolveRecipients(eventType, snapshot, plan, submitter);
        return Optional.of(new NotificationContext(
                eventType,
                snapshot.organizationId(),
                snapshot.id(),
                snapshot.queryType(),
                snapshot.sqlText(),
                truncate(snapshot.sqlText(), 200),
                truncate(snapshot.sqlText(), 300),
                ai != null ? ai.riskLevel() : null,
                ai != null ? ai.riskScore() : null,
                ai != null ? ai.summary() : null,
                snapshot.datasourceId(),
                datasource != null ? datasource.name() : null,
                snapshot.submittedByUserId(),
                submitter != null ? submitter.email() : null,
                submitter != null ? submitter.displayName() : null,
                null,
                reviewer != null ? reviewer.id() : null,
                reviewer != null ? reviewer.displayName() : null,
                reviewerComment,
                buildReviewUrl(snapshot.id()),
                recipients,
                Instant.now()));
    }

    private List<RecipientView> resolveRecipients(NotificationEventType eventType,
                                                  QueryRequestSnapshot snapshot,
                                                  ReviewPlanSnapshot plan,
                                                  UserView submitter) {
        return switch (eventType) {
            case QUERY_SUBMITTED -> reviewersForLowestStage(plan, snapshot);
            case QUERY_APPROVED, QUERY_REJECTED -> submitter != null
                    ? List.of(toRecipient(submitter))
                    : List.of();
            case AI_HIGH_RISK -> userQueryService
                    .findByOrganizationAndRole(snapshot.organizationId(), UserRoleType.ADMIN)
                    .stream()
                    .filter(UserView::active)
                    .map(NotificationContextBuilder::toRecipient)
                    .toList();
            case TEST -> submitter != null ? List.of(toRecipient(submitter)) : List.of();
        };
    }

    private List<RecipientView> reviewersForLowestStage(ReviewPlanSnapshot plan,
                                                        QueryRequestSnapshot snapshot) {
        if (plan == null || plan.approvers() == null || plan.approvers().isEmpty()) {
            return List.of();
        }
        var lowestStage = plan.approvers().stream()
                .mapToInt(ApproverRule::stage)
                .min()
                .orElse(0);
        var rules = plan.approvers().stream()
                .filter(r -> r.stage() == lowestStage)
                .toList();
        var byUserId = new LinkedHashMap<UUID, RecipientView>();
        for (ApproverRule rule : rules) {
            if (rule.userId() != null) {
                userQueryService.findById(rule.userId())
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .ifPresent(u -> byUserId.putIfAbsent(u.id(), toRecipient(u)));
            } else if (rule.role() != null) {
                userQueryService.findByOrganizationAndRole(snapshot.organizationId(), rule.role())
                        .stream()
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .forEach(u -> byUserId.putIfAbsent(u.id(), toRecipient(u)));
            }
        }
        return byUserId.values().stream()
                .sorted(Comparator.comparing(RecipientView::email,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private URI buildReviewUrl(UUID queryRequestId) {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/queries/" + queryRequestId);
    }

    private static RecipientView toRecipient(UserView user) {
        return new RecipientView(user.id(), user.email(), user.displayName());
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        var stripped = text.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max) + "…";
    }
}
