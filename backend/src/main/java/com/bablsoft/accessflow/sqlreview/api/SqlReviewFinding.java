package com.bablsoft.accessflow.sqlreview.api;

import java.util.Map;
import java.util.Objects;

/**
 * One rule violation on one statement of a submitted query. Carries no human-readable text: the
 * message is resolved per reader from {@code ruleId} and {@code args} in the reader's locale.
 *
 * @param ruleId         the code-defined rule identifier (e.g. {@code missing_where_on_delete})
 * @param severity       the severity the resolved ruleset assigned to the rule
 * @param statementIndex zero-based index of the statement inside the submitted SQL
 * @param lineNumber     one-based line of the offending construct, or {@code null} when unknown
 * @param args           message arguments keyed by placeholder name; never {@code null}
 */
public record SqlReviewFinding(
        String ruleId,
        SqlReviewSeverity severity,
        int statementIndex,
        Integer lineNumber,
        Map<String, String> args
) {
    public SqlReviewFinding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /** Whether this finding forces the query to human review. */
    public boolean isBlocking() {
        return severity == SqlReviewSeverity.BLOCK;
    }
}
