package com.bablsoft.accessflow.requestgroups.api;

/** Per-member execution outcome within a grouped run. */
public enum RequestGroupItemStatus {
    PENDING,
    EXECUTED,
    FAILED,
    SKIPPED,
    CANCELLED
}
