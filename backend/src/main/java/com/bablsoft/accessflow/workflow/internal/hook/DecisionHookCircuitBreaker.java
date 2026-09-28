package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-hook circuit breaker (#945), so a dead endpoint costs one timeout per open period instead of
 * one per submission. {@code circuit-failure-threshold} consecutive failures open the circuit;
 * while open every consult fails closed — human review — without a call. Once
 * {@code circuit-open-duration} has passed exactly one caller is let through as a half-open trial:
 * success closes the circuit, failure re-opens it.
 *
 * <p>In-memory and per JVM, like the read-replica breaker: each node learns of an outage on its
 * own, which costs at most a threshold's worth of timeouts per node.
 */
@Component
class DecisionHookCircuitBreaker {

    private final Clock clock;
    private final DecisionHookProperties properties;
    private final ConcurrentMap<UUID, State> states = new ConcurrentHashMap<>();

    DecisionHookCircuitBreaker(Clock clock, DecisionHookProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** @param openUntil {@code null} while the circuit is still closed */
    private record State(int consecutiveFailures, Instant openUntil, boolean trialInFlight) {
    }

    /** Whether a call may be made now; a {@code true} on an elapsed open circuit claims the trial. */
    boolean tryAcquire(UUID hookId) {
        var state = states.get(hookId);
        if (state == null || state.openUntil() == null) {
            return true;
        }
        if (state.trialInFlight() || clock.instant().isBefore(state.openUntil())) {
            return false;
        }
        return states.replace(hookId, state,
                new State(state.consecutiveFailures(), state.openUntil(), true));
    }

    void recordSuccess(UUID hookId) {
        states.remove(hookId);
    }

    void recordFailure(UUID hookId) {
        states.compute(hookId, (id, state) -> {
            int failures = state == null ? 1 : state.consecutiveFailures() + 1;
            var open = failures >= properties.circuitFailureThreshold()
                    ? clock.instant().plus(properties.circuitOpenDuration())
                    : null;
            return new State(failures, open, false);
        });
    }

    boolean isOpen(UUID hookId) {
        var state = states.get(hookId);
        return state != null && state.openUntil() != null;
    }

    /** Forgets a hook's history, on update or delete. */
    void reset(UUID hookId) {
        states.remove(hookId);
    }
}
