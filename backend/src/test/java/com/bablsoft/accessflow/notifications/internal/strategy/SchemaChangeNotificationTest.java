package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.RecipientView;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutySeverity;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyTrigger;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.messageresolver.StandardMessageResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.messageresolver.SpringMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the #882 schema-change fan-out on every channel: each of the four events renders a
 * type-specific title and its own field set, never the generic query fields or a default branch.
 * The four switches the compiler cannot check — PagerDuty's summary, both silent email switches and
 * the ticket headline — are the regression risk this class exists for.
 */
class SchemaChangeNotificationTest {

    /** Every schema-change event — a value added later is forced through these tests too. */
    private static final String ALL = "SCHEMA_.*";

    private static final List<String> TEMPLATES = List.of(
            "email/schema-change-promotion-submitted",
            "email/schema-change-promotion-applied",
            "email/schema-change-promotion-failed",
            "email/schema-drift-detected");

    private final JsonMapper json = JsonMapper.builder().build();

    /** A fully populated context for one schema-change event, as the context builder shapes it. */
    static NotificationContext schemaChangeCtx(NotificationEventType type) {
        var drift = type == NotificationEventType.SCHEMA_DRIFT_DETECTED;
        var failed = type == NotificationEventType.SCHEMA_CHANGE_PROMOTION_FAILED;
        return new NotificationContext(
                type, UUID.randomUUID(), null,
                null, null, null, null,
                null, null, null,
                UUID.randomUUID(), "billing-pipeline",
                drift ? null : UUID.randomUUID(),
                drift ? null : "dba@example.com",
                drift ? null : "Dana DBA",
                null,
                null, null, null,
                null,
                List.of(new RecipientView(UUID.randomUUID(), "rcpt@example.com", "R")),
                Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, "staging", null, null, null,
                drift ? null : UUID.randomUUID(),
                drift ? null : "add-invoice-index",
                drift ? null : (failed ? "PARTIALLY_APPLIED" : "APPLIED"),
                failed ? "ERROR: relation \"invoices\" does not exist" : null,
                drift ? 3 : null);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void slackRendersASchemaChangeTitleAndFieldSet(NotificationEventType type) {
        var payload = new SlackBlockKitFactory().buildEventPayload(schemaChangeCtx(type), null);
        var text = payload.getText() + "\n" + payload.getBlocks().toString();

        assertThat(text).contains("Schema").contains("billing-pipeline").contains("staging")
                .doesNotContain("Datasource:").doesNotContain(type.name());
        assertExtras(type, text);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void discordRendersASchemaChangeTitleAndFieldSet(NotificationEventType type) {
        var body = new DiscordPayloadFactory(json).buildEventBody(schemaChangeCtx(type),
                new DiscordChannelConfig(URI.create("https://discord.example/hook"), null, null));

        assertThat(body).contains("Schema").contains("billing-pipeline").contains("staging")
                .doesNotContain("\"Datasource\"");
        assertExtras(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void teamsRendersASchemaChangeTitleAndFieldSet(NotificationEventType type) {
        var body = new MsTeamsPayloadFactory(json).buildEventBody(schemaChangeCtx(type));

        assertThat(body).contains("Schema").contains("billing-pipeline").contains("staging")
                .doesNotContain("\"Datasource\"");
        assertExtras(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void telegramRendersASchemaChangeTitleAndFieldSet(NotificationEventType type) {
        var body = new TelegramMessageFactory(json).buildEventBody(schemaChangeCtx(type), "42");

        assertThat(body).contains("Schema").contains("billing").contains("staging")
                .doesNotContain("Datasource");
        assertExtras(type, body.replace("\\\\", ""));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void pagerDutyHasASpecificSummaryEvenThoughNothingPages(NotificationEventType type) {
        var body = new PagerDutyPayloadFactory(json).buildEventBody(schemaChangeCtx(type),
                new PagerDutyChannelConfig("KEY", PagerDutySeverity.WARNING, EnumSet.allOf(PagerDutyTrigger.class)));

        assertThat(body).contains("schema").contains("on pipeline billing-pipeline")
                .doesNotContain("for a query");
        assertThat(PagerDutyTrigger.forEvent(type)).as("%s must not page", type).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void webhookCarriesTheAdditiveSchemaChangeBlock(NotificationEventType type) {
        var tree = json.readTree(new WebhookPayloadFactory(json).buildBody(schemaChangeCtx(type)));

        assertThat(tree.path("event").asString()).isEqualTo(type.name());
        var block = tree.path("schema_change");
        assertThat(block.isObject()).isTrue();
        assertThat(block.path("pipeline_name").asString()).isEqualTo("billing-pipeline");
        assertThat(block.path("environment").asString()).isEqualTo("staging");
        if (type == NotificationEventType.SCHEMA_DRIFT_DETECTED) {
            assertThat(block.path("new_finding_count").asInt()).isEqualTo(3);
            assertThat(block.path("promotion_id").isNull()).isTrue();
        } else {
            assertThat(block.path("change_set_name").asString()).isEqualTo("add-invoice-index");
            assertThat(block.path("promotion_id").isNull()).isFalse();
        }
        // Existing top-level blocks are untouched.
        assertThat(tree.has("query_request")).isTrue();
        assertThat(tree.has("deployment")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void ticketHeadlineIsSpecificAndNothingTickets(NotificationEventType type) {
        assertThat(TicketDescriptionBuilder.summary(schemaChangeCtx(type)))
                .contains("pipeline billing-pipeline")
                .doesNotContain(type.name());
        assertThat(TicketingTrigger.forEvent(type)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void everyEventRoutesToAnEmailTemplate(NotificationEventType type) {
        assertThat(EmailNotificationStrategy.hasTemplateFor(type)).isTrue();
        assertThat(EmailNotificationStrategy.hasTemplateFor(schemaChangeCtx(type))).isTrue();
    }

    @Test
    void failedTemplateBranchesOnPartialApplyAndShowsTheError() {
        var engine = buildEngine();
        var partial = templateContext(Locale.ENGLISH);
        assertThat(engine.process("email/schema-change-promotion-failed", partial))
                .contains("partially changed")
                .contains("relation &quot;invoices&quot; does not exist")
                .doesNotContain("none of its statements");

        var failed = templateContext(Locale.ENGLISH);
        failed.setVariable("schemaChangeStatus", "FAILED");
        assertThat(engine.process("email/schema-change-promotion-failed", failed))
                .contains("none of its statements")
                .doesNotContain("partially changed");
    }

    @Test
    void driftTemplateCarriesTheNewFindingCountAndNoDeadCta() {
        var html = buildEngine().process("email/schema-drift-detected", templateContext(Locale.ENGLISH));

        assertThat(html).contains("billing-pipeline").contains("staging").contains(">3<")
                .doesNotContain("href=\"null\"").doesNotContain("href=\"\"");
    }

    @Test
    void promotionTemplatesNameTheChangeSetAndThePromoter() {
        var engine = buildEngine();
        for (var template : List.of("email/schema-change-promotion-submitted",
                "email/schema-change-promotion-applied")) {
            assertThat(engine.process(template, templateContext(Locale.ENGLISH)))
                    .contains("add-invoice-index").contains("Dana DBA").contains("staging");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de", "es", "fr", "hy", "ru", "zh-CN"})
    void everyTemplateRendersInEveryShippedLocale(String locale) {
        var engine = buildEngine();
        for (var template : TEMPLATES) {
            assertThat(engine.process(template, templateContext(Locale.forLanguageTag(locale))))
                    .as("template %s in locale %s", template, locale)
                    .doesNotContain("??notification.email");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de", "es", "fr", "hy", "ru", "zh-CN"})
    void subjectsFillEveryPlaceholderInEveryShippedLocale(String locale) {
        var messages = messageSource();
        var loc = Locale.forLanguageTag(locale);
        for (var key : List.of("schema_change_promotion_submitted", "schema_change_promotion_applied",
                "schema_change_promotion_failed")) {
            assertThat(messages.getMessage("notification.email.subject." + key,
                    new Object[]{"add-invoice-index", "staging"}, loc))
                    .contains("add-invoice-index").contains("staging").doesNotContain("{");
        }
        assertThat(messages.getMessage("notification.email.subject.schema_drift_detected",
                new Object[]{"billing-pipeline", "staging", 3}, loc))
                .contains("billing-pipeline").contains("staging").contains("3").doesNotContain("{");
    }

    private static void assertExtras(NotificationEventType type, String rendered) {
        switch (type) {
            case SCHEMA_DRIFT_DETECTED -> assertThat(rendered).contains("New findings").contains("3");
            case SCHEMA_CHANGE_PROMOTION_FAILED -> assertThat(rendered).contains("add-invoice-index")
                    .contains("PARTIALLY_APPLIED").contains("does not exist");
            default -> assertThat(rendered).contains("add-invoice-index").contains("dba@example.com");
        }
    }

    private static Context templateContext(Locale locale) {
        var ctx = new Context(locale);
        ctx.setVariable("datasourceName", "billing-pipeline");
        ctx.setVariable("environmentName", "staging");
        ctx.setVariable("schemaChangeSetName", "add-invoice-index");
        ctx.setVariable("schemaChangeStatus", "PARTIALLY_APPLIED");
        ctx.setVariable("schemaChangeErrorMessage", "ERROR: relation \"invoices\" does not exist");
        ctx.setVariable("driftNewFindingCount", 3);
        ctx.setVariable("submitterEmail", "dba@example.com");
        ctx.setVariable("submitterDisplayName", "Dana DBA");
        ctx.setVariable("reviewUrl", null);
        return ctx;
    }

    private static ReloadableResourceBundleMessageSource messageSource() {
        var messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:i18n/messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private static SpringTemplateEngine buildEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        var springResolver = new SpringMessageResolver();
        springResolver.setMessageSource(messageSource());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageResolvers(Set.of(springResolver, new StandardMessageResolver()));
        return engine;
    }
}
