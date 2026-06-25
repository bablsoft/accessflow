-- Personalized dashboard (AF-498): per-item AI-suggestion lifecycle overrides and the opt-in
-- weekly-digest subscription. Both are self-scoped (user_id) and owned by the `dashboard` module.

-- A suggestion is implicitly OPEN; a row exists only when the user diverged from that default.
-- The enum type is named distinctly from the table — in Postgres a table and a type share one
-- namespace, so a same-named enum and table would collide.
CREATE TYPE dashboard_suggestion_status AS ENUM ('OPEN', 'APPLIED', 'DISMISSED');

CREATE TABLE dashboard_suggestion_state (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ai_analysis_id   UUID NOT NULL REFERENCES ai_analyses (id) ON DELETE CASCADE,
    suggestion_index INTEGER NOT NULL,
    status           dashboard_suggestion_status NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dashboard_suggestion_state
        UNIQUE (organization_id, user_id, ai_analysis_id, suggestion_index)
);

-- One opt-in row per user; last_sent_at lets WeeklyDigestJob fire at most once per configured period.
CREATE TABLE dashboard_digest_subscription (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    enabled          BOOLEAN NOT NULL DEFAULT false,
    last_sent_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0
);

-- Partial index for the job's "due" scan: enabled subscriptions ordered by last_sent_at.
CREATE INDEX idx_dashboard_digest_due
    ON dashboard_digest_subscription (last_sent_at)
    WHERE enabled = true;
