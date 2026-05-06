package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Slack Block Kit payloads for each {@link com.partqam.accessflow.notifications.api.NotificationEventType}
 * per {@code docs/08-notifications.md} §Slack.
 */
@Component
@RequiredArgsConstructor
class SlackBlockKitFactory {

    private final ObjectMapper objectMapper;

    String buildEventBody(NotificationContext ctx, String optionalChannelOverride) {
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(headerBlock(headerLabel(ctx)));
        blocks.add(summarySection(ctx));
        if (ctx.fullSqlText() != null && !ctx.fullSqlText().isBlank()) {
            blocks.add(sqlPreviewSection(ctx.sqlPreview300()));
        }
        if (ctx.reviewUrl() != null) {
            blocks.add(actionsBlock(ctx.reviewUrl().toString()));
        }
        return body(blocks, optionalChannelOverride);
    }

    String buildTestBody(String optionalChannelOverride) {
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(textSection("AccessFlow notification channel test successful"));
        return body(blocks, optionalChannelOverride);
    }

    private Map<String, Object> headerBlock(String label) {
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "plain_text");
        text.put("text", label);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "header");
        block.put("text", text);
        return block;
    }

    private Map<String, Object> summarySection(NotificationContext ctx) {
        var fields = new ArrayList<Map<String, Object>>();
        fields.add(fieldMrkdwn("*Datasource:*\n" + nullToDash(ctx.datasourceName())));
        fields.add(fieldMrkdwn("*Submitted by:*\n" + nullToDash(ctx.submitterEmail())));
        if (ctx.queryType() != null) {
            fields.add(fieldMrkdwn("*Query Type:*\n" + ctx.queryType()));
        }
        if (ctx.riskLevel() != null) {
            fields.add(fieldMrkdwn("*Risk Level:*\n" + riskBadge(ctx.riskLevel(), ctx.riskScore())));
        }
        if (ctx.reviewerDisplayName() != null) {
            fields.add(fieldMrkdwn("*Reviewer:*\n" + ctx.reviewerDisplayName()));
        }
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "section");
        block.put("fields", fields);
        return block;
    }

    private Map<String, Object> sqlPreviewSection(String preview) {
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "mrkdwn");
        text.put("text", "*SQL Preview:*\n```" + preview + "```");
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "section");
        block.put("text", text);
        return block;
    }

    private Map<String, Object> actionsBlock(String url) {
        var btn = new LinkedHashMap<String, Object>();
        btn.put("type", "button");
        var btnText = new LinkedHashMap<String, Object>();
        btnText.put("type", "plain_text");
        btnText.put("text", "View in AccessFlow");
        btn.put("text", btnText);
        btn.put("url", url);
        btn.put("style", "primary");
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "actions");
        block.put("elements", List.of(btn));
        return block;
    }

    private Map<String, Object> textSection(String text) {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("type", "mrkdwn");
        inner.put("text", text);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "section");
        block.put("text", inner);
        return block;
    }

    private static Map<String, Object> fieldMrkdwn(String text) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", "mrkdwn");
        m.put("text", text);
        return m;
    }

    private String body(List<Map<String, Object>> blocks, String optionalChannelOverride) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("blocks", blocks);
        if (optionalChannelOverride != null && !optionalChannelOverride.isBlank()) {
            envelope.put("channel", optionalChannelOverride);
        }
        return objectMapper.writeValueAsString(envelope);
    }

    private static String headerLabel(NotificationContext ctx) {
        return switch (ctx.eventType()) {
            case QUERY_SUBMITTED -> "🔍 New Query Awaiting Review";
            case QUERY_APPROVED -> "✅ Query Approved";
            case QUERY_REJECTED -> "❌ Query Rejected";
            case AI_HIGH_RISK -> "🚨 AI Flagged High-Risk Query";
            case TEST -> "AccessFlow Test";
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
}
