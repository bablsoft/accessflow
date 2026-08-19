package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.api.ResultExportGovernanceService;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.RecipientView;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationStrategyTest {

    private ChannelConfigCodec codec;
    private SpringTemplateEngine templateEngine;
    private EmailNotificationStrategy.MailSenderFactory factory;
    private JavaMailSender sender;
    private MessageSource messageSource;
    private QueryResultCsvRenderer csvRenderer;
    private ResultExportGovernanceService governanceService;
    private EmailNotificationStrategy strategy;

    private static final ExportDecision ALLOW_DECISION = new ExportDecision(
            true, ExportPolicyMode.ALLOW, null, false, List.of(), List.of());
    private static final ExportDecision DENY_DECISION = new ExportDecision(
            false, ExportPolicyMode.DENY_CLASSIFIED, null, false, List.of(),
            List.of(DataClassification.PCI));

    @BeforeEach
    void setUp() {
        codec = mock(ChannelConfigCodec.class);
        templateEngine = mock(SpringTemplateEngine.class);
        factory = mock(EmailNotificationStrategy.MailSenderFactory.class);
        sender = mock(JavaMailSender.class);
        messageSource = mock(MessageSource.class);
        csvRenderer = mock(QueryResultCsvRenderer.class);
        governanceService = mock(ResultExportGovernanceService.class);
        strategy = new EmailNotificationStrategy(codec, templateEngine, factory, messageSource,
                csvRenderer, governanceService,
                Clock.fixed(Instant.parse("2026-08-18T09:30:00Z"), ZoneOffset.UTC));
        when(governanceService.decide(any(), any(), any())).thenReturn(ALLOW_DECISION);

        when(factory.create(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(inv -> {
            var session = Session.getInstance(new Properties());
            return new MimeMessage(session);
        });
        when(templateEngine.process(anyString(), any())).thenReturn("<html>body</html>");
        when(codec.decodeEmail(anyString())).thenReturn(emailConfig("Sender Name"));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> "subject:" + inv.getArgument(0));
    }

    @Test
    void supportsEmail() {
        assertThat(strategy.supports()).isEqualTo(NotificationChannelType.EMAIL);
    }

    @Test
    void deliverSendsOneMessagePerRecipientWithRenderedHtml() {
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice"),
                new RecipientView(UUID.randomUUID(), "bob@example.com", "Bob")));

        strategy.deliver(ctx, channel());

        // Since #626 the body renders per recipient (the attachment decision is per recipient).
        verify(templateEngine, times(2)).process(eq("email/query-ready-for-review"), any());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void deliverSkipsWhenRecipientsEmpty() {
        var ctx = ctx(NotificationEventType.QUERY_APPROVED, List.of());
        strategy.deliver(ctx, channel());
        verify(sender, never()).send(any(MimeMessage.class));
        verify(codec, never()).decodeEmail(anyString());
    }

    @Test
    void deliverSkipsWhenRecipientsNull() {
        var ctx = new NotificationContext(NotificationEventType.QUERY_APPROVED,
                UUID.randomUUID(), UUID.randomUUID(), QueryType.SELECT,
                "SELECT 1", "SELECT 1", "SELECT 1",
                null, null, null, UUID.randomUUID(), "ds",
                UUID.randomUUID(), "x@example.com", "X", null, null, null, null,
                URI.create("https://app.example.com/queries/x"), null, Instant.now(),
                "en", null);
        strategy.deliver(ctx, channel());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void deliverSkipsTestEventBecauseTemplateIsNull() {
        var ctx = ctx(NotificationEventType.TEST, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(sender, never()).send(any(MimeMessage.class));
        verify(codec, never()).decodeEmail(anyString());
    }

    @Test
    void deliverUsesApprovedTemplateForApprovedEvent() {
        var ctx = ctx(NotificationEventType.QUERY_APPROVED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-approved"), any());
    }

    @Test
    void deliverUsesConnectorTokenFailedTemplate() {
        var ctx = ctx(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, List.of(
                new RecipientView(UUID.randomUUID(), "admin@example.com", "Admin")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/api-connector-token-failed"), any());
    }

    @Test
    void deliverUsesRejectedTemplateForRejectedEvent() {
        var ctx = ctx(NotificationEventType.QUERY_REJECTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-rejected"), any());
    }

    @Test
    void deliverUsesEscalatedTemplateAndSubjectForEscalatedEvent() throws Exception {
        var ctx = ctx(NotificationEventType.QUERY_ESCALATED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-escalated"), any());
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("subject:notification.email.subject.query_escalated");
    }

    @Test
    void deliverUsesReviewTemplateForAiHighRisk() {
        var ctx = ctx(NotificationEventType.AI_HIGH_RISK, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-ready-for-review"), any());
    }

    @Test
    void deliverUsesReviewTimeoutTemplateForTimeoutEvent() {
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")),
                "en", 24);
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-review-timeout"), any());
    }

    @Test
    void deliverPassesApprovalTimeoutHoursToTemplateContext() {
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")),
                "en", 24);
        strategy.deliver(ctx, channel());
        var captor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("email/query-review-timeout"), captor.capture());
        assertThat(captor.getValue().getVariable("approvalTimeoutHours")).isEqualTo(24);
    }

    @Test
    void deliverLeavesApprovalTimeoutHoursNullForNonTimeoutEvents() {
        var ctx = ctx(NotificationEventType.QUERY_APPROVED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        var captor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("email/query-approved"), captor.capture());
        assertThat(captor.getValue().getVariable("approvalTimeoutHours")).isNull();
    }

    @Test
    void subjectResolvedFromMessageSourceUsingContextLocale() throws Exception {
        when(messageSource.getMessage(
                eq("notification.email.subject.review_timeout"),
                any(),
                eq(Locale.forLanguageTag("es"))))
                .thenReturn("[AccessFlow] Consulta auto-rechazada en Production");
        var ctx = ctx(NotificationEventType.REVIEW_TIMEOUT, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")),
                "es", 24);

        strategy.deliver(ctx, channel());

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("[AccessFlow] Consulta auto-rechazada en Production");
    }

    @Test
    void subjectLocaleFallsBackToEnglishWhenContextLocaleBlank() throws Exception {
        when(messageSource.getMessage(
                eq("notification.email.subject.query_approved"),
                any(),
                eq(Locale.ENGLISH)))
                .thenReturn("[AccessFlow] Query approved on Production");
        var ctx = ctx(NotificationEventType.QUERY_APPROVED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")),
                null, null);

        strategy.deliver(ctx, channel());

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("[AccessFlow] Query approved on Production");
    }

    @Test
    void sendOneSkipsBlankRecipientEmail() {
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, List.of(
                new RecipientView(UUID.randomUUID(), "", "Anon"),
                new RecipientView(UUID.randomUUID(), null, "Other")));
        strategy.deliver(ctx, channel());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void deliveryFailureWrappedAsNotificationDeliveryException() {
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        doThrow(new MailSendException("smtp down")).when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> strategy.deliver(ctx, channel()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Email delivery failed");
    }

    @Test
    void invalidFromAddressFailsWithDeliveryException() {
        when(codec.decodeEmail(anyString())).thenReturn(new EmailChannelConfig(
                "smtp.example.com", 587, "u", "pw", true,
                "not a valid address", null));
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));

        assertThatThrownBy(() -> strategy.deliver(ctx, channel()))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void sendTestUsesOverrideEmailWhenProvided() {
        strategy.sendTest(channel(), "ops@example.com");

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        // We asked the sender to send exactly once with the override recipient.
        assertThat(captor.getAllValues()).hasSize(1);
    }

    @Test
    void sendTestFallsBackToFromAddressWhenOverrideBlank() {
        strategy.sendTest(channel(), "");
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void sendTestFallsBackToFromAddressWhenOverrideNull() {
        strategy.sendTest(channel(), null);
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void deliverWorksWhenFromNameIsBlank() {
        when(codec.decodeEmail(anyString())).thenReturn(emailConfig(null));
        var ctx = ctx(NotificationEventType.QUERY_SUBMITTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void deliverUsesExecutedTemplateAndSubjectForQueryExecutedEvent() throws Exception {
        var ctx = execCtx(QueryStatus.FAILED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));

        strategy.deliver(ctx, channel());

        verify(templateEngine).process(eq("email/query-executed"), any());
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("subject:notification.email.subject.query_executed");
    }

    @Test
    void deliverAttachesResultsCsvForSuccessfulQueryExecuted() throws Exception {
        var recipient = new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice");
        var ctx = execCtx(QueryStatus.EXECUTED, List.of(recipient));
        when(csvRenderer.render(eq(ctx.queryRequestId()), eq(ALLOW_DECISION),
                eq("alice@example.com"), any(Instant.class)))
                .thenReturn(java.util.Optional.of(new QueryResultCsvRenderer.Csv(
                        "id\r\n1\r\n".getBytes(StandardCharsets.UTF_8),
                        "results-" + ctx.queryRequestId() + ".csv", 1, false)));

        strategy.deliver(ctx, channel());

        verify(governanceService).decide(ctx.organizationId(), ctx.queryRequestId(),
                recipient.userId());
        var contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("email/query-executed"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getVariable("resultsAttached")).isEqualTo(true);
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        var multipart = (jakarta.mail.internet.MimeMultipart) captor.getValue().getContent();
        // Body part + the results.csv attachment part.
        assertThat(multipart.getCount()).isEqualTo(2);
        assertThat(multipart.getBodyPart(1).getFileName())
                .isEqualTo("results-" + ctx.queryRequestId() + ".csv");
        // The export is audited per recipient once the mail actually went out (#626).
        verify(governanceService).recordAttachmentExport(ctx.organizationId(),
                ctx.queryRequestId(), recipient.userId(), "alice@example.com",
                ALLOW_DECISION, 1L, false);
    }

    @Test
    void deliverSuppressesAttachmentWhenExportDenied() {
        var recipient = new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice");
        var ctx = execCtx(QueryStatus.EXECUTED, List.of(recipient));
        when(governanceService.decide(any(), any(), any())).thenReturn(DENY_DECISION);

        strategy.deliver(ctx, channel());

        // The email still delivers — only the attachment is withheld, and nothing is audited.
        verify(csvRenderer, never()).render(any(), any(), any(), any());
        verify(sender, times(1)).send(any(MimeMessage.class));
        verify(governanceService, never()).recordAttachmentExport(any(), any(), any(), any(),
                any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
        var contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("email/query-executed"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getVariable("resultsAttached")).isEqualTo(false);
    }

    @Test
    void deliverDoesNotInvokeRendererForFailedOccurrence() {
        var ctx = execCtx(QueryStatus.FAILED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));

        strategy.deliver(ctx, channel());

        verify(csvRenderer, never()).render(any(), any(), any(), any());
        verify(sender, times(1)).send(any(MimeMessage.class));
        verify(governanceService, never()).decide(any(), any(), any());
    }

    @Test
    void hasTemplateForQueryExecutedIsTrue() {
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.QUERY_EXECUTED))
                .isTrue();
    }

    @Test
    void hasTemplateForReflectsQueryEventsAndExcludesAccessEvents() {
        // Query/AI events resolve to an email template…
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.QUERY_SUBMITTED))
                .isTrue();
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.QUERY_APPROVED))
                .isTrue();
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.QUERY_ESCALATED))
                .isTrue();
        // …while TEST and the JIT access (in-app only) events have no template.
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.TEST)).isFalse();
        for (var event : new NotificationEventType[]{
                NotificationEventType.ACCESS_REQUEST_SUBMITTED,
                NotificationEventType.ACCESS_REQUEST_APPROVED,
                NotificationEventType.ACCESS_REQUEST_REJECTED,
                NotificationEventType.ACCESS_GRANT_EXPIRED,
                NotificationEventType.ACCESS_GRANT_REVOKED}) {
            assertThat(EmailNotificationStrategy.hasTemplateFor(event)).isFalse();
        }
    }

    private static EmailChannelConfig emailConfig(String fromName) {
        return new EmailChannelConfig(
                "smtp.example.com", 587, "smtpuser", "smtppw", true,
                "from@example.com", fromName);
    }

    private static NotificationChannelEntity channel() {
        var c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(UUID.randomUUID());
        c.setChannelType(NotificationChannelType.EMAIL);
        c.setName("Email");
        c.setActive(true);
        c.setConfigJson("{}");
        c.setCreatedAt(Instant.now());
        return c;
    }

    private static NotificationContext ctx(NotificationEventType eventType,
                                           List<RecipientView> recipients) {
        return ctx(eventType, recipients, "en", null);
    }

    /** A QUERY_EXECUTED context (#627) carrying the execution outcome in the trailing fields. */
    private static NotificationContext execCtx(QueryStatus executionStatus,
                                               List<RecipientView> recipients) {
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
                recipients,
                Instant.now(),
                "en",
                null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                executionStatus, 5L, 120L);
    }

    private static NotificationContext ctx(NotificationEventType eventType,
                                           List<RecipientView> recipients,
                                           String locale,
                                           Integer approvalTimeoutHours) {
        return new NotificationContext(
                eventType,
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
                UUID.randomUUID(),
                "Bob",
                "looks risky",
                URI.create("https://app.example.com/queries/abc"),
                recipients,
                Instant.now(),
                locale,
                approvalTimeoutHours);
    }
}
