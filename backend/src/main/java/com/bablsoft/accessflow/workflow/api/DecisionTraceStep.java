package com.bablsoft.accessflow.workflow.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stage of a {@link DecisionTrace} (issue AF-859).
 *
 * @param reasonKey  a {@code MessageSource} key, never rendered text. The evaluator runs inside an
 *                   asynchronous listener where there is no request locale to resolve against, so
 *                   deferring resolution to the controller is what lets a single trace serve both
 *                   the live path and an HTTP response in the caller's language.
 * @param reasonArgs positional arguments for {@code reasonKey}
 * @param details    stage-specific structured data. The key set varies by outcome within a stage,
 *                   and a key whose value is null is omitted on the wire; both are documented per
 *                   stage in {@code docs/04-api-spec.md}
 */
public record DecisionTraceStep(DecisionStepKind step, StepOutcome outcome, String reasonKey,
                                List<String> reasonArgs, Map<String, Object> details) {

    public DecisionTraceStep {
        reasonArgs = reasonArgs == null ? List.of() : List.copyOf(reasonArgs);
        // LinkedHashMap rather than Map.copyOf: detail keys render in a documented order, and a
        // stage legitimately RECORDS a null (no matched policy, no expiry), which copyOf rejects
        // outright. Serialization then omits those keys, so a client reads absent as "not
        // applicable" — see docs/04-api-spec.md.
        details = details == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static DecisionTraceStep of(DecisionStepKind step, StepOutcome outcome, String reasonKey) {
        return new DecisionTraceStep(step, outcome, reasonKey, List.of(), Map.of());
    }

    public static DecisionTraceStep of(DecisionStepKind step, StepOutcome outcome, String reasonKey,
                                       Map<String, Object> details) {
        return new DecisionTraceStep(step, outcome, reasonKey, List.of(), details);
    }
}
