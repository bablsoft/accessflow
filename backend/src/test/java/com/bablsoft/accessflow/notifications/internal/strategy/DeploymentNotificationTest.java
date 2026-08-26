package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the #695 deployment-event fan-out sites the compiler cannot: the five Thymeleaf
 * templates (in every shipped locale), and the two trigger registries — only
 * {@code DEPLOYMENT_BREAK_GLASS_EXECUTED} may page, and no deployment event may open a ticket.
 * Chat-factory bodies are pinned per-factory in the {@code *FactoryTest} classes.
 */
class DeploymentNotificationTest {

    private static final List<NotificationEventType> DEPLOYMENT_EVENTS = List.of(
            NotificationEventType.DEPLOYMENT_SUBMITTED,
            NotificationEventType.DEPLOYMENT_APPROVED,
            NotificationEventType.DEPLOYMENT_REJECTED,
            NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
            NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED);

    private static final List<String> TEMPLATES = List.of(
            "email/deployment-pending-review",
            "email/deployment-approved",
            "email/deployment-rejected",
            "email/deployment-outcome-failed",
            "email/deployment-break-glass-executed");

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

    private static Context templateContext(Locale locale) {
        var ctx = new Context(locale);
        ctx.setVariable("datasourceName", "payments-pipeline");
        ctx.setVariable("environmentName", "production");
        ctx.setVariable("deploymentVersion", "2.4.1");
        ctx.setVariable("deploymentRequestId", UUID.randomUUID());
        ctx.setVariable("deploymentOutcome", "ROLLED_BACK");
        ctx.setVariable("deploymentDecisionReason", "review_timeout");
        ctx.setVariable("submitterEmail", "dev@example.com");
        ctx.setVariable("submitterDisplayName", "Dev");
        ctx.setVariable("justification", "hotfix for incident 42");
        ctx.setVariable("aiSummary", "Low-risk configuration change");
        ctx.setVariable("reviewUrl", null);
        return ctx;
    }

    @Test
    void pendingReviewTemplateRendersPipelineEnvironmentVersionAndSubmitter() {
        var html = buildEngine().process("email/deployment-pending-review",
                templateContext(Locale.ENGLISH));

        assertThat(html)
                .contains("payments-pipeline")
                .contains("production")
                .contains("2.4.1")
                .contains("Dev")
                .contains("hotfix for incident 42")
                .contains("Low-risk configuration change");
    }

    @Test
    void rejectedTemplateNamesTheTimeoutOnlyForTimeoutRejections() {
        var engine = buildEngine();
        var timedOut = engine.process("email/deployment-rejected", templateContext(Locale.ENGLISH));
        assertThat(timedOut).contains("timed out");

        var reviewerRejected = templateContext(Locale.ENGLISH);
        reviewerRejected.setVariable("deploymentDecisionReason", null);
        assertThat(engine.process("email/deployment-rejected", reviewerRejected))
                .doesNotContain("timed out");
    }

    @Test
    void outcomeFailedTemplateBranchesOnTheOutcome() {
        var engine = buildEngine();
        assertThat(engine.process("email/deployment-outcome-failed",
                templateContext(Locale.ENGLISH)))
                .contains("rolled back")
                .doesNotContain("as failed");

        var failed = templateContext(Locale.ENGLISH);
        failed.setVariable("deploymentOutcome", "FAILED");
        assertThat(engine.process("email/deployment-outcome-failed", failed))
                .contains("as failed")
                .doesNotContain("rolled back");
    }

    @Test
    void breakGlassTemplateCarriesTheJustificationAndNoDeadCta() {
        var html = buildEngine().process("email/deployment-break-glass-executed",
                templateContext(Locale.ENGLISH));

        assertThat(html)
                .contains("hotfix for incident 42")
                .contains("retrospective")
                // reviewUrl is null for deployments — the CTA must be guarded, not a dead link.
                .doesNotContain("href=\"\"")
                .doesNotContain("href=\"null\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "es", "fr", "hy", "ru", "zh-CN"})
    void everyTemplateRendersInEveryShippedLocale(String locale) {
        var engine = buildEngine();
        for (var template : TEMPLATES) {
            var html = engine.process(template, templateContext(Locale.forLanguageTag(locale)));
            assertThat(html)
                    .as("template %s in locale %s renders without an unresolved message key",
                            template, locale)
                    .doesNotContain("??notification.email");
        }
    }

    /** Only a break-glass release is an incident; routine lifecycle progress never pages. */
    @Test
    void onlyBreakGlassPages() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED))
                .contains(PagerDutyTrigger.BREAK_GLASS);
        for (var event : DEPLOYMENT_EVENTS) {
            if (event != NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED) {
                assertThat(PagerDutyTrigger.forEvent(event))
                        .as("%s must not page", event)
                        .isEmpty();
            }
        }
    }

    /** No deployment event opens a ticket — see the PR notes: queries never ticket on submission
     *  either (TicketingTrigger is rejection/timeout/escalation only), and the ticketing strategy
     *  hard-skips non-query contexts. */
    @ParameterizedTest
    @EnumSource(names = {"DEPLOYMENT_SUBMITTED", "DEPLOYMENT_APPROVED", "DEPLOYMENT_REJECTED",
            "DEPLOYMENT_OUTCOME_FAILED", "DEPLOYMENT_BREAK_GLASS_EXECUTED"})
    void neverOpensATicket(NotificationEventType event) {
        assertThat(TicketingTrigger.forEvent(event)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(names = {"DEPLOYMENT_SUBMITTED", "DEPLOYMENT_APPROVED", "DEPLOYMENT_REJECTED",
            "DEPLOYMENT_OUTCOME_FAILED", "DEPLOYMENT_BREAK_GLASS_EXECUTED"})
    void everyDeploymentEventRoutesToItsOwnEmailTemplate(NotificationEventType event) {
        assertThat(EmailNotificationStrategy.hasTemplateFor(event)).isTrue();
    }
}
