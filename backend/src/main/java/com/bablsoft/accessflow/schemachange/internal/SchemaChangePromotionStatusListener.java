package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
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
            // ERROR, not WARN: a lost projection strands the promotion in a non-terminal status
            // after its DDL may already have run, which freezes the change set and blocks every
            // higher rung. There is no retry — an operator has to see this.
            log.error("Could not project request group {} status {} onto its promotion",
                    event.requestGroupId(), event.newStatus(), ex);
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
            // The snapshot is deliberately NOT taken here: it is remote I/O against the customer
            // database, and this transaction holds the promotion row's write lock. It runs in
            // SchemaChangePromotionSnapshotListener once this transition is committed and visible.
            case APPLIED -> promotion.setAppliedAt(clock.instant());
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
            // A rejected or timed-out group lands here too; group_status below says which.
            case CANCELLED -> AuditAction.SCHEMA_CHANGE_PROMOTION_CANCELLED;
            // #882: the group's reviewer decisions are audited by requestgroups; this row is the
            // promotion-level fact that the environment's review was satisfied.
            case APPROVED -> AuditAction.SCHEMA_CHANGE_PROMOTION_APPROVED;
            case PENDING, IN_REVIEW -> null;
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
