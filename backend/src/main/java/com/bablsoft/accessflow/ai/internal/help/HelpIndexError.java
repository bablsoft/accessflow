package com.bablsoft.accessflow.ai.internal.help;

import java.util.Arrays;
import java.util.List;

/**
 * A localized ingestion failure, encoded for storage in {@code help_agent_config.index_error}.
 *
 * <p>{@code index_error} is served straight through {@code GET /admin/help-agent} onto a localized
 * admin page, so the indexer may not write authored English into it — the same rule that keeps every
 * other user-facing string out of Java. But the indexer runs on a background thread with no request
 * locale, and the row outlives the pass that wrote it, so the message cannot be resolved at write
 * time either: an admin who switches the product to German should see a German reason for a failure
 * recorded last week.
 *
 * <p>So the column stores a message key plus its arguments, and the read path resolves it against the
 * caller's locale. Arguments carry the parts that are not translatable — a provider's own error text,
 * a vector dimension. The encoding is unit-separator-delimited (a control character that cannot occur
 * in a message key and will not occur in a provider message), and decoding is tolerant: anything that
 * does not look like a key is returned verbatim, so a row written by an older build still reads.
 *
 * @param messageKey a key present in every {@code messages*.properties}
 * @param args       substitution arguments for that key, in order
 */
record HelpIndexError(String messageKey, List<String> args) {

    /** Message keys this type may carry all share this prefix, which is how {@link #decode} spots one. */
    static final String KEY_PREFIX = "error.help_agent.";

    private static final String SEPARATOR = "";
    /** Long enough for any provider message worth reading; {@code index_error} is TEXT, admins are not. */
    private static final int MAX_ARG_CHARS = 1000;

    static HelpIndexError of(String messageKey, String... args) {
        return new HelpIndexError(messageKey, List.of(args));
    }

    /** The stored form: {@code key} followed by its arguments, each truncated to a readable length. */
    String encode() {
        var joined = new StringBuilder(messageKey);
        for (var arg : args) {
            joined.append(SEPARATOR).append(truncate(arg));
        }
        return joined.toString();
    }

    /**
     * Splits a stored value back into a key and its arguments, or {@code null} when it is not an
     * encoded error — a value written before this encoding existed, or a hand-edited row. Callers
     * show such a value as-is rather than dropping it: a stale reason still beats a blank field.
     */
    static HelpIndexError decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        var parts = stored.split(SEPARATOR, -1);
        if (!parts[0].startsWith(KEY_PREFIX)) {
            return null;
        }
        return new HelpIndexError(parts[0], Arrays.stream(parts).skip(1).toList());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_ARG_CHARS ? value.substring(0, MAX_ARG_CHARS) : value;
    }
}
