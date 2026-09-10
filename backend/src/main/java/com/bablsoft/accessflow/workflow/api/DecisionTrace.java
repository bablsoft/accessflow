package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.List;

/**
 * Why a query would end up in {@code resultingStatus} (issue AF-859) — the ordered stages that were
 * evaluated, each with its outcome and the data it saw.
 *
 * <p>Produced by the same evaluator that decides real queries. That is the whole point: an explainer
 * with its own copy of the rules would disagree with enforcement the first time either changed, and
 * an explainer that lies is worse than none.
 */
public record DecisionTrace(List<DecisionTraceStep> steps, QueryStatus resultingStatus) {

    public DecisionTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
