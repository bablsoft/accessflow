package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.DataBudgetNotice;
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
 * Covers the #942 data-budget fan-out on every channel: both events render a type-specific title
 * and the budget field set, never the generic query fields or a default branch — PagerDuty's
 * summary, both silent email switches and the ticket headline included.
 */
class DataBudgetNotificationTest {

    private static final String ALL = "DATA_BUDGET_.*";

    private static final List<String> TEMPLATES = List.of(
            "email/data-budget-threshold-reached",
            "email/data-budget-exhausted");

    private final JsonMapper json = JsonMapper.builder().build();

    /** A fully populated context for one data-budget event, as the context builder shapes it. */
    static NotificationContext dataBudgetCtx(NotificationEventType type) {
        var exhausted = type == NotificationEventType.DATA_BUDGET_EXHAUSTED;
        return new NotificationContext(
                type, UUID.randomUUID(), null,
                null, null, null, null,
                null, null, null,
                UUID.randomUUID(), "warehouse",
                UUID.randomUUID(), "ana@example.com", "Ana Analyst",
                null,
                null, null, null,
                URI.create("https://app.example.test/editor"),
                List.of(new RecipientView(UUID.randomUUID(), "rcpt@example.com", "R")),
                Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null,
                new DataBudgetNotice(UUID.randomUUID(), "daily-reads", exhausted, 80,
                        exhausted ? 100 : 85, 1000L, 5_000_000_000L, exhausted ? 1000L : 850L,
                        1_200_000_000L, 1440, DataBudgetBreachAction.REQUIRE_REVIEW));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void slackRendersADataBudgetTitleAndFieldSet(NotificationEventType type) {
        var payload = new SlackBlockKitFactory().buildEventPayload(dataBudgetCtx(type), null);
        var text = payload.getText() + "\n" + payload.getBlocks().toString();

        assertThat(text).contains("Data Budget").contains("warehouse").contains("daily-reads")
                .contains("ana@example.com").doesNotContain("Submitted by").doesNotContain(type.name());
        assertExtras(type, text);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void discordRendersADataBudgetTitleAndFieldSet(NotificationEventType type) {
        var body = new DiscordPayloadFactory(json).buildEventBody(dataBudgetCtx(type),
                new DiscordChannelConfig(URI.create("https://discord.example/hook"), null, null));

        assertThat(body).contains("Data Budget").contains("warehouse").contains("daily-reads")
                .contains("https://app.example.test/editor").doesNotContain("\"Submitted by\"");
        assertExtras(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void teamsRendersADataBudgetTitleAndFieldSet(NotificationEventType type) {
        var body = new MsTeamsPayloadFactory(json).buildEventBody(dataBudgetCtx(type));

        assertThat(body).contains("Data Budget").contains("warehouse").contains("daily-reads")
                .contains("Action.OpenUrl").doesNotContain("\"Submitted by\"");
        assertExtras(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void telegramRendersADataBudgetTitleAndFieldSet(NotificationEventType type) {
        var body = new TelegramMessageFactory(json).buildEventBody(dataBudgetCtx(type), "42").replace("\\\\", "");

        assertThat(body).contains("Data Budget").contains("warehouse").contains("daily-reads")
                .contains("Open the query editor").doesNotContain("Submitted by");
        assertExtras(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void pagerDutyHasASpecificSummaryEvenThoughNothingPages(NotificationEventType type) {
        var body = new PagerDutyPayloadFactory(json).buildEventBody(dataBudgetCtx(type),
                new PagerDutyChannelConfig("KEY", PagerDutySeverity.WARNING, EnumSet.allOf(PagerDutyTrigger.class)));

        assertThat(body).contains("data budget").contains("on warehouse")
                .contains("\"budget_name\":\"daily-reads\"").doesNotContain("for a query");
        assertThat(PagerDutyTrigger.forEvent(type)).as("%s must not page", type).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void webhookCarriesTheAdditiveDataBudgetBlock(NotificationEventType type) {
        var ctx = dataBudgetCtx(type);
        var tree = json.readTree(new WebhookPayloadFactory(json).buildBody(ctx));

        assertThat(tree.path("event").asString()).isEqualTo(type.name());
        var block = tree.path("data_budget");
        assertThat(block.isObject()).isTrue();
        assertThat(block.path("budget_name").asString()).isEqualTo("daily-reads");
        assertThat(block.path("datasource_id").asString()).isEqualTo(ctx.datasourceId().toString());
        assertThat(block.path("user_id").asString()).isEqualTo(ctx.submittedByUserId().toString());
        assertThat(block.path("exhausted").asBoolean())
                .isEqualTo(type == NotificationEventType.DATA_BUDGET_EXHAUSTED);
        assertThat(block.path("window_minutes").asInt()).isEqualTo(1440);
        assertThat(block.path("breach_action").asString()).isEqualTo("REQUIRE_REVIEW");
        assertThat(tree.has("query_request")).isTrue();
        assertThat(tree.has("schema_change")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void ticketHeadlineIsSpecificAndNothingTickets(NotificationEventType type) {
        assertThat(TicketDescriptionBuilder.summary(dataBudgetCtx(type)))
                .contains("Data budget").contains("on warehouse")
                .doesNotContain(type.name());
        assertThat(TicketingTrigger.forEvent(type)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void everyEventRoutesToAnEmailTemplate(NotificationEventType type) {
        assertThat(EmailNotificationStrategy.hasTemplateFor(type)).isTrue();
        assertThat(EmailNotificationStrategy.hasTemplateFor(dataBudgetCtx(type))).isTrue();
    }

    @Test
    void thresholdTemplateShowsUsageThresholdAndCta() {
        var html = buildEngine().process("email/data-budget-threshold-reached", templateContext(Locale.ENGLISH));

        assertThat(html).contains("warehouse").contains("daily-reads").contains("85%")
                .contains("850 / 1000").contains("1.2 GB").contains("1 day").contains("80%")
                .contains("Ana Analyst").contains("https://app.example.test/editor")
                .doesNotContain("When used up");
    }

    @Test
    void exhaustedTemplateShowsTheBreachActionAndOmitsUnsetLimits() {
        var ctx = templateContext(Locale.ENGLISH);
        ctx.setVariable("dataBudgetMaxRows", null);
        ctx.setVariable("dataBudgetWindowUnit", "HOURS");
        ctx.setVariable("dataBudgetWindowValue", 3);
        ctx.setVariable("reviewUrl", null);

        var html = buildEngine().process("email/data-budget-exhausted", ctx);

        assertThat(html).contains("Further reads go to human review").contains("3 hours")
                .doesNotContain("Rows:").doesNotContain("href=\"null\"");
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
        var args = new Object[]{"daily-reads", "warehouse", 85};
        assertThat(messages.getMessage("notification.email.subject.data_budget_threshold_reached", args, loc))
                .contains("daily-reads").contains("warehouse").contains("85").doesNotContain("{");
        assertThat(messages.getMessage("notification.email.subject.data_budget_exhausted", args, loc))
                .contains("daily-reads").contains("warehouse").doesNotContain("{");
    }

    private static void assertExtras(NotificationEventType type, String rendered) {
        assertThat(rendered).contains("850 / 1000 rows".replace("850", type == NotificationEventType.DATA_BUDGET_EXHAUSTED
                ? "1000" : "850")).contains("1 day");
        if (type == NotificationEventType.DATA_BUDGET_EXHAUSTED) {
            assertThat(rendered).contains("REQUIRE_REVIEW").contains("100%");
        } else {
            assertThat(rendered).doesNotContain("REQUIRE_REVIEW").contains("85%");
        }
    }

    private static Context templateContext(Locale locale) {
        var ctx = new Context(locale);
        ctx.setVariable("datasourceName", "warehouse");
        ctx.setVariable("submitterEmail", "ana@example.com");
        ctx.setVariable("submitterDisplayName", "Ana Analyst");
        ctx.setVariable("dataBudgetName", "daily-reads");
        ctx.setVariable("dataBudgetUsedPercent", 85);
        ctx.setVariable("dataBudgetWarnThresholdPercent", 80);
        ctx.setVariable("dataBudgetUsedRows", 850L);
        ctx.setVariable("dataBudgetMaxRows", 1000L);
        ctx.setVariable("dataBudgetBytesUsage", "1.2 GB (1200000000 B) / 5 GB (5000000000 B)");
        ctx.setVariable("dataBudgetWindowUnit", "DAYS");
        ctx.setVariable("dataBudgetWindowValue", 1);
        ctx.setVariable("dataBudgetBreachAction", "REQUIRE_REVIEW");
        ctx.setVariable("reviewUrl", "https://app.example.test/editor");
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
