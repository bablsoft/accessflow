package com.bablsoft.accessflow.core.api;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Pure application of a {@link MaskingStrategy} to a single already-read string value. Stateless;
 * never touches the database. Returns {@code null} for a {@code null} input (a NULL cell stays
 * NULL). The output is what gets serialized and persisted — the unmasked value is never retained.
 */
public final class ColumnMasker {

    public static final String FULL_MASK = "***";
    static final int DEFAULT_VISIBLE_SUFFIX = 4;
    static final char MASK_CHAR = '*';

    private ColumnMasker() {
    }

    public static String apply(MaskingStrategy strategy, String raw, Map<String, String> params) {
        if (raw == null) {
            return null;
        }
        return switch (strategy) {
            case FULL -> FULL_MASK;
            case PARTIAL -> partial(raw, params);
            case HASH -> hash(raw);
            case EMAIL -> email(raw);
            case FORMAT_PRESERVING -> formatPreserving(raw);
        };
    }

    private static String partial(String raw, Map<String, String> params) {
        int visible = visibleSuffix(params);
        int length = raw.length();
        if (length <= visible) {
            // Never reveal the whole value when it is no longer than the visible window.
            return repeat(length);
        }
        return repeat(length - visible) + raw.substring(length - visible);
    }

    private static int visibleSuffix(Map<String, String> params) {
        if (params == null) {
            return DEFAULT_VISIBLE_SUFFIX;
        }
        var raw = params.get("visible_suffix");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_VISIBLE_SUFFIX;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 0 ? DEFAULT_VISIBLE_SUFFIX : value;
        } catch (NumberFormatException ex) {
            return DEFAULT_VISIBLE_SUFFIX;
        }
    }

    private static String hash(String raw) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK spec; this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String email(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            // Not an email shape — fall back to full masking rather than leaking structure.
            return FULL_MASK;
        }
        var local = raw.substring(0, at);
        var domain = raw.substring(at + 1);
        return local.charAt(0) + "***@" + domain;
    }

    private static String formatPreserving(String raw) {
        var sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(MASK_CHAR);
            } else if (Character.isLetter(c)) {
                sb.append('x');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String repeat(int count) {
        return String.valueOf(MASK_CHAR).repeat(Math.max(0, count));
    }
}
