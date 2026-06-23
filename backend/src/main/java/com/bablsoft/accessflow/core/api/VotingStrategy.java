package com.bablsoft.accessflow.core.api;

/**
 * How a multi-model orchestration combines its members' per-model risk verdicts into one (AF-450).
 *
 * <ul>
 *     <li>{@code WEIGHTED_AVERAGE} — the aggregate risk score is the weight-weighted mean of the
 *     members' scores; the level is derived from that score.</li>
 *     <li>{@code MAX_RISK} — the single highest-risk member wins (its score, level and summary).</li>
 *     <li>{@code MAJORITY} — the weight-weighted most common risk level wins (ties break toward the
 *     higher risk); the score is the weighted average of the members at that level.</li>
 * </ul>
 *
 * Regardless of strategy, issues and optimizations are merged across members and the
 * missing-indexes flag is OR-ed.
 */
public enum VotingStrategy {
    WEIGHTED_AVERAGE,
    MAX_RISK,
    MAJORITY
}
