package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.VerificationCheck;

/**
 * Evaluates one kind of {@link VerificationCheck} into a {@link VerificationSample} (#1024). A
 * check that cannot be evaluated is a failed sample, never a skipped one, and never an exception.
 */
sealed interface VerificationCheckEvaluator<C extends VerificationCheck>
        permits HttpHealthCheckEvaluator, PromqlThresholdCheckEvaluator {

    VerificationSample evaluate(C check);
}
