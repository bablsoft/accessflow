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
 * Covers the connector-scoped API-governance fan-out (#895) on every channel: each event renders a
 * type-specific title and the connector field set, never the generic query fields; only
 * {@code API_CONNECTOR_UNHEALTHY} pages, and none opens a ticket. The budget event (#973) rides the
 * same sweep.
 */
class ApiConnectorNotificationTest {

    private static final String ALL = "API_CONNECTOR_.*";

    private static final List<String> TEMPLATES = List.of(
            "email/api-connector-token-failed",
            "email/api-connector-drift",
            "email/api-connector-unhealthy",
            "email/api-connector-budget-exceeded");

    private final JsonMapper json = JsonMapper.builder().build();

    /** A connector context shaped exactly as {@code NotificationContextBuilder.buildApiConnector} builds it. */
    static NotificationContext connectorCtx(NotificationEventType type) {
        var detail = switch (type) {
            case API_CONNECTOR_DRIFT -> "4";
            case API_CONNECTOR_UNHEALTHY -> "Connection refused";
            case API_CONNECTOR_BUDGET_EXCEEDED -> "12.5 / 10";
            default -> null;
        };
        return new NotificationContext(
                type, UUID.randomUUID(),
                null, null, null, null, null, null, null, null,
                UUID.randomUUID(), "Stripe",
                null, null, null, null, null, null,
                detail,
                URI.create("https://af.example/api-connectors/x/settings"),
                List.of(new RecipientView(UUID.randomUUID(), "admin@example.com", "Admin")),
                Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void contextIsRecognisedAsAConnectorEvent(NotificationEventType type) {
        assertThat(connectorCtx(type).isApiConnectorEvent()).isTrue();
    }

    @Test
    void otherEventsAreNotConnectorEvents() {
        assertThat(connectorCtx(NotificationEventType.API_CONNECTOR_DRIFT).isApiConnectorEvent()).isTrue();
        var query = SchemaChangeNotificationTest.schemaChangeCtx(NotificationEventType.SCHEMA_DRIFT_DETECTED);
        assertThat(query.isApiConnectorEvent()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void slackRendersTheConnectorFieldSet(NotificationEventType type) {
        var payload = new SlackBlockKitFactory().buildEventPayload(connectorCtx(type), null);
        var text = payload.getText() + "\n" + payload.getBlocks().toString();

        assertThat(text).contains("API Connector").contains("Connector:").contains("Stripe")
                .doesNotContain("Datasource:").doesNotContain("Submitted by");
        assertDetail(type, text);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void discordRendersTheConnectorFieldSet(NotificationEventType type) {
        var body = new DiscordPayloadFactory(json).buildEventBody(connectorCtx(type),
                new DiscordChannelConfig(URI.create("https://discord.example/hook"), null, null));

        assertThat(body).contains("API Connector").contains("\"Connector\"").contains("Stripe")
                .contains("api-connectors").doesNotContain("\"Datasource\"");
        assertDetail(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void teamsRendersTheConnectorFieldSet(NotificationEventType type) {
        var body = new MsTeamsPayloadFactory(json).buildEventBody(connectorCtx(type));

        assertThat(body).contains("API Connector").contains("Stripe")
                .contains("Open connector settings").doesNotContain("\"Datasource\"");
        assertDetail(type, body);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void telegramRendersTheConnectorFieldSet(NotificationEventType type) {
        var body = new TelegramMessageFactory(json).buildEventBody(connectorCtx(type), "42");

        assertThat(body).contains("API Connector").contains("Stripe").doesNotContain("Datasource");
        assertDetail(type, body.replace("\\\\", ""));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void pagerDutyHasASpecificSummaryAndConnectorDetails(NotificationEventType type) {
        var body = new PagerDutyPayloadFactory(json).buildEventBody(connectorCtx(type),
                new PagerDutyChannelConfig("KEY", PagerDutySeverity.ERROR, EnumSet.allOf(PagerDutyTrigger.class)));

        assertThat(body).contains("connector").contains("Stripe").contains("\"connector_id\"")
                .doesNotContain("for a query");
        if (type != NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED) {
            assertThat(body).contains("\"detail\"");
        }
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void webhookCarriesTheAdditiveConnectorBlock(NotificationEventType type) {
        var ctx = connectorCtx(type);
        var tree = json.readTree(new WebhookPayloadFactory(json).buildBody(ctx));

        assertThat(tree.path("event").asString()).isEqualTo(type.name());
        var block = tree.path("api_connector");
        assertThat(block.path("connector_id").asString()).isEqualTo(ctx.datasourceId().toString());
        assertThat(block.path("connector_name").asString()).isEqualTo("Stripe");
        if (ctx.reviewerComment() != null) {
            assertThat(block.path("detail").asString()).isEqualTo(ctx.reviewerComment());
        }
        assertThat(tree.has("query_request")).isTrue();
    }

    @Test
    void webhookOmitsTheConnectorBlockForOtherEvents() {
        var ctx = SchemaChangeNotificationTest.schemaChangeCtx(NotificationEventType.SCHEMA_DRIFT_DETECTED);
        assertThat(json.readTree(new WebhookPayloadFactory(json).buildBody(ctx)).has("api_connector")).isFalse();
    }

    @Test
    void onlyUnhealthyPages() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.API_CONNECTOR_UNHEALTHY))
                .contains(PagerDutyTrigger.CONNECTOR_DOWN);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.API_CONNECTOR_DRIFT)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.API_CONNECTOR_BUDGET_EXCEEDED)).isEmpty();
    }

    @Test
    void budgetTemplateShowsSpendAgainstBudgetAndRoutesToItsOwnTemplate() {
        assertThat(EmailNotificationStrategy.templateName(NotificationEventType.API_CONNECTOR_BUDGET_EXCEEDED))
                .isEqualTo("email/api-connector-budget-exceeded");
        var engine = buildEngine();
        assertThat(engine.process("email/api-connector-budget-exceeded",
                templateContext(Locale.ENGLISH, "12.5 / 10")))
                .contains("Stripe").contains("Month-to-date spend / budget:").contains("12.5 / 10")
                .contains("reached its monthly budget");
        assertThat(engine.process("email/api-connector-budget-exceeded", templateContext(Locale.ENGLISH, null)))
                .doesNotContain("Month-to-date spend / budget:");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void nothingTickets(NotificationEventType type) {
        assertThat(TicketingTrigger.forEvent(type)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationEventType.class, mode = EnumSource.Mode.MATCH_ANY, names = ALL)
    void everyEventRoutesToAnEmailTemplate(NotificationEventType type) {
        assertThat(EmailNotificationStrategy.hasTemplateFor(type)).isTrue();
    }

    @Test
    void driftTemplateShowsTheFindingCount() {
        var html = buildEngine().process("email/api-connector-drift", templateContext(Locale.ENGLISH, "4"));

        assertThat(html).contains("Stripe").contains("Drift findings:").contains(">4<")
                .contains("Review the drift");
    }

    @Test
    void unhealthyTemplateShowsTheLastErrorAndOmitsTheLineWithoutOne() {
        var engine = buildEngine();
        assertThat(engine.process("email/api-connector-unhealthy",
                templateContext(Locale.ENGLISH, "Connection refused")))
                .contains("Stripe").contains("Last error:").contains("Connection refused");
        assertThat(engine.process("email/api-connector-unhealthy", templateContext(Locale.ENGLISH, null)))
                .doesNotContain("Last error:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de", "es", "fr", "hy", "ru", "zh-CN"})
    void everyTemplateRendersInEveryShippedLocale(String locale) {
        var engine = buildEngine();
        for (var template : TEMPLATES) {
            assertThat(engine.process(template, templateContext(Locale.forLanguageTag(locale), "4")))
                    .as("template %s in locale %s", template, locale)
                    .doesNotContain("??notification.email");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de", "es", "fr", "hy", "ru", "zh-CN"})
    void subjectsFillEveryPlaceholderInEveryShippedLocale(String locale) {
        var messages = messageSource();
        var loc = Locale.forLanguageTag(locale);
        assertThat(messages.getMessage("notification.email.subject.api_connector_drift",
                new Object[]{"Stripe", "4"}, loc))
                .contains("Stripe").contains("4").doesNotContain("{");
        assertThat(messages.getMessage("notification.email.subject.api_connector_unhealthy",
                new Object[]{"Stripe"}, loc))
                .contains("Stripe").doesNotContain("{");
        assertThat(messages.getMessage("notification.email.subject.api_connector_budget_exceeded",
                new Object[]{"Stripe"}, loc))
                .contains("Stripe").doesNotContain("{");
    }

    private static void assertDetail(NotificationEventType type, String rendered) {
        switch (type) {
            case API_CONNECTOR_DRIFT -> assertThat(rendered).contains("Drift findings").contains("4");
            case API_CONNECTOR_UNHEALTHY -> assertThat(rendered).contains("Last error")
                    .contains("Connection refused");
            case API_CONNECTOR_BUDGET_EXCEEDED -> assertThat(rendered)
                    .contains("Month-to-date spend / budget").contains("12.5 / 10");
            default -> assertThat(rendered).doesNotContain("Drift findings").doesNotContain("Last error");
        }
    }

    private static Context templateContext(Locale locale, String detail) {
        var ctx = new Context(locale);
        ctx.setVariable("datasourceName", "Stripe");
        ctx.setVariable("reviewerComment", detail);
        ctx.setVariable("reviewUrl", "https://af.example/api-connectors/x/settings");
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
