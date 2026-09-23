package com.bablsoft.accessflow.schemachange.api;

/**
 * Why a ladder rung cannot be promoted (#883). Evaluated in this order — the promotion gate's own
 * order, with one UI-only advisory ({@link #PARTIALLY_APPLIED}) that the gate does not enforce.
 */
public enum SchemaChangeLadderBlocker {
    /** The change set is archived — gate check 2. */
    SET_ARCHIVED,
    /** The change set has no statements — gate check 3. */
    SET_EMPTY,
    /** The environment binds no datasource, a deploy-only rung — gate check 5. */
    NO_DATASOURCE,
    /** The bound datasource no longer exists — gate check 6. */
    DATASOURCE_MISSING,
    /**
     * Advisory only: the newest promotion here ran part-way and nothing rolls it back, so the UI
     * steers the author to a new change set. The promotion gate itself does not refuse it.
     */
    PARTIALLY_APPLIED,
    /** The pipeline's environments do not carry distinct sort orders — gate check 8. */
    LADDER_INVALID,
    /** A lower rung that binds a datasource has no {@code APPLIED} promotion of the set — gate check 9. */
    LOWER_ENVIRONMENT_NOT_APPLIED,
    /** A freeze window ({@code HOLD} or {@code REJECT}) is in effect for the environment — gate check 10. */
    FREEZE_ACTIVE
}
