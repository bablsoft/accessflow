package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolutionFailedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolvedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Records audit rows for system-driven state transitions. User-initiated transitions are audited
 * synchronously at the controller layer so {@code ip_address} / {@code user_agent} can be captured
 * from the live HTTP request — those events are intentionally not handled here.
 *
 * <p>Each handler swallows its own runtime failures so an audit-write problem never propagates back
 * to the publisher (the publishing transaction has already committed by the time these listeners
 * fire).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AuditEventListener {

    private final AuditLogService auditLogService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final DatasourceLookupService datasourceLookupService;

    @ApplicationModuleListener
    void onAiCompleted(AiAnalysisCompletedEvent event) {
        recordSafely(AuditAction.QUERY_AI_ANALYZED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    metadata.put("ai_analysis_id", event.aiAnalysisId().toString());
                    metadata.put("risk_level", event.riskLevel().name());
                    return new AuditEntry(
                            AuditAction.QUERY_AI_ANALYZED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onAiFailed(AiAnalysisFailedEvent event) {
        recordSafely(AuditAction.QUERY_AI_FAILED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    if (event.reason() != null) {
                        metadata.put("reason", event.reason());
                    }
                    return new AuditEntry(
                            AuditAction.QUERY_AI_FAILED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onQueryReadyForReview(QueryReadyForReviewEvent event) {
        recordSafely(AuditAction.QUERY_REVIEW_REQUESTED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    if (event.matchedPolicyId() != null) {
                        metadata.put("source", "ROUTING_POLICY");
                        metadata.put("routing_policy_id", event.matchedPolicyId().toString());
                        if (event.effectiveMinApprovals() != null) {
                            metadata.put("effective_min_approvals", event.effectiveMinApprovals());
                        }
                        if (event.routingReason() != null) {
                            metadata.put("reason", event.routingReason());
                        }
                    }
                    return new AuditEntry(
                            AuditAction.QUERY_REVIEW_REQUESTED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onQueryTimedOut(QueryTimedOutEvent event) {
        recordSafely(AuditAction.QUERY_REJECTED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    metadata.put("auto_rejected", true);
                    metadata.put("reason", "approval_timeout");
                    metadata.put("timeout_hours", event.approvalTimeoutHours());
                    return new AuditEntry(
                            AuditAction.QUERY_REJECTED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onQueryAutoApproved(QueryAutoApprovedEvent event) {
        recordSafely(AuditAction.QUERY_APPROVED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    metadata.put("auto_approved", true);
                    if (event.matchedPolicyId() != null) {
                        metadata.put("source", "ROUTING_POLICY");
                        metadata.put("routing_policy_id", event.matchedPolicyId().toString());
                        if (event.reason() != null) {
                            metadata.put("reason", event.reason());
                        }
                    } else if (event.accessGrantId() != null) {
                        metadata.put("source", "ACCESS_GRANT");
                        metadata.put("access_grant_id", event.accessGrantId().toString());
                        if (event.grantApproverEmail() != null) {
                            metadata.put("grant_approver", event.grantApproverEmail());
                        }
                        if (event.reason() != null) {
                            metadata.put("reason", event.reason());
                        }
                    }
                    return new AuditEntry(
                            AuditAction.QUERY_APPROVED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onQueryAutoRejected(QueryAutoRejectedEvent event) {
        recordSafely(AuditAction.QUERY_REJECTED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> {
                    var metadata = new HashMap<String, Object>();
                    metadata.put("auto_rejected", true);
                    metadata.put("source", "ROUTING_POLICY");
                    if (event.matchedPolicyId() != null) {
                        metadata.put("routing_policy_id", event.matchedPolicyId().toString());
                    }
                    if (event.reason() != null) {
                        metadata.put("reason", event.reason());
                    }
                    return new AuditEntry(
                            AuditAction.QUERY_REJECTED,
                            AuditResourceType.QUERY_REQUEST,
                            snapshot.id(),
                            snapshot.organizationId(),
                            null,
                            metadata,
                            null,
                            null);
                });
    }

    @ApplicationModuleListener
    void onBootstrapResourceUpserted(BootstrapResourceUpsertedEvent event) {
        try {
            var action = resolveBootstrapAction(event.resourceType(), event.changeKind());
            var resourceType = resolveBootstrapResourceType(event.resourceType());
            var metadata = new HashMap<String, Object>();
            metadata.put("source", "BOOTSTRAP");
            metadata.put("change_kind", event.changeKind().name());
            if (!event.changedFields().isEmpty()) {
                metadata.put("changed_fields", event.changedFields());
            }
            metadata.putAll(event.summaryMetadata());
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    event.resourceId(),
                    event.organizationId(),
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for BootstrapResourceUpsertedEvent {} {} {}",
                    event.resourceType(), event.changeKind(), event.resourceId(), ex);
        }
    }

    private static AuditAction resolveBootstrapAction(BootstrapResourceType type, BootstrapChangeKind kind) {
        return switch (type) {
            case ORGANIZATION -> AuditAction.ORGANIZATION_CREATED;
            case ADMIN_USER -> AuditAction.USER_CREATED;
            case SERVICE_ACCOUNT -> kind == BootstrapChangeKind.CREATE
                    ? AuditAction.API_KEY_CREATED
                    : AuditAction.API_KEY_UPDATED;
            case NOTIFICATION_CHANNEL -> kind == BootstrapChangeKind.CREATE
                    ? AuditAction.NOTIFICATION_CHANNEL_CREATED
                    : AuditAction.NOTIFICATION_CHANNEL_UPDATED;
            case AI_CONFIG -> kind == BootstrapChangeKind.CREATE
                    ? AuditAction.AI_CONFIG_CREATED
                    : AuditAction.AI_CONFIG_UPDATED;
            case REVIEW_PLAN -> kind == BootstrapChangeKind.CREATE
                    ? AuditAction.REVIEW_PLAN_CREATED
                    : AuditAction.REVIEW_PLAN_UPDATED;
            case DATASOURCE -> kind == BootstrapChangeKind.CREATE
                    ? AuditAction.DATASOURCE_CREATED
                    : AuditAction.DATASOURCE_UPDATED;
            case SAML_CONFIG -> AuditAction.SAML_CONFIG_UPDATED;
            case OAUTH2_CONFIG -> AuditAction.OAUTH2_CONFIG_UPDATED;
            case LANGFUSE_CONFIG -> AuditAction.LANGFUSE_CONFIG_UPDATED;
            case SYSTEM_SMTP -> AuditAction.SYSTEM_SMTP_UPDATED;
        };
    }

    private static AuditResourceType resolveBootstrapResourceType(BootstrapResourceType type) {
        return switch (type) {
            case ORGANIZATION -> AuditResourceType.ORGANIZATION;
            case ADMIN_USER -> AuditResourceType.USER;
            case SERVICE_ACCOUNT -> AuditResourceType.API_KEY;
            case NOTIFICATION_CHANNEL -> AuditResourceType.NOTIFICATION_CHANNEL;
            case AI_CONFIG -> AuditResourceType.AI_CONFIG;
            case REVIEW_PLAN -> AuditResourceType.REVIEW_PLAN;
            case DATASOURCE -> AuditResourceType.DATASOURCE;
            case SAML_CONFIG -> AuditResourceType.SAML_CONFIG;
            case OAUTH2_CONFIG -> AuditResourceType.OAUTH2_CONFIG;
            case LANGFUSE_CONFIG -> AuditResourceType.LANGFUSE_CONFIG;
            case SYSTEM_SMTP -> AuditResourceType.SYSTEM_SMTP;
        };
    }

    @ApplicationModuleListener
    void onNotificationDeliveryExhausted(NotificationDeliveryExhaustedEvent event) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("source", "DISPATCHER");
            metadata.put("channel_id", event.channelId().toString());
            metadata.put("channel_type", event.channelType());
            metadata.put("event_type", event.eventType());
            metadata.put("attempt_count", event.attemptCount());
            if (event.lastHttpStatus() != null) {
                metadata.put("last_http_status", event.lastHttpStatus());
            }
            if (event.lastError() != null) {
                metadata.put("last_error", event.lastError());
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.NOTIFICATION_DELIVERY_EXHAUSTED,
                    AuditResourceType.NOTIFICATION_CHANNEL,
                    event.channelId(),
                    event.organizationId(),
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for NotificationDeliveryExhaustedEvent {} {}",
                    event.channelId(), event.eventType(), ex);
        }
    }

    @ApplicationModuleListener
    void onDatasourceDeactivated(DatasourceDeactivatedEvent event) {
        try {
            var descriptor = datasourceLookupService.findById(event.datasourceId()).orElse(null);
            if (descriptor == null) {
                log.warn("DatasourceDeactivatedEvent for unknown datasource {}", event.datasourceId());
                return;
            }
            var metadata = new HashMap<String, Object>();
            metadata.put("change", "deactivated");
            auditLogService.record(new AuditEntry(
                    AuditAction.DATASOURCE_UPDATED,
                    AuditResourceType.DATASOURCE,
                    descriptor.id(),
                    descriptor.organizationId(),
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for DatasourceDeactivatedEvent {}", event.datasourceId(), ex);
        }
    }

    // The two secret-reference handlers (AF-448) use plain @EventListener deliberately:
    // @ApplicationModuleListener is an AFTER_COMMIT listener, and pool-init-time resolves are
    // published outside any application-DB transaction, where transactional events are
    // silently dropped.

    @EventListener
    void onSecretReferenceResolved(SecretReferenceResolvedEvent event) {
        try {
            recordSecretResolution(AuditAction.DATASOURCE_SECRET_RESOLVED,
                    event.provider(), event.reference(), event.datasourceId(),
                    event.organizationId(), null);
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SecretReferenceResolvedEvent {}", event.reference(), ex);
        }
    }

    @EventListener
    void onSecretReferenceResolutionFailed(SecretReferenceResolutionFailedEvent event) {
        try {
            recordSecretResolution(AuditAction.DATASOURCE_SECRET_RESOLUTION_FAILED,
                    event.provider(), event.reference(), event.datasourceId(),
                    event.organizationId(), event.error());
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SecretReferenceResolutionFailedEvent {}",
                    event.reference(), ex);
        }
    }

    /**
     * Records one audit row per owning datasource. Engine-lane resolves carry no datasource
     * context ({@code datasourceId}/{@code organizationId} null) — those are attributed by
     * matching the reference against the stored credential columns. The metadata carries the
     * provider and the reference (a store path, not a secret) — never the resolved value.
     */
    private void recordSecretResolution(AuditAction action, String provider, String reference,
                                        UUID datasourceId, UUID organizationId, String error) {
        var metadata = new HashMap<String, Object>();
        metadata.put("provider", provider);
        metadata.put("reference", reference);
        if (error != null) {
            metadata.put("error", error);
        }
        if (datasourceId != null && organizationId != null) {
            auditLogService.record(new AuditEntry(action, AuditResourceType.DATASOURCE,
                    datasourceId, organizationId, null, metadata, null, null));
            return;
        }
        var owners = datasourceLookupService.findByCredentialReference(reference);
        if (owners.isEmpty()) {
            log.warn("{} for reference {} could not be attributed to a datasource", action, reference);
            return;
        }
        for (var owner : owners) {
            auditLogService.record(new AuditEntry(action, AuditResourceType.DATASOURCE,
                    owner.id(), owner.organizationId(), null, metadata, null, null));
        }
    }

    private void recordSafely(AuditAction action, UUID queryRequestId,
                              Supplier<Optional<QueryRequestSnapshot>> snapshotSupplier,
                              java.util.function.Function<QueryRequestSnapshot, AuditEntry> entryBuilder) {
        try {
            var snapshot = snapshotSupplier.get().orElse(null);
            if (snapshot == null) {
                log.warn("{} listener received unknown queryRequestId {}", action, queryRequestId);
                return;
            }
            auditLogService.record(entryBuilder.apply(snapshot));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query {}", action, queryRequestId, ex);
        }
    }
}
