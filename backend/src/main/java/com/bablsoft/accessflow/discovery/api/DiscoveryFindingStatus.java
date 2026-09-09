package com.bablsoft.accessflow.discovery.api;

/**
 * Worklist state of a discovery finding (AF-623). Mirrors the PostgreSQL enum
 * {@code discovery_finding_status}. {@code CONFIRMED} and {@code DISMISSED} rows are never
 * touched by rescans — a dismissal permanently suppresses the proposal.
 *
 * <p>{@code STALE} (AF-659) is an aged {@code PENDING}, not a decision: a scan that sampled the
 * finding's table but no longer proposed the column counts it as missed, and after
 * {@code accessflow.discovery.stale-scans-before-expiry} consecutive misses the row leaves the
 * active worklist. It stays visible, filterable and decidable, and re-detection revives it to
 * {@code PENDING}.
 */
public enum DiscoveryFindingStatus {
    PENDING,
    CONFIRMED,
    DISMISSED,
    STALE
}
