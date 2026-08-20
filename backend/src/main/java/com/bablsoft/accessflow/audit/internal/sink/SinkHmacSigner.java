package com.bablsoft.accessflow.audit.internal.sink;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signer for HTTPS_BATCH deliveries — the same contract as webhook notifications
 * ({@code X-AccessFlow-Signature: sha256=<hex>}). Deliberately a local copy of the
 * notifications module's package-private signer: audit is the module-graph sink and must not
 * depend on notifications for twenty lines.
 */
final class SinkHmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private SinkHmacSigner() {
    }

    /** Returns the lowercase hex HMAC-SHA256 of {@code body} keyed by {@code secret}. */
    static String sha256Hex(byte[] body, String secret) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (secret == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }
}
