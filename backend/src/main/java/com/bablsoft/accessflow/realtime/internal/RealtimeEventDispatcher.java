package com.bablsoft.accessflow.realtime.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryStatusChangedEvent;
import com.bablsoft.accessflow.notifications.api.UserNotificationLookupService;
import com.bablsoft.accessflow.notifications.events.UserNotificationCreatedEvent;
import com.bablsoft.accessflow.realtime.internal.ws.SessionRegistry;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import com.bablsoft.accessflow.workflow.events.ReviewDecisionMadeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges domain events to the WebSocket fan-out. One {@link ApplicationModuleListener} per
 * source event builds the spec-shaped envelope (see {@code docs/04-api-spec.md}) and pushes
 * it to the targeted users via {@link SessionRegistry}. Each handler swallows its own
 * exceptions so a transient WS or lookup failure never propagates back to the publishing
 * transaction.
 */
@Component
@Slf4j
class RealtimeEventDispatcher {

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final UserQueryService userQueryService;
    private final DatasourceAdminService datasourceAdminService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final UserNotificationLookupService userNotificationLookupService;
    private final Clock clock;

    @Autowired
    RealtimeEventDispatcher(SessionRegistry sessionRegistry,
                            ObjectMapper objectMapper,
                            QueryRequestLookupService queryRequestLookupService,
                            ReviewPlanLookupService reviewPlanLookupService,
                            UserQueryService userQueryService,
                            DatasourceAdminService datasourceAdminService,
                            AiAnalysisLookupService aiAnalysisLookupService,
                            UserNotificationLookupService userNotificationLookupService) {
        this(sessionRegistry, objectMapper, queryRequestLookupService, reviewPlanLookupService,
                userQueryService, datasourceAdminService, aiAnalysisLookupService,
                userNotificationLookupService, Clock.systemUTC());
    }

    // Package-private constructor for tests that need a fixed clock.
    RealtimeEventDispatcher(SessionRegistry sessionRegistry,
                            ObjectMapper objectMapper,
                            QueryRequestLookupService queryRequestLookupService,
                            ReviewPlanLookupService reviewPlanLookupService,
                            UserQueryService userQueryService,
                            DatasourceAdminService datasourceAdminService,
                            AiAnalysisLookupService aiAnalysisLookupService,
                            UserNotificationLookupService userNotificationLookupService,
                            Clock clock) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.queryRequestLookupService = queryRequestLookupService;
        this.reviewPlanLookupService = reviewPlanLookupService;
        this.userQueryService = userQueryService;
        this.datasourceAdminService = datasourceAdminService;
        this.aiAnalysisLookupService = aiAnalysisLookupService;
        this.userNotificationLookupService = userNotificationLookupService;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onQueryStatusChanged(QueryStatusChangedEvent event) {
        safe("query.status_changed", event.queryRequestId(), () -> {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query_id", event.queryRequestId().toString());
            data.put("old_status", event.oldStatus().name());
            data.put("new_status", event.newStatus().name());
            sendTo(event.submitterId(), "query.status_changed", data);
        });
    }

    @ApplicationModuleListener
    void onQueryExecuted(QueryExecutedEvent event) {
        safe("query.executed", event.queryRequestId(), () -> {
            var snapshot = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
            if (snapshot == null) {
                log.debug("query.executed: query {} not found, skipping push",
                        event.queryRequestId());
                return;
            }
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query_id", event.queryRequestId().toString());
            if (event.rowsAffected() != null) {
                data.put("rows_affected", event.rowsAffected());
            } else {
                data.putNull("rows_affected");
            }
            data.put("duration_ms", event.durationMs());
            sendTo(snapshot.submittedByUserId(), "query.executed", data);
        });
    }

    @ApplicationModuleListener
    void onAiAnalysisCompleted(AiAnalysisCompletedEvent event) {
        safe("ai.analysis_complete", event.queryRequestId(), () -> {
            var snapshot = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
            if (snapshot == null) {
                return;
            }
            var ai = aiAnalysisLookupService.findByQueryRequestId(event.queryRequestId())
                    .orElse(null);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query_id", event.queryRequestId().toString());
            data.put("risk_level", event.riskLevel().name());
            if (ai != null) {
                data.put("risk_score", ai.riskScore());
            } else {
                data.putNull("risk_score");
            }
            sendTo(snapshot.submittedByUserId(), "ai.analysis_complete", data);
        });
    }

