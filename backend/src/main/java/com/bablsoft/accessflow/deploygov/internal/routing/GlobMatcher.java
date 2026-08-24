package com.bablsoft.accessflow.deploygov.internal.routing;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates an admin-facing glob (where {@code *} matches any run of characters, including dots
 * and separators) into a case-insensitive anchored regex. All other characters are treated
 * literally. Used by {@link DeploymentRoutingPolicyEngine} to match artifact versions.
 * Package-private clone of {@code apigov.internal.schema.GlobMatcher} — cross-module reuse of an
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
