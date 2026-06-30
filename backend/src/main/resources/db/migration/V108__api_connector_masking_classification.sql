-- AF-518: masking & data classification for API connectors. Brings the database-side governance
-- model — dynamic masking policies (AF-381) and data-classification tagging with derived masking +
-- AI risk (AF-447) — to the apigov module, adapted to non-tabular API responses. Because API
-- responses aren't columnar, a masked field is targeted three ways: a schema-bound field (operation
-- + field, when the connector has an ingested schema), a JSON/XML path into the response body, or a
-- regex over the body. Enforcement runs at response-read time in ApiResponseMasker, before the
-- snapshot is persisted, so unmasked values never persist. Reuses the existing masking_strategy
-- (AF-381) and data_classification (AF-447) enum types.

CREATE TYPE api_masking_matcher_type AS ENUM ('SCHEMA_FIELD', 'JSON_PATH', 'XML_PATH', 'REGEX');

-- Per-connector, org-scoped masking policies. matcher_type selects how field_ref targets the field:
-- SCHEMA_FIELD (operation_id required) resolves through the parsed operation catalog; JSON_PATH /
-- XML_PATH / REGEX match the response body directly. A submitter in any reveal_to_* list sees the
-- unmasked value; everyone else gets the strategy output. strategy_params carries strategy tuning
-- (e.g. visible_suffix length) like masking_policy.
CREATE TABLE api_connector_masking_policy (
    id                  UUID                     PRIMARY KEY,
    organization_id     UUID                     NOT NULL,
    connector_id        UUID                     NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    matcher_type        api_masking_matcher_type NOT NULL,
    operation_id        TEXT,
    field_ref           TEXT                     NOT NULL,
    strategy            masking_strategy         NOT NULL,
    strategy_params     JSONB                    NOT NULL DEFAULT '{}',
    reveal_to_roles     TEXT[],
    reveal_to_group_ids UUID[],
    reveal_to_user_ids  UUID[],
    enabled             BOOLEAN                  NOT NULL DEFAULT true,
    version             BIGINT                   NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the per-execution resolution scan (enabled policies for one connector).
CREATE INDEX idx_api_masking_policy_connector_enabled
    ON api_connector_masking_policy (organization_id, connector_id, enabled);

-- Per-connector classification tags (PII / PCI / PHI / GDPR / FINANCIAL / SENSITIVE). Mirrors
-- data_classification_tag: tags raise the apigov AI analyzer's risk score for requests that
-- reference the tagged operation, and drive auto-derivation of a stricter masking policy. One row
-- per (object, classification); operation_id NULL = a connector-level tag (informational; raises
-- review posture + AI risk but derives no masking).
CREATE TABLE api_connector_classification_tag (
    id              UUID                     PRIMARY KEY,
    organization_id UUID                     NOT NULL,
    connector_id    UUID                     NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    operation_id    TEXT,
    field_ref       TEXT                     NOT NULL,
    matcher_type    api_masking_matcher_type NOT NULL,
    classification  data_classification      NOT NULL,
    note            TEXT,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Per-connector list / derivation scan.
CREATE INDEX idx_api_classification_tag_connector
    ON api_connector_classification_tag (organization_id, connector_id);

-- Uniqueness across (org, connector, operation_id, field_ref, classification). COALESCE collapses a
-- NULL operation_id to '' so duplicate connector-level tags are rejected too (mirror uq_dct_object_class).
CREATE UNIQUE INDEX uq_api_classification_tag_object_class ON api_connector_classification_tag
    (organization_id, connector_id, COALESCE(operation_id, ''), field_ref, classification);