    @ApplicationModuleListener
    void onQueryReadyForReview(QueryReadyForReviewEvent event) {
        safe("review.new_request", event.queryRequestId(), () -> {
            var snapshot = queryRequestLookupService.findById(event.queryRequestId()).orElse(null);
            if (snapshot == null) {
                return;
            }
            var datasourceName = lookupDatasourceName(snapshot);
            var submitterEmail = userQueryService.findById(snapshot.submittedByUserId())
                    .map(UserView::email)
                    .orElse(null);
            var ai = aiAnalysisLookupService.findByQueryRequestId(event.queryRequestId())
                    .orElse(null);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query_id", event.queryRequestId().toString());
            if (ai != null) {
                data.put("risk_level", ai.riskLevel().name());
            } else {
                data.putNull("risk_level");
            }
            if (submitterEmail != null) {
                data.put("submitter", submitterEmail);
            } else {
                data.putNull("submitter");
            }
            if (datasourceName != null) {
                data.put("datasource", datasourceName);
            } else {
                data.putNull("datasource");
            }
            var envelope = serialize("review.new_request", data);
            for (var reviewerId : eligibleReviewersForLowestStage(snapshot)) {
                sessionRegistry.sendToUser(reviewerId, envelope);
            }
        });
    }

    @ApplicationModuleListener
    void onUserNotificationCreated(UserNotificationCreatedEvent event) {
        safe("notification.created", event.notificationId(), () -> {
            var note = userNotificationLookupService.findById(event.notificationId()).orElse(null);
            if (note == null) {
                log.debug("notification.created: notification {} not found, skipping push",
                        event.notificationId());
                return;
            }
            ObjectNode data = objectMapper.createObjectNode();
            data.put("notification_id", note.id().toString());
            data.put("event_type", note.eventType().name());
            if (note.queryRequestId() != null) {
                data.put("query_id", note.queryRequestId().toString());
            } else {
                data.putNull("query_id");
            }
            data.put("created_at", note.createdAt().toString());
            sendTo(event.userId(), "notification.created", data);
        });
    }

    @ApplicationModuleListener
    void onReviewDecisionMade(ReviewDecisionMadeEvent event) {
        safe("review.decision_made", event.queryRequestId(), () -> {
            var reviewerEmail = userQueryService.findById(event.reviewerId())
                    .map(UserView::email)
                    .orElse(null);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query_id", event.queryRequestId().toString());
            data.put("decision", event.decision().name());
            if (reviewerEmail != null) {
                data.put("reviewer", reviewerEmail);
            } else {
                data.putNull("reviewer");
            }
            if (event.comment() != null) {
                data.put("comment", event.comment());
            } else {
                data.putNull("comment");
            }
            sendTo(event.submitterId(), "review.decision_made", data);
        });
    }

    private void sendTo(UUID userId, String eventName, ObjectNode data) {
        var envelope = serialize(eventName, data);
        sessionRegistry.sendToUser(userId, envelope);
    }

    private String serialize(String eventName, ObjectNode data) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("event", eventName);
        envelope.put("timestamp", Instant.now(clock).toString());
        envelope.set("data", data);
        return envelope.toString();
    }

    private Set<UUID> eligibleReviewersForLowestStage(QueryRequestSnapshot snapshot) {
        var plan = reviewPlanLookupService.findForDatasource(snapshot.datasourceId()).orElse(null);
        if (plan == null || plan.approvers() == null || plan.approvers().isEmpty()) {
            return Set.of();
        }
        var lowestStage = plan.approvers().stream()
                .mapToInt(ApproverRule::stage)
                .min()
                .orElse(0);
        Set<UUID> reviewers = new LinkedHashSet<>();
        for (ApproverRule rule : plan.approvers()) {
            if (rule.stage() != lowestStage) {
                continue;
            }
            if (rule.userId() != null) {
                userQueryService.findById(rule.userId())
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .ifPresent(u -> reviewers.add(u.id()));
            } else if (rule.role() != null) {
                userQueryService
                        .findByOrganizationAndRole(snapshot.organizationId(), rule.role())
                        .stream()
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .forEach(u -> reviewers.add(u.id()));
            }
        }
        return reviewers;
    }

    private String lookupDatasourceName(QueryRequestSnapshot snapshot) {
        try {
            var view = datasourceAdminService.getForAdmin(snapshot.datasourceId(),
                    snapshot.organizationId());
            return view != null ? view.name() : null;
        } catch (RuntimeException ex) {
            log.debug("Datasource name lookup failed for {}: {}", snapshot.datasourceId(),
                    ex.getMessage());
            return null;
        }
    }

    private void safe(String eventName, UUID subjectId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException ex) {
            log.error("Realtime dispatch for {} on subject {} failed", eventName, subjectId, ex);
        }
    }
}
