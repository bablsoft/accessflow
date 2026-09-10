package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Why a governed request would end up in {@code resultingStatus} (issues AF-859, AF-967) — the
 * ordered stages that were evaluated, each with its outcome and the data it saw.
 *
 * <p>Produced by the same evaluator that decides real requests. That is the whole point: an explainer
 * with its own copy of the rules would disagree with enforcement the first time either changed, and
 * an explainer that lies is worse than none.
 *
 * <p>Shared by all three governed request kinds — queries, API calls and deployments — which is why
 * it lives in {@code core.api} rather than in any one of them. {@link DecisionStepKind} is the seam:
 * each module names its own stages, and only the vocabulary of a trace is common. All three kinds
 * reuse the {@code query_status} lifecycle, so {@link QueryStatus} is the shared status type.
 */
public record DecisionTrace(List<DecisionTraceStep> steps, QueryStatus resultingStatus) {

    public DecisionTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
