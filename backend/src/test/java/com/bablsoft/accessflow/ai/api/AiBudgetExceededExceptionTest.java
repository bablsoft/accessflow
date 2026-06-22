package com.bablsoft.accessflow.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiBudgetExceededExceptionTest {

    @Test
    void carriesBudgetAndUsedAndMessage() {
        var ex = new AiBudgetExceededException(1000, 1200);
        assertThat(ex.budget()).isEqualTo(1000L);
        assertThat(ex.used()).isEqualTo(1200L);
        assertThat(ex.getMessage()).contains("1200").contains("1000");
    }

    @Test
    void isAnAiAnalysisException() {
        assertThat(new AiBudgetExceededException(1000, 1200)).isInstanceOf(AiAnalysisException.class);
    }
}
