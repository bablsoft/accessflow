package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
    void buildEventPayloadIncludesHeaderSummaryAndAction() {
        var ctx = ctxWith(NotificationEventType.QUERY_SUBMITTED);

        var payload = factory.buildEventPayload(ctx, "#review");

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

    private static NotificationContext ctxWith(NotificationEventType eventType) {
        return ctxWith(eventType, null);
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
}
