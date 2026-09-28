package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookConsultationTest {

    @Test
    void aSimulatedConsultationHasNoOutcomeAndIsNotAFailure() {
        var simulated = DecisionHookConsultation.simulated(UUID.randomUUID(), "OPA");

        assertThat(simulated.isSimulated()).isTrue();
        assertThat(simulated.isFailure()).isFalse();
        assertThat(simulated.latencyMs()).isZero();
    }

    @Test
    void aFailedConsultationIsAFailure() {
        var failed = new DecisionHookConsultation(UUID.randomUUID(), "OPA",
                DecisionHookOutcome.FAILED, DecisionHookFailure.TIMEOUT, null, null, null, 1L);

        assertThat(failed.isFailure()).isTrue();
        assertThat(failed.isSimulated()).isFalse();
    }

    @Test
    void theNoneInvokerNeverAnswers() {
        assertThat(DecisionHookInvoker.NONE.consult(null, null, null)).isEmpty();
    }
}
