package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the immutable execution snapshot when a query reaches {@code EXECUTED} (AF-449).
 *
 * <p>A plain synchronous {@link EventListener} — <em>not</em> an {@code @ApplicationModuleListener} —
 * because {@link QueryExecutedEvent} is published outside a surrounding transaction (the EXECUTED
 * outcome is already committed by {@code QueryRequestStateService} before the event fires). An
 * {@code AFTER_COMMIT} transactional listener would be silently skipped when no transaction is active,
 * so the snapshot would never be written. Running synchronously also guarantees the snapshot exists the
 * moment {@code execute()} returns, so an immediate replay never races a missing snapshot.
 *
 * <p>The committed query / AI / decision rows are read back via fresh transactions inside
 * {@code recordOnExecution}. FAILED executions get no snapshot. Any failure is logged and swallowed so
 * snapshot capture never disrupts execution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class QuerySnapshotListener {

    private final QuerySnapshotService querySnapshotService;

    @EventListener
    void onQueryExecuted(QueryExecutedEvent event) {
        if (event.finalStatus() != QueryStatus.EXECUTED) {
            return;
        }
        try {
            querySnapshotService.recordOnExecution(event.queryRequestId());
        } catch (RuntimeException ex) {
            log.error("Snapshot listener failed for query {}", event.queryRequestId(), ex);
        }
    }
}
