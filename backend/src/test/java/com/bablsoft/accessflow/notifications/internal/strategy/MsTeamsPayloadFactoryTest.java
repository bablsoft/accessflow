package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MsTeamsPayloadFactoryTest {

    private final MsTeamsPayloadFactory factory = new MsTeamsPayloadFactory(JsonMapper.builder().build());

    @Test
    void buildEventBodyEmitsAdaptiveCardWithFactsAndOpenUrlAction() {
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, null);
        var body = factory.buildEventBody(ctx);
        assertThat(body).contains("\"type\":\"message\"")
                .contains("\"contentType\":\"application/vnd.microsoft.card.adaptive\"")
                .contains("\"type\":\"AdaptiveCard\"")
                .contains("New Query Awaiting Review")
                .contains("Production")
                .contains("alice@example.com")
                .contains("\"type\":\"FactSet\"")
                .contains("\"type\":\"Action.OpenUrl\"")
                .contains("https://app.example.com/queries/abc");
    }

    @Test
    void buildEventBodyOmitsActionWhenReviewUrlMissing() {
        var ctx = ctxWithoutReviewUrl(NotificationEventType.QUERY_APPROVED);
        var body = factory.buildEventBody(ctx);
        assertThat(body).doesNotContain("Action.OpenUrl")
                .contains("Query Approved");
    }

    @Test
    void reviewTimeoutIncludesAutoRejectedAfter() {
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT, 24);
        var body = factory.buildEventBody(ctx);
        assertThat(body).contains("Auto-rejected after")
                .contains("24 hours")
                .contains("Auto-Rejected");
    }

    @Test
    void buildTestBodyContainsSuccessText() {
        var body = factory.buildTestBody();
        assertThat(body).contains("test successful")
                .contains("AdaptiveCard");
    }

    @Test
    void aiHighRiskHeaderIsUsed() {
        var ctx = ctx(NotificationEventType.AI_HIGH_RISK, null);
        var body = factory.buildEventBody(ctx);
        assertThat(body).contains("AI Flagged High-Risk Query");
    }

    private static NotificationContext ctx(NotificationEventType eventType, Integer approvalTimeoutHours) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.UPDATE,
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                RiskLevel.MEDIUM,
                42,
                "ok",
                UUID.randomUUID(),
                "Production",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                null,
                UUID.randomUUID(),
                "Bob",
                null,
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.parse("2026-05-06T10:15:00Z"),
                "en",
                approvalTimeoutHours);
    }

    private static NotificationContext ctxWithoutReviewUrl(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.UPDATE,
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                "UPDATE orders SET status='shipped'",
                RiskLevel.MEDIUM,
                42,
                "ok",
                UUID.randomUUID(),
                "Production",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                Instant.now(),
                "en",
                null);
    }
}
