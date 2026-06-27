package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.RecipientView;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
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
public class EmailNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final SpringTemplateEngine templateEngine;
    private final MailSenderFactory mailSenderFactory;
    private final MessageSource messageSource;

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
        if (templateName(ctx.eventType()) == null) {
            log.debug("No email template for event {}; skipping delivery", ctx.eventType());
            return;
        }
        deliverInternal(ctx, codec.decodeEmail(channel.getConfigJson()));
    }

    /**
     * Render and send the configured event template using the given SMTP config. Shared by the
     * per-channel path and the system-SMTP fallback so behaviour is identical regardless of source.
     */
    public void deliverInternal(NotificationContext ctx, EmailChannelConfig config) {
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
        var sender = mailSenderFactory.create(config);
        var subject = subject(ctx);
        var html = renderHtml(template, ctx);
        for (RecipientView recipient : ctx.recipients()) {
            sendOne(sender, config, recipient.email(), subject, html);
        }
    }

    /**
     * Returns true if the event type has an email template configured. Used by the
     * dispatcher to decide whether to attempt a system-SMTP fallback for an event.
     */
    public static boolean hasTemplateFor(NotificationEventType eventType) {
        return templateName(eventType) != null;
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
        var context = new Context(resolveLocale(ctx));
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
        context.setVariable("approvalTimeoutHours", ctx.approvalTimeoutHours());
        context.setVariable("reviewUrl",
                ctx.reviewUrl() != null ? ctx.reviewUrl().toString() : null);
        context.setVariable("anomalyFeature", ctx.anomalyFeature());
        context.setVariable("anomalyScore", ctx.anomalyScore());
        context.setVariable("anomalyObservedValue", ctx.anomalyObservedValue());
        context.setVariable("anomalyBaselineMean", ctx.anomalyBaselineMean());
        context.setVariable("anomalyUserLabel", ctx.anomalyUserLabel());
        var digest = ctx.digest();
        context.setVariable("digestWeekStart", digest != null ? digest.weekStart() : null);
        context.setVariable("digestWeekEnd", digest != null ? digest.weekEnd() : null);
        context.setVariable("digestTotalQueries", digest != null ? digest.totalQueries() : null);
        context.setVariable("digestPendingApprovals", digest != null ? digest.pendingApprovals() : null);
        context.setVariable("digestOpenAnomalies", digest != null ? digest.openAnomalies() : null);
        context.setVariable("digestOpenSuggestions", digest != null ? digest.openSuggestions() : null);
        context.setVariable("dashboardUrl",
                ctx.reviewUrl() != null ? ctx.reviewUrl().toString() : null);
        context.setVariable("attestationCampaignName", ctx.attestationCampaignName());
        context.setVariable("attestationDueAt", ctx.attestationDueAt());
        context.setVariable("attestationUrl",
                ctx.reviewUrl() != null ? ctx.reviewUrl().toString() : null);
        return templateEngine.process(template, context);
    }

    private static String templateName(NotificationEventType eventType) {
        return switch (eventType) {
            case QUERY_SUBMITTED -> "email/query-ready-for-review";
            case QUERY_APPROVED -> "email/query-approved";
            case QUERY_REJECTED -> "email/query-rejected";
            case REVIEW_TIMEOUT -> "email/query-review-timeout";
            case AI_HIGH_RISK -> "email/query-ready-for-review";
            case ANOMALY_DETECTED -> "email/anomaly-detected";
            case BREAK_GLASS_EXECUTED -> "email/break-glass-executed";
            case WEEKLY_DIGEST -> "email/weekly-digest";
            case ATTESTATION_CAMPAIGN_OPENED -> "email/attestation-campaign-opened";
            // Access (JIT) events are delivered as in-app notifications by AccessNotificationListener,
            // not through the channel-strategy email path — no email template.
            // API-governance events (AF-500) deliver as in-app + chat notifications, not email.
            case TEST, ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED, API_REQUEST_SUBMITTED, API_REQUEST_APPROVED,
                 API_REQUEST_EXECUTED, API_REQUEST_FAILED -> null;
        };
    }

    private String subject(NotificationContext ctx) {
        var key = subjectKey(ctx.eventType());
        var args = switch (ctx.eventType()) {
            case TEST -> null;
            case ATTESTATION_CAMPAIGN_OPENED -> new Object[]{ctx.attestationCampaignName()};
            default -> new Object[]{ctx.datasourceName()};
        };
        return messageSource.getMessage(key, args, resolveLocale(ctx));
    }

    private static String subjectKey(NotificationEventType eventType) {
        return switch (eventType) {
            case QUERY_SUBMITTED -> "notification.email.subject.query_submitted";
            case QUERY_APPROVED -> "notification.email.subject.query_approved";
            case QUERY_REJECTED -> "notification.email.subject.query_rejected";
            case REVIEW_TIMEOUT -> "notification.email.subject.review_timeout";
            case AI_HIGH_RISK -> "notification.email.subject.ai_high_risk";
            case ANOMALY_DETECTED -> "notification.email.subject.anomaly_detected";
            case BREAK_GLASS_EXECUTED -> "notification.email.subject.break_glass_executed";
            case WEEKLY_DIGEST -> "notification.email.subject.weekly_digest";
            case ATTESTATION_CAMPAIGN_OPENED ->
                    "notification.email.subject.attestation_campaign_opened";
            // Unreachable for access events (no email template); kept for switch exhaustiveness.
            case TEST, ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED, API_REQUEST_SUBMITTED, API_REQUEST_APPROVED,
                 API_REQUEST_EXECUTED, API_REQUEST_FAILED -> "notification.email.subject.test";
        };
    }

    private static Locale resolveLocale(NotificationContext ctx) {
        if (ctx.locale() == null || ctx.locale().isBlank()) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(ctx.locale());
    }

    /**
     * Seam for tests: produces a {@link JavaMailSender} from a per-channel SMTP config.
     */
    @Component
    public static class MailSenderFactory {

        public JavaMailSender create(EmailChannelConfig config) {
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
