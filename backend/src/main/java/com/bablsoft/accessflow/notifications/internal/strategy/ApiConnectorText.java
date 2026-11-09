package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared field set for the connector-scoped API-governance events (#895), so Slack, Discord, Teams
 * and Telegram show the same lines in the same order. The detail line is labelled per event: the
 * drift finding count, the last health-probe error, spend / budget (#973); the token-failure
 * event carries none.
 */
final class ApiConnectorText {

    private ApiConnectorText() {
    }

    static List<Map.Entry<String, String>> fields(NotificationContext ctx) {
        var fields = new ArrayList<Map.Entry<String, String>>();
        fields.add(Map.entry("Connector", dash(ctx.datasourceName())));
        var detail = ctx.reviewerComment();
        if (detail != null && !detail.isBlank()) {
            fields.add(Map.entry(detailLabel(ctx.eventType()), detail));
        }
        return fields;
    }

    private static String detailLabel(NotificationEventType eventType) {
        return switch (eventType) {
            case API_CONNECTOR_DRIFT -> "Drift findings";
            case API_CONNECTOR_UNHEALTHY -> "Last error";
            case API_CONNECTOR_BUDGET_EXCEEDED -> "Month-to-date spend / budget";
            default -> "Detail";
        };
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
