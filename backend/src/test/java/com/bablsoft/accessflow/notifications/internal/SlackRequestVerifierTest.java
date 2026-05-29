package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.SlackProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SlackRequestVerifierTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a5";
    private static final String BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D";

    private final SlackRequestVerifier verifier =
            new SlackRequestVerifier(new SlackProperties(Duration.ofMinutes(10), Duration.ofMinutes(5)));

    @Test
    void acceptsValidSignatureWithinWindow() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        assertThat(verifier.isValid(BODY, ts, sign(ts, BODY), SECRET, now)).isTrue();
    }

    @Test
    void rejectsMissingHeaders() {
        var now = Instant.now();
        assertThat(verifier.isValid(BODY, null, "v0=abc", SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", null, SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "  ", "v0=abc", SECRET, now)).isFalse();
    }

    @Test
    void rejectsNullBodyOrSecret() {
        var now = Instant.now();
        assertThat(verifier.isValid(null, "123", "v0=abc", SECRET, now)).isFalse();
        assertThat(verifier.isValid(BODY, "123", "v0=abc", null, now)).isFalse();
    }

    @Test
    void rejectsNonNumericTimestamp() {
        var now = Instant.now();
        assertThat(verifier.isValid(BODY, "not-a-number", "v0=abc", SECRET, now)).isFalse();
    }

    @Test
    void rejectsStaleTimestampBeyondTolerance() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var staleTs = Long.toString(now.minus(Duration.ofMinutes(6)).getEpochSecond());
        assertThat(verifier.isValid(BODY, staleTs, sign(staleTs, BODY), SECRET, now)).isFalse();
    }

    @Test
    void rejectsFutureTimestampBeyondTolerance() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var futureTs = Long.toString(now.plus(Duration.ofMinutes(6)).getEpochSecond());
        assertThat(verifier.isValid(BODY, futureTs, sign(futureTs, BODY), SECRET, now)).isFalse();
    }

    @Test
    void rejectsBadSignature() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        assertThat(verifier.isValid(BODY, ts, "v0=deadbeef", SECRET, now)).isFalse();
    }

    @Test
    void rejectsSignatureComputedWithDifferentSecret() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        var signedWithOtherKey = signWith(ts, BODY, "another-secret");
        assertThat(verifier.isValid(BODY, ts, signedWithOtherKey, SECRET, now)).isFalse();
    }

    @Test
    void rejectsWhenBodyTamperedAfterSigning() {
        var now = Instant.parse("2026-05-29T12:00:00Z");
        var ts = Long.toString(now.getEpochSecond());
        var signature = sign(ts, BODY);
        assertThat(verifier.isValid(BODY + "&tampered=1", ts, signature, SECRET, now)).isFalse();
    }

    private static String sign(String ts, String body) {
        return signWith(ts, body, SECRET);
    }

    private static String signWith(String ts, String body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var basestring = "v0:" + ts + ":" + body;
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(basestring.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
