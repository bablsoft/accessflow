package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects a request group's status onto the promotion it runs (#880). Not an
 * {@code @ApplicationModuleListener}: the group's execution transitions (EXECUTING, EXECUTED,
 * PARTIALLY_EXECUTED, FAILED) are published from the scheduled job with no surrounding
 * transaction, which a plain after-commit listener never sees — {@code fallbackExecution} runs the
 * handler immediately in that case. It stays {@code @Async} on purpose: in the fallback path a
 * synchronous handler would run inline inside the group executor, so a failure here must never be
 * able to stall the group or surface to an approver.
 */
@Component
@RequiredArgsConstructor
class SchemaChangePromotionStatusListener {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangePromotionStatusListener.class);

    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final RequestGroupService requestGroupService;
    private final DatasourceAdminService datasourceAdminService;
    private final ObjectMapper objectMapper;
    private final SchemaChangeAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequestGroupStatusChanged(RequestGroupStatusChangedEvent event) {
        try {
            project(event);
        } catch (RuntimeException ex) {
            log.warn("Could not project request group {} status {} onto its promotion: {}",
                    event.requestGroupId(), event.newStatus(), ex.getMessage());
        }
    }

    private void project(RequestGroupStatusChangedEvent event) {
        var next = SchemaChangePromotionStatusMapper.map(event.newStatus()).orElse(null);
        if (next == null) {
            return;
        }
        var promotion = promotionRepository.findByRequestGroupIdForUpdate(event.requestGroupId()).orElse(null);
        if (promotion == null) {
            return; // a group this module did not create
        }
        var current = promotion.getStatus();
        if (!SchemaChangePromotionStatusMapper.advances(current, next)) {
            return;
        }
        promotion.setStatus(next);
        switch (next) {
            case APPLIED -> {
                promotion.setAppliedAt(clock.instant());
                takeSnapshot(promotion);
            }
            case FAILED, PARTIALLY_APPLIED -> promotion.setErrorMessage(firstFailure(promotion));
            default -> {
                // IN_REVIEW / APPROVED / CANCELLED carry no extra state.
            }
        }
        promotionRepository.saveAndFlush(promotion);
        audit(promotion, event);
        eventPublisher.publishEvent(new SchemaChangePromotionStatusChangedEvent(promotion.getId(),
                promotion.getChangeSet().getId(), promotion.getEnvironmentId(), promotion.getOrganizationId(),
                promotion.getPromotedBy(), current, next));
    }

    /**
     * The PROMOTION_SNAPSHOT drift baseline (#881): the target introspected as close to the
     * transition as possible. Anything changed out of band in between is baked in, which is why
     * the timestamp is always recorded; an introspection failure leaves the snapshot null and the
     * transition intact.
     */
    private void takeSnapshot(SchemaChangeSetPromotionEntity promotion) {
        try {
            var schema = datasourceAdminService.introspectSchemaForSystem(promotion.getDatasourceId(),
                    promotion.getOrganizationId());
            promotion.setSchemaSnapshot(objectMapper.writeValueAsString(schema));
            promotion.setSnapshotTakenAt(clock.instant());
        } catch (RuntimeException ex) {
            log.warn("Could not snapshot datasource {} after promotion {}: {}", promotion.getDatasourceId(),
                    promotion.getId(), ex.getMessage());
        }
    }

    /** The group never records its own error; the first FAILED member holds the reason. */
    private String firstFailure(SchemaChangeSetPromotionEntity promotion) {
        try {
            return requestGroupService.get(promotion.getRequestGroupId(), promotion.getOrganizationId(),
                            promotion.getPromotedBy(), true)
                    .items().stream()
                    .filter(i -> i.status() == RequestGroupItemStatus.FAILED)
                    .map(RequestGroupItemView::errorMessage)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Could not read the failed member of request group {}: {}", promotion.getRequestGroupId(),
                    ex.getMessage());
            return null;
        }
    }

    private void audit(SchemaChangeSetPromotionEntity promotion, RequestGroupStatusChangedEvent event) {
        var action = switch (promotion.getStatus()) {
            case APPLIED -> AuditAction.SCHEMA_CHANGE_PROMOTION_APPLIED;
            case PARTIALLY_APPLIED -> AuditAction.SCHEMA_CHANGE_PROMOTION_PARTIALLY_APPLIED;
            case FAILED -> AuditAction.SCHEMA_CHANGE_PROMOTION_FAILED;
            case CANCELLED -> AuditAction.SCHEMA_CHANGE_PROMOTION_CANCELLED;
            case PENDING, IN_REVIEW, APPROVED -> null;
        };
        if (action == null) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("trigger", "request_group");
        metadata.put("request_group_id", event.requestGroupId().toString());
        metadata.put("group_status", event.newStatus().name());
        metadata.put("change_set_id", promotion.getChangeSet().getId().toString());
        metadata.put("environment_id", promotion.getEnvironmentId().toString());
        if (promotion.getErrorMessage() != null) {
            metadata.put("error_message", promotion.getErrorMessage());
        }
        // System decision: no actor, no caller ip.
        auditWriter.record(action, promotion.getId(), promotion.getOrganizationId(), null, metadata, null, null);
    }
}
