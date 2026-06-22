package com.bablsoft.accessflow.notifications.api;

import java.util.UUID;

/**
 * Stores and removes per-user Web Push subscriptions and exposes the deployment VAPID public key
 * the browser needs to subscribe (AF-444). Subscriptions are per device/browser; a user may hold
 * several. Pure {@code api} type — primitives and project types only.
 */
public interface PushSubscriptionService {

    /** Stores (or refreshes) a subscription, keyed by its unique endpoint. */
    void subscribe(PushSubscriptionCommand command);

    /** Removes the subscription with this endpoint, if it belongs to the user. */
    void unsubscribe(UUID userId, String endpoint);

    /** The base64url-encoded VAPID public key the frontend passes to {@code pushManager.subscribe}. */
    String vapidPublicKey();

    record PushSubscriptionCommand(UUID userId, UUID organizationId, String endpoint,
                                   String p256dhKey, String authKey, String userAgent) {
    }
}
