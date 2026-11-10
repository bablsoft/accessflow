package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Dispatches a {@link VerificationCheck} to the evaluator of its kind (#1024). */
@Component
@RequiredArgsConstructor
public class VerificationCheckEvaluators {

    private final HttpHealthCheckEvaluator httpHealth;
    private final PromqlThresholdCheckEvaluator promqlThreshold;

    public VerificationSample evaluate(VerificationCheck check) {
        // Exhaustive over the sealed api type: a new kind fails compilation here first.
        return switch (check) {
            case HttpHealthCheck http -> httpHealth.evaluate(http);
            case PromqlThresholdCheck promql -> promqlThreshold.evaluate(promql);
        };
    }
}
