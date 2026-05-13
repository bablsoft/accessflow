package com.bablsoft.accessflow.security.internal.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyHasher {

    public static final String PREFIX = "af_";
    public static final int PREFIX_LENGTH = 12;
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeyHasher() {
    }

    public static String generate() {
        var bytes = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public static String prefixOf(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX_LENGTH) {
            return rawKey == null ? "" : rawKey;
        }
        return rawKey.substring(0, PREFIX_LENGTH);
    }

    public static boolean hasExpectedShape(String rawKey) {
        return rawKey != null && rawKey.startsWith(PREFIX) && rawKey.length() > PREFIX.length();
    }
}
