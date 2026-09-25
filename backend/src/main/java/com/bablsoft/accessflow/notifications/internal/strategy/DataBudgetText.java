package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.internal.NotificationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared field set for the #942 data-budget events, so Slack, Discord, Teams and Telegram show the
 * same lines in the same order. Usage lines appear only for the limits the budget sets; the breach
 * action only once the budget is exhausted, since that is when it takes effect.
 */
final class DataBudgetText {

    private DataBudgetText() {
    }

    static List<Map.Entry<String, String>> fields(NotificationContext ctx) {
        var budget = ctx.dataBudget();
        var fields = new ArrayList<Map.Entry<String, String>>();
        fields.add(Map.entry("Datasource", dash(ctx.datasourceName())));
        fields.add(Map.entry("User", dash(user(ctx))));
        if (budget == null) {
            return fields;
        }
        fields.add(Map.entry("Budget", dash(budget.budgetName())));
        fields.add(Map.entry("Used", budget.usedPercent() + "%"));
        if (budget.rowsUsage() != null) {
            fields.add(Map.entry("Rows", budget.rowsUsage()));
        }
        if (budget.bytesUsage() != null) {
            fields.add(Map.entry("Bytes", budget.bytesUsage()));
        }
        fields.add(Map.entry("Window", budget.windowLabel()));
        if (budget.exhausted() && budget.breachAction() != null) {
            fields.add(Map.entry("On breach", budget.breachAction().name()));
        }
        return fields;
    }

    private static String user(NotificationContext ctx) {
        if (ctx.submitterDisplayName() != null && ctx.submitterEmail() != null) {
            return ctx.submitterDisplayName() + " (" + ctx.submitterEmail() + ")";
        }
        return ctx.submitterEmail() != null ? ctx.submitterEmail() : ctx.submitterDisplayName();
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
