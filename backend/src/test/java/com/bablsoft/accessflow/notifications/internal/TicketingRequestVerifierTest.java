package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.TicketingProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class TicketingRequestVerifierTest {

    private static final String SECRET = "ticketing-shared-secret";
    private static final String BODY = "{\"external_id\":\"abc\",\"status\":\"Resolved\"}";

    private final TicketingRequestVerifier verifier =
            new TicketingRequestVerifier(new TicketingProperties(Duration.ofMinutes(5)));

    @Test
    void acceptsValidSignatureWithinWindow() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        assertThat(verifier.isValid(BODY, ts, sign(ts, BODY), SECRET, now)).isTrue();
    }

    @Test
    void acceptsTimestampHeaderWithSurroundingWhitespace() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        assertThat(verifier.isValid(BODY, " " + ts + " ", sign(ts, BODY), SECRET, now)).isTrue();
    }

    @Test
    void rejectsMissingHeaders() {
        var now = Instant.now();
        assertThat(verifier.isValid(BODY, null, "sha256=abc", SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", null, SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "  ", "sha256=abc", SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", "  ", SECRET, now)).isFalse();
    }

    @Test
    void rejectsNullBodyOrBlankSecret() {
        var now = Instant.now();
        assertThat(verifier.isValid(null, "123", "sha256=abc", SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", "sha256=abc", null, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", "sha256=abc", " ", now)).isFalse();
    }

    @Test
    void rejectsNonNumericTimestamp() {
        var now = Instant.now();
        assertThat(verifier.isValid(BODY, "not-a-number", "sha256=abc", SECRET, now)).isFalse();
    }

    @Test
    void rejectsStaleTimestampBeyondTolerance() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var staleTs = Long.toString(now.minus(Duration.ofMinutes(6)).getEpochSecond());
        assertThat(verifier.isValid(BODY, staleTs, sign(staleTs, BODY), SECRET, now)).isFalse();
    }

    @Test
    void rejectsFutureTimestampBeyondTolerance() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var futureTs = Long.toString(now.plus(Duration.ofMinutes(6)).getEpochSecond());
        assertThat(verifier.isValid(BODY, futureTs, sign(futureTs, BODY), SECRET, now)).isFalse();
    }

    @Test
    void rejectsBadSignature() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        assertThat(verifier.isValid(BODY, ts, "sha256=deadbeef", SECRET, now)).isFalse();
    }

    @Test
    void rejectsSignatureComputedWithDifferentSecret() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        var signedWithOtherKey = signWith(ts, BODY, "another-secret");
        assertThat(verifier.isValid(BODY, ts, signedWithOtherKey, SECRET, now)).isFalse();
    }

    @Test
    void rejectsWhenBodyTamperedAfterSigning() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        var signature = sign(ts, BODY);
        assertThat(verifier.isValid(BODY + " ", ts, signature, SECRET, now)).isFalse();
    }

    @Test
    void rejectsSignatureWithoutSha256Prefix() {
        var now = Instant.parse("2026-07-17T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        var withoutPrefix = sign(ts, BODY).substring("sha256=".length());
        assertThat(verifier.isValid(BODY, ts, withoutPrefix, SECRET, now)).isFalse();
    }

    private static String sign(String ts, String body) {
        return signWith(ts, body, SECRET);
    }

    private static String signWith(String ts, String body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var basestring = "v1:" + ts + ":" + body;
            return "sha256=" + HexFormat.of()
                    .formatHex(mac.doFinal(basestring.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
