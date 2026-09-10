package com.bablsoft.accessflow.workflow.api;

/**
 * The class of statement a reverse-index query asks about (issue AF-859), mapped onto the three
 * per-datasource capability flags.
 */
public enum StatementCapability {

    /** {@code SELECT} — needs {@code can_read}. */
    READ,

    /** {@code INSERT} / {@code UPDATE} / {@code DELETE} — all need {@code can_write}. */
    WRITE,

    /** Schema changes — need {@code can_ddl}. */
    DDL
}
