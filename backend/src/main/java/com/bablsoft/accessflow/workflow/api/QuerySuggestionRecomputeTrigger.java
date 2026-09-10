package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * The on-demand "recompute now" entry point for automatic query suggestions (#776), mirroring
 * {@code DiscoveryScanTriggerService}.
 *
 * <p>An operator who has just imported history, widened the lookback, or onboarded a datasource
 * should not wait out a poll interval to see whether suggestions appear.
 */
public interface QuerySuggestionRecomputeTrigger {

    /**
     * Starts a rebuild of one datasource's suggestions on a background thread.
     *
     * @throws com.bablsoft.accessflow.core.api.DatasourceNotFoundException when the datasource is
     *         not in the caller's organisation.
     * @throws QuerySuggestionRecomputeInProgressException when another replica — or the scheduled
     *         job — is already rebuilding this datasource.
     */
    void requestRecompute(UUID datasourceId, UUID organizationId);
}
