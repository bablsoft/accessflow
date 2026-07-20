package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiVariableEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:30:45.123Z");

    private final ApiVariableEvaluator evaluator =
            new ApiVariableEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

    private String eval(ApiVariableKind kind, ApiVariableAlgorithm algorithm,
                        ApiVariableEncoding encoding, String input, String secret) {
        return evaluator.evaluate("v", kind, algorithm, encoding, input, secret);
    }

    @Nested
    class Constant {

        @Test
        void returnsInputVerbatim() {
            assertThat(eval(ApiVariableKind.CONSTANT, null, null, "v1.2", null)).isEqualTo("v1.2");
        }

        @Test
        void treatsNullInputAsEmpty() {
            assertThat(eval(ApiVariableKind.CONSTANT, null, null, null, null)).isEmpty();
        }
    }

    @Nested
    class Nonces {

        @Test
        void uuidProducesAParseableAndDistinctValueEachCall() {
            var first = eval(ApiVariableKind.UUID, null, null, null, null);
            var second = eval(ApiVariableKind.UUID, null, null, null, null);

            assertThat(java.util.UUID.fromString(first)).isNotNull();
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void randomHexDefaultsToSixteenBytes() {
            assertThat(eval(ApiVariableKind.RANDOM_HEX, null, null, null, null)).hasSize(32);
        }

        @Test
        void randomHexHonoursAnExplicitByteCount() {
            assertThat(eval(ApiVariableKind.RANDOM_HEX, null, null, "8", null)).hasSize(16);
        }

        @Test
        void randomHexHonoursTheEncoding() {
            var value = eval(ApiVariableKind.RANDOM_HEX, null, ApiVariableEncoding.BASE64URL, "16", null);

            assertThat(value).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        }

        @Test
        void randomHexDiffersBetweenCalls() {
            assertThat(eval(ApiVariableKind.RANDOM_HEX, null, null, "16", null))
                    .isNotEqualTo(eval(ApiVariableKind.RANDOM_HEX, null, null, "16", null));
        }

        @Test
        void randomHexRejectsANonNumericOrOutOfRangeSize() {
            assertThatThrownBy(() -> eval(ApiVariableKind.RANDOM_HEX, null, null, "abc", null))
                    .isInstanceOf(ApiVariableEvaluationException.class);
            assertThatThrownBy(() -> eval(ApiVariableKind.RANDOM_HEX, null, null, "0", null))
                    .isInstanceOf(ApiVariableEvaluationException.class);
            assertThatThrownBy(() -> eval(ApiVariableKind.RANDOM_HEX, null, null, "257", null))
                    .isInstanceOf(ApiVariableEvaluationException.class);
        }
    }

    @Nested
    class Timestamps {

        @Test
        void timestampDefaultsToIsoTruncatedToSeconds() {
            assertThat(eval(ApiVariableKind.TIMESTAMP, null, null, null, null))
                    .isEqualTo("2026-07-20T10:30:45Z");
        }

        @Test
        void timestampAppliesAnExplicitPatternAtUtc() {
            assertThat(eval(ApiVariableKind.TIMESTAMP, null, null, "yyyyMMdd'T'HHmmss'Z'", null))
                    .isEqualTo("20260720T103045Z");
        }

        @Test
        void timestampRejectsAnInvalidPattern() {
            assertThatThrownBy(() -> eval(ApiVariableKind.TIMESTAMP, null, null, "yyyy-QQQQQQQ", null))
                    .isInstanceOf(ApiVariableEvaluationException.class);
        }

        @Test
        void epochMillisUsesTheClock() {
            assertThat(eval(ApiVariableKind.EPOCH_MILLIS, null, null, null, null))
                    .isEqualTo(Long.toString(NOW.toEpochMilli()));
        }
    }

    @Nested
    class Hashes {

        // SHA-256("abc"), the NIST test vector.
        private static final String SHA256_ABC =
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        @Test
        void sha256MatchesTheKnownVector() {
            assertThat(eval(ApiVariableKind.HASH, ApiVariableAlgorithm.SHA256, null, "abc", null))
                    .isEqualTo(SHA256_ABC);
        }

        @Test
        void md5MatchesTheKnownVector() {
            assertThat(eval(ApiVariableKind.HASH, ApiVariableAlgorithm.MD5, null, "abc", null))
                    .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        }

        @Test
        void hashHonoursBase64Encoding() {
            var expected = java.util.Base64.getEncoder()
                    .encodeToString(java.util.HexFormat.of().parseHex(SHA256_ABC));

            assertThat(eval(ApiVariableKind.HASH, ApiVariableAlgorithm.SHA256,
                    ApiVariableEncoding.BASE64, "abc", null)).isEqualTo(expected);
        }

        @Test
        void hashRejectsAnHmacAlgorithm() {
            assertThatThrownBy(() -> eval(ApiVariableKind.HASH, ApiVariableAlgorithm.HMAC_SHA256,
                    null, "abc", null)).isInstanceOf(ApiVariableEvaluationException.class);
        }
    }

    @Nested
    class Macs {

        @Test
        void hmacSha256MatchesRfc4231TestCase2() {
            // key = "Jefe", data = "what do ya want for nothing?"
            assertThat(eval(ApiVariableKind.HMAC, ApiVariableAlgorithm.HMAC_SHA256, null,
                    "what do ya want for nothing?", "Jefe"))
                    .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        }

        @Test
        void hmacSha512MatchesRfc4231TestCase2() {
            assertThat(eval(ApiVariableKind.HMAC, ApiVariableAlgorithm.HMAC_SHA512, null,
                    "what do ya want for nothing?", "Jefe"))
                    .isEqualTo("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
                            + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737");
        }

        @Test
        void hmacRejectsAMissingSecret() {
            assertThatThrownBy(() -> eval(ApiVariableKind.HMAC, ApiVariableAlgorithm.HMAC_SHA256,
                    null, "data", null)).isInstanceOf(ApiVariableEvaluationException.class);
            assertThatThrownBy(() -> eval(ApiVariableKind.HMAC, ApiVariableAlgorithm.HMAC_SHA256,
                    null, "data", "")).isInstanceOf(ApiVariableEvaluationException.class);
        }

        @Test
        void hmacRejectsADigestAlgorithm() {
            assertThatThrownBy(() -> eval(ApiVariableKind.HMAC, ApiVariableAlgorithm.SHA256,
                    null, "data", "k")).isInstanceOf(ApiVariableEvaluationException.class);
        }

        @Test
        void hmacRejectsAMissingAlgorithm() {
            assertThatThrownBy(() -> eval(ApiVariableKind.HMAC, null, null, "data", "k"))
                    .isInstanceOf(ApiVariableEvaluationException.class);
        }
    }

    @Nested
    class Encodings {

        @Test
        void encodeProducesPaddedStandardBase64() {
            assertThat(eval(ApiVariableKind.ENCODE, null, ApiVariableEncoding.BASE64, "user:pw", null))
                    .isEqualTo("dXNlcjpwdw==");
        }

        /** RFC 7515 shape: URL-safe and unpadded, which is what vendors actually specify. */
        @Test
        void base64UrlIsUnpaddedAndUrlSafe() {
            var value = eval(ApiVariableKind.ENCODE, null, ApiVariableEncoding.BASE64URL, "user:pw", null);

            assertThat(value).isEqualTo("dXNlcjpwdw");
        }

        @Test
        void hexIsLowercase() {
            assertThat(eval(ApiVariableKind.ENCODE, null, ApiVariableEncoding.HEX, "ÿ", null))
                    .isEqualTo("c3bf");
        }
    }
}
