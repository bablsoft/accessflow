package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.events.DataBudgetThresholdCrossedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans out data-budget crossings (#942). The usage service publishes inside its own
 * {@code REQUIRES_NEW} transaction, so the after-commit module listener runs once the usage row is
 * durable. Delivery is best-effort and never affects the read that crossed the mark.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class DataBudgetNotificationListener {

    private final NotificationDispatcher dispatcher;

    @ApplicationModuleListener
    void onThresholdCrossed(DataBudgetThresholdCrossedEvent event) {
        try {
            dispatcher.dispatchDataBudget(event);
        } catch (RuntimeException ex) {
            log.error("Failed to dispatch data-budget notification for budget {} and user {}",
                    event.budgetId(), event.userId(), ex);
        }
    }
}
