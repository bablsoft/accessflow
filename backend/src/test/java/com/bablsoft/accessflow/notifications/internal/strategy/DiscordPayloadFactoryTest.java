package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPayloadFactoryTest {

    private final DiscordPayloadFactory factory = new DiscordPayloadFactory(JsonMapper.builder().build());

    @Test
    void buildEventBodyIncludesHeaderEmbedAndFields() {
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, null);
        var body = factory.buildEventBody(ctx, new DiscordChannelConfig(
                URI.create("https://discord.com/api/webhooks/x"), "AccessFlow",
                "https://accessflow.example/logo.png"));
        assertThat(body).contains("\"username\":\"AccessFlow\"")
                .contains("\"avatar_url\":\"https://accessflow.example/logo.png\"")
                .contains("New Query Awaiting Review")
                .contains("Production")
                .contains("alice@example.com")
                .contains("Risk Level")
                .contains("```sql")
                .contains("https://app.example.com/queries/abc");
    }

    @Test
    void buildEventBodyOmitsIdentityOverridesWhenBlank() {
        var ctx = ctx(NotificationEventType.QUERY_APPROVED, null);
        var body = factory.buildEventBody(ctx,
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).doesNotContain("\"username\"")
                .doesNotContain("\"avatar_url\"")
                .contains("Query Approved");
    }

    @Test
    void buildTestBodyContainsSuccessText() {
        var body = factory.buildTestBody(new DiscordChannelConfig(
                URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("test successful");
    }

    @Test
    void reviewTimeoutIncludesAutoRejectedAfter() {
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT, 24);
        var body = factory.buildEventBody(ctx,
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("Auto-rejected after")
                .contains("24 hours")
                .contains("Auto-Rejected");
    }

    @Test
    void aiHighRiskHeaderIsUsed() {
        var ctx = ctx(NotificationEventType.AI_HIGH_RISK, null);
        var body = factory.buildEventBody(ctx,
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("AI Flagged High-Risk Query");
    }

    @Test
    void weeklyDigestRendersDigestFields() {
        var body = factory.buildEventBody(digestCtx(),
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("Weekly Digest")
                .contains("Queries this week")
                .contains("Pending approvals")
                .contains("Open anomalies")
                .contains("Open suggestions")
                .contains("https://app.example.com/dashboard");
    }

    private static NotificationContext digestCtx() {
        return new NotificationContext(
                NotificationEventType.WEEKLY_DIGEST,
                UUID.randomUUID(),
                null, null, null, null, null, null, null, null, null, null,
                UUID.randomUUID(), "user@example.com", "User",
                null, null, null, null,
                URI.create("https://app.example.com/dashboard"),
                List.of(),
                Instant.parse("2026-06-25T10:15:00Z"),
                "en",
                null,
                null, null, null, null, null, null,
                new com.bablsoft.accessflow.notifications.internal.WeeklyDigestData(
                        java.time.LocalDate.of(2026, 6, 22), java.time.LocalDate.of(2026, 6, 29),
                        5, 2, 1, 3));
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
}
