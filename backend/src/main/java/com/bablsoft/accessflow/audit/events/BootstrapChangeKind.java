package com.bablsoft.accessflow.audit.events;

/**
 * Whether a {@link BootstrapResourceUpsertedEvent} represents a brand-new resource ({@link #CREATE})
 * or a modification to an existing one ({@link #UPDATE}). No-op reconciles never publish an event.
 */
public enum BootstrapChangeKind {
    CREATE,
    UPDATE
}
