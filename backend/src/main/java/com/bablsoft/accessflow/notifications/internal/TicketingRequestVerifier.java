package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.TicketingProperties;
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
 * Verifies the {@code X-AccessFlow-Signature} HMAC on inbound ticketing status callbacks
 * (ServiceNow / Jira, AF-453): base string {@code v1:{timestamp}:{rawBody}}, HMAC-SHA256 keyed by
 * the channel's {@code webhook_secret}, hex-encoded with a {@code sha256=} prefix, compared in
 * constant time. Stale timestamps (outside the configured tolerance) are rejected to bound replay.
 */
@Component
@RequiredArgsConstructor
public class TicketingRequestVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String BASE_VERSION = "v1";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final TicketingProperties properties;

    /**
     * @return {@code true} when the signature is present, the timestamp is within tolerance of
     *     {@code now}, and the HMAC matches; {@code false} otherwise.
     */
    public boolean isValid(String rawBody, String timestampHeader, String signatureHeader,
                           String webhookSecret, Instant now) {
        if (rawBody == null || isBlank(timestampHeader) || isBlank(signatureHeader)
                || isBlank(webhookSecret)) {
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
        var basestring = BASE_VERSION + ":" + timestamp + ":" + rawBody;
        var expected = SIGNATURE_PREFIX + hmacHex(basestring, webhookSecret);
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
