package com.bablsoft.accessflow.scheduling.api;

/** Outcome of one recorded {@code @Scheduled} run. Mirrors the {@code job_execution_status} PG enum. */
public enum JobExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
