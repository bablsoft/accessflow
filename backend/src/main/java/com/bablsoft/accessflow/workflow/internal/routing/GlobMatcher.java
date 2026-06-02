package com.bablsoft.accessflow.workflow.internal.routing;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates a table glob (where {@code *} matches any run of characters, including the
 * {@code schema.table} dot) into a case-insensitive anchored regex. All other characters are
 * treated literally. Used by {@link RoutingConditionEvaluator} for
 * {@link com.bablsoft.accessflow.workflow.api.ConditionNode.ReferencedTableMatches}.
 */
final class GlobMatcher {

    private GlobMatcher() {
    }

    static Pattern compile(String glob) {
        var normalized = glob == null ? "" : glob.trim().toLowerCase(Locale.ROOT);
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

    static boolean matches(String glob, String candidate) {
        if (candidate == null) {
            return false;
        }
        return compile(glob).matcher(candidate.toLowerCase(Locale.ROOT)).matches();
    }
}
