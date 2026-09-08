package com.bablsoft.accessflow.ai.internal.help;

import java.util.regex.Pattern;

/**
 * Reduces the caller-supplied "which screen am I on" hint to something safe to put in a prompt
 * (AF-905, epic AF-899 decision 3).
 *
 * <p>The client is supposed to send a mapped label — "Review queue", not {@code /reviews/<uuid>} —
 * and this is the server-side half of that contract. It matters because the label lands in the
 * <em>system</em> message, is sent to a third-party model, and is the one field on the chat request a
 * page could fill straight from {@code window.location}. A URL carrying a query request id, a
 * datasource id or a search term would make the help panel a quiet exfiltration path out of a product
 * whose entire point is that data access is governed.
 *
 * <p>Anything that still looks like a location is dropped whole rather than scrubbed in place. A
 * half-redacted path ("/queries/…") tells the model nothing useful anyway, and a substitution rule is
 * something an attacker can probe; "no label" is not.
 */
final class HelpRouteLabel {

    /** Ceiling on the label, matching the prompt renderer's own bound on interpolated context. */
    static final int MAX_LENGTH = HelpChatPromptRenderer.MAX_USER_CONTEXT_CHARS;

    /** A canonical UUID anywhere in the value — the id shape every AccessFlow route uses. */
    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** A run of digits long enough to be an identifier or a timestamp rather than a word. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");

    /** A hex blob long enough to be a token, an API key fragment or a content hash. */
    private static final Pattern HEX_BLOB = Pattern.compile("(?i)\\b[0-9a-f]{16,}\\b");

    private HelpRouteLabel() {
    }

    /**
     * The label to send, or {@code ""} when the value is missing or does not read as a label.
     *
     * <p>Dropped: anything containing a forward or back slash (which covers a scheme, a leading path
     * and an embedded one alike), a query string or a fragment, and anything carrying a UUID, a long
     * digit run or a hex blob.
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        var collapsed = value.replaceAll("\\s+", " ").strip();
        if (collapsed.isEmpty() || looksLikeLocation(collapsed) || carriesAnIdentifier(collapsed)) {
            return "";
        }
        return collapsed.length() <= MAX_LENGTH ? collapsed : collapsed.substring(0, MAX_LENGTH);
    }

    /**
     * A slash is enough on its own. {@code window.location.pathname.substring(1)} carries no leading
     * slash and no id — {@code datasources/analytics-prod/tables/customer_pii} — and every segment of
     * it is a name the product is supposed to govern access to. Losing the occasional real label that
     * happened to contain a slash costs a line of prompt context; keeping this rule loose costs the
     * guarantee the label exists to preserve.
     */
    private static boolean looksLikeLocation(String value) {
        return value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0;
    }

    private static boolean carriesAnIdentifier(String value) {
        return UUID_LIKE.matcher(value).find()
                || LONG_DIGITS.matcher(value).find()
                || HEX_BLOB.matcher(value).find();
    }
}
