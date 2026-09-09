package com.bablsoft.accessflow.engine.databricks;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an {@code INLINE} statement result hit the Statement Execution API's ~25 MiB
 * inline ceiling and should therefore be re-submitted with {@code disposition=EXTERNAL_LINKS}
 * (AF-633). Two independent signals:
 *
 * <ul>
 *   <li>{@link #inlineLimitExceeded(DatabricksApiException)} — the API rejected the result. The
 *       vendor's error code and wording are not contractual, so the match is deliberately
 *       generous; a false positive costs one wasted re-submission and never changes what the user
 *       sees, because {@code DatabricksStatementClient} rethrows the <em>original</em> inline
 *       failure when the fallback itself fails.</li>
 *   <li>{@link #sizeTruncatedInline(boolean, Integer, int)} — the API silently truncated. A
 *       manifest {@code truncated} flag the requested {@code row_limit} cannot explain was a size
 *       cut, not a row cut. The {@code rowLimit == null} case is the load-bearing one: it is
 *       exactly the unbounded {@code information_schema} introspection reads, where truncation
 *       provably cannot come from a row limit.</li>
 * </ul>
 *
 * A missed signal simply degrades to the pre-AF-633 behaviour (the API error surfaces verbatim),
 * which is the safe direction to fail in.
 */
final class DatabricksInlineLimitDetector {

    /** Vendor error codes observed (or plausibly emitted) for an oversized inline result. */
    private static final Set<String> OVERSIZE_ERROR_CODES = Set.of(
            "MAX_RESULT_SIZE_EXCEEDED", "RESULT_SIZE_EXCEEDED", "RESULT_TOO_LARGE",
            "RESULT_SET_TOO_LARGE", "INLINE_RESULT_TOO_LARGE");

    private DatabricksInlineLimitDetector() {
    }

    static boolean inlineLimitExceeded(DatabricksApiException e) {
        if (e == null || e.timedOut()) {
            return false;
        }
        var code = e.errorCode();
        if (code != null && OVERSIZE_ERROR_CODES.contains(code.strip().toUpperCase(Locale.ROOT))) {
            return true;
        }
        var message = e.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        var text = message.toLowerCase(Locale.ROOT);
        // The API's own remediation hint names the disposition — the single most reliable marker.
        if (text.contains("external_links") || text.contains("external links")) {
            return true;
        }
        return (text.contains("result") || text.contains("response"))
                && (text.contains("too large") || text.contains("exceed"));
    }

    static boolean sizeTruncatedInline(boolean manifestTruncated, Integer rowLimit, int rowCount) {
        return manifestTruncated && (rowLimit == null || rowCount < rowLimit);
    }
}
