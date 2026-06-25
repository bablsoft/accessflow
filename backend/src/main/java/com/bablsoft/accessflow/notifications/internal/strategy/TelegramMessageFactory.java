package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * Builds the JSON body for Telegram Bot API {@code sendMessage} calls. Uses MarkdownV2 so the
 * SQL preview renders inside a code block; reserved characters in dynamic values are escaped
 * per the
 * <a href="https://core.telegram.org/bots/api#formatting-options">MarkdownV2 spec</a>.
 */
@Component
@RequiredArgsConstructor
class TelegramMessageFactory {

    private static final int TELEGRAM_MESSAGE_LIMIT = 4096;

    private final ObjectMapper objectMapper;

    String buildEventBody(NotificationContext ctx, String chatId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("parse_mode", "MarkdownV2");
        body.put("disable_web_page_preview", true);
        body.put("text", truncate(buildEventText(ctx)));
        return objectMapper.writeValueAsString(body);
    }

    String buildTestBody(String chatId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("parse_mode", "MarkdownV2");
        body.put("text", escape("AccessFlow notification channel test successful"));
        return objectMapper.writeValueAsString(body);
    }

    private static String buildEventText(NotificationContext ctx) {
        var sb = new StringBuilder();
        sb.append("*").append(escape(headerLabel(ctx))).append("*\n\n");
        if (ctx.digest() != null) {
            var d = ctx.digest();
            appendField(sb, "Week", d.weekStart() + " - " + d.weekEnd());
            appendField(sb, "Queries this week", Long.toString(d.totalQueries()));
            appendField(sb, "Pending approvals", Long.toString(d.pendingApprovals()));
            appendField(sb, "Open anomalies", Long.toString(d.openAnomalies()));
            appendField(sb, "Open suggestions", Long.toString(d.openSuggestions()));
            if (ctx.reviewUrl() != null) {
                sb.append("\n[").append(escape("Open your dashboard")).append("](")
                        .append(escapeUrl(ctx.reviewUrl().toString())).append(")");
            }
            return sb.toString();
        }
        appendField(sb, "Datasource", nullToDash(ctx.datasourceName()));
        appendField(sb, "Submitted by", nullToDash(ctx.submitterEmail()));
        if (ctx.queryType() != null) {
            appendField(sb, "Query Type", ctx.queryType().name());
        }
        if (ctx.riskLevel() != null) {
            appendField(sb, "Risk Level", riskBadge(ctx.riskLevel(), ctx.riskScore()));
        }
        if (ctx.reviewerDisplayName() != null) {
            appendField(sb, "Reviewer", ctx.reviewerDisplayName());
        }
        if (ctx.eventType() == NotificationEventType.REVIEW_TIMEOUT
                && ctx.approvalTimeoutHours() != null) {
            appendField(sb, "Auto-rejected after", ctx.approvalTimeoutHours() + " hours");
        }
        if (ctx.fullSqlText() != null && !ctx.fullSqlText().isBlank()) {
            var preview = ctx.sqlPreview300() != null ? ctx.sqlPreview300() : "";
            sb.append("\n*").append(escape("SQL Preview")).append("*\n");
            sb.append("```sql\n").append(escapeForCodeBlock(preview)).append("\n```\n");
        }
        if (ctx.reviewUrl() != null) {
            sb.append("\n[").append(escape("View in AccessFlow")).append("](")
                    .append(escapeUrl(ctx.reviewUrl().toString())).append(")");
        }
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        sb.append("*").append(escape(label)).append(":* ").append(escape(value)).append("\n");
    }

    private static String headerLabel(NotificationContext ctx) {
        return switch (ctx.eventType()) {
            case QUERY_SUBMITTED -> "🔍 New Query Awaiting Review";
            case QUERY_APPROVED -> "✅ Query Approved";
            case QUERY_REJECTED -> "❌ Query Rejected";
            case REVIEW_TIMEOUT -> "⌛ Query Auto-Rejected (review timeout)";
            case AI_HIGH_RISK -> "🚨 AI Flagged High-Risk Query";
            case TEST -> "AccessFlow Test";
            case ANOMALY_DETECTED -> "🚨 Behavioral Anomaly Detected";
            case BREAK_GLASS_EXECUTED -> "🚨 Break-glass Query Executed";
            case WEEKLY_DIGEST -> "📊 Weekly Digest";
            case ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED -> "🔐 Access Request";
        };
    }

    private static String riskBadge(RiskLevel level, Integer score) {
        var emoji = switch (level) {
            case LOW -> "🟢";
            case MEDIUM -> "🟡";
            case HIGH -> "🟠";
            case CRITICAL -> "🔴";
        };
        return score != null
                ? emoji + " " + level + " (score: " + score + ")"
                : emoji + " " + level;
    }

    private static String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    private static String truncate(String text) {
        return text.length() > TELEGRAM_MESSAGE_LIMIT
                ? text.substring(0, TELEGRAM_MESSAGE_LIMIT)
                : text;
    }

    private static String escape(String input) {
        if (input == null) {
            return "";
        }
        var sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ("_*[]()~`>#+-=|{}.!\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapeForCodeBlock(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("`", "\\`");
    }

    private static String escapeUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replace("\\", "\\\\").replace(")", "\\)");
    }
}
