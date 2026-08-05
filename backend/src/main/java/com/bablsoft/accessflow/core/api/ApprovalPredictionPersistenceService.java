package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Write side of {@code approval_predictions} (issue AF-645), for the {@code ai} module's serving
 * path. Insert-once with a single update path: when a row already exists for the query request it
 * is left untouched and its id returned — <em>unless</em> the existing row's feature snapshot
 * carried {@code estimate_missing=true}, in which case the row is replaced in place (the cost
 * estimate arrived late and the prediction was recomputed once). The row id and
 * {@code created_at} are stable across the replace. A row whose feature snapshot is null or lacks
 * the key — including the skipped / failed sentinel rows — is never replaced.
 */
public interface ApprovalPredictionPersistenceService {

    /** Persists the prediction and returns the persisted-or-existing row id. */
    UUID persist(PersistApprovalPredictionCommand command);
}
