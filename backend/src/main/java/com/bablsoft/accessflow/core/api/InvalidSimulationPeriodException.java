package com.bablsoft.accessflow.core.api;

/**
 * Raised when a policy simulation's {@code [from, to)} window is missing a bound, inverted, or
 * longer than the configured maximum (issue AF-630). Mapped to HTTP 400
 * {@code INVALID_SIMULATION_PERIOD}; the detail is resolved from a message key by the handler, not
 * carried here, so the exception stays free of any localized text.
 */
public final class InvalidSimulationPeriodException extends RuntimeException {

    public InvalidSimulationPeriodException() {
        super("Invalid policy-simulation period");
    }
}
