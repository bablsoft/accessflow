package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerificationCheckEvaluatorsTest {

    private final HttpHealthCheckEvaluator http = mock(HttpHealthCheckEvaluator.class);
    private final PromqlThresholdCheckEvaluator promql = mock(PromqlThresholdCheckEvaluator.class);
    private final VerificationCheckEvaluators evaluators = new VerificationCheckEvaluators(http, promql);

    @Test
    void dispatchesAnHttpHealthCheck() {
        var check = new HttpHealthCheck("health", "https://svc", 200, null);
        var sample = new VerificationSample("health", VerificationCheckKind.HTTP_HEALTH, true, "200", null);
        when(http.evaluate(check)).thenReturn(sample);

        assertThat(evaluators.evaluate(check)).isSameAs(sample);
        verifyNoInteractions(promql);
    }

    @Test
    void dispatchesAPromqlThresholdCheck() {
        var check = new PromqlThresholdCheck("rate", "https://prom", "up", VerificationComparator.GT, 0, true);
        var sample = new VerificationSample("rate", VerificationCheckKind.PROMQL_THRESHOLD, true, "1", null);
        when(promql.evaluate(check)).thenReturn(sample);

        assertThat(evaluators.evaluate(check)).isSameAs(sample);
        verifyNoInteractions(http);
    }
}
