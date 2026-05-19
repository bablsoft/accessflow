package com.bablsoft.accessflow.notifications.internal.strategy;

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
 * Renders the email templates through a standalone Thymeleaf engine wired against the real
 * {@code i18n/messages*.properties} bundle to verify the new REVIEW_TIMEOUT template renders
 * with the correct heading, duration, and CTA, and that the migration of the existing
 * templates to {@code #{...}} keys did not break their rendering pipeline. No Spring context.
 */
class EmailTemplateRenderingTest {

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

    @Test
    void reviewTimeoutTemplateRendersHeadingDurationAndCta() {
        var engine = buildEngine();
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("sqlPreview", "SELECT 1");
        ctx.setVariable("approvalTimeoutHours", 24);
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-review-timeout", ctx);

        assertThat(html)
                .contains("Your query was automatically rejected")
                .contains("Production")
                .contains("24 hours")
                .contains("Open in AccessFlow")
                .contains("https://app.example.com/queries/abc");
    }

    @Test
    void reviewTimeoutTemplateLocalizesHeadingForSpanish() {
        var engine = buildEngine();
        var ctx = new Context(Locale.forLanguageTag("es"));
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("sqlPreview", "SELECT 1");
        ctx.setVariable("approvalTimeoutHours", 12);
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-review-timeout", ctx);

        assertThat(html).contains("Su consulta fue rechazada automáticamente");
    }

    @Test
    void reviewTimeoutTemplateOmitsBodyWhenApprovalTimeoutHoursMissing() {
        var engine = buildEngine();
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("sqlPreview", "SELECT 1");
        ctx.setVariable("approvalTimeoutHours", null);
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-review-timeout", ctx);

        assertThat(html).doesNotContain("hours, so AccessFlow");
    }

    @Test
    void existingRejectedTemplateStillRendersAfterI18nMigration() {
        var engine = buildEngine();
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("reviewerDisplayName", "Bob");
        ctx.setVariable("reviewerComment", "too risky");
        ctx.setVariable("sqlPreview", "DROP TABLE x");
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-rejected", ctx);

        assertThat(html)
                .contains("Your query was rejected")
                .contains("Rejected by:")
                .contains("Bob")
                .contains("too risky")
                .contains("Open in AccessFlow");
    }

    @Test
    void existingApprovedTemplateRendersAutoApprovedFallbackInLocale() {
        var engine = buildEngine();
        var ctx = new Context(Locale.forLanguageTag("es"));
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("reviewerDisplayName", null);
        ctx.setVariable("sqlPreview", "SELECT 1");
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-approved", ctx);

        assertThat(html)
                .contains("Su consulta fue aprobada")
                .contains("auto-aprobada");
    }

    @Test
    void readyForReviewTemplateRendersRiskBadgeAndScore() {
        var engine = buildEngine();
        var ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("datasourceName", "Production");
        ctx.setVariable("submitterDisplayName", "Alice");
        ctx.setVariable("submitterEmail", "alice@example.com");
        ctx.setVariable("queryType", "UPDATE");
        ctx.setVariable("riskLevel", "HIGH");
        ctx.setVariable("riskScore", 85);
        ctx.setVariable("aiSummary", "Touches PII column");
        ctx.setVariable("sqlPreview", "UPDATE users SET ...");
        ctx.setVariable("reviewUrl", "https://app.example.com/queries/abc");

        var html = engine.process("email/query-ready-for-review", ctx);

        assertThat(html)
                .contains("Query awaiting your review")
                .contains("AI risk:")
                .contains("HIGH")
                .contains("(score 85)")
                .contains("Review in AccessFlow");
    }
}
