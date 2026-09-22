package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Takes the post-apply schema snapshot — the {@code PROMOTION_SNAPSHOT} drift baseline (#881) —
 * once the {@code APPLIED} transition is committed. Deliberately a second listener rather than a
 * step inside {@link SchemaChangePromotionStatusListener}: introspection opens a connection to the
 * customer database, and doing that inside the projection transaction would hold the promotion
 * row's write lock and an application connection across remote I/O, and would keep the applied
 * status invisible to every reader until the introspection finished.
 *
 * <p>Idempotent — it re-reads the row and writes only while the promotion is still {@code APPLIED}
 * with no snapshot. A failure leaves the snapshot null and the transition intact: losing the
 * baseline is recoverable, losing the status is not.
 */
@Component
@RequiredArgsConstructor
class SchemaChangePromotionSnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangePromotionSnapshotListener.class);

    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final DatasourceAdminService datasourceAdminService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPromotionStatusChanged(SchemaChangePromotionStatusChangedEvent event) {
        if (event.newStatus() != SchemaChangePromotionStatus.APPLIED) {
            return;
        }
        try {
            snapshot(event);
        } catch (RuntimeException ex) {
            log.warn("Could not snapshot the target of promotion {}: {}", event.promotionId(), ex.getMessage());
        }
    }

    private void snapshot(SchemaChangePromotionStatusChangedEvent event) {
        var promotion = promotionRepository.findById(event.promotionId()).orElse(null);
        if (promotion == null || promotion.getStatus() != SchemaChangePromotionStatus.APPLIED
                || promotion.getSchemaSnapshot() != null) {
            return;
        }
        var schema = datasourceAdminService.introspectSchemaForSystem(promotion.getDatasourceId(),
                promotion.getOrganizationId());
        promotion.setSchemaSnapshot(objectMapper.writeValueAsString(schema));
        promotion.setSnapshotTakenAt(clock.instant());
        promotionRepository.saveAndFlush(promotion);
    }
}
