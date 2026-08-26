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

    @Test
    void connectorTokenFailureHeaderIsUsed() {
        var ctx = ctx(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, null);
        var body = factory.buildEventBody(ctx);
        assertThat(body).contains("API Connector Token Failure");
    }

    @Test
    void escalatedHeaderIsUsed() {
        var ctx = ctx(NotificationEventType.QUERY_ESCALATED, null);
        var body = factory.buildEventBody(ctx);
        assertThat(body).contains("Query Escalated for Review");
    }

    @Test
    void reviewEscalationAndNudgeHeadersAreDistinctFromTheRoutingPolicyEscalation() {
        // #622 — REVIEW_ESCALATED means nobody decided in time; QUERY_ESCALATED means a routing
        // policy raised the approval bar at submission. Sharing a headline would conflate them.
        var escalated = factory.buildEventBody(ctx(NotificationEventType.REVIEW_ESCALATED, null));
        var nudge = factory.buildEventBody(ctx(NotificationEventType.REVIEW_NUDGE, null));

        assertThat(escalated).contains("Review Escalated");
        assertThat(escalated).doesNotContain("Query Escalated for Review");
        assertThat(nudge).contains("Reminder");
    }

    @Test
    void queryExecutedHeaderReflectsSuccessAndFailure() {
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.EXECUTED)))
                .contains("Recurring Query Results Ready");
        assertThat(factory.buildEventBody(executedCtx(QueryStatus.FAILED)))
                .contains("Recurring Query Run Failed");
    }

    @Test
    void weeklyDigestRendersFactsAndDashboardAction() {
        var body = factory.buildEventBody(digestCtx());
        assertThat(body).contains("Weekly Digest")
                .contains("Queries this week")
                .contains("Pending approvals")
                .contains("Open anomalies")
                .contains("Open suggestions")
                .contains("\"type\":\"Action.OpenUrl\"")
                .contains("https://app.example.com/dashboard");
    }

    @Test
    void deploymentSubmittedHeaderIsUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null));
        assertThat(body).contains("🚀 New Deployment Awaiting Review");
    }

    @Test
    void deploymentApprovedHeaderIsUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, null));
        assertThat(body).contains("✅ Deployment Approved");
    }

    @Test
    void deploymentRejectedHeaderIsUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_REJECTED, null));
        assertThat(body).contains("❌ Deployment Rejected");
    }

    @Test
    void deploymentOutcomeFailedHeaderIsUsed() {
        var body = factory.buildEventBody(deploymentCtx(
                NotificationEventType.DEPLOYMENT_OUTCOME_FAILED, DeploymentOutcome.FAILED));
        assertThat(body).contains("🚨 Deployment Failed or Rolled Back");
    }

    @Test
    void deploymentBreakGlassHeaderIsUsed() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED, null));
        assertThat(body).contains("🚨 Break-glass Deployment Executed");
    }

    @Test
    void deploymentSubmittedCardCarriesPipelineFacts() {
        var body = factory.buildEventBody(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null));
        assertThat(body).contains("Pipeline")
                .contains("payments-service")
                .contains("Environment")
                .contains("production")
                .contains("Version")
                .contains("v2.4.1")
                .contains("Submitted by")
                .contains("alice@example.com")
                .doesNotContain("Datasource");
    }

    @Test
    void deploymentOutcomeFailedCardCarriesOutcome() {
        var body = factory.buildEventBody(deploymentCtx(
                NotificationEventType.DEPLOYMENT_OUTCOME_FAILED, DeploymentOutcome.ROLLED_BACK));
        assertThat(body).contains("Outcome")
                .contains("ROLLED_BACK");
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

    /**
     * A DEPLOYMENT_* context (#695) — the pipeline name rides in {@code datasourceName}, the
     * deployment fields trail the record; fullSqlText/queryType/reviewUrl are null.
     */
    private static NotificationContext deploymentCtx(NotificationEventType eventType,
                                                     DeploymentOutcome outcome) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                null,
                null,
                null, null, null,
                RiskLevel.MEDIUM,
                42,
                "ok",
                UUID.randomUUID(),
                "payments-service",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "Hotfix for the checkout outage",
                null, null, null,
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
                "v2.4.1",
                outcome,
                null);
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
