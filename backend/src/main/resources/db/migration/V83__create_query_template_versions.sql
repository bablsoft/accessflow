-- AF-442: version history for saved query templates. Every template save (create or
-- update that actually changes content) and every restore records an immutable snapshot
-- row here, so teams can list how a template evolved, diff two revisions, and roll back.
-- Rows are INSERT-only — never mutated retroactively. Restoring a prior version writes a
-- new snapshot (change_type = RESTORED) rather than destroying history.

CREATE TYPE query_template_change_type AS ENUM ('CREATED', 'UPDATED', 'RESTORED');

CREATE TABLE query_template_versions (
    id              UUID                       PRIMARY KEY,
    template_id     UUID                       NOT NULL REFERENCES query_templates(id) ON DELETE CASCADE,
    organization_id UUID                       NOT NULL,
    version_number  INTEGER                    NOT NULL,
    datasource_id   UUID,
    name            VARCHAR(128)               NOT NULL,
    body            TEXT                       NOT NULL,
    description     VARCHAR(1000),
    tags            TEXT[]                     NOT NULL DEFAULT ARRAY[]::TEXT[],
    visibility      query_template_visibility  NOT NULL,
    change_type     query_template_change_type NOT NULL,
    -- No FK on author_id: this is an immutable audit-style row, like audit_log.actor_id.
    -- A SET NULL / CASCADE FK would mutate or erase history when the author is deleted.
    author_id       UUID                       NOT NULL,
    created_at      TIMESTAMPTZ                NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A template's version numbers are contiguous and unique; the unique index is the
-- race safety-net for the read-then-increment (max + 1) in the versioning service.
CREATE UNIQUE INDEX uq_query_template_versions_template_number
    ON query_template_versions (template_id, version_number);

CREATE INDEX idx_query_template_versions_template
    ON query_template_versions (template_id);
