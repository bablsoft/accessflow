package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackBlockKitFactoryTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final SlackBlockKitFactory factory = new SlackBlockKitFactory(objectMapper);

    @Test
    void buildEventBodyContainsHeaderAndSummaryBlocks() {
        var ctx = ctxWith(NotificationEventType.QUERY_SUBMITTED);

        JsonNode tree = objectMapper.readTree(factory.buildEventBody(ctx, "#review"));

        assertThat(tree.get("channel").asString()).isEqualTo("#review");
        var blocks = tree.get("blocks");
        assertThat(blocks.isArray()).isTrue();
        assertThat(blocks.get(0).get("type").asString()).isEqualTo("header");
        assertThat(blocks.get(0).get("text").get("text").asString())
                .contains("New Query Awaiting Review");
        assertThat(blocks.get(1).get("type").asString()).isEqualTo("section");
        var fields = blocks.get(1).get("fields");
        assertThat(fields.isArray()).isTrue();
        assertThat(fields.get(0).get("text").asString()).contains("Production");
    }

    @Test
    void buildTestBodyContainsConfirmationText() {
        JsonNode tree = objectMapper.readTree(factory.buildTestBody(null));
        assertThat(tree.get("blocks").get(0).get("text").get("text").asString())
                .contains("AccessFlow notification channel test successful");
    }

    @Test
    void approvedHeaderUsesCheckmark() {
        var ctx = ctxWith(NotificationEventType.QUERY_APPROVED);
        JsonNode tree = objectMapper.readTree(factory.buildEventBody(ctx, null));
        assertThat(tree.get("blocks").get(0).get("text").get("text").asString())
                .contains("Query Approved");
    }

    private static NotificationContext ctxWith(NotificationEventType eventType) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.UPDATE,
                "UPDATE orders SET status = 'shipped'",
                "UPDATE orders SET status = 'shipped'",
                "UPDATE orders SET status = 'shipped'",
                RiskLevel.MEDIUM,
                42,
                "Looks fine",
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
                Instant.parse("2026-05-06T10:15:00Z"));
    }
}
