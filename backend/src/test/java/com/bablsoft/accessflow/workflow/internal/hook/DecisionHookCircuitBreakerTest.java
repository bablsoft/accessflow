package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookCircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
    private final UUID hookId = UUID.randomUUID();
    private DecisionHookCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new DecisionHookCircuitBreaker(clock,
                new DecisionHookProperties(false, 3, Duration.ofSeconds(30)));
    }

    @Test
    void staysClosedBelowTheThreshold() {
        breaker.recordFailure(hookId);
        breaker.recordFailure(hookId);

        assertThat(breaker.isOpen(hookId)).isFalse();
        assertThat(breaker.tryAcquire(hookId)).isTrue();
    }

    @Test
    void opensAtTheThresholdAndRefusesCallsUntilTheCooldownPasses() {
        failTimes(3);

        assertThat(breaker.isOpen(hookId)).isTrue();
        assertThat(breaker.tryAcquire(hookId)).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire(hookId)).isFalse();
    }

    @Test
    void letsExactlyOneTrialThroughOnceTheCooldownPasses() {
        failTimes(3);
        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.tryAcquire(hookId)).isTrue();
        assertThat(breaker.tryAcquire(hookId)).isFalse();
    }

    @Test
    void aSuccessfulTrialClosesTheCircuit() {
        failTimes(3);
        clock.advance(Duration.ofSeconds(31));
        breaker.tryAcquire(hookId);

        breaker.recordSuccess(hookId);

        assertThat(breaker.isOpen(hookId)).isFalse();
        assertThat(breaker.tryAcquire(hookId)).isTrue();
    }

    @Test
    void aFailedTrialReopensTheCircuit() {
        failTimes(3);
        clock.advance(Duration.ofSeconds(31));
        breaker.tryAcquire(hookId);

        breaker.recordFailure(hookId);

        assertThat(breaker.tryAcquire(hookId)).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire(hookId)).isTrue();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        failTimes(2);
        breaker.recordSuccess(hookId);
        failTimes(2);

        assertThat(breaker.isOpen(hookId)).isFalse();
    }

    @Test
    void resetForgetsAnOpenCircuit() {
        failTimes(3);

        breaker.reset(hookId);

        assertThat(breaker.tryAcquire(hookId)).isTrue();
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) {
            breaker.recordFailure(hookId);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
