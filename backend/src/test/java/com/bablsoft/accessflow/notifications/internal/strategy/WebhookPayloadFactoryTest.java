package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadFactoryTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final WebhookPayloadFactory factory = new WebhookPayloadFactory(objectMapper);

    @Test
    void buildBodyEmitsSnakeCaseEnvelope() {
        var ctx = sampleContext();

        var body = factory.buildBody(ctx);
        JsonNode tree = objectMapper.readTree(body);

        assertThat(tree.get("event").asString()).isEqualTo("QUERY_SUBMITTED");
        assertThat(tree.get("organization_id").asString()).isEqualTo(ctx.organizationId().toString());
        assertThat(tree.get("query_request").get("id").asString())
                .isEqualTo(ctx.queryRequestId().toString());
        assertThat(tree.get("query_request").get("risk_level").asString()).isEqualTo("MEDIUM");
        assertThat(tree.get("query_request").get("risk_score").asInt()).isEqualTo(42);
        assertThat(tree.get("query_request").get("review_url").asString())
                .isEqualTo(ctx.reviewUrl().toString());
        assertThat(tree.get("timestamp").asString()).isNotBlank();
    }

    @Test
    void buildTestBodyOnlyIncludesEventAndTimestamp() {
        var body = factory.buildTestBody();
        JsonNode tree = objectMapper.readTree(body);

        assertThat(tree.get("event").asString()).isEqualTo("TEST");
        assertThat(tree.get("timestamp").asString()).isNotBlank();
        assertThat(tree.has("query_request")).isFalse();
    }

    private static NotificationContext sampleContext() {
        return new NotificationContext(
                NotificationEventType.QUERY_SUBMITTED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.UPDATE,
                "UPDATE x SET y = 1",
                "UPDATE x SET y = 1",
                "UPDATE x SET y = 1",
                RiskLevel.MEDIUM,
                42,
                "Looks fine",
                UUID.randomUUID(),
                "Production",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                null,
                null,
                null,
                null,
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.parse("2026-05-06T10:15:00Z"));
    }
}
