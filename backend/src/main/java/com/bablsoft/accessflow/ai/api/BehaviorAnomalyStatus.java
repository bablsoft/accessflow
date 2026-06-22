package com.bablsoft.accessflow.ai.api;

/**
 * Lifecycle of a detected behavioural anomaly (UBA, AF-383). A freshly detected anomaly is
 * {@code OPEN}; an admin {@code ACKNOWLEDGED}s it (seen, under review) or {@code DISMISSED}es it
 * (false positive / accepted). Only {@code OPEN} anomalies raise the routing-escalation signal on a
 * user's next query.
 */
public enum BehaviorAnomalyStatus {
    OPEN,
    ACKNOWLEDGED,
    DISMISSED
}
