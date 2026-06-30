package com.bablsoft.accessflow.lifecycle.api;

/** Lifecycle of a staged/executed run. Mirrors the PostgreSQL {@code lifecycle_run_status} enum. */
public enum LifecycleRunStatus {
    STAGED,
    EXECUTING,
    COMPLETED,
    FAILED
}
