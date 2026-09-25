package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataBudgetTextTest {

    @Test
    void warningListsUsageButNotTheBreachAction() {
        var fields = DataBudgetText.fields(
                DataBudgetNotificationTest.dataBudgetCtx(NotificationEventType.DATA_BUDGET_THRESHOLD_REACHED));

        assertThat(fields).extracting(Map.Entry::getKey)
                .containsExactly("Datasource", "User", "Budget", "Used", "Rows", "Bytes", "Window");
        assertThat(fields).contains(Map.entry("User", "Ana Analyst (ana@example.com)"),
                Map.entry("Used", "85%"), Map.entry("Window", "1 day"));
    }

    @Test
    void exhaustionAddsTheBreachAction() {
        var fields = DataBudgetText.fields(
                DataBudgetNotificationTest.dataBudgetCtx(NotificationEventType.DATA_BUDGET_EXHAUSTED));

        assertThat(fields).contains(Map.entry("On breach", "REQUIRE_REVIEW"));
    }

    @Test
    void missingNamesRenderAsDashes() {
        var ctx = DataBudgetNotificationTest.dataBudgetCtx(NotificationEventType.DATA_BUDGET_EXHAUSTED);
        var bare = new com.bablsoft.accessflow.notifications.internal.NotificationContext(
                ctx.eventType(), ctx.organizationId(), null, null, null, null, null, null, null, null,
                ctx.datasourceId(), null, null, null, null, null, null, null, null, null,
                ctx.recipients(), ctx.occurredAt(), "en", null);

        assertThat(DataBudgetText.fields(bare))
                .containsExactly(Map.entry("Datasource", "—"), Map.entry("User", "—"));
    }
}
