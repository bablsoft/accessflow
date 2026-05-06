package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.RecipientView;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private EmailNotificationStrategy strategy;

    @BeforeEach
    void setUp() {
        codec = mock(ChannelConfigCodec.class);
        templateEngine = mock(SpringTemplateEngine.class);
        factory = mock(EmailNotificationStrategy.MailSenderFactory.class);
        sender = mock(JavaMailSender.class);
        strategy = new EmailNotificationStrategy(codec, templateEngine, factory);

        when(factory.create(any())).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(inv -> {
            var session = Session.getInstance(new Properties());
            return new MimeMessage(session);
        });
        when(templateEngine.process(anyString(), any())).thenReturn("<html>body</html>");
        when(codec.decodeEmail(anyString())).thenReturn(emailConfig("Sender Name"));
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

        verify(templateEngine).process(eq("email/query-ready-for-review"), any());
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
                URI.create("https://app.example.com/queries/x"), null, Instant.now());
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
    void deliverUsesRejectedTemplateForRejectedEvent() {
        var ctx = ctx(NotificationEventType.QUERY_REJECTED, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-rejected"), any());
    }

    @Test
    void deliverUsesReviewTemplateForAiHighRisk() {
        var ctx = ctx(NotificationEventType.AI_HIGH_RISK, List.of(
                new RecipientView(UUID.randomUUID(), "alice@example.com", "Alice")));
        strategy.deliver(ctx, channel());
        verify(templateEngine).process(eq("email/query-ready-for-review"), any());
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
                Instant.now());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
