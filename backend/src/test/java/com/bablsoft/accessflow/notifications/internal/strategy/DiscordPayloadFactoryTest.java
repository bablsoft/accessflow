package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void connectorTokenFailureHasTitle() {
        var body = factory.buildEventBody(
                ctx(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, null),
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("API Connector Token Failure");
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
    void escalatedHeaderIsUsed() {
        var ctx = ctx(NotificationEventType.QUERY_ESCALATED, null);
        var body = factory.buildEventBody(ctx,
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("Query Escalated for Review");
    }

    @Test
    void reviewEscalationAndNudgeHeadersAreDistinctFromTheRoutingPolicyEscalation() {
        // #622 — REVIEW_ESCALATED means nobody decided in time; QUERY_ESCALATED means a routing
        // policy raised the approval bar at submission. Sharing a headline would conflate them.
        var config = new DiscordChannelConfig(
                URI.create("https://discord.com/api/webhooks/x"), null, null);

        var escalated = factory.buildEventBody(
                ctx(NotificationEventType.REVIEW_ESCALATED, null), config);
        var nudge = factory.buildEventBody(ctx(NotificationEventType.REVIEW_NUDGE, null), config);

        assertThat(escalated).contains("Review Escalated");
        assertThat(escalated).doesNotContain("Query Escalated for Review");
        assertThat(nudge).contains("Reminder");
    }

    @Test
    void queryExecutedHeaderReflectsSuccessAndFailure() {
        var config = new DiscordChannelConfig(
                URI.create("https://discord.com/api/webhooks/x"), null, null);
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.EXECUTED), config))
                .contains("Recurring Query Results Ready");
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.FAILED), config))
                .contains("Recurring Query Run Failed");
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

    // #695: deployment governance headers.
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            DEPLOYMENT_SUBMITTED            | 🚀 New Deployment Awaiting Review
            DEPLOYMENT_APPROVED             | ✅ Deployment Approved
            DEPLOYMENT_REJECTED             | ❌ Deployment Rejected
            DEPLOYMENT_OUTCOME_FAILED       | 🚨 Deployment Failed or Rolled Back
            DEPLOYMENT_BREAK_GLASS_EXECUTED | 🚨 Break-glass Deployment Executed
            """)
    void deploymentHeadersAreUsed(NotificationEventType eventType, String expectedHeader) {
        var body = factory.buildEventBody(deploymentCtx(eventType, null),
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains(expectedHeader);
    }

    @Test
    void deploymentSubmittedEmbedCarriesPipelineEnvironmentVersionAndSubmitter() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null),
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("Pipeline")
                .contains("payments-service")
                .contains("Environment")
                .contains("production")
                .contains("Version")
                .contains("v2.14.0")
                .contains("Submitted by")
                .contains("alice@example.com")
                .doesNotContain("Datasource");
    }

    @Test
    void deploymentOutcomeFailedEmbedCarriesOutcomeName() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                        DeploymentOutcome.ROLLED_BACK),
                new DiscordChannelConfig(URI.create("https://discord.com/api/webhooks/x"), null, null));
        assertThat(body).contains("Outcome")
                .contains("ROLLED_BACK");
    }

    /**
     * A DEPLOYMENT_* context (#695): pipeline name rides in datasourceName, submitter in
     * submitterEmail/DisplayName; fullSqlText/queryType/reviewUrl are null.
     */
    private static NotificationContext deploymentCtx(NotificationEventType eventType,
                                                     DeploymentOutcome outcome) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                RiskLevel.MEDIUM,
                42,
                "ok",
                UUID.randomUUID(),
                "payments-service",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "Ship the hotfix",
                null,
                null,
                null,
                null,
                List.of(),
                Instant.parse("2026-08-25T10:15:00Z"),
                "en",
                null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null,
                null, null, null,
                UUID.randomUUID(),
                "production",
                "v2.14.0",
                outcome,
                null);
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

    /** A QUERY_EXECUTED context (#627) carrying the execution outcome in the trailing fields. */
    private static NotificationContext executedCtx(QueryStatus executionStatus) {
        return new NotificationContext(
                NotificationEventType.QUERY_EXECUTED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                QueryType.SELECT,
                "SELECT 1",
                "SELECT 1",
                "SELECT 1",
                RiskLevel.LOW,
                10,
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
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.parse("2026-05-06T10:15:00Z"),
                "en",
                null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                executionStatus, 5L, 120L);
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
