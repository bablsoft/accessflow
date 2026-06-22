package com.bablsoft.accessflow.notifications.internal.push;

import java.util.UUID;

/**
 * The renderable payload of a one-tap review push (AF-444). The service worker reads {@code title}
 * / {@code body} for the notification, {@code data.url} for the deep link the
 * {@code notificationclick} handler opens, and renders the approve/reject action buttons.
 */
public record WebPushMessage(String title, String body, String url, UUID queryId) {
}
