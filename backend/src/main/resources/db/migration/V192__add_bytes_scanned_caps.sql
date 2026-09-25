-- Bytes-scanned cost caps (#941). The warehouse engines' pre-flight scan estimate (AF-634) is now
-- persisted with the query's estimate, and an admin may cap it per datasource and per grant
-- (most restrictive wins; NULL = no cap). The cap is only configurable on engines that report a
-- bytes estimate (BigQuery, Snowflake, Databricks) — enforced by the service, not here.
ALTER TABLE query_estimates ADD COLUMN estimated_bytes_scanned BIGINT;

CREATE TYPE bytes_cap_missing_estimate_action AS ENUM ('REQUIRE_REVIEW', 'REJECT');
CREATE TYPE bytes_scanned_cap_source AS ENUM ('DATASOURCE', 'GRANT');
CREATE TYPE bytes_scanned_cap_outcome AS ENUM ('WITHIN', 'EXCEEDED', 'NO_ESTIMATE_REVIEW',
    'NO_ESTIMATE_REJECTED');

ALTER TABLE datasources ADD COLUMN max_bytes_scanned_per_query BIGINT
    CONSTRAINT chk_datasources_max_bytes_scanned_positive CHECK (max_bytes_scanned_per_query > 0);
ALTER TABLE datasources ADD COLUMN bytes_cap_missing_estimate bytes_cap_missing_estimate_action
    NOT NULL DEFAULT 'REQUIRE_REVIEW';

ALTER TABLE datasource_user_permissions ADD COLUMN bytes_scanned_limit_override BIGINT
    CONSTRAINT chk_dup_bytes_scanned_limit_positive CHECK (bytes_scanned_limit_override > 0);
ALTER TABLE datasource_group_permissions ADD COLUMN bytes_scanned_limit_override BIGINT
    CONSTRAINT chk_dgp_bytes_scanned_limit_positive CHECK (bytes_scanned_limit_override > 0);

-- Stamped when the query leaves PENDING_AI and a cap applied, so the detail view can say why.
ALTER TABLE query_requests ADD COLUMN bytes_scanned_cap BIGINT;
ALTER TABLE query_requests ADD COLUMN bytes_scanned_cap_source bytes_scanned_cap_source;
ALTER TABLE query_requests ADD COLUMN bytes_scanned_cap_outcome bytes_scanned_cap_outcome;
