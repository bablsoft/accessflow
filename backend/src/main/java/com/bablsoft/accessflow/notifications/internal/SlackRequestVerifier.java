package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.SlackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Slack-Signature} HMAC on inbound Slack callbacks per
 * <a href="https://api.slack.com/authentication/verifying-requests-from-slack">Slack's spec</a>:
 * base string {@code v0:{timestamp}:{rawBody}}, HMAC-SHA256 keyed by the app's signing secret,
 * hex-encoded with a {@code v0=} prefix, compared in constant time. Stale timestamps (outside the
 * configured tolerance) are rejected to bound replay.
 */
@Component
@RequiredArgsConstructor
public class SlackRequestVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v0";

    private final SlackProperties properties;

    /**
     * @return {@code true} when the signature is present, the timestamp is within tolerance of
     *     {@code now}, and the HMAC matches; {@code false} otherwise.
     */
    public boolean isValid(String rawBody, String timestampHeader, String signatureHeader,
                           String signingSecret, Instant now) {
        if (rawBody == null || isBlank(timestampHeader) || isBlank(signatureHeader)
                || isBlank(signingSecret)) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        var skew = Math.abs(now.getEpochSecond() - timestamp);
        if (skew > properties.signatureTolerance().toSeconds()) {
            return false;
        }
        var basestring = VERSION + ":" + timestamp + ":" + rawBody;
        var expected = VERSION + "=" + hmacHex(basestring, signingSecret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String data, String secret) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
