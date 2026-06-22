package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import com.bablsoft.accessflow.notifications.internal.push.WebPushMessage;
import com.bablsoft.accessflow.notifications.internal.push.WebPushSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Delivers a one-tap Web Push to every eligible reviewer when a query becomes ready for review
 * (AF-444). Runs alongside the channel-based {@code NotificationDispatcher} but on the per-user
 * subscription model: it resolves the same recipients the {@code QUERY_SUBMITTED} channel
 * notification would target, looks up each recipient's stored subscriptions, and pushes a deep
 * link to the {@code /reviews/{id}/decide} step-up landing. Entirely best-effort — any failure is
 * logged and never affects the workflow transition.
 *
 * <p>Lives in {@code notifications.internal} (not {@code .push}) so it can reach the package-private
 * {@link NotificationContextBuilder} that already encapsulates recipient resolution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class WebPushNotificationListener {

    private final NotificationContextBuilder contextBuilder;
    private final PushSubscriptionRepository subscriptionRepository;
    private final WebPushSender webPushSender;
    private final NotificationsProperties properties;
    private final MessageSource messageSource;

    @ApplicationModuleListener
    void onQueryReadyForReview(QueryReadyForReviewEvent event) {
        try {
            var context = contextBuilder
                    .build(NotificationEventType.QUERY_SUBMITTED, event.queryRequestId(), null, null, null)
                    .orElse(null);
            if (context == null || context.recipients().isEmpty()) {
                return;
            }
            var recipientIds = context.recipients().stream().map(RecipientView::userId).toList();
            var subscriptions = subscriptionRepository.findByUserIdIn(recipientIds);
            if (subscriptions.isEmpty()) {
                return;
            }
            webPushSender.sendAll(subscriptions, toMessage(context));
        } catch (RuntimeException ex) {
            log.error("Web Push fan-out failed for query {}", event.queryRequestId(), ex);
        }
    }

    private WebPushMessage toMessage(NotificationContext context) {
        var locale = context.locale() != null ? Locale.forLanguageTag(context.locale()) : Locale.ENGLISH;
        var title = messageSource.getMessage("push.review_request.title",
                new Object[]{context.datasourceName()}, locale);
        var line = messageSource.getMessage("push.review_request.body",
                new Object[]{context.submitterDisplayName()}, locale);
        var body = context.sqlPreview200() != null && !context.sqlPreview200().isBlank()
                ? line + "\n" + context.sqlPreview200()
                : line;
        return new WebPushMessage(title, body, decideUrl(context.queryRequestId().toString()),
                context.queryRequestId());
    }

    private String decideUrl(String queryId) {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/reviews/" + queryId + "/decide";
    }
}
