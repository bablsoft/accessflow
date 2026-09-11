package com.bablsoft.accessflow.sqlreview.api;

/**
 * Per-rule severity inside a ruleset (#861, epic #860). {@code OFF} is not evaluated; {@code WARN}
 * records and surfaces the finding with no workflow effect; {@code BLOCK} records the finding and
 * forces the query to human review — it never rejects.
 */
public enum SqlReviewSeverity {
    OFF,
    WARN,
    BLOCK
}
