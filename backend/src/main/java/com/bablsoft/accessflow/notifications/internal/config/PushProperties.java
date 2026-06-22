package com.bablsoft.accessflow.notifications.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push (VAPID) configuration (AF-444). All fields are optional: when {@code publicKey} and
 * {@code privateKey} are both supplied (base64url raw keys, e.g. from {@code web-push
 * generate-vapid-keys}) they take precedence and the operator owns the lifecycle; otherwise the
 * {@code PushVapidKeyProvider} auto-generates a keypair on first use and persists it. The
 * {@code subject} is the VAPID {@code sub} claim — a {@code mailto:} or {@code https:} contact URL.
 */
@ConfigurationProperties(prefix = "accessflow.push.vapid")
public record PushProperties(String publicKey, String privateKey, String subject) {

    public static final String DEFAULT_SUBJECT = "mailto:accessflow@localhost";

    public PushProperties {
        if (subject == null || subject.isBlank()) {
            subject = DEFAULT_SUBJECT;
        }
    }

    public boolean hasExplicitKeyPair() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
