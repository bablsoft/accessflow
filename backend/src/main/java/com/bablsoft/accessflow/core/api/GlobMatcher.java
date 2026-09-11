package com.bablsoft.accessflow.core.api;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates an admin-facing glob — where {@code *} matches any run of characters, including the
 * {@code schema.table} dot, a path separator or a version dot — into a case-insensitive anchored
 * regex. Every other character is literal. The single shared matcher behind routing-policy table /
 * scan-type / user-agent globs, API-governance operation filters, deployment version globs and the
 * SQL review {@code protected_table} rule (#862 replaced three identical module-private clones).
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    /** Compiles {@code glob} (trimmed, lower-cased; {@code null} reads as empty) to an anchored regex. */
    public static Pattern compile(String glob) {
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

    /** Whole-string, case-insensitive match; a {@code null} glob or candidate never matches. */
    public static boolean matches(String glob, String candidate) {
        if (glob == null || candidate == null) {
            return false;
        }
        return compile(glob).matcher(candidate.toLowerCase(Locale.ROOT)).matches();
    }
}
