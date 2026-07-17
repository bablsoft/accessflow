package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.internal.NotificationContext;

/**
 * Shared plain-text summary / description rendering for the ticketing channels (AF-453). Both
 * ServiceNow ({@code short_description} / {@code description}) and Jira ({@code summary} /
 * {@code description}, REST v2 plain text) consume the same shape.
 */
final class TicketDescriptionBuilder {

    private static final int SUMMARY_MAX_LENGTH = 255;

    private TicketDescriptionBuilder() {
    }

    static String summary(NotificationContext ctx) {
        var datasource = (ctx.datasourceName() == null || ctx.datasourceName().isBlank())
                ? "a datasource"
                : ctx.datasourceName();
        var headline = switch (ctx.eventType()) {
            case QUERY_REJECTED -> "Query rejected on " + datasource;
            case QUERY_ESCALATED -> "Query escalated for review on " + datasource;
            case REVIEW_TIMEOUT -> "Query review timed out on " + datasource;
            default -> ctx.eventType().name() + " on " + datasource;
        };
        var summary = "[AccessFlow] " + headline;
        return summary.length() > SUMMARY_MAX_LENGTH
                ? summary.substring(0, SUMMARY_MAX_LENGTH)
                : summary;
    }

    static String description(NotificationContext ctx) {
        var sb = new StringBuilder();
        sb.append("Event: ").append(ctx.eventType().name()).append('\n');
        if (ctx.queryRequestId() != null) {
            sb.append("Query request: ").append(ctx.queryRequestId()).append('\n');
        }
        if (ctx.datasourceName() != null) {
            sb.append("Datasource: ").append(ctx.datasourceName()).append('\n');
        }
        if (ctx.submitterDisplayName() != null || ctx.submitterEmail() != null) {
            sb.append("Submitted by: ")
                    .append(ctx.submitterDisplayName() != null
                            ? ctx.submitterDisplayName() : ctx.submitterEmail());
            if (ctx.submitterDisplayName() != null && ctx.submitterEmail() != null) {
                sb.append(" <").append(ctx.submitterEmail()).append('>');
            }
            sb.append('\n');
        }
        if (ctx.riskLevel() != null) {
            sb.append("AI risk: ").append(ctx.riskLevel().name());
            if (ctx.riskScore() != null) {
                sb.append(" (score: ").append(ctx.riskScore()).append(')');
            }
            sb.append('\n');
        }
        if (ctx.justification() != null && !ctx.justification().isBlank()) {
            sb.append("Justification: ").append(ctx.justification()).append('\n');
        }
        if (ctx.reviewerComment() != null && !ctx.reviewerComment().isBlank()) {
            sb.append("Reviewer comment: ").append(ctx.reviewerComment()).append('\n');
        }
        if (ctx.sqlPreview300() != null) {
            sb.append('\n').append("Query preview:\n").append(ctx.sqlPreview300()).append('\n');
        }
        if (ctx.reviewUrl() != null) {
            sb.append('\n').append("Review in AccessFlow: ").append(ctx.reviewUrl());
        }
        return sb.toString();
    }
}
