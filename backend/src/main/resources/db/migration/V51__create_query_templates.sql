-- AF-364: query templates library — saved SQL snippets analysts can load into the
-- editor. Owned by a user, scoped to an organisation, optionally pinned to a
-- datasource, visible PRIVATE (owner only) or TEAM (whole org reads, owner mutates).
-- :param placeholders are stored verbatim; substitution happens client-side before
-- submission. Submission still flows through POST /api/v1/queries unchanged.

CREATE TYPE query_template_visibility AS ENUM ('PRIVATE', 'TEAM');

CREATE TABLE query_templates (
    id              UUID                      PRIMARY KEY,
    organization_id UUID                      NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    owner_id        UUID                      NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    datasource_id   UUID                                REFERENCES datasources(id)  ON DELETE SET NULL,
    name            VARCHAR(128)              NOT NULL,
    body            TEXT                      NOT NULL,
    description     VARCHAR(1000),
    tags            TEXT[]                    NOT NULL DEFAULT ARRAY[]::TEXT[],
    visibility      query_template_visibility NOT NULL,
    version         BIGINT                    NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ               NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_query_templates_org_owner_name_ci
    ON query_templates (organization_id, owner_id, LOWER(name));

CREATE INDEX idx_query_templates_org_owner
    ON query_templates (organization_id, owner_id);

CREATE INDEX idx_query_templates_org_visibility
    ON query_templates (organization_id, visibility);

CREATE INDEX idx_query_templates_org_datasource
    ON query_templates (organization_id, datasource_id)
    WHERE datasource_id IS NOT NULL;

CREATE INDEX idx_query_templates_tags_gin
    ON query_templates USING GIN (tags);
