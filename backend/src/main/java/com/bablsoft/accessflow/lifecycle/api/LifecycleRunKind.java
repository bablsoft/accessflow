package com.bablsoft.accessflow.lifecycle.api;

/** Origin of a lifecycle run. Mirrors the PostgreSQL {@code lifecycle_run_kind} enum. */
public enum LifecycleRunKind {
    RETENTION_POLICY,
    ERASURE_REQUEST
}
