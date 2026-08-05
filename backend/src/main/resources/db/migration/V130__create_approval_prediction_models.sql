-- AF-645: approval-outcome prediction. One row per organization holding a hand-rolled logistic
-- regression model trained daily on the org's historical human review decisions (auto-approval
-- paths are excluded — they carry no reviewer judgment). The model serves an advisory-only
-- "historical approval likelihood" triage signal to reviewers; it is never an input to routing,
-- grant coverage, or any decision path. coefficients holds the full standardized model as a JSONB
-- blob because the scorer always reads it as a unit and adding a feature later needs no migration.

CREATE TABLE approval_prediction_model (
    id                     UUID             PRIMARY KEY,
    organization_id        UUID             NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    feature_schema_version INTEGER          NOT NULL,   -- feature-extractor schema the model was trained against
    coefficients           JSONB            NOT NULL,   -- {intercept, weights: {name: value}, means: {...}, stddevs: {...}}
    training_samples       INTEGER          NOT NULL,
    positive_samples       INTEGER          NOT NULL,
    auc                    DOUBLE PRECISION,            -- holdout AUC (nullable when holdout evaluation unavailable)
    accuracy               DOUBLE PRECISION,
    serving                BOOLEAN          NOT NULL DEFAULT false,  -- quality gate: >=50 samples, >=10 per class, AUC >= threshold
    trained_at             TIMESTAMPTZ      NOT NULL,
    version                BIGINT           NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
