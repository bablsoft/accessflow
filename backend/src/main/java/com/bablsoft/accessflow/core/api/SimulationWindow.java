package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.time.Instant;

/**
 * The half-open {@code [from, to)} window of historical traffic a policy simulation replays
 * (issue AF-630).
 */
public record SimulationWindow(Instant from, Instant to) {

    /**
     * @throws InvalidSimulationPeriodException when either bound is missing, the window is empty or
     *         inverted, or it spans more than {@code maxWindow}.
     */
    public void validate(Duration maxWindow) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new InvalidSimulationPeriodException();
        }
        if (maxWindow != null && Duration.between(from, to).compareTo(maxWindow) > 0) {
            throw new InvalidSimulationPeriodException();
        }
    }
}
