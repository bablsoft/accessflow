package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.events.SchemaDriftDetectedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans out schema-change notifications (#882, epic #870). {@code requestgroups} has no notification
 * path of its own, so a promotion is notified from its own status event rather than its group's.
 * {@code SCHEMA_CHANGE_PROMOTION_SUBMITTED} deliberately fires on {@code IN_REVIEW} — the group
 * reached {@code PENDING_REVIEW} — rather than on submission, so a promotion that needs no review
 * never pings reviewers. {@code PARTIALLY_APPLIED} folds into {@code SCHEMA_CHANGE_PROMOTION_FAILED}:
 * the run stops on the first failed statement. Delivery is best-effort and never blocks the flow.
 */
@Component
@RequiredArgsConstructor
class SchemaChangeNotificationListener {

    private final NotificationDispatcher dispatcher;

    @ApplicationModuleListener
    void onPromotionStatusChanged(SchemaChangePromotionStatusChangedEvent event) {
        var type = mapStatus(event.newStatus());
        if (type != null) {
            dispatcher.dispatchSchemaChangePromotion(type, event.promotionId());
        }
    }

    /**
     * The drift scan publishes without a transaction, where a plain after-commit listener (and so
     * {@code @ApplicationModuleListener}) would never run — hence {@code fallbackExecution}.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDriftDetected(SchemaDriftDetectedEvent event) {
        dispatcher.dispatchSchemaDrift(event);
    }

    static NotificationEventType mapStatus(SchemaChangePromotionStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case IN_REVIEW -> NotificationEventType.SCHEMA_CHANGE_PROMOTION_SUBMITTED;
            case APPLIED -> NotificationEventType.SCHEMA_CHANGE_PROMOTION_APPLIED;
            case FAILED, PARTIALLY_APPLIED -> NotificationEventType.SCHEMA_CHANGE_PROMOTION_FAILED;
            case PENDING, APPROVED, CANCELLED -> null;
        };
    }
}
