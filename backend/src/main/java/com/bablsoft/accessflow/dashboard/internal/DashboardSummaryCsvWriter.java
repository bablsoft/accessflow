package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Renders a {@link DashboardWeeklySummary} as an RFC 4180 CSV (AF-498). Sectioned: report metadata,
 * headline metrics, status breakdown, risk breakdown. Owns its own escaping (the audit module's CSV
 * helper can't cross the Modulith boundary — same constraint as {@code ComplianceCsvWriter}).
 */
@Component
class DashboardSummaryCsvWriter {

    private static final String LINE = "\r\n";

    byte[] write(DashboardWeeklySummary summary) {
        var sb = new StringBuilder();
        sb.append("section,key,value").append(LINE);
        row(sb, "report", "type", "weekly_dashboard_summary");
        row(sb, "report", "user_email", nullToBlank(summary.userEmail()));
        row(sb, "report", "user_display_name", nullToBlank(summary.userDisplayName()));
        row(sb, "report", "week_start", String.valueOf(summary.weekStart()));
        row(sb, "report", "week_end", String.valueOf(summary.weekEnd()));
        row(sb, "report", "generated_at", String.valueOf(summary.generatedAt()));
        row(sb, "metrics", "total_queries", String.valueOf(summary.totalQueries()));
        row(sb, "metrics", "pending_approvals", String.valueOf(summary.pendingApprovals()));
        row(sb, "metrics", "open_anomalies", String.valueOf(summary.openAnomalies()));
        row(sb, "metrics", "open_suggestions", String.valueOf(summary.openSuggestions()));
        summary.statusBreakdown().forEach(
                c -> row(sb, "status_breakdown", c.status().name(), String.valueOf(c.count())));
        summary.riskBreakdown().forEach(
                c -> row(sb, "risk_breakdown", c.riskLevel().name(), String.valueOf(c.count())));
        return sb.toString().getBytes(UTF_8);
    }

    private static void row(StringBuilder sb, String section, String key, String value) {
        sb.append(escape(section)).append(',')
                .append(escape(key)).append(',')
                .append(escape(value)).append(LINE);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
