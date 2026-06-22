package com.bablsoft.accessflow.ai.api;

/**
 * Compact anomaly indicator for a user (optionally scoped to one datasource): the number of OPEN
 * anomalies and the highest score among them. Drives the anomaly badge on user-list / query-detail
 * views. {@code openCount == 0} means "no badge".
 */
public record AnomalyBadgeView(int openCount, double maxScore) {

    public static AnomalyBadgeView none() {
        return new AnomalyBadgeView(0, 0.0);
    }
}
