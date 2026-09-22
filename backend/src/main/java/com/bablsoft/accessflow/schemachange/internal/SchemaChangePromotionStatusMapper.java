package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;

import java.util.Optional;

/**
 * Projects a request group's status onto its promotion (#880). {@link #map} is the status table;
 * {@link #advances} is the monotonic guard — group events are delivered asynchronously and may
 * arrive out of order (the APPROVED event from the AI listener's transaction, EXECUTED from the
 * job thread), so a promotion only ever moves forward and a terminal state is final.
 */
final class SchemaChangePromotionStatusMapper {

    private SchemaChangePromotionStatusMapper() {
    }

    /** Empty for the group states that have no promotion counterpart (DRAFT, PENDING_AI, EXECUTING). */
    static Optional<SchemaChangePromotionStatus> map(RequestGroupStatus status) {
        return Optional.ofNullable(switch (status) {
            case PENDING_REVIEW -> SchemaChangePromotionStatus.IN_REVIEW;
            case APPROVED -> SchemaChangePromotionStatus.APPROVED;
            case EXECUTED -> SchemaChangePromotionStatus.APPLIED;
            case PARTIALLY_EXECUTED -> SchemaChangePromotionStatus.PARTIALLY_APPLIED;
            case FAILED -> SchemaChangePromotionStatus.FAILED;
            case REJECTED, TIMED_OUT, CANCELLED -> SchemaChangePromotionStatus.CANCELLED;
            case DRAFT, PENDING_AI, EXECUTING -> null;
        });
    }

    /** True only when {@code next} is strictly further along than {@code current}. */
    static boolean advances(SchemaChangePromotionStatus current, SchemaChangePromotionStatus next) {
        if (current.isTerminal()) {
            return false;
        }
        return rank(next) > rank(current);
    }

    private static int rank(SchemaChangePromotionStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case IN_REVIEW -> 1;
            case APPROVED -> 2;
            case APPLIED, FAILED, PARTIALLY_APPLIED, CANCELLED -> 3;
        };
    }
}
