package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Produces the value of one connector variable from its already-rendered input (AF-613).
 *
 * <p>Pure with respect to configuration — the only ambient inputs are the clock and a secure random
 * source, both injected so the time- and randomness-dependent kinds are testable. There is no
 * scripting engine and no expression language here: a variable is a fixed function over a template
 * substitution, deliberately mirroring how the engine plugins reject server-side scripting
 * ({@code $where}, Painless, CQL UDFs).
 */
@Component
class ApiVariableEvaluator {

    private static final int DEFAULT_RANDOM_BYTES = 16;
    private static final int MIN_RANDOM_BYTES = 1;
    private static final int MAX_RANDOM_BYTES = 256;

    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    ApiVariableEvaluator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param name       the variable name, used only for error reporting
     * @param input      the expression after template rendering
     * @param secret     the decrypted shared key, for {@link ApiVariableKind#HMAC} only
     */
    String evaluate(String name, ApiVariableKind kind, ApiVariableAlgorithm algorithm,
                    ApiVariableEncoding encoding, String input, String secret) {
        return switch (kind) {
            case CONSTANT -> input == null ? "" : input;
            case UUID -> UUID.randomUUID().toString();
            case TIMESTAMP -> timestamp(name, input);
            case EPOCH_MILLIS -> Long.toString(clock.instant().toEpochMilli());
            case RANDOM_HEX -> randomBytes(name, input, encoding);
            case HASH -> encode(digest(name, algorithm, input), encoding, ApiVariableEncoding.HEX);
            case HMAC -> encode(mac(name, algorithm, input, secret), encoding, ApiVariableEncoding.HEX);
            case ENCODE -> encode(utf8(input), encoding, ApiVariableEncoding.BASE64);
        };
    }

    private String timestamp(String name, String pattern) {
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (pattern == null || pattern.isBlank()) {
            return DateTimeFormatter.ISO_INSTANT.format(now);
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(now);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_timestamp_pattern", name);
        }
    }

    private String randomBytes(String name, String size, ApiVariableEncoding encoding) {
        var count = DEFAULT_RANDOM_BYTES;
        if (size != null && !size.isBlank()) {
            try {
                count = Integer.parseInt(size.trim());
            } catch (NumberFormatException ex) {
                throw new ApiVariableEvaluationException("error.api_connector_variable_random_hex_size",
                        name, MIN_RANDOM_BYTES, MAX_RANDOM_BYTES);
            }
        }
        if (count < MIN_RANDOM_BYTES || count > MAX_RANDOM_BYTES) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_random_hex_size",
                    name, MIN_RANDOM_BYTES, MAX_RANDOM_BYTES);
        }
        var bytes = new byte[count];
        secureRandom.nextBytes(bytes);
        return encode(bytes, encoding, ApiVariableEncoding.HEX);
    }

    private byte[] digest(String name, ApiVariableAlgorithm algorithm, String input) {
        // MD5 is offered only for legacy vendor contracts that mandate it; it is rejected for HMAC
        // in the admin service, so it can never back a signature here.
        var jca = switch (algorithm) {
            case SHA256 -> "SHA-256";
            case MD5 -> "MD5";
            case HMAC_SHA256, HMAC_SHA512 -> null;
            case null -> null;
        };
        if (jca == null) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_algorithm_invalid", name);
        }
        try {
            return MessageDigest.getInstance(jca).digest(utf8(input));
        } catch (NoSuchAlgorithmException ex) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_algorithm_invalid", name);
        }
    }

    private byte[] mac(String name, ApiVariableAlgorithm algorithm, String input, String secret) {
        var jca = switch (algorithm) {
            case HMAC_SHA256 -> "HmacSHA256";
            case HMAC_SHA512 -> "HmacSHA512";
            case SHA256, MD5 -> null;
            case null -> null;
        };
        if (jca == null) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_algorithm_invalid", name);
        }
        if (secret == null || secret.isEmpty()) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_secret_required", name);
        }
        try {
            var mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), jca));
            return mac.doFinal(utf8(input));
        } catch (GeneralSecurityException ex) {
            throw new ApiVariableEvaluationException("error.api_connector_variable_algorithm_invalid", name);
        }
    }

    private static String encode(byte[] bytes, ApiVariableEncoding encoding, ApiVariableEncoding fallback) {
        return switch (encoding == null ? fallback : encoding) {
            case HEX -> HexFormat.of().formatHex(bytes);
            case BASE64 -> Base64.getEncoder().encodeToString(bytes);
            // Unpadded, matching RFC 7515 — the shape vendors actually specify.
            case BASE64URL -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        };
    }

    private static byte[] utf8(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }
}
