package com.bablsoft.accessflow.core.api;

import java.time.Duration;

/**
 * The bounds a policy simulation runs within (issue AF-630). One source of truth, consumed by both
 * the routing simulator in {@code workflow} and the row-security / masking simulators in
 * {@code proxy}, so the three cannot drift into different limits.
 */
public interface PolicySimulationLimits {

    /** Hard cap on rows replayed by one simulation; beyond it the result is marked truncated. */
    int maxRows();

    /** Hard cap on drill-down rows returned. The counts are the answer; samples are the detail. */
    int maxSamples();

    /** Hard cap on entries in the per-user impact list. */
    int maxUserImpacts();

    /** Longest window a single simulation may span. */
    Duration maxWindow();
}
