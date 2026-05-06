package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.RecipientView;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

@Component
@RequiredArgsConstructor
@Slf4j
class EmailNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final SpringTemplateEngine templateEngine;
    private final MailSenderFactory mailSenderFactory;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        if (ctx.recipients() == null || ctx.recipients().isEmpty()) {
            log.debug("Skipping email delivery for {} on query {} — no recipients",
                    ctx.eventType(), ctx.queryRequestId());
            return;
        }
        var template = templateName(ctx.eventType());
        if (template == null) {
            log.debug("No email template for event {}; skipping delivery", ctx.eventType());
            return;
        }
        var config = codec.decodeEmail(channel.getConfigJson());
        var sender = mailSenderFactory.create(config);
        var subject = subject(ctx);
        var html = renderHtml(template, ctx);
        for (RecipientView recipient : ctx.recipients()) {
            sendOne(sender, config, recipient.email(), subject, html);
        }
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeEmail(channel.getConfigJson());
        var sender = mailSenderFactory.create(config);
        var to = (optionalEmailOverride != null && !optionalEmailOverride.isBlank())
                ? optionalEmailOverride
                : config.fromAddress();
        sendOne(sender, config, to, "AccessFlow notification test",
                "<p>This is a test message from AccessFlow.</p>");
    }

    private void sendOne(JavaMailSender sender, EmailChannelConfig config, String to,
                         String subject, String html) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(buildFrom(config));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new NotificationDeliveryException("Email composition failed", ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("Email delivery failed", ex);
        }
    }

    private InternetAddress buildFrom(EmailChannelConfig config) throws UnsupportedEncodingException {
        if (config.fromName() != null && !config.fromName().isBlank()) {
            return new InternetAddress(config.fromAddress(), config.fromName(),
                    StandardCharsets.UTF_8.name());
        }
        try {
            return new InternetAddress(config.fromAddress());
        } catch (jakarta.mail.internet.AddressException ex) {
            throw new NotificationDeliveryException(
                    "Invalid from address: " + config.fromAddress(), ex);
        }
    }

    private String renderHtml(String template, NotificationContext ctx) {
        var context = new Context(Locale.US);
        context.setVariable("eventType", ctx.eventType());
        context.setVariable("queryRequestId", ctx.queryRequestId());
        context.setVariable("queryType", ctx.queryType());
        context.setVariable("sqlPreview", ctx.sqlPreview200());
        context.setVariable("riskLevel", ctx.riskLevel());
        context.setVariable("riskScore", ctx.riskScore());
        context.setVariable("aiSummary", ctx.aiSummary());
        context.setVariable("datasourceName", ctx.datasourceName());
        context.setVariable("submitterEmail", ctx.submitterEmail());
        context.setVariable("submitterDisplayName", ctx.submitterDisplayName());
        context.setVariable("reviewerDisplayName", ctx.reviewerDisplayName());
        context.setVariable("reviewerComment", ctx.reviewerComment());
        context.setVariable("reviewUrl",
                ctx.reviewUrl() != null ? ctx.reviewUrl().toString() : null);
        return templateEngine.process(template, context);
    }

    private static String templateName(NotificationEventType eventType) {
        return switch (eventType) {
            case QUERY_SUBMITTED -> "email/query-ready-for-review";
            case QUERY_APPROVED -> "email/query-approved";
            case QUERY_REJECTED -> "email/query-rejected";
            case AI_HIGH_RISK -> "email/query-ready-for-review";
            case TEST -> null;
        };
    }

    private static String subject(NotificationContext ctx) {
        return switch (ctx.eventType()) {
            case QUERY_SUBMITTED -> "[AccessFlow] Query awaiting review on " + ctx.datasourceName();
            case QUERY_APPROVED -> "[AccessFlow] Query approved on " + ctx.datasourceName();
            case QUERY_REJECTED -> "[AccessFlow] Query rejected on " + ctx.datasourceName();
            case AI_HIGH_RISK -> "[AccessFlow] High-risk query flagged on " + ctx.datasourceName();
            case TEST -> "AccessFlow notification test";
        };
    }

    /**
     * Seam for tests: produces a {@link JavaMailSender} from a per-channel SMTP config.
     */
    @Component
    static class MailSenderFactory {

        JavaMailSender create(EmailChannelConfig config) {
            var sender = new JavaMailSenderImpl();
            sender.setHost(config.smtpHost());
            sender.setPort(config.smtpPort());
            sender.setUsername(config.smtpUser());
            sender.setPassword(config.smtpPasswordPlain());
            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", config.smtpUser() != null);
            props.put("mail.smtp.starttls.enable", config.smtpTls());
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");
            return sender;
        }
    }

}
