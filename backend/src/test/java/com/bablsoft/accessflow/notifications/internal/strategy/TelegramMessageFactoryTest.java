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

class TelegramMessageFactoryTest {

    private final TelegramMessageFactory factory = new TelegramMessageFactory(JsonMapper.builder().build());

    @Test
    void buildEventBodySetsChatIdParseModeAndText() {
        var body = factory.buildEventBody(ctx(NotificationEventType.QUERY_SUBMITTED, null), "-100123");
        assertThat(body).contains("\"chat_id\":\"-100123\"")
                .contains("\"parse_mode\":\"MarkdownV2\"")
                .contains("New Query Awaiting Review")
                .contains("Production")
                .contains("Risk Level")
                .contains("View in AccessFlow");
    }

    @Test
    void buildEventBodyEscapesMarkdownReservedCharacters() {
        var body = factory.buildEventBody(ctx(NotificationEventType.QUERY_REJECTED, null), "-100");
        // "alice@example.com" contains a dot which MarkdownV2 reserves; the factory must escape it.
        assertThat(body).contains("alice@example\\\\.com");
    }

    @Test
    void reviewTimeoutIncludesAutoRejectedAfter() {
        var body = factory.buildEventBody(ctx(NotificationEventType.REVIEW_TIMEOUT, 24), "-100");
        // MarkdownV2 reserves '-' so the factory escapes it; in JSON that becomes a literal \\-.
        assertThat(body).contains("Auto\\\\-rejected after").contains("24 hours");
    }

    @Test
    void buildTestBodyContainsSuccessText() {
        var body = factory.buildTestBody("-100");
        assertThat(body).contains("\"chat_id\":\"-100\"")
                .contains("test successful");
    }

    @Test
    void aiHighRiskHeaderUsed() {
        var body = factory.buildEventBody(ctx(NotificationEventType.AI_HIGH_RISK, null), "-100");
        // '-' is escaped to '\-' by MarkdownV2 escaping, JSON-encoded as \\-.
        assertThat(body).contains("AI Flagged High\\\\-Risk Query");
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
