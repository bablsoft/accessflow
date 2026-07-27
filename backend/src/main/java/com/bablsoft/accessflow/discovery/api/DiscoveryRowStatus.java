package com.bablsoft.accessflow.discovery.api;

/**
 * Per-finding outcome of a bulk decision (AF-623). {@code TAG_CONFLICT} means the classification
 * tag already existed (e.g. added manually since the scan) — the finding is still marked
 * CONFIRMED so the worklist clears, but no new tag or derived masking was created.
 */
public enum DiscoveryRowStatus {
    SUCCESS,
    NOT_FOUND,
    INVALID_STATE,
    TAG_CONFLICT,
    ERROR
}
