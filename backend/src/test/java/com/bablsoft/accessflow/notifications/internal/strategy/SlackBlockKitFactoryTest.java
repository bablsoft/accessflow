package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackBlockKitFactoryTest {

    private final SlackBlockKitFactory factory = new SlackBlockKitFactory();

    @Test
    @SuppressWarnings("deprecation")
    void buildEventPayloadIncludesHeaderSummaryAndAction() {
        var ctx = ctxWith(NotificationEventType.QUERY_SUBMITTED);

        var payload = factory.buildEventPayload(ctx, "#review");

        // Payload.channel is deprecated upstream but deliberately retained — see SlackBlockKitFactory.
        assertThat(payload.getChannel()).isEqualTo("#review");
        assertThat(payload.getText()).contains("New Query Awaiting Review");
        var blocks = payload.getBlocks();
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(3);

        assertThat(blocks.get(0)).isInstanceOf(HeaderBlock.class);
        var header = (HeaderBlock) blocks.get(0);
        assertThat(header.getText().getText()).contains("New Query Awaiting Review");

        assertThat(blocks.get(1)).isInstanceOf(SectionBlock.class);
        var summary = (SectionBlock) blocks.get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .anyMatch(t -> t.contains("Production"))
                .anyMatch(t -> t.contains("alice@example.com"))
                .anyMatch(t -> t.contains("MEDIUM"));

        assertThat(blocks).hasAtLeastOneElementOfType(ActionsBlock.class);
        var actions = (ActionsBlock) blocks.stream()
                .filter(b -> b instanceof ActionsBlock)
                .findFirst()
                .orElseThrow();
        var btn = (ButtonElement) actions.getElements().get(0);
        assertThat(btn.getUrl()).isEqualTo("https://app.example.com/queries/abc");
        assertThat(btn.getStyle()).isEqualTo("primary");
    }

    @Test
    void connectorTokenFailureHasHeader() {
        var payload = factory.buildEventPayload(
                ctxWith(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED), "#ops");
        assertThat(payload.getText()).contains("API Connector Token Failure");
    }

    @Test
    void buildBlocksWithActionButtonsAddsApproveAndReject() {
        var ctx = ctxWith(NotificationEventType.QUERY_SUBMITTED);

        var blocks = factory.buildBlocks(ctx, true);

        var actions = (ActionsBlock) blocks.stream()
                .filter(b -> b instanceof ActionsBlock)
                .findFirst()
                .orElseThrow();
        var elements = actions.getElements();
        assertThat(elements).hasSize(3);
        var approve = (ButtonElement) elements.get(0);
        var reject = (ButtonElement) elements.get(1);
        var view = (ButtonElement) elements.get(2);
        assertThat(approve.getActionId()).isEqualTo("approve");
        assertThat(approve.getStyle()).isEqualTo("primary");
        assertThat(approve.getValue()).isEqualTo(ctx.queryRequestId().toString());
        assertThat(reject.getActionId()).isEqualTo("reject");
        assertThat(reject.getStyle()).isEqualTo("danger");
        assertThat(reject.getValue()).isEqualTo(ctx.queryRequestId().toString());
        assertThat(view.getUrl()).isEqualTo("https://app.example.com/queries/abc");
    }

    @Test
    void buildBlocksWithoutActionButtonsUsesLinkOnly() {
        var ctx = ctxWith(NotificationEventType.QUERY_SUBMITTED);

        var blocks = factory.buildBlocks(ctx, false);

        var actions = (ActionsBlock) blocks.stream()
                .filter(b -> b instanceof ActionsBlock)
                .findFirst()
                .orElseThrow();
        assertThat(actions.getElements()).hasSize(1);
        var btn = (ButtonElement) actions.getElements().get(0);
        assertThat(btn.getActionId()).isNull();
        assertThat(btn.getUrl()).isEqualTo("https://app.example.com/queries/abc");
    }

    @Test
    void fallbackTextMatchesHeaderLabel() {
        assertThat(factory.fallbackText(ctxWith(NotificationEventType.QUERY_SUBMITTED)))
                .contains("New Query Awaiting Review");
        assertThat(factory.testText()).contains("AccessFlow notification channel test successful");
    }

    @Test
    @SuppressWarnings("deprecation")
    void buildTestPayloadContainsConfirmationText() {
        var payload = factory.buildTestPayload(null);

        assertThat(payload.getChannel()).isNull();
        assertThat(payload.getText()).contains("AccessFlow notification channel test successful");
        assertThat(payload.getBlocks()).hasSize(1);
        var section = (SectionBlock) payload.getBlocks().get(0);
        assertThat(((MarkdownTextObject) section.getText()).getText())
                .contains("AccessFlow notification channel test successful");
    }

    @Test
    void escalatedHeaderUsesEscalationLabel() {
        var ctx = ctxWith(NotificationEventType.QUERY_ESCALATED);
        var payload = factory.buildEventPayload(ctx, null);
        var header = (HeaderBlock) payload.getBlocks().get(0);
        assertThat(header.getText().getText()).contains("Query Escalated for Review");
        assertThat(payload.getText()).contains("Query Escalated for Review");
    }

    @Test
    void approvedHeaderUsesCheckmark() {
        var ctx = ctxWith(NotificationEventType.QUERY_APPROVED);
        var payload = factory.buildEventPayload(ctx, null);
        var header = (HeaderBlock) payload.getBlocks().get(0);
        assertThat(header.getText().getText()).contains("Query Approved");
    }

    @Test
    void reviewTimeoutHeaderIncludesHourglassAndTimeoutLabel() {
        var ctx = ctxWith(NotificationEventType.REVIEW_TIMEOUT, 24);
        var payload = factory.buildEventPayload(ctx, null);
        var header = (HeaderBlock) payload.getBlocks().get(0);
        assertThat(header.getText().getText())
                .contains("⌛")
                .contains("Query Auto-Rejected");
    }

    @Test
    void reviewTimeoutPayloadIncludesAutoRejectedAfterField() {
        var ctx = ctxWith(NotificationEventType.REVIEW_TIMEOUT, 24);
        var payload = factory.buildEventPayload(ctx, null);
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .anyMatch(t -> t.contains("Auto-rejected after:") && t.contains("24 hours"));
    }

    @Test
    void nonTimeoutPayloadOmitsAutoRejectedAfterField() {
        var ctx = ctxWith(NotificationEventType.QUERY_REJECTED);
        var payload = factory.buildEventPayload(ctx, null);
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .noneMatch(t -> t.contains("Auto-rejected after:"));
    }

    @Test
    void reviewTimeoutWithoutApprovalTimeoutHoursOmitsField() {
        var ctx = ctxWith(NotificationEventType.REVIEW_TIMEOUT, null);
        var payload = factory.buildEventPayload(ctx, null);
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .noneMatch(t -> t.contains("Auto-rejected after:"));
    }

    @Test
    void queryExecutedHeaderReflectsSuccessAndFailure() {
        var success = factory.buildEventPayload(executedCtx(QueryStatus.EXECUTED), null);
        assertThat(success.getText()).contains("Recurring Query Results Ready");
        var successHeader = (HeaderBlock) success.getBlocks().get(0);
        assertThat(successHeader.getText().getText()).contains("Recurring Query Results Ready");

        var failed = factory.buildEventPayload(executedCtx(QueryStatus.FAILED), null);
        assertThat(failed.getText()).contains("Recurring Query Run Failed");
        var failedHeader = (HeaderBlock) failed.getBlocks().get(0);
        assertThat(failedHeader.getText().getText()).contains("Recurring Query Run Failed");
    }

    @Test
    void weeklyDigestHeaderAndSectionRenderTheDigestMetrics() {
        var payload = factory.buildEventPayload(digestCtx(), null);
        var header = (HeaderBlock) payload.getBlocks().get(0);
        assertThat(header.getText().getText()).contains("Weekly Digest");
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .anyMatch(t -> t.contains("Queries this week:") && t.contains("5"))
                .anyMatch(t -> t.contains("Pending approvals:") && t.contains("2"))
                .anyMatch(t -> t.contains("Open anomalies:"))
                .anyMatch(t -> t.contains("Open suggestions:"));
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

    private static NotificationContext ctxWith(NotificationEventType eventType) {
        return ctxWith(eventType, null);
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

    private static NotificationContext ctxWith(NotificationEventType eventType,
                                               Integer approvalTimeoutHours) {
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
                Instant.parse("2026-05-06T10:15:00Z"),
                "en",
                approvalTimeoutHours);
    }

    @Test
    void deploymentSubmittedHeaderUsesRocketLabel() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null), null);
        assertThat(payload.getText()).isEqualTo("🚀 New Deployment Awaiting Review");
        var header = (HeaderBlock) payload.getBlocks().get(0);
        assertThat(header.getText().getText()).isEqualTo("🚀 New Deployment Awaiting Review");
    }

    @Test
    void deploymentApprovedHeaderUsesCheckmark() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_APPROVED, null), null);
        assertThat(payload.getText()).isEqualTo("✅ Deployment Approved");
    }

    @Test
    void deploymentRejectedHeaderUsesCross() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_REJECTED, null), null);
        assertThat(payload.getText()).isEqualTo("❌ Deployment Rejected");
    }

    @Test
    void deploymentOutcomeFailedHeaderCoversFailureAndRollback() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                        DeploymentOutcome.FAILED), null);
        assertThat(payload.getText()).isEqualTo("🚨 Deployment Failed or Rolled Back");
    }

    @Test
    void deploymentBreakGlassHeaderUsesSiren() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED, null), null);
        assertThat(payload.getText()).isEqualTo("🚨 Break-glass Deployment Executed");
    }

    @Test
    void deploymentSubmittedPayloadRendersPipelineFieldsNotDatasource() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_SUBMITTED, null), null);
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .anyMatch(t -> t.contains("Pipeline:") && t.contains("payments-service"))
                .anyMatch(t -> t.contains("Environment:") && t.contains("production"))
                .anyMatch(t -> t.contains("Version:") && t.contains("v2.4.1"))
                .anyMatch(t -> t.contains("Submitted by:") && t.contains("alice@example.com"))
                .noneMatch(t -> t.contains("Datasource:"))
                .noneMatch(t -> t.contains("Outcome:"));
    }

    @Test
    void deploymentOutcomeFailedPayloadCarriesTheOutcomeName() {
        var payload = factory.buildEventPayload(
                deploymentCtx(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                        DeploymentOutcome.ROLLED_BACK), null);
        var summary = (SectionBlock) payload.getBlocks().get(1);
        assertThat(summary.getFields()).extracting(t -> ((MarkdownTextObject) t).getText())
                .anyMatch(t -> t.contains("Outcome:") && t.contains("ROLLED_BACK"));
    }

    /**
     * A DEPLOYMENT_* context (#695): the pipeline rides in {@code datasourceName}, no SQL, no
     * queryType, no reviewUrl; the deployment fields are the trailing canonical components.
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
                null,
                UUID.randomUUID(),
                "payments-service",
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "hotfix for incident 4711",
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
                "v2.4.1",
                outcome,
                null);
    }

    @Test
    void escalationAndNudgeHeadersAreDistinctFromTheRoutingPolicyEscalation() {
        // REVIEW_ESCALATED (#622) means nobody decided in time; QUERY_ESCALATED means a routing
        // policy raised the approval bar. Sharing a headline would conflate the two.
        var escalated = (HeaderBlock) factory
                .buildEventPayload(ctxWith(NotificationEventType.REVIEW_ESCALATED), null)
                .getBlocks().get(0);
        var nudge = (HeaderBlock) factory
                .buildEventPayload(ctxWith(NotificationEventType.REVIEW_NUDGE), null)
                .getBlocks().get(0);

        assertThat(escalated.getText().getText()).contains("Review Escalated");
        assertThat(nudge.getText().getText()).contains("Reminder");
        assertThat(escalated.getText().getText()).isNotEqualTo(nudge.getText().getText());
    }
}
