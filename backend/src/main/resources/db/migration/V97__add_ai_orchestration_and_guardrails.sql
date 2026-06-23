-- AF-450: multi-model orchestration, voting & guardrails with per-analysis cost/latency.
-- An ai_config can now run several models in parallel (the primary row + ai_config_model members),
-- aggregate their verdicts via a voting strategy, and block configured prompt patterns before the
-- model call. Each analysis records a per-model cost/latency breakdown in ai_analysis_model_result.
-- All ai_config columns are defaulted so the migration is zero-downtime and orchestration stays
-- opt-in (orchestration_enabled defaults FALSE; existing single-model configs are unaffected).

CREATE TYPE voting_strategy AS ENUM ('WEIGHTED_AVERAGE', 'MAX_RISK', 'MAJORITY');

ALTER TABLE ai_config
    ADD COLUMN orchestration_enabled BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN voting_strategy       voting_strategy  NOT NULL DEFAULT 'WEIGHTED_AVERAGE',
    -- Weight of the primary model in the weighted vote (members carry their own weight).
    ADD COLUMN voting_weight         DOUBLE PRECISION NOT NULL DEFAULT 1.0
        CHECK (voting_weight > 0),
    -- JSON array of case-insensitive Java regex patterns; matching SQL / NL prompts are blocked
    -- before any model call. Empty array = guardrails off.
    ADD COLUMN guardrail_patterns    JSONB            NOT NULL DEFAULT '[]';

-- Additional orchestration members. Each carries its own provider/model/endpoint/key + weight and
-- inherits the parent ai_config's timeout / max-completion-tokens / prompt / RAG. The encrypted key
-- is AES-256-GCM, never serialized.
CREATE TABLE ai_config_model (
    id                UUID             PRIMARY KEY,
    ai_config_id      UUID             NOT NULL REFERENCES ai_config(id) ON DELETE CASCADE,
    provider          ai_provider      NOT NULL,
    model             VARCHAR(100)     NOT NULL,
    endpoint          VARCHAR(500),
    api_key_encrypted TEXT,
    weight            DOUBLE PRECISION NOT NULL DEFAULT 1.0 CHECK (weight > 0),
    enabled           BOOLEAN          NOT NULL DEFAULT TRUE,
    sort_order        INTEGER          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_config_model_config ON ai_config_model (ai_config_id, sort_order);

-- Per-model breakdown of one ai_analyses row (written for every analysis — single-model configs get
-- exactly one row). risk_score / risk_level are null for a member that failed. latency_ms is the
-- per-member wall-clock of the provider call.
CREATE TABLE ai_analysis_model_result (
    id                UUID             PRIMARY KEY,
    ai_analysis_id    UUID             NOT NULL REFERENCES ai_analyses(id) ON DELETE CASCADE,
    ai_provider       ai_provider      NOT NULL,
    ai_model          VARCHAR(100)     NOT NULL,
    risk_score        INTEGER          CHECK (risk_score BETWEEN 0 AND 100),
    risk_level        risk_level,
    weight            DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    prompt_tokens     INTEGER          NOT NULL DEFAULT 0,
    completion_tokens INTEGER          NOT NULL DEFAULT 0,
    latency_ms        BIGINT           NOT NULL DEFAULT 0,
    failed            BOOLEAN          NOT NULL DEFAULT FALSE,
    error_message     TEXT,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_analysis_model_result_analysis ON ai_analysis_model_result (ai_analysis_id);
CREATE INDEX idx_ai_analysis_model_result_model    ON ai_analysis_model_result (ai_provider, ai_model);
