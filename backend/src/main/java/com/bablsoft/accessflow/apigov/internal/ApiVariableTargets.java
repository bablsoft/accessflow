package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parsing for a connector variable's optional auto-injection {@code target} (AF-613), stored as
 * {@code "header:<Name>"} or {@code "query:<name>"}.
 *
 * <p>The header-name character class is RFC 7230's {@code token}, so a target can never smuggle a
 * separator or control character into the header block.
 */
final class ApiVariableTargets {

    private static final Pattern TARGET =
            Pattern.compile("^(header|query):([A-Za-z0-9!#$%&'*+\\-.^_`|~]{1,128})$");

    private ApiVariableTargets() {
    }

    record Target(ApiVariableTargetType type, String key) {
    }

    /** Returns {@code null} for a blank target (the common case — no auto-injection). */
    static Target parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var matcher = TARGET.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }
        var type = "header".equals(matcher.group(1).toLowerCase(Locale.ROOT))
                ? ApiVariableTargetType.HEADER
                : ApiVariableTargetType.QUERY;
        return new Target(type, matcher.group(2));
    }

    /** True when {@code raw} is blank or a well-formed target. Used by save-time validation. */
    static boolean isValid(String raw) {
        return raw == null || raw.isBlank() || TARGET.matcher(raw.trim()).matches();
    }

    /** The canonical stored form, or {@code null} when blank. */
    static String normalize(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
