-- #776: automatic query suggestions. Precomputed, per-datasource read model over the
-- organisation's own approved query history, refreshed by QuerySuggestionAggregationJob.
-- Advisory only: a row here is a draft an analyst may load into the editor, never anything
-- that executes on its own, and applying one still submits through POST /api/v1/queries.
--
-- referenced_tables is NEVER empty: the aggregation drops a group whose tables it could not
-- resolve, because DatasourcePermissionChecker.rejectedTables() treats an empty set as "nothing
-- to reject" and such a row would pass every viewer's allow-list unconditionally.
--
-- submitter_ids records (capped) who ran the shape. It is read back only to build the viewer's own
-- table affinity for the ranking's overlap term — the analyst who has been working in these tables
-- sees them first — and is never exposed on the wire; the API returns counts, not identities.
--
-- The group key is canonical_hash — a SHA-256 hex digest of core.api.SqlCanonicalizer's
-- output — rather than the canonical text itself, because a btree unique index caps at
-- ~2704 bytes and a long approved query would overflow it. query_requests.canonical_sql is
-- deliberately NOT reused as the key: it is stamped only on execution, so grouping by it
-- would silently drop every query that was approved but never run.

CREATE TABLE query_suggestions (
    id                       UUID        PRIMARY KEY,
    organization_id          UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    datasource_id            UUID        NOT NULL REFERENCES datasources(id)   ON DELETE CASCADE,
    canonical_hash           VARCHAR(64) NOT NULL,
    sql_text                 TEXT        NOT NULL,
    query_type               query_type  NOT NULL,
    referenced_tables        TEXT[]      NOT NULL DEFAULT ARRAY[]::TEXT[],
    submitter_ids            UUID[]      NOT NULL DEFAULT ARRAY[]::UUID[],
    approved_count           INTEGER     NOT NULL DEFAULT 0,
    distinct_submitter_count INTEGER     NOT NULL DEFAULT 0,
    first_submitted_at       TIMESTAMPTZ NOT NULL,
    last_submitted_at        TIMESTAMPTZ NOT NULL,
    computed_at              TIMESTAMPTZ NOT NULL,
    version                  BIGINT      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_query_suggestions_ds_hash
    ON query_suggestions (datasource_id, canonical_hash);

CREATE INDEX idx_query_suggestions_ds_rank
    ON query_suggestions (datasource_id, last_submitted_at DESC);

CREATE INDEX idx_query_suggestions_org
    ON query_suggestions (organization_id);

CREATE INDEX idx_query_suggestions_tables_gin
    ON query_suggestions USING GIN (referenced_tables);
