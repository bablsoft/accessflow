package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeInProgressException;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeTrigger;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Cluster-wide guard around an on-demand suggestion rebuild (#776), following
 * {@code DiscoveryScanService.scanAsync} (AF-660).
 *
 * <p>The lock is taken on the request thread so the endpoint can answer 202 or 409 accurately
 * across every replica; only the rebuild itself is handed to the virtual-thread executor. Taking it
 * inside the executor instead would make the answer a guess, and holding it on the request thread
 * for the whole rebuild would occupy that thread for the duration.
 */
@Service
@RequiredArgsConstructor
class DefaultQuerySuggestionRecomputeTrigger implements QuerySuggestionRecomputeTrigger {

    private final DatasourceAdminService datasourceAdminService;
    private final DistributedLockService distributedLockService;
    private final QuerySuggestionAggregationService aggregationService;
    private final QuerySuggestionProperties properties;
    private final ExecutorService querySuggestionExecutor;

    @Override
    public void requestRecompute(UUID datasourceId, UUID organizationId) {
        // 404 before 409: an unknown datasource must not be distinguishable from a busy one.
        datasourceAdminService.getForAdmin(datasourceId, organizationId);
        boolean started = distributedLockService.runLockedAsync(lockName(datasourceId),
                properties.recomputeLockAtMostFor(), querySuggestionExecutor,
                () -> aggregationService.aggregateDatasource(organizationId, datasourceId));
        if (!started) {
            throw new QuerySuggestionRecomputeInProgressException(datasourceId);
        }
    }

    /** Shares the ShedLock namespace with the scheduled job, so the two can never overlap. */
    private static String lockName(UUID datasourceId) {
        return "querySuggestionRecompute:" + datasourceId;
    }
}
