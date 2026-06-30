package com.bablsoft.accessflow.lifecycle.api;

/** A reviewer's decision on a deletion request. Mirrors the PostgreSQL {@code erasure_decision} enum. */
public enum ErasureDecision {
    APPROVED,
    REJECTED
}
