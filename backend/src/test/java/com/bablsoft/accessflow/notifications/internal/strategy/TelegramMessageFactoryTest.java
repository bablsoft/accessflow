package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
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

    @Test
    void connectorTokenFailureHeaderUsed() {
        var body = factory.buildEventBody(
                ctx(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, null), "-100");
        assertThat(body).contains("API Connector Token Failure");
    }

    @Test
    void escalatedHeaderUsed() {
        var body = factory.buildEventBody(ctx(NotificationEventType.QUERY_ESCALATED, null), "-100");
        assertThat(body).contains("Query Escalated for Review");
    }

    @Test
    void reviewEscalationAndNudgeHeadersAreDistinctFromTheRoutingPolicyEscalation() {
        // #622 — REVIEW_ESCALATED means nobody decided in time; QUERY_ESCALATED means a routing
        // policy raised the approval bar at submission. Sharing a headline would conflate them.
        var escalated = factory.buildEventBody(
                ctx(NotificationEventType.REVIEW_ESCALATED, null), "-100");
        var nudge = factory.buildEventBody(ctx(NotificationEventType.REVIEW_NUDGE, null), "-100");

        assertThat(escalated).contains("Review Escalated");
        assertThat(escalated).doesNotContain("Query Escalated for Review");
        assertThat(nudge).contains("Reminder");
    }

    @Test
    void queryExecutedHeaderReflectsSuccessAndFailure() {
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.EXECUTED), "-100"))
                .contains("Recurring Query Results Ready");
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.FAILED), "-100"))
                .contains("Recurring Query Run Failed");
    }

    @Test
    void weeklyDigestRendersMetricsAndDashboardLink() {
        var body = factory.buildEventBody(digestCtx(), "-100");
        assertThat(body).contains("Weekly Digest")
                .contains("Queries this week")
                .contains("Pending approvals")
                .contains("Open anomalies")
                .contains("Open suggestions")
                .contains("Open your dashboard");
    }

    @Test
    void deploymentSubmittedHeaderUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null), "-100");
        assertThat(body).contains("New Deployment Awaiting Review");
    }

    @Test
    void deploymentApprovedHeaderUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, null), "-100");
        assertThat(body).contains("Deployment Approved");
    }

    @Test
    void deploymentRejectedHeaderUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_REJECTED, null), "-100");
        assertThat(body).contains("Deployment Rejected");
    }

    @Test
    void deploymentOutcomeFailedHeaderUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                        DeploymentOutcome.FAILED), "-100");
        assertThat(body).contains("Deployment Failed or Rolled Back");
    }

    @Test
    void deploymentBreakGlassHeaderUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED, null), "-100");
        // '-' is escaped to '\-' by MarkdownV2 escaping, JSON-encoded as \\-.
        assertThat(body).contains("Break\\\\-glass Deployment Executed");
    }

    @Test
    void deploymentSubmittedRendersPipelineEnvironmentVersionAndSubmitter() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null), "-100");
        // The pipeline name rides in datasourceName but must be labeled "Pipeline".
        assertThat(body).contains("Pipeline")
                .doesNotContain("Datasource")
                .contains("payments deploy")
                .contains("production")
                // "2.4.0" — MarkdownV2 escapes '.', JSON-encoded as \\.
                .contains("2\\\\.4\\\\.0")
                .contains("alice@example\\\\.com");
    }

    @Test
    void deploymentOutcomeFailedCarriesOutcomeName() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                        DeploymentOutcome.ROLLED_BACK), "-100");
        // '_' is escaped to '\_' by MarkdownV2 escaping, JSON-encoded as \\_.
        assertThat(body).contains("Outcome").contains("ROLLED\\\\_BACK");
    }

    /** A DEPLOYMENT_* context (#695) — pipeline in datasourceName, no SQL, no review URL. */
    private static NotificationContext deploymentCtx(
            NotificationEventType eventType, DeploymentOutcome outcome) {
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
                null,
                UUID.randomUUID(),
                "payments deploy",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "release rollout",
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
                "2.4.0",
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
