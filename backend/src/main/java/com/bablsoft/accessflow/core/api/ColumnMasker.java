package com.bablsoft.accessflow.core.api;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pure application of a {@link MaskingStrategy} to a single already-read string value. Stateless;
 * never touches the database. Returns {@code null} for a {@code null} input (a NULL cell stays
 * NULL). The output is what gets serialized and persisted — the unmasked value is never retained.
 *
 * <p>Every strategy fails closed: a value the strategy cannot apply to (wrong type, no regex
 * match, a regex that exceeds its backtracking budget) yields {@link #FULL_MASK}, never the raw
 * value. Parameters are validated at save time by {@link MaskingStrategyParamsValidator}.
 */
public final class ColumnMasker {

    public static final String FULL_MASK = "***";

    public static final String PARAM_VISIBLE_SUFFIX = "visible_suffix";
    public static final String PARAM_VISIBLE_PREFIX = "visible_prefix";
    public static final String PARAM_SALT = "salt";
    public static final String PARAM_PATTERN = "pattern";
    public static final String PARAM_REPLACEMENT = "replacement";
    public static final String PARAM_BUCKET_SIZE = "bucket_size";
    public static final String PARAM_BOUNDARIES = "boundaries";
    public static final String PARAM_PRECISION = "precision";

    static final int DEFAULT_VISIBLE_SUFFIX = 4;
    static final int DEFAULT_VISIBLE_PREFIX = 4;
    static final char MASK_CHAR = '*';
    /** Longest value a regex strategy is run against; longer values are fully masked. */
    static final int MAX_REGEX_INPUT_LENGTH = 4096;
    /** Character reads one regex evaluation may perform before it is abandoned as runaway. */
    static final long REGEX_CHAR_BUDGET = 1_000_000L;
    private static final int PATTERN_CACHE_LIMIT = 256;
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final Pattern DATE_PREFIX = Pattern.compile("^(\\d{4})-(\\d{2})");

    private ColumnMasker() {
    }

    public static String apply(MaskingStrategy strategy, String raw, Map<String, String> params) {
        if (raw == null) {
            return null;
        }
        return switch (strategy) {
            case FULL -> FULL_MASK;
            case PARTIAL -> partial(raw, params);
            case HASH -> hash(raw, params);
            case EMAIL -> email(raw);
            case FORMAT_PRESERVING -> formatPreserving(raw);
            case REGEX_REPLACE -> regexReplace(raw, params);
            case CONSTANT -> constant(params);
            case NULLIFY -> null;
            case KEEP_FIRST -> keepFirst(raw, params);
            case NUMERIC_BUCKET -> numericBucket(raw, params);
            case DATE_GENERALIZE -> dateGeneralize(raw, params);
        };
    }

    /**
     * Whether the strategy's output depends on the raw value. Result readers use this to avoid
     * materializing the raw value at all for {@link MaskingStrategy#FULL},
     * {@link MaskingStrategy#CONSTANT} and {@link MaskingStrategy#NULLIFY}.
     */
    public static boolean readsRawValue(MaskingStrategy strategy) {
        return switch (strategy) {
            case FULL, CONSTANT, NULLIFY -> false;
            case PARTIAL, HASH, EMAIL, FORMAT_PRESERVING, REGEX_REPLACE, KEEP_FIRST, NUMERIC_BUCKET,
                 DATE_GENERALIZE -> true;
        };
    }

    /**
     * Output for a non-NULL value under a strategy that does not read it
     * ({@link #readsRawValue(MaskingStrategy)} is {@code false}).
     */
    public static String applyWithoutValue(MaskingStrategy strategy, Map<String, String> params) {
        return switch (strategy) {
            case CONSTANT -> constant(params);
            case NULLIFY -> null;
            default -> FULL_MASK;
        };
    }

    /** Compiles (and caches) a masking regex; throws {@code PatternSyntaxException} when invalid. */
    static Pattern compile(String pattern) {
        var cached = PATTERN_CACHE.get(pattern);
        if (cached != null) {
            return cached;
        }
        var compiled = Pattern.compile(pattern);
        if (PATTERN_CACHE.size() >= PATTERN_CACHE_LIMIT) {
            PATTERN_CACHE.clear();
        }
        PATTERN_CACHE.put(pattern, compiled);
        return compiled;
    }

    private static String partial(String raw, Map<String, String> params) {
        int visible = intParam(params, PARAM_VISIBLE_SUFFIX, DEFAULT_VISIBLE_SUFFIX);
        int length = raw.length();
        if (length <= visible) {
            // Never reveal the whole value when it is no longer than the visible window.
            return repeat(length);
        }
        return repeat(length - visible) + raw.substring(length - visible);
    }

    private static String keepFirst(String raw, Map<String, String> params) {
        int visible = intParam(params, PARAM_VISIBLE_PREFIX, DEFAULT_VISIBLE_PREFIX);
        int length = raw.length();
        if (length <= visible) {
            return repeat(length);
        }
        return raw.substring(0, visible) + repeat(length - visible);
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        var raw = param(params, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 0 ? fallback : value;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String param(Map<String, String> params, String key) {
        return params == null ? null : params.get(key);
    }

    private static String hash(String raw, Map<String, String> params) {
        // Optional per-org salt (lifecycle pseudonymization, AF-499). Absent → today's plain digest,
        // so existing HASH masking policies are unchanged.
        var salt = param(params, PARAM_SALT);
        var input = salt == null || salt.isBlank() ? raw : salt + raw;
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
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

    private static String constant(Map<String, String> params) {
        var replacement = param(params, PARAM_REPLACEMENT);
        return replacement == null || replacement.isEmpty() ? FULL_MASK : replacement;
    }

    private static String regexReplace(String raw, Map<String, String> params) {
        var pattern = param(params, PARAM_PATTERN);
        var replacement = param(params, PARAM_REPLACEMENT);
        if (pattern == null || pattern.isEmpty() || replacement == null
                || raw.length() > MAX_REGEX_INPUT_LENGTH) {
            return FULL_MASK;
        }
        try {
            var matcher = compile(pattern).matcher(new BudgetedCharSequence(raw, REGEX_CHAR_BUDGET));
            if (!matcher.find()) {
                // An unmatched value would pass through unchanged — mask it instead of leaking it.
                return FULL_MASK;
            }
            matcher.reset();
            return matcher.replaceAll(replacement);
        } catch (IllegalArgumentException | IndexOutOfBoundsException
                 | RegexBudgetExceededException | StackOverflowError ex) {
            // Fail closed: an invalid pattern, a bad group reference, runaway backtracking or deep
            // recursion must never surface the raw value or fail the query.
            return FULL_MASK;
        }
    }

    private static String numericBucket(String raw, Map<String, String> params) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return FULL_MASK;
        }
        var bucketSize = parseDecimal(param(params, PARAM_BUCKET_SIZE));
        if (bucketSize != null && bucketSize.signum() > 0) {
            var floor = value.divide(bucketSize, 0, RoundingMode.FLOOR).multiply(bucketSize);
            return plain(floor);
        }
        var boundaries = parseBoundaries(param(params, PARAM_BOUNDARIES));
        if (boundaries == null || boundaries.isEmpty()) {
            return FULL_MASK;
        }
        if (value.compareTo(boundaries.getFirst()) < 0) {
            return "<" + plain(boundaries.getFirst());
        }
        for (int i = 0; i < boundaries.size() - 1; i++) {
            if (value.compareTo(boundaries.get(i + 1)) < 0) {
                return "[" + plain(boundaries.get(i)) + ", " + plain(boundaries.get(i + 1)) + ")";
            }
        }
        return ">=" + plain(boundaries.getLast());
    }

    /** Parses a strictly ascending comma-separated list; {@code null} when malformed. */
    static List<BigDecimal> parseBoundaries(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var result = new ArrayList<BigDecimal>();
        for (var part : raw.split(",", -1)) {
            var value = parseDecimal(part);
            if (value == null || (!result.isEmpty() && value.compareTo(result.getLast()) <= 0)) {
                return null;
            }
            result.add(value);
        }
        return result;
    }

    static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String dateGeneralize(String raw, Map<String, String> params) {
        var precision = param(params, PARAM_PRECISION);
        var matcher = DATE_PREFIX.matcher(raw.trim());
        if (precision == null || !matcher.find()) {
            return FULL_MASK;
        }
        var year = matcher.group(1);
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) {
            return FULL_MASK;
        }
        return switch (precision.trim().toUpperCase(Locale.ROOT)) {
            case "YEAR" -> year;
            case "QUARTER" -> year + "-Q" + ((month - 1) / 3 + 1);
            case "MONTH" -> year + "-" + matcher.group(2);
            default -> FULL_MASK;
        };
    }

    private static String repeat(int count) {
        return String.valueOf(MASK_CHAR).repeat(Math.max(0, count));
    }

    /** Thrown by {@link BudgetedCharSequence} when a regex evaluation runs away. */
    static final class RegexBudgetExceededException extends RuntimeException {
        RegexBudgetExceededException() {
            super("masking regex exceeded its evaluation budget", null, false, false);
        }
    }

    /**
     * Counts every character the regex engine reads and aborts once the budget is spent — the
     * catastrophic-backtracking guard, since {@link Pattern} itself has no timeout.
     */
    static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long[] remaining;

        BudgetedCharSequence(CharSequence delegate, long budget) {
            this(delegate, new long[] {budget});
        }

        private BudgetedCharSequence(CharSequence delegate, long[] remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new RegexBudgetExceededException();
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(delegate.subSequence(start, end), remaining);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
