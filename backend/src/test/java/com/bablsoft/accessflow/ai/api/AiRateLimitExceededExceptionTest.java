package com.bablsoft.accessflow.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiRateLimitExceededExceptionTest {

    @Test
    void carriesLimitAndRetryAfterAndMessage() {
        var ex = new AiRateLimitExceededException(30, 60);
        assertThat(ex.limit()).isEqualTo(30);
        assertThat(ex.retryAfterSeconds()).isEqualTo(60L);
        assertThat(ex.getMessage()).contains("30");
    }

    @Test
    void isAnAiAnalysisException() {
        assertThat(new AiRateLimitExceededException(30, 60)).isInstanceOf(AiAnalysisException.class);
    }
}
