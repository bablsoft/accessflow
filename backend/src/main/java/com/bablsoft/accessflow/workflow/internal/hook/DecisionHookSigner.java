package com.bablsoft.accessflow.workflow.internal.hook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * HMAC-SHA256 over the decision hook's request and response bodies (#945), in the
 * {@code X-AccessFlow-Signature: sha256=<hex>} format notification webhooks already use. A local
 * copy, like the audit module's, so workflow does not depend on notifications for twenty lines.
 */
final class DecisionHookSigner {

    static final String HEADER = "X-AccessFlow-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private DecisionHookSigner() {
    }

    /** The full header value, {@code sha256=<lowercase hex>}. */
    static String sign(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    /** Constant-time check of a received header value; {@code false} when it is absent. */
    static boolean verify(byte[] body, String secret, String headerValue) {
        if (headerValue == null) {
            return false;
        }
        var expected = sign(body, secret).getBytes(StandardCharsets.US_ASCII);
        var actual = headerValue.trim().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
