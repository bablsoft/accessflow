package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.security.api.StepUpService.StepUpToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpResponseTest {

    @Test
    void mapsTokenAndExpiry() {
        var expiry = Instant.parse("2026-06-22T00:05:00Z");

        var response = StepUpResponse.from(new StepUpToken("tok-123", expiry));

        assertThat(response.stepUpToken()).isEqualTo("tok-123");
        assertThat(response.expiresAt()).isEqualTo(expiry);
    }
}
