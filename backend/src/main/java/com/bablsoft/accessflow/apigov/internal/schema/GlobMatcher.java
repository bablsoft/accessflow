package com.bablsoft.accessflow.apigov.internal.schema;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates an admin-facing glob (where {@code *} matches any run of characters, including path
 * separators and dots) into a case-insensitive anchored regex. All other characters are treated
 * literally. Used by {@link OperationFilterMatcher} to match operation paths and operation ids.
 * Package-private clone of {@code workflow.internal.routing.GlobMatcher} — cross-module reuse of an
 * {@code internal} type is forbidden and the helper is intentionally tiny.
 */
final class GlobMatcher {

    private GlobMatcher() {
    }

    static boolean matches(String glob, String candidate) {
        if (candidate == null || glob == null) {
            return false;
        }
        return compile(glob).matcher(candidate.toLowerCase(Locale.ROOT)).matches();
    }

    private static Pattern compile(String glob) {
        var normalized = glob.trim().toLowerCase(Locale.ROOT);
        var regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
