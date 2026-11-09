package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConnectorTextTest {

    @Test
    void driftLabelsTheFindingCount() {
        var fields = ApiConnectorText.fields(
                ApiConnectorNotificationTest.connectorCtx(NotificationEventType.API_CONNECTOR_DRIFT));

        assertThat(fields).containsExactly(Map.entry("Connector", "Stripe"), Map.entry("Drift findings", "4"));
    }

    @Test
    void unhealthyLabelsTheLastError() {
        var fields = ApiConnectorText.fields(
                ApiConnectorNotificationTest.connectorCtx(NotificationEventType.API_CONNECTOR_UNHEALTHY));

        assertThat(fields).containsExactly(Map.entry("Connector", "Stripe"),
                Map.entry("Last error", "Connection refused"));
    }

    @Test
    void budgetLabelsSpendAgainstBudget() {
        var fields = ApiConnectorText.fields(
                ApiConnectorNotificationTest.connectorCtx(NotificationEventType.API_CONNECTOR_BUDGET_EXCEEDED));

        assertThat(fields).containsExactly(Map.entry("Connector", "Stripe"),
                Map.entry("Month-to-date spend / budget", "12.5 / 10"));
    }

    @Test
    void tokenFailureCarriesNoDetailLine() {
        var fields = ApiConnectorText.fields(
                ApiConnectorNotificationTest.connectorCtx(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED));

        assertThat(fields).containsExactly(Map.entry("Connector", "Stripe"));
    }

    @Test
    void genericLabelAndDashForAnUnexpectedShape() {
        var ctx = ApiConnectorNotificationTest.connectorCtx(NotificationEventType.API_CONNECTOR_DRIFT);
        var other = new com.bablsoft.accessflow.notifications.internal.NotificationContext(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, ctx.organizationId(),
                null, null, null, null, null, null, null, null,
                ctx.datasourceId(), " ",
                null, null, null, null, null, null,
                "boom",
                ctx.reviewUrl(), ctx.recipients(), ctx.occurredAt(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null);

        assertThat(ApiConnectorText.fields(other))
                .containsExactly(Map.entry("Connector", "—"), Map.entry("Detail", "boom"));
    }
}
