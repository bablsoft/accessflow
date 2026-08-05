-- AF-645: one advisory approval-likelihood prediction per query request, persisted when the query
-- lands in PENDING_REVIEW. skipped/failed sentinel rows (probability NULL) let the UI distinguish
-- "not enough history yet" from a scoring failure, mirroring query_estimates (V127). model_id is a
-- bare back-pointer to approval_prediction_model (no FK — models are rewritten by the retrain job).

CREATE TABLE approval_predictions (
    id                     UUID             PRIMARY KEY,
    query_request_id       UUID             NOT NULL UNIQUE REFERENCES query_requests(id) ON DELETE CASCADE,
    probability            DOUBLE PRECISION,            -- NULL when skipped or failed
    model_id               UUID,
    feature_schema_version INTEGER,
    features               JSONB,                       -- serving-time feature snapshot (explainability)
    skipped                BOOLEAN          NOT NULL DEFAULT false,
    skipped_reason         VARCHAR(500),
    failed                 BOOLEAN          NOT NULL DEFAULT false,
    error_message          VARCHAR(500),
    created_at             TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
