package com.bablsoft.accessflow.discovery.api;

/**
 * Worklist state of a discovery finding (AF-623). Mirrors the PostgreSQL enum
 * {@code discovery_finding_status}. {@code CONFIRMED} and {@code DISMISSED} rows are never
 * touched by rescans — a dismissal permanently suppresses the proposal.
 */
public enum DiscoveryFindingStatus {
    PENDING,
    CONFIRMED,
    DISMISSED
}
