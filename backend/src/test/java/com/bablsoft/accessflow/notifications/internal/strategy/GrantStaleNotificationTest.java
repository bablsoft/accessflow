package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
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

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the #625 GRANT_STALE fan-out sites the compiler cannot: the Thymeleaf template, and the two
 * trigger registries whose {@code default} branches would otherwise let the event page an on-call
 * engineer or open a ticket without anyone noticing.
 */
class GrantStaleNotificationTest {

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

    private static Context context(Long daysSinceLastUse) {
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("submitterEmail", "dev@example.com");
        ctx.setVariable("datasourceName", "analytics-prod");
        ctx.setVariable("grantResourceKind", "DATASOURCE");
        ctx.setVariable("grantDaysSinceLastUse", daysSinceLastUse);
        ctx.setVariable("grantRecommendation", "STALE");
        ctx.setVariable("reviewUrl", "https://app.example.com/admin/over-provisioned-access");
        return ctx;
    }

    @Test
    void templateRendersTheGrantHolderResourceAndIdleDays() {
        var html = buildEngine().process("email/grant-stale", context(94L));

        assertThat(html)
                .contains("An access grant looks unused")
                .contains("dev@example.com")
                .contains("analytics-prod")
                .contains("Datasource")
                .doesNotContain("DATASOURCE")
                .contains("Days since last use:")
                .contains("94")
                .contains("Stale")
                .doesNotContain(">STALE<")
                .contains("https://app.example.com/admin/over-provisioned-access");
    }

    /**
     * "Never used" must read as a sentence, not as "0 days" — a zero would be indistinguishable from
     * "used today", which is the opposite conclusion.
     */
    @Test
    void templateRendersNeverUsedAsASentenceNotZeroDays() {
        var html = buildEngine().process("email/grant-stale", context(null));

        assertThat(html)
                .contains("This grant has never been used.")
                .doesNotContain("Days since last use:");
    }

    @Test
    void templateRendersInEveryShippedLocale() {
        var engine = buildEngine();
        for (var locale : new String[]{"de", "es", "fr", "hy", "ru", "zh-CN"}) {
            var ctx = context(31L);
            var localised = new Context(Locale.forLanguageTag(locale));
            ctx.getVariableNames().forEach(n -> localised.setVariable(n, ctx.getVariable(n)));
            var html = engine.process("email/grant-stale", localised);
            assertThat(html)
                    .as("locale %s renders without an unresolved message key", locale)
                    .doesNotContain("??notification.email.grant_stale");
        }
    }

    /** A stale grant is a hygiene finding, not an outage — it must never page an on-call engineer. */
    @Test
    void neverPages() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.GRANT_STALE)).isEmpty();
    }

    /** Nor open a ticket: nothing is broken and nothing needs a work item. */
    @Test
    void neverOpensATicket() {
        assertThat(TicketingTrigger.forEvent(NotificationEventType.GRANT_STALE)).isEmpty();
    }

    @Test
    void routesToItsOwnEmailTemplate() {
        assertThat(EmailNotificationStrategy.hasTemplateFor(NotificationEventType.GRANT_STALE))
                .isTrue();
    }
}
