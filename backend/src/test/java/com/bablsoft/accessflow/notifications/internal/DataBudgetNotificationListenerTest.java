package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.events.DataBudgetThresholdCrossedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DataBudgetNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private DataBudgetNotificationListener listener;

    private static DataBudgetThresholdCrossedEvent event(boolean exhausted) {
        return new DataBudgetThresholdCrossedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "daily-reads", exhausted, 80, exhausted ? 100 : 80, 1000L, null,
                800L, 0L, 1440, DataBudgetBreachAction.REJECT);
    }

    @Test
    void forwardsEveryCrossingToTheDispatcher() {
        var warning = event(false);
        var exhausted = event(true);

        listener.onThresholdCrossed(warning);
        listener.onThresholdCrossed(exhausted);

        verify(dispatcher).dispatchDataBudget(warning);
        verify(dispatcher).dispatchDataBudget(exhausted);
    }

    @Test
    void aDispatchFailureNeverPropagates() {
        var event = event(true);
        doThrow(new IllegalStateException("boom")).when(dispatcher).dispatchDataBudget(event);

        assertThatCode(() -> listener.onThresholdCrossed(event)).doesNotThrowAnyException();
    }
}
