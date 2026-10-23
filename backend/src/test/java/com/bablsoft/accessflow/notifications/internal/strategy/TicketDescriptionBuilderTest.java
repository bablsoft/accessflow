package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDescriptionBuilderTest {

    private final UUID queryId = UUID.randomUUID();

    @Test
    void summaryPerEventType() {
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.QUERY_REJECTED)))
                .isEqualTo("[AccessFlow] Query rejected on Production");
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.QUERY_ESCALATED)))
                .isEqualTo("[AccessFlow] Query escalated for review on Production");
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.REVIEW_TIMEOUT)))
                .isEqualTo("[AccessFlow] Query review timed out on Production");
        // #622. This switch carries a default, so a missing arm would compile and silently produce
        // "[AccessFlow] REVIEW_ESCALATED on Production" in a real ServiceNow/Jira ticket.
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.REVIEW_ESCALATED)))
                .isEqualTo("[AccessFlow] Review escalated — no decision yet on Production");
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.REVIEW_NUDGE)))
                .isEqualTo("[AccessFlow] Reminder: request awaiting review on Production");
    }

    @Test
    void summaryFallsBackToEventNameForOtherEvents() {
        assertThat(TicketDescriptionBuilder.summary(ctx(NotificationEventType.QUERY_SUBMITTED)))
                .isEqualTo("[AccessFlow] QUERY_SUBMITTED on Production");
    }

    @Test
    void summaryRendersBothDeploymentHeadlines() {
        assertThat(TicketDescriptionBuilder.summary(ServiceNowNotificationStrategyTest
                .deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, true)))
                .isEqualTo("[AccessFlow] Deployment 2.4.1 to production awaiting approval "
                        + "(payments)");
        assertThat(TicketDescriptionBuilder.summary(ServiceNowNotificationStrategyTest
                .deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, true)))
                .isEqualTo("[AccessFlow] Deployment 2.4.1 to production approved (payments)");
    }

    @Test
    void deploymentHeadlineSurvivesMissingNames() {
        var ctx = new NotificationContext(NotificationEventType.DEPLOYMENT_APPROVED,
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), Instant.now(), "en",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                UUID.randomUUID(), null, " ", null, null);
        assertThat(TicketDescriptionBuilder.summary(ctx))
                .isEqualTo("[AccessFlow] Deployment unknown to unknown approved (unknown)");
    }

    @Test
    void defaultSummaryDropsTheDatasourcePlaceholderWhenThereIsNone() {
        assertThat(TicketDescriptionBuilder.summary(
                ctx(NotificationEventType.QUERY_SUBMITTED, null)))
                .isEqualTo("[AccessFlow] QUERY_SUBMITTED");
    }

    @Test
    void deploymentDescriptionRendersEveryChangeTicketLine() {
        var ctx = ServiceNowNotificationStrategyTest.deploymentCtx(
                NotificationEventType.DEPLOYMENT_SUBMITTED,
                new NotificationContext.DeploymentDetails("abc123", "ghcr.io/acme/payments:2.4.1",
                        "https://ci.example/runs/42", true, "Quarter-end freeze"));

        assertThat(TicketDescriptionBuilder.description(ctx)).isEqualTo(
                "Event: DEPLOYMENT_SUBMITTED\n"
                        + "Deployment request: " + ctx.deploymentRequestId() + "\n"
                        + "Pipeline: payments\n"
                        + "Environment: production\n"
                        + "Version: 2.4.1\n"
                        + "Commit: abc123\n"
                        + "Artifact: ghcr.io/acme/payments:2.4.1\n"
                        + "CI run: https://ci.example/runs/42\n"
                        + "Submitted by: Alice <alice@example.com>\n"
                        + "AI risk: MEDIUM (score: 45)\n"
                        + "Freeze: Quarter-end freeze\n"
                        + "Justification: release train 42\n"
                        + "\nReview in AccessFlow: https://app.example.test/deployments/"
                        + ctx.deploymentRequestId());
    }

    @Test
    void deploymentDescriptionSaysNoneWithoutAFreeze() {
        var ctx = ServiceNowNotificationStrategyTest.deploymentCtx(
                NotificationEventType.DEPLOYMENT_SUBMITTED, true);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .contains("Freeze: none\n")
                .doesNotContain("Query request:")
                .doesNotContain("Datasource:");
    }

    @Test
    void deploymentDescriptionWithoutDetailsOmitsProvenance() {
        var ctx = ServiceNowNotificationStrategyTest.deploymentCtx(
                NotificationEventType.DEPLOYMENT_SUBMITTED,
                (NotificationContext.DeploymentDetails) null);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .contains("Freeze: none\n")
                .doesNotContain("Commit:")
                .doesNotContain("CI run:");
    }

    @Test
    void summaryUsesPlaceholderWhenDatasourceNameMissing() {
        assertThat(TicketDescriptionBuilder.summary(
                ctx(NotificationEventType.QUERY_REJECTED, null)))
                .isEqualTo("[AccessFlow] Query rejected on a datasource");
        assertThat(TicketDescriptionBuilder.summary(
                ctx(NotificationEventType.QUERY_REJECTED, "  ")))
                .isEqualTo("[AccessFlow] Query rejected on a datasource");
    }

    @Test
    void summaryTruncatesTo255Characters() {
        var longName = "X".repeat(300);
        var summary = TicketDescriptionBuilder.summary(
                ctx(NotificationEventType.QUERY_REJECTED, longName));
        assertThat(summary).hasSize(255)
                .startsWith("[AccessFlow] Query rejected on X");
    }

    @Test
    void descriptionIncludesAllLinesWhenFullyPopulated() {
        var description = TicketDescriptionBuilder.description(ctx(
                NotificationEventType.QUERY_ESCALATED));

        assertThat(description)
                .contains("Event: QUERY_ESCALATED\n")
                .contains("Query request: " + queryId + "\n")
                .contains("Datasource: Production\n")
                .contains("Submitted by: Alice <alice@example.com>\n")
                .contains("AI risk: MEDIUM (score: 42)\n")
                .contains("Justification: need it\n")
                .contains("Reviewer comment: too broad\n")
                .contains("Query preview:\nUPDATE orders SET status = 'shipped'\n")
                .contains("Review in AccessFlow: https://app.example.com/queries/abc");
    }

    @Test
    void descriptionOmitsOptionalLinesWhenNull() {
        var description = TicketDescriptionBuilder.description(minimalCtx());

        assertThat(description).isEqualTo("Event: QUERY_REJECTED\n");
    }

    @Test
    void descriptionUsesEmailAloneWhenDisplayNameMissing() {
        var ctx = new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null, null, null, null,
                null, null, null, null, null,
                UUID.randomUUID(), "alice@example.com", null,
                null, null, null, null, null,
                List.of(), Instant.now(), "en", null);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .contains("Submitted by: alice@example.com\n")
                .doesNotContain("<alice@example.com>");
    }

    @Test
    void descriptionUsesDisplayNameAloneWhenEmailMissing() {
        var ctx = new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null, null, null, null,
                null, null, null, null, null,
                UUID.randomUUID(), null, "Alice",
                null, null, null, null, null,
                List.of(), Instant.now(), "en", null);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .contains("Submitted by: Alice\n")
                .doesNotContain("<");
    }

    @Test
    void descriptionOmitsScoreWhenRiskScoreNull() {
        var ctx = new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null, null, null, null,
                RiskLevel.HIGH, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                List.of(), Instant.now(), "en", null);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .contains("AI risk: HIGH\n")
                .doesNotContain("score:");
    }

    @Test
    void descriptionOmitsBlankJustificationAndReviewerComment() {
        var ctx = new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                "  ", null, null, "  ", null,
                List.of(), Instant.now(), "en", null);

        assertThat(TicketDescriptionBuilder.description(ctx))
                .doesNotContain("Justification:")
                .doesNotContain("Reviewer comment:");
    }

    private NotificationContext minimalCtx() {
        return new NotificationContext(
                NotificationEventType.QUERY_REJECTED,
                UUID.randomUUID(), null, null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                List.of(), Instant.now(), "en", null);
    }

    private NotificationContext ctx(NotificationEventType eventType) {
        return ctx(eventType, "Production");
    }

    private NotificationContext ctx(NotificationEventType eventType, String datasourceName) {
        return new NotificationContext(
                eventType,
                UUID.randomUUID(),
                queryId,
                QueryType.UPDATE,
                "UPDATE orders SET status = 'shipped'",
                "UPDATE orders SET status = 'shipped'",
                "UPDATE orders SET status = 'shipped'",
                RiskLevel.MEDIUM,
                42,
                "Looks fine",
                UUID.randomUUID(),
                datasourceName,
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "need it",
                UUID.randomUUID(),
                "Bob",
                "too broad",
                URI.create("https://app.example.com/queries/abc"),
                List.of(),
                Instant.parse("2026-07-17T10:15:00Z"),
                "en",
                null);
    }
}
