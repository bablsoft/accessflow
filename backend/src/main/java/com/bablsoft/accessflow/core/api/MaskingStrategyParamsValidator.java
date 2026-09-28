package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Save-time validation of a masking strategy's {@code strategy_params}, shared by every admin path
 * that persists or simulates a masking policy. Parameters are checked here so that a malformed
 * policy is rejected when it is configured — never discovered during someone's query. Pure JDK.
 */
public final class MaskingStrategyParamsValidator {

    public static final int MAX_VISIBLE_LENGTH = 256;
    public static final int MAX_REPLACEMENT_LENGTH = 256;
    public static final int MAX_PATTERN_LENGTH = 512;
    public static final int MAX_BOUNDARIES = 50;
    public static final Set<String> PRECISIONS = Set.of("YEAR", "QUARTER", "MONTH");

    /** A rejected parameter set: an i18n message key plus its arguments. */
    public record Violation(String messageKey, List<Object> args) {
        public Violation {
            args = args == null ? List.of() : List.copyOf(args);
        }

        public Object[] argsArray() {
            return args.toArray();
        }
    }

    private MaskingStrategyParamsValidator() {
    }

    public static Optional<Violation> validate(MaskingStrategy strategy, Map<String, String> params) {
        if (strategy == null) {
            return Optional.empty();
        }
        var safe = params == null ? Map.<String, String>of() : params;
        var unknown = unknownParam(strategy, safe);
        if (unknown != null) {
            return violation("error.masking_params.unknown_param", unknown, strategy.name());
        }
        return switch (strategy) {
            case FULL, EMAIL, FORMAT_PRESERVING, NULLIFY, HASH -> Optional.empty();
            case PARTIAL -> visibleLength(safe.get(ColumnMasker.PARAM_VISIBLE_SUFFIX),
                    "error.masking_params.visible_suffix_invalid");
            case KEEP_FIRST -> visibleLength(safe.get(ColumnMasker.PARAM_VISIBLE_PREFIX),
                    "error.masking_params.visible_prefix_invalid");
            case CONSTANT -> constant(safe.get(ColumnMasker.PARAM_REPLACEMENT));
            case REGEX_REPLACE -> regex(safe.get(ColumnMasker.PARAM_PATTERN),
                    safe.get(ColumnMasker.PARAM_REPLACEMENT));
            case NUMERIC_BUCKET -> numericBucket(safe.get(ColumnMasker.PARAM_BUCKET_SIZE),
                    safe.get(ColumnMasker.PARAM_BOUNDARIES));
            case DATE_GENERALIZE -> precision(safe.get(ColumnMasker.PARAM_PRECISION));
        };
    }

    private static Set<String> allowedParams(MaskingStrategy strategy) {
        return switch (strategy) {
            case FULL, EMAIL, FORMAT_PRESERVING, NULLIFY -> Set.of();
            case PARTIAL -> Set.of(ColumnMasker.PARAM_VISIBLE_SUFFIX);
            case HASH -> Set.of(ColumnMasker.PARAM_SALT);
            case KEEP_FIRST -> Set.of(ColumnMasker.PARAM_VISIBLE_PREFIX);
            case CONSTANT -> Set.of(ColumnMasker.PARAM_REPLACEMENT);
            case REGEX_REPLACE -> Set.of(ColumnMasker.PARAM_PATTERN, ColumnMasker.PARAM_REPLACEMENT);
            case NUMERIC_BUCKET -> Set.of(ColumnMasker.PARAM_BUCKET_SIZE, ColumnMasker.PARAM_BOUNDARIES);
            case DATE_GENERALIZE -> Set.of(ColumnMasker.PARAM_PRECISION);
        };
    }

    private static String unknownParam(MaskingStrategy strategy, Map<String, String> params) {
        var allowed = allowedParams(strategy);
        return params.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static Optional<Violation> visibleLength(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 || value > MAX_VISIBLE_LENGTH ? violation(key) : Optional.empty();
        } catch (NumberFormatException ex) {
            return violation(key);
        }
    }

    private static Optional<Violation> constant(String replacement) {
        if (replacement == null || replacement.isEmpty()
                || replacement.length() > MAX_REPLACEMENT_LENGTH) {
            return violation("error.masking_params.constant_replacement_invalid");
        }
        return Optional.empty();
    }

    private static Optional<Violation> regex(String pattern, String replacement) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_PATTERN_LENGTH) {
            return violation("error.masking_params.pattern_required");
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            return violation("error.masking_params.pattern_invalid", ex.getIndex());
        }
        if (replacement == null || replacement.length() > MAX_REPLACEMENT_LENGTH) {
            return violation("error.masking_params.regex_replacement_invalid");
        }
        return replacementReferences(compiled, replacement);
    }

    /**
     * Mirrors {@code Matcher.appendReplacement}'s parsing of {@code \x} escapes, {@code $n} and
     * {@code ${name}} so a reference to a group the pattern does not define is caught at save time.
     */
    static Optional<Violation> replacementReferences(Pattern pattern, String replacement) {
        int groupCount = pattern.matcher("").groupCount();
        var named = pattern.namedGroups().keySet();
        int i = 0;
        while (i < replacement.length()) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (i + 1 >= replacement.length()) {
                    return violation("error.masking_params.replacement_syntax_invalid");
                }
                i += 2;
            } else if (c == '$') {
                if (i + 1 >= replacement.length()) {
                    return violation("error.masking_params.replacement_syntax_invalid");
                }
                char next = replacement.charAt(i + 1);
                if (next == '{') {
                    int close = replacement.indexOf('}', i + 2);
                    if (close < 0) {
                        return violation("error.masking_params.replacement_syntax_invalid");
                    }
                    var name = replacement.substring(i + 2, close);
                    if (!named.contains(name)) {
                        return violation("error.masking_params.replacement_group_invalid", name);
                    }
                    i = close + 1;
                } else if (Character.isDigit(next)) {
                    int ref = next - '0';
                    if (ref > groupCount) {
                        return violation("error.masking_params.replacement_group_invalid",
                                String.valueOf(ref));
                    }
                    i += 2;
                    while (i < replacement.length() && Character.isDigit(replacement.charAt(i))) {
                        int extended = ref * 10 + (replacement.charAt(i) - '0');
                        if (extended > groupCount) {
                            break;
                        }
                        ref = extended;
                        i++;
                    }
                } else {
                    return violation("error.masking_params.replacement_syntax_invalid");
                }
            } else {
                i++;
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> numericBucket(String bucketSize, String boundaries) {
        boolean hasSize = bucketSize != null && !bucketSize.isBlank();
        boolean hasBoundaries = boundaries != null && !boundaries.isBlank();
        if (hasSize == hasBoundaries) {
            return violation("error.masking_params.bucket_mode_required");
        }
        if (hasSize) {
            var size = ColumnMasker.parseDecimal(bucketSize);
            return size == null || size.signum() <= 0
                    ? violation("error.masking_params.bucket_size_invalid")
                    : Optional.empty();
        }
        var parsed = ColumnMasker.parseBoundaries(boundaries);
        if (parsed == null || parsed.size() > MAX_BOUNDARIES) {
            return violation("error.masking_params.boundaries_invalid", MAX_BOUNDARIES);
        }
        return Optional.empty();
    }

    private static Optional<Violation> precision(String raw) {
        if (raw == null || !PRECISIONS.contains(raw.trim().toUpperCase(Locale.ROOT))) {
            return violation("error.masking_params.precision_invalid");
        }
        return Optional.empty();
    }

    private static Optional<Violation> violation(String key, Object... args) {
        return Optional.of(new Violation(key, List.of(args)));
    }
}
