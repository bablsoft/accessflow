package com.bablsoft.accessflow.sqlreview.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One rule's configuration inside a ruleset.
 *
 * @param ruleId   the code-defined rule identifier
 * @param severity the assigned severity
 * @param params   rule-specific list parameters keyed by the rule's declared param key — the JSONB
 *                 shape is {@code {"names": ["pg_sleep"]}} for {@code disallowed_function} and
 *                 {@code {"globs": ["payroll.*"]}} for {@code protected_table}; never {@code null},
 *                 deep-copied, a {@code null} list reads as empty. {@code null} entries are kept
 *                 so the write-time validator can reject them as blank instead of a copy failing
 */
public record SqlReviewRuleConfigView(String ruleId, SqlReviewSeverity severity, Map<String, List<String>> params) {
    public SqlReviewRuleConfigView {
        params = params == null ? Map.of() : deepCopy(params);
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> source) {
        var copy = new HashMap<String, List<String>>(source.size());
        source.forEach((key, values) -> copy.put(key, values == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values))));
        return Map.copyOf(copy);
    }
}
