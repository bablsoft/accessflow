package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;

import java.time.Instant;
import java.util.List;

/**
 * One implementation per {@link AuditSinkType}, resolved by the drain job and the test
 * dispatcher. Implementations decode their own typed config from the sink row, enforce short
 * (≤10s) connect/read timeouts so a hung destination cannot exhaust the drain job's scheduler
 * lock, and throw {@link AuditSinkDeliveryException} on any delivery failure.
 */
public interface AuditSinkDeliverer {

    AuditSinkType type();

    /** Delivers one ordered batch. Throws {@link AuditSinkDeliveryException} on failure. */
    void deliver(AuditSinkEntity sink, List<AuditExportEvent> batch);

    /**
     * Whether the pending batch should be delivered this tick. Streaming sinks always say yes;
     * the S3 segment sink holds back until the batch is full or the oldest pending row is older
     * than the configured segment age, so segments stay chunky. Returning {@code false} is a
     * clean skip: no error, no cursor movement.
     */
    default boolean readyToDeliver(AuditSinkEntity sink, List<AuditExportEvent> batch,
                                   int fullBatchSize, Instant now) {
        return true;
    }

    /**
     * Delivers a single synthetic event for the admin "test" action. The S3 sink overrides this
     * to upload an unlocked test object rather than burning a WORM-retained segment.
     */
    default void deliverTest(AuditSinkEntity sink, AuditExportEvent event) {
        deliver(sink, List.of(event));
    }
}
