package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.workflow.api.ConditionContext;

import java.util.Optional;

/**
 * How the decision evaluator reaches the external decision hook (#945). A parameter rather than an
 * injected bean so the evaluator stays read-only for the simulators: the live state machine passes
 * the calling invoker, the simulators one that only reports which hook would apply.
 */
@FunctionalInterface
public interface DecisionHookInvoker {

    /** No hook at all — every existing caller that predates #945. */
    DecisionHookInvoker NONE = (query, context, aiOutcome) -> Optional.empty();

    /** @return empty when no enabled hook applies to the query's datasource */
    Optional<DecisionHookConsultation> consult(QueryRequestSnapshot query, ConditionContext context,
                                               AiOutcome aiOutcome);
}
