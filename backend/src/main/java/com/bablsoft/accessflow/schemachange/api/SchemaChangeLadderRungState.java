package com.bablsoft.accessflow.schemachange.api;

/** Where one environment of a change set's ladder stands (#883). */
public enum SchemaChangeLadderRungState {
    /** An {@code APPLIED} promotion of the set exists here — the ladder gate counts it. */
    APPLIED,
    /** A {@code PENDING}, {@code IN_REVIEW} or {@code APPROVED} promotion is open here. */
    IN_PROGRESS,
    /** The previewed gate passes; the promotion call still runs the full gate. */
    PROMOTABLE,
    /** Not promotable; {@code blocker} says why. */
    BLOCKED
}
