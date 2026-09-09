package com.bablsoft.accessflow.core.internal.config;

import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the policy simulator (issue AF-630).
 *
 * <p>{@code maxRows} defaults far below the compliance report's 50 000: every replayed row is
 * re-parsed and evaluated twice (once per arm of the A/B), so the per-row cost is much higher than
 * a report's.
 *
 * @param maxRows        rows replayed per simulation before the result is marked truncated
 * @param maxSamples     drill-down rows returned
 * @param maxUserImpacts entries in the per-user impact list
 * @param maxWindow      longest period a single simulation may span
 */
@ConfigurationProperties(prefix = "accessflow.policy-simulation")
public record PolicySimulationProperties(int maxRows, int maxSamples, int maxUserImpacts,
                                         Duration maxWindow) implements PolicySimulationLimits {

    public PolicySimulationProperties {
        if (maxRows <= 0) {
            maxRows = 5_000;
        }
        if (maxSamples <= 0) {
            maxSamples = 100;
        }
        if (maxUserImpacts <= 0) {
            maxUserImpacts = 100;
        }
        if (maxWindow == null || maxWindow.isZero() || maxWindow.isNegative()) {
            maxWindow = Duration.ofDays(90);
        }
    }
}
