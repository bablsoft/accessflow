package com.partqam.accessflow.audit.internal;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
import com.partqam.accessflow.core.events.DatasourceDeactivatedEvent;
import com.partqam.accessflow.core.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
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
                snapshot -> new AuditEntry(
                        AuditAction.QUERY_REVIEW_REQUESTED,
                        AuditResourceType.QUERY_REQUEST,
                        snapshot.id(),
                        snapshot.organizationId(),
                        null,
                        Map.of(),
                        null,
                        null));
    }

    @ApplicationModuleListener
    void onQueryAutoApproved(QueryAutoApprovedEvent event) {
        recordSafely(AuditAction.QUERY_APPROVED, event.queryRequestId(),
                () -> queryRequestLookupService.findById(event.queryRequestId()),
                snapshot -> new AuditEntry(
                        AuditAction.QUERY_APPROVED,
                        AuditResourceType.QUERY_REQUEST,
                        snapshot.id(),
                        snapshot.organizationId(),
                        null,
                        Map.of("auto_approved", true),
                        null,
                        null));
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
