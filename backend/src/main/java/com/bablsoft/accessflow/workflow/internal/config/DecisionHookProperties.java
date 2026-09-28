package com.bablsoft.accessflow.workflow.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deployment-wide tunables for the external decision hook (#945), bound from
 * {@code accessflow.workflow.decision-hook.*}.
 *
 * <ul>
 *   <li>{@code allowPrivateNetwork} — permits plain {@code http://} and endpoints that are, or
 *       resolve to, loopback / private / link-local / unique-local addresses. Off by default: the
 *       endpoint is admin-supplied and called from inside the network. Turn it on for an in-cluster
 *       OPA sidecar; default {@code false}.</li>
 *   <li>{@code circuitFailureThreshold} — consecutive failed calls that open a hook's circuit;
 *       default 5.</li>
 *   <li>{@code circuitOpenDuration} — how long an open circuit short-circuits every consult to
 *       human review before one trial call is let through; default {@code PT30S}.</li>
 * </ul>
 *
 * <p>A single compact constructor and no convenience overload: a second constructor on a
 * {@code @ConfigurationProperties} record silently unbinds every property.
 */
@ConfigurationProperties("accessflow.workflow.decision-hook")
public record DecisionHookProperties(
        Boolean allowPrivateNetwork,
        Integer circuitFailureThreshold,
        Duration circuitOpenDuration) {

    public DecisionHookProperties {
        if (allowPrivateNetwork == null) {
            allowPrivateNetwork = false;
        }
        if (circuitFailureThreshold == null || circuitFailureThreshold < 1) {
            circuitFailureThreshold = 5;
        }
        if (circuitOpenDuration == null || circuitOpenDuration.isNegative()
                || circuitOpenDuration.isZero()) {
            circuitOpenDuration = Duration.ofSeconds(30);
        }
    }
}
