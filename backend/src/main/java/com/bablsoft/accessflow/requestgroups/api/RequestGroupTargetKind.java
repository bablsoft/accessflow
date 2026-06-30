package com.bablsoft.accessflow.requestgroups.api;

/** Whether a group member targets a database datasource ({@code QUERY}) or an AF-500 API connector. */
public enum RequestGroupTargetKind {
    QUERY,
    API_CALL
}
