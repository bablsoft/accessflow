package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutySeverity;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyTrigger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PagerDutyPayloadFactoryTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final PagerDutyPayloadFactory factory = new PagerDutyPayloadFactory(objectMapper);

    private final UUID orgId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();

    @Test
    void buildEventBodyEmitsEventsApiV2Envelope() {
        var ctx = sampleContext(NotificationEventType.AI_HIGH_RISK, null);
        var config = new PagerDutyChannelConfig("R0UT1NGKEY", PagerDutySeverity.CRITICAL,
                EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));

        var body = factory.buildEventBody(ctx, config);
        JsonNode tree = objectMapper.readTree(body);

        assertThat(tree.get("routing_key").asString()).isEqualTo("R0UT1NGKEY");
        assertThat(tree.get("event_action").asString()).isEqualTo("trigger");
        assertThat(tree.get("dedup_key").asString())
                .isEqualTo("accessflow-" + orgId + "-" + queryId);
        assertThat(tree.get("client").asString()).isEqualTo("AccessFlow");
        assertThat(tree.get("client_url").asString()).isEqualTo(ctx.reviewUrl().toString());

        var payload = tree.get("payload");
        assertThat(payload.get("summary").asString()).contains("Production");
        assertThat(payload.get("source").asString()).isEqualTo("Production");
        assertThat(payload.get("severity").asString()).isEqualTo("critical");
        assertThat(payload.get("class").asString()).isEqualTo("AI_HIGH_RISK");
        assertThat(payload.get("group").asString()).isEqualTo(orgId.toString());

        var details = payload.get("custom_details");
        assertThat(details.get("query_id").asString()).isEqualTo(queryId.toString());
        assertThat(details.get("risk_level").asString()).isEqualTo("CRITICAL");
        assertThat(details.get("risk_score").asInt()).isEqualTo(95);
        assertThat(details.get("submitter_email").asString()).isEqualTo("alice@example.com");
        assertThat(details.get("review_url").asString()).isEqualTo(ctx.reviewUrl().toString());
        assertThat(details.has("approval_timeout_hours")).isFalse();
    }

    @Test
    void buildEventBodyIncludesApprovalTimeoutForReviewTimeout() {
        var ctx = sampleContext(NotificationEventType.REVIEW_TIMEOUT, 24);
        var config = new PagerDutyChannelConfig("k", PagerDutySeverity.ERROR,
                EnumSet.of(PagerDutyTrigger.REVIEW_TIMEOUT));

        var tree = objectMapper.readTree(factory.buildEventBody(ctx, config));

        assertThat(tree.get("payload").get("severity").asString()).isEqualTo("error");
        assertThat(tree.get("payload").get("custom_details").get("approval_timeout_hours").asInt())
                .isEqualTo(24);
    }

    @Test
    void connectorTokenFailureSummaryMentionsConnector() {
        var ctx = sampleContext(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, null);
        var config = new PagerDutyChannelConfig("k", PagerDutySeverity.ERROR,
                EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));

        var tree = objectMapper.readTree(factory.buildEventBody(ctx, config));

        assertThat(tree.get("payload").get("summary").asString())
                .contains("OAuth2 token fetch repeatedly failing");
        assertThat(tree.get("payload").get("class").asString())
                .isEqualTo("API_CONNECTOR_OAUTH2_TOKEN_FAILED");
    }

    @Test
    void buildTestBodyUsesFixedDedupKeyAndInfoSeverity() {
        var config = new PagerDutyChannelConfig("R0UT1NGKEY", PagerDutySeverity.CRITICAL,
                EnumSet.of(PagerDutyTrigger.CRITICAL_RISK));

        var tree = objectMapper.readTree(factory.buildTestBody(config));

        assertThat(tree.get("routing_key").asString()).isEqualTo("R0UT1NGKEY");
        assertThat(tree.get("event_action").asString()).isEqualTo("trigger");
        assertThat(tree.get("dedup_key").asString()).isEqualTo("accessflow-test");
        assertThat(tree.get("payload").get("severity").asString()).isEqualTo("info");
        assertThat(tree.get("payload").get("summary").asString())
                .isEqualTo("AccessFlow notification channel test");
    }

    private NotificationContext sampleContext(NotificationEventType eventType,
                                              Integer approvalTimeoutHours) {
        return new NotificationContext(
                eventType,
                orgId,
                queryId,
                QueryType.UPDATE,
                "UPDATE x SET y = 1",
                "UPDATE x SET y = 1",
                "UPDATE x SET y = 1",
                RiskLevel.CRITICAL,
                95,
                "Looks risky",
                UUID.randomUUID(),
                "Production",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "need access",
                null,
                null,
                null,
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.parse("2026-05-06T10:15:00Z"),
                "en",
                approvalTimeoutHours);
    }
}
