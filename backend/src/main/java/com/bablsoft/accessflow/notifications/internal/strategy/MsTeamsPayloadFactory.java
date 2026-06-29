package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON body for Microsoft Teams Incoming Webhook / Power Automate Workflow webhook
 * deliveries. Emits an Adaptive Card (schema 1.5) wrapped in the {@code attachments} envelope
 * the Teams webhook accepts. Adaptive Cards work for both legacy Office 365 connector webhooks
 * and the newer Power Automate "Post to a channel when a webhook request is received" flow.
 */
@Component
@RequiredArgsConstructor
class MsTeamsPayloadFactory {

    private static final String ADAPTIVE_CARD_SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json";
    private static final String ADAPTIVE_CARD_VERSION = "1.5";

    private final ObjectMapper objectMapper;

    String buildEventBody(NotificationContext ctx) {
        return objectMapper.writeValueAsString(envelope(buildEventCard(ctx)));
    }

    String buildTestBody() {
        return objectMapper.writeValueAsString(envelope(buildTestCard()));
    }

    private static Map<String, Object> envelope(Map<String, Object> card) {
        var attachment = new LinkedHashMap<String, Object>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content", card);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("type", "message");
        envelope.put("attachments", List.of(attachment));
        return envelope;
    }

    private static Map<String, Object> buildEventCard(NotificationContext ctx) {
        var card = baseCard();
        var body = new ArrayList<Map<String, Object>>();
        body.add(textBlock(headerLabel(ctx), "ExtraLarge", "Bolder", colorForRisk(ctx.riskLevel())));

        var facts = new ArrayList<Map<String, String>>();
        if (ctx.digest() != null) {
            var d = ctx.digest();
            facts.add(fact("Week", d.weekStart() + " – " + d.weekEnd()));
            facts.add(fact("Queries this week", Long.toString(d.totalQueries())));
            facts.add(fact("Pending approvals", Long.toString(d.pendingApprovals())));
            facts.add(fact("Open anomalies", Long.toString(d.openAnomalies())));
            facts.add(fact("Open suggestions", Long.toString(d.openSuggestions())));
            body.add(factSet(facts));
            card.put("body", body);
            if (ctx.reviewUrl() != null) {
                var action = new LinkedHashMap<String, Object>();
                action.put("type", "Action.OpenUrl");
                action.put("title", "Open your dashboard");
                action.put("url", ctx.reviewUrl().toString());
                card.put("actions", List.of(action));
            }
            return card;
        }
        if (ctx.attestationCampaignId() != null) {
            facts.add(fact("Campaign", nullToDash(ctx.attestationCampaignName())));
            if (ctx.attestationDueAt() != null) {
                facts.add(fact("Due", ctx.attestationDueAt().toString()));
            }
            body.add(factSet(facts));
            card.put("body", body);
            if (ctx.reviewUrl() != null) {
                var action = new LinkedHashMap<String, Object>();
                action.put("type", "Action.OpenUrl");
                action.put("title", "Open recertification queue");
                action.put("url", ctx.reviewUrl().toString());
                card.put("actions", List.of(action));
            }
            return card;
        }
        facts.add(fact("Datasource", nullToDash(ctx.datasourceName())));
        facts.add(fact("Submitted by", nullToDash(ctx.submitterEmail())));
        if (ctx.queryType() != null) {
            facts.add(fact("Query Type", ctx.queryType().name()));
        }
        if (ctx.riskLevel() != null) {
            facts.add(fact("Risk Level", riskBadge(ctx.riskLevel(), ctx.riskScore())));
        }
        if (ctx.reviewerDisplayName() != null) {
            facts.add(fact("Reviewer", ctx.reviewerDisplayName()));
        }
        if (ctx.eventType() == NotificationEventType.REVIEW_TIMEOUT
                && ctx.approvalTimeoutHours() != null) {
            facts.add(fact("Auto-rejected after", ctx.approvalTimeoutHours() + " hours"));
        }
        body.add(factSet(facts));

        if (ctx.fullSqlText() != null && !ctx.fullSqlText().isBlank()) {
            body.add(textBlock("SQL Preview", "Default", "Bolder", null));
            body.add(codeBlock(ctx.sqlPreview300() != null ? ctx.sqlPreview300() : ""));
        }
        card.put("body", body);

        if (ctx.reviewUrl() != null) {
            var action = new LinkedHashMap<String, Object>();
            action.put("type", "Action.OpenUrl");
            action.put("title", "View in AccessFlow");
            action.put("url", ctx.reviewUrl().toString());
            card.put("actions", List.of(action));
        }
        return card;
    }

    private static Map<String, Object> buildTestCard() {
        var card = baseCard();
        card.put("body", List.of(
                textBlock("AccessFlow notification channel test successful", "Default", "Bolder", null)));
        return card;
    }

    private static Map<String, Object> baseCard() {
        var card = new LinkedHashMap<String, Object>();
        card.put("type", "AdaptiveCard");
        card.put("$schema", ADAPTIVE_CARD_SCHEMA);
        card.put("version", ADAPTIVE_CARD_VERSION);
        return card;
    }

    private static Map<String, Object> textBlock(String text, String size, String weight, String color) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "TextBlock");
        block.put("text", text);
        block.put("wrap", true);
        if (size != null) {
            block.put("size", size);
        }
        if (weight != null) {
            block.put("weight", weight);
        }
        if (color != null) {
            block.put("color", color);
        }
        return block;
    }

    private static Map<String, Object> codeBlock(String code) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "TextBlock");
        block.put("text", code);
        block.put("wrap", true);
        block.put("fontType", "Monospace");
        block.put("isSubtle", true);
        return block;
    }

    private static Map<String, Object> factSet(List<Map<String, String>> facts) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "FactSet");
        block.put("facts", facts);
        return block;
    }

    private static Map<String, String> fact(String title, String value) {
        var fact = new LinkedHashMap<String, String>();
        fact.put("title", title);
        fact.put("value", value);
        return fact;
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
            case ATTESTATION_CAMPAIGN_OPENED -> "📋 Access Recertification Campaign Opened";
            case ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED -> "🔐 Access Request";
            case API_REQUEST_SUBMITTED -> "🔌 New API Call Awaiting Review";
            case API_REQUEST_APPROVED -> "✅ API Call Approved";
            case API_REQUEST_EXECUTED -> "🚀 API Call Executed";
            case API_REQUEST_FAILED -> "❌ API Call Failed";
            case API_CONNECTOR_OAUTH2_TOKEN_FAILED -> "🔑 API Connector Token Failure";
        };
    }

    private static String colorForRisk(RiskLevel level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case LOW -> "Good";
            case MEDIUM -> "Warning";
            case HIGH -> "Warning";
            case CRITICAL -> "Attention";
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
