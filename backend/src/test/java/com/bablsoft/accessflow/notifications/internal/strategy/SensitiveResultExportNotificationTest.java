package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.RecipientView;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyTrigger;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.messageresolver.StandardMessageResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.messageresolver.SpringMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the #626 SENSITIVE_RESULT_EXPORTED fan-out sites the compiler cannot: the Thymeleaf
 * template, the chat-factory bodies (the classification/format fields ride on {@code default}-free
 * title switches but field assembly is runtime-only), and the two trigger registries whose
 * {@code default} branches would otherwise let the event page an on-call engineer or open a ticket
 * without anyone noticing.
 */
class SensitiveResultExportNotificationTest {

    private static SpringTemplateEngine buildEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);

        var messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:i18n/messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);

        var springResolver = new SpringMessageResolver();
        springResolver.setMessageSource(messages);

        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageResolvers(Set.of(springResolver, new StandardMessageResolver()));
        return engine;
    }

    private static Context templateContext(String trigger) {
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("submitterEmail", "analyst@example.com");
        ctx.setVariable("datasourceName", "analytics-prod");
        ctx.setVariable("exportClassifications", "PCI, PHI");
        ctx.setVariable("exportFormat", "CSV");
        ctx.setVariable("exportTrigger", trigger);
        ctx.setVariable("executionRowsAffected", 128L);
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");
        return ctx;
    }

    private static NotificationContext eventContext() {
        return new NotificationContext(
                NotificationEventType.SENSITIVE_RESULT_EXPORTED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null, null, null, null, null, null, null,
                UUID.randomUUID(),
                "analytics-prod",
                UUID.randomUUID(),
                "analyst@example.com",
                null,
                null, null, null, null,
                URI.create("https://app.example.com/queries/abc"),
                List.of(new RecipientView(UUID.randomUUID(), "admin@example.com", "Admin")),
                Instant.now(),
                "en",
                null,
                null, null, null, null, null, null, null,
                null, null, null, null,
                null, 128L, null,
                null, null, null,
                "CSV",
                "PCI, PHI",
                "endpoint");
    }

    @Test
    void templateRendersExporterDatasourceClassificationsAndFormat() {
        var html = buildEngine().process("email/sensitive-result-exported",
                templateContext("endpoint"));

        assertThat(html)
                .contains("Classified query results were exported")
                .contains("analyst@example.com")
                .contains("analytics-prod")
                .contains("PCI, PHI")
                .contains("CSV")
                .contains("128")
                .doesNotContain("recurring-execution email")
                .contains("https://app.example.com/queries/abc");
    }

    @Test
    void templateNamesTheEmailAttachmentTriggerWhenApplicable() {
        var html = buildEngine().process("email/sensitive-result-exported",
                templateContext("email_attachment"));

        assertThat(html).contains("recurring-execution email");
    }

    @Test
    void templateRendersInEveryShippedLocale() {
        var engine = buildEngine();
        for (var locale : new String[]{"de", "es", "fr", "hy", "ru", "zh-CN"}) {
            var ctx = templateContext("endpoint");
            var localised = new Context(Locale.forLanguageTag(locale));
            ctx.getVariableNames().forEach(n -> localised.setVariable(n, ctx.getVariable(n)));
            var html = engine.process("email/sensitive-result-exported", localised);
            assertThat(html)
                    .as("locale %s renders without an unresolved message key", locale)
                    .doesNotContain("??notification.email.sensitive_result_exported");
        }
    }

    @Test
    void slackPayloadCarriesClassificationsAndExportSummary() {
        var payload = new SlackBlockKitFactory().buildEventPayload(eventContext(), null);

        assertThat(payload.getText()).isEqualTo("📤 Sensitive Data Exported");
        assertThat(payload.getBlocks().toString())
                .contains("PCI, PHI")
                .contains("CSV")
                .contains("128");
    }

    @Test
    void discordEmbedCarriesClassificationsAndExportSummary() {
        var factory = new DiscordPayloadFactory(
                tools.jackson.databind.json.JsonMapper.builder().build());
        var body = factory.buildEventBody(eventContext(), new com.bablsoft.accessflow
                .notifications.internal.codec.DiscordChannelConfig(
                        URI.create("https://discord.com/api/webhooks/x"), null, null));

        assertThat(body)
                .contains("Sensitive Data Exported")
                .contains("PCI, PHI")
                .contains("CSV · 128 rows");
    }

    @Test
    void teamsCardCarriesClassificationsAndExportSummary() {
        var factory = new MsTeamsPayloadFactory(
                tools.jackson.databind.json.JsonMapper.builder().build());
        var body = factory.buildEventBody(eventContext());

        assertThat(body)
                .contains("Sensitive Data Exported")
                .contains("PCI, PHI")
                .contains("CSV · 128 rows");
    }

    @Test
    void telegramMessageCarriesClassificationsAndExportSummary() {
        var body = new TelegramMessageFactory(
                tools.jackson.databind.json.JsonMapper.builder().build())
                .buildEventBody(eventContext(), "chat-1");

        assertThat(body)
                .contains("Sensitive Data Exported")
                .contains("PCI, PHI")
                .contains("128 rows");
    }

    /** A policy-permitted export is oversight, not an outage — it must never page on-call. */
    @Test
    void neverPages() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.SENSITIVE_RESULT_EXPORTED))
                .isEmpty();
    }

    /** Nor open a ticket: the export was allowed; the audit row is the system of record. */
    @Test
    void neverOpensATicket() {
        assertThat(TicketingTrigger.forEvent(NotificationEventType.SENSITIVE_RESULT_EXPORTED))
                .isEmpty();
    }

    @Test
    void routesToItsOwnEmailTemplate() {
        assertThat(EmailNotificationStrategy
                .hasTemplateFor(NotificationEventType.SENSITIVE_RESULT_EXPORTED))
                .isTrue();
    }
}
