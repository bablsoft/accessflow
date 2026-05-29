package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.composition.TextObject;
import com.slack.api.model.block.element.ButtonElement;
import com.slack.api.webhook.Payload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link Payload} objects (Slack incoming-webhook envelope) for each event type per
 * {@code docs/08-notifications.md} §Slack. Payloads are constructed using the typed Block Kit
 * builders from the official Slack Java SDK so the wire shape is validated by the SDK rather
 * than hand-rolled.
 */
@Component
class SlackBlockKitFactory {

    Payload buildEventPayload(NotificationContext ctx, String optionalChannelOverride) {
        return Payload.builder()
                .channel(blankToNull(optionalChannelOverride))
                .text(headerLabel(ctx))
                .blocks(buildBlocks(ctx, false))
                .build();
    }

    /**
     * Block list for the bot-token ({@code chat.postMessage}) delivery path. When
     * {@code withActionButtons} is set (review-request messages with a configured Slack app), an
     * Approve / Reject action block is appended carrying the query request id as the button value.
     */
    List<LayoutBlock> buildBlocks(NotificationContext ctx, boolean withActionButtons) {
        var blocks = new ArrayList<LayoutBlock>();
        blocks.add(headerBlock(headerLabel(ctx)));
        blocks.add(summarySection(ctx));
        if (ctx.fullSqlText() != null && !ctx.fullSqlText().isBlank()) {
            blocks.add(sqlPreviewSection(ctx.sqlPreview300()));
        }
        if (withActionButtons && ctx.queryRequestId() != null) {
            blocks.add(reviewActionsBlock(ctx));
        } else if (ctx.reviewUrl() != null) {
            blocks.add(actionsBlock(ctx.reviewUrl().toString()));
        }
        return blocks;
    }

    String fallbackText(NotificationContext ctx) {
        return headerLabel(ctx);
    }

    Payload buildTestPayload(String optionalChannelOverride) {
        var blocks = List.<LayoutBlock>of(textSection(TEST_TEXT));
        return Payload.builder()
                .channel(blankToNull(optionalChannelOverride))
                .text(TEST_TEXT)
                .blocks(blocks)
                .build();
    }

    String testText() {
        return TEST_TEXT;
    }

    private static final String TEST_TEXT = "AccessFlow notification channel test successful";

    private static HeaderBlock headerBlock(String label) {
        return HeaderBlock.builder()
                .text(PlainTextObject.builder().text(label).build())
                .build();
    }

    private static SectionBlock summarySection(NotificationContext ctx) {
        var fields = new ArrayList<TextObject>();
        fields.add(mrkdwn("*Datasource:*\n" + nullToDash(ctx.datasourceName())));
        fields.add(mrkdwn("*Submitted by:*\n" + nullToDash(ctx.submitterEmail())));
        if (ctx.queryType() != null) {
            fields.add(mrkdwn("*Query Type:*\n" + ctx.queryType()));
        }
        if (ctx.riskLevel() != null) {
            fields.add(mrkdwn("*Risk Level:*\n" + riskBadge(ctx.riskLevel(), ctx.riskScore())));
        }
        if (ctx.reviewerDisplayName() != null) {
            fields.add(mrkdwn("*Reviewer:*\n" + ctx.reviewerDisplayName()));
        }
        if (ctx.eventType() == NotificationEventType.REVIEW_TIMEOUT
                && ctx.approvalTimeoutHours() != null) {
            fields.add(mrkdwn("*Auto-rejected after:*\n" + ctx.approvalTimeoutHours() + " hours"));
        }
        return SectionBlock.builder().fields(fields).build();
    }

    private static SectionBlock sqlPreviewSection(String preview) {
        return SectionBlock.builder()
                .text(mrkdwn("*SQL Preview:*\n```" + preview + "```"))
                .build();
    }

    private static ActionsBlock actionsBlock(String url) {
        var button = ButtonElement.builder()
                .text(PlainTextObject.builder().text("View in AccessFlow").build())
                .url(url)
                .style("primary")
                .build();
        return ActionsBlock.builder().elements(List.of(button)).build();
    }

    private static ActionsBlock reviewActionsBlock(NotificationContext ctx) {
        var queryRequestId = ctx.queryRequestId().toString();
        var elements = new ArrayList<com.slack.api.model.block.element.BlockElement>();
        elements.add(ButtonElement.builder()
                .text(PlainTextObject.builder().text("Approve").build())
                .style("primary")
                .actionId("approve")
                .value(queryRequestId)
                .build());
        elements.add(ButtonElement.builder()
                .text(PlainTextObject.builder().text("Reject").build())
                .style("danger")
                .actionId("reject")
                .value(queryRequestId)
                .build());
        if (ctx.reviewUrl() != null) {
            elements.add(ButtonElement.builder()
                    .text(PlainTextObject.builder().text("View in AccessFlow").build())
                    .url(ctx.reviewUrl().toString())
                    .build());
        }
        return ActionsBlock.builder().elements(elements).build();
    }

    private static SectionBlock textSection(String text) {
        return SectionBlock.builder().text(mrkdwn(text)).build();
    }

    private static MarkdownTextObject mrkdwn(String text) {
        return MarkdownTextObject.builder().text(text).build();
    }

    private static String headerLabel(NotificationContext ctx) {
        return switch (ctx.eventType()) {
            case QUERY_SUBMITTED -> "🔍 New Query Awaiting Review";
            case QUERY_APPROVED -> "✅ Query Approved";
            case QUERY_REJECTED -> "❌ Query Rejected";
            case REVIEW_TIMEOUT -> "⌛ Query Auto-Rejected (review timeout)";
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

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
