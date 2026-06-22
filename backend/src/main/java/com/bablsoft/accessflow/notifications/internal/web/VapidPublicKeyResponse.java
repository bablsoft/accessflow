package com.bablsoft.accessflow.notifications.internal.web;

/** The deployment VAPID public key (base64url) the browser passes to {@code pushManager.subscribe}. */
public record VapidPublicKeyResponse(String publicKey) {
}
