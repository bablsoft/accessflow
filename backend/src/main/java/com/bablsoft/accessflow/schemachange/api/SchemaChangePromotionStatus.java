package com.bablsoft.accessflow.schemachange.api;

/**
 * Lifecycle of one promotion attempt of a change set to one environment (#878, epic #870) — maps
 * to the {@code schema_change_promotion_status} PG enum. {@code PENDING}, {@code IN_REVIEW} and
 * {@code APPROVED} are the non-terminal states, of which at most one may exist per change set
 * and environment (the partial unique index {@code uq_schema_change_set_promotions_open});
 * {@code APPLIED} is the only outcome the ladder gate counts. {@code PARTIALLY_APPLIED} means a
 * statement failed part-way — there is no rollback at all, each statement runs autocommit.
 */
public enum SchemaChangePromotionStatus {
    PENDING,
    IN_REVIEW,
    APPROVED,
    APPLIED,
    FAILED,
    PARTIALLY_APPLIED,
    CANCELLED;

    /** True for the states no further transition leaves — the complement of the partial index. */
    public boolean isTerminal() {
        return switch (this) {
            case PENDING, IN_REVIEW, APPROVED -> false;
            case APPLIED, FAILED, PARTIALLY_APPLIED, CANCELLED -> true;
        };
    }
}
