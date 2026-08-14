package com.bablsoft.accessflow.scim.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Raw-token generation and SHA-256 hashing for SCIM bearer tokens (#621). A deliberate small
 * sibling of {@code security.internal.apikey.ApiKeyHasher} — that class is module-private to the
 * security module and cannot be imported here. 32 bytes of {@link SecureRandom} entropy make the
 * unsalted hash safe to look up directly (same reasoning as API keys, docs/07-security.md).
 */
final class ScimTokenHasher {

    static final String PREFIX = "af_scim_";
    static final int PREFIX_LENGTH = 12;
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ScimTokenHasher() {
    }

    static String generate() {
        var bytes = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    static String prefixOf(String rawToken) {
        if (rawToken == null || rawToken.length() < PREFIX_LENGTH) {
            return rawToken == null ? "" : rawToken;
        }
        return rawToken.substring(0, PREFIX_LENGTH);
    }

    static boolean hasExpectedShape(String rawToken) {
        return rawToken != null && rawToken.startsWith(PREFIX)
                && rawToken.length() > PREFIX.length();
    }
}
