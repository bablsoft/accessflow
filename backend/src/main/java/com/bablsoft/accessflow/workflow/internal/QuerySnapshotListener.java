package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the immutable execution snapshot when a query reaches {@code EXECUTED} (AF-449). Mirrors the
 * realtime dispatcher's listener on the same {@link QueryExecutedEvent}: runs in its own AFTER_COMMIT
 * transaction so the committed query / AI / decision rows are readable. FAILED executions get no
 * snapshot. Any failure is logged and swallowed so snapshot capture never disrupts execution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class QuerySnapshotListener {

    private final QuerySnapshotService querySnapshotService;

    @ApplicationModuleListener
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
