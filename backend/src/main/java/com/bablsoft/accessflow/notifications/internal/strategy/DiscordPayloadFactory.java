package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Discord webhook payloads (rich embeds) per the
 * <a href="https://discord.com/developers/docs/resources/webhook#execute-webhook">execute-webhook</a>
 * contract. The payload mirrors the Slack Block Kit layout (header, summary fields, SQL preview,
 * review URL) so operators see the same information across channels.
 */
@Component
@RequiredArgsConstructor
class DiscordPayloadFactory {

    private static final int DISCORD_EMBED_DESCRIPTION_LIMIT = 4096;

    private final ObjectMapper objectMapper;

    String buildEventBody(NotificationContext ctx, DiscordChannelConfig config) {
        var envelope = new LinkedHashMap<String, Object>();
        applyIdentityOverrides(envelope, config);
        envelope.put("content", headerLabel(ctx));
        envelope.put("embeds", List.of(buildEventEmbed(ctx)));
        return objectMapper.writeValueAsString(envelope);
    }

    String buildTestBody(DiscordChannelConfig config) {
        var envelope = new LinkedHashMap<String, Object>();
        applyIdentityOverrides(envelope, config);
        envelope.put("content", "AccessFlow notification channel test successful");
        return objectMapper.writeValueAsString(envelope);
    }

    private static void applyIdentityOverrides(Map<String, Object> envelope,
                                               DiscordChannelConfig config) {
        if (config.username() != null && !config.username().isBlank()) {
            envelope.put("username", config.username());
        }
        if (config.avatarUrl() != null && !config.avatarUrl().isBlank()) {
            envelope.put("avatar_url", config.avatarUrl());
        }
    }

    private static Map<String, Object> buildEventEmbed(NotificationContext ctx) {
        var embed = new LinkedHashMap<String, Object>();
        embed.put("title", headerLabel(ctx));
        embed.put("color", embedColor(ctx.riskLevel()));
        if (ctx.fullSqlText() != null && !ctx.fullSqlText().isBlank()) {
            embed.put("description", buildDescription(ctx));
        }
        var fields = new ArrayList<Map<String, Object>>();
        if (ctx.digest() != null) {
            var d = ctx.digest();
            addField(fields, "Week", d.weekStart() + " – " + d.weekEnd());
            addField(fields, "Queries this week", Long.toString(d.totalQueries()));
            addField(fields, "Pending approvals", Long.toString(d.pendingApprovals()));
            addField(fields, "Open anomalies", Long.toString(d.openAnomalies()));
            addField(fields, "Open suggestions", Long.toString(d.openSuggestions()));
            if (ctx.reviewUrl() != null) {
                addField(fields, "Dashboard", ctx.reviewUrl().toString());
                embed.put("url", ctx.reviewUrl().toString());
            }
            embed.put("fields", fields);
            return embed;
        }
        if (ctx.attestationCampaignId() != null) {
            addField(fields, "Campaign", nullToDash(ctx.attestationCampaignName()));
            if (ctx.attestationDueAt() != null) {
                addField(fields, "Due", ctx.attestationDueAt().toString());
            }
            if (ctx.reviewUrl() != null) {
                addField(fields, "Review URL", ctx.reviewUrl().toString());
                embed.put("url", ctx.reviewUrl().toString());
            }
            embed.put("fields", fields);
            return embed;
        }
        addField(fields, "Datasource", nullToDash(ctx.datasourceName()));
        addField(fields, "Submitted by", nullToDash(ctx.submitterEmail()));
        if (ctx.queryType() != null) {
            addField(fields, "Query Type", ctx.queryType().name());
        }
        if (ctx.riskLevel() != null) {
            addField(fields, "Risk Level", riskBadge(ctx.riskLevel(), ctx.riskScore()));
        }
        if (ctx.reviewerDisplayName() != null) {
            addField(fields, "Reviewer", ctx.reviewerDisplayName());
        }
        if (ctx.eventType() == NotificationEventType.REVIEW_TIMEOUT
                && ctx.approvalTimeoutHours() != null) {
            addField(fields, "Auto-rejected after", ctx.approvalTimeoutHours() + " hours");
        }
        if (ctx.reviewUrl() != null) {
            addField(fields, "Review URL", ctx.reviewUrl().toString());
            embed.put("url", ctx.reviewUrl().toString());
        }
        embed.put("fields", fields);
        return embed;
    }

    private static String buildDescription(NotificationContext ctx) {
        var preview = ctx.sqlPreview300() != null ? ctx.sqlPreview300() : "";
        var fenced = "```sql\n" + preview + "\n```";
        if (fenced.length() > DISCORD_EMBED_DESCRIPTION_LIMIT) {
            return fenced.substring(0, DISCORD_EMBED_DESCRIPTION_LIMIT);
        }
        return fenced;
    }

    private static void addField(List<Map<String, Object>> fields, String name, String value) {
        var field = new LinkedHashMap<String, Object>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", true);
        fields.add(field);
    }

    private static String headerLabel(NotificationContext ctx) {
        return switch (ctx.eventType()) {
            case QUERY_SUBMITTED -> "🔍 New Query Awaiting Review";
            case QUERY_APPROVED -> "✅ Query Approved";
            case QUERY_REJECTED -> "❌ Query Rejected";
            case QUERY_ESCALATED -> "⚠️ Query Escalated for Review";
            case REVIEW_TIMEOUT -> "⌛ Query Auto-Rejected (review timeout)";
            case AI_HIGH_RISK -> "🚨 AI Flagged High-Risk Query";
            case TEST -> "AccessFlow Test";
            case ANOMALY_DETECTED -> "🚨 Behavioral Anomaly Detected";
            case BREAK_GLASS_EXECUTED -> "🚨 Break-glass Query Executed";
            case WEEKLY_DIGEST -> "📊 Weekly Digest";
            case ATTESTATION_CAMPAIGN_OPENED -> "📋 Access Recertification Campaign Opened";
            case ERASURE_APPROVED -> "🗑️ Data Erasure Approved";
            case ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED -> "🔐 Access Request";
            case API_REQUEST_SUBMITTED -> "🔌 New API Call Awaiting Review";
            case API_REQUEST_APPROVED -> "✅ API Call Approved";
            case API_REQUEST_EXECUTED -> "🚀 API Call Executed";
            case API_REQUEST_FAILED -> "❌ API Call Failed";
            case API_CONNECTOR_OAUTH2_TOKEN_FAILED -> "🔑 API Connector Token Failure";
        };
    }

    private static int embedColor(RiskLevel level) {
        if (level == null) {
            return 0x2563eb;
        }
        return switch (level) {
            case LOW -> 0x16a34a;
            case MEDIUM -> 0xeab308;
            case HIGH -> 0xea580c;
            case CRITICAL -> 0xdc2626;
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
