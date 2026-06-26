-- AF-500: API Access Governance — foundation. A new `apigov` module governs outbound HTTP/RPC API
-- calls (REST / SOAP / GraphQL / gRPC) with the same review → approval → audit machinery as a
-- database query. This migration lands the connector-management foundation: an admin registers an
-- API connector (URL + auth + protocol), uploads a schema (OpenAPI / WSDL / GraphQL SDL / gRPC
-- proto) parsed into a normalized operation catalog with read/write classification, and shares
-- governed connectivity with the team via per-user/per-connector permissions. The governed-call
-- pipeline (api_requests, AI risk + text-to-API, review/execute, routing, break-glass) follows in a
-- subsequent migration.
--
-- Cross-module references (organization_id / created_by / review_plan_id / ai_config_id) are bare
-- UUIDs (no FK), like access_grant_request / break_glass_events / audit_log, so the apigov rows
-- survive after the referenced aggregate is deleted.

CREATE TYPE api_protocol AS ENUM ('REST', 'SOAP', 'GRAPHQL', 'GRPC');
CREATE TYPE api_auth_method AS ENUM (
    'NONE', 'API_KEY', 'BEARER_TOKEN', 'BASIC', 'OAUTH2_CLIENT_CREDENTIALS', 'CUSTOM_HEADER', 'MTLS'
);
CREATE TYPE api_schema_type AS ENUM ('OPENAPI', 'WSDL', 'GRAPHQL_SDL', 'GRPC_PROTO');

-- Governed API targets (per org). `auth_credentials_encrypted` is AES-256-GCM ciphertext, never
-- serialized (@JsonIgnore). `default_headers` is a JSON object merged into every outbound call.
CREATE TABLE api_connectors (
    id                          UUID            PRIMARY KEY,
    organization_id             UUID            NOT NULL,
    name                        VARCHAR(255)    NOT NULL,
    protocol                    api_protocol    NOT NULL,
    base_url                    TEXT            NOT NULL,
    default_headers             JSONB           NOT NULL DEFAULT '{}',
    timeout_ms                  INTEGER         NOT NULL DEFAULT 30000,
    tls_verify                  BOOLEAN         NOT NULL DEFAULT TRUE,
    auth_method                 api_auth_method NOT NULL DEFAULT 'NONE',
    auth_credentials_encrypted  TEXT,
    review_plan_id              UUID,
    ai_analysis_enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    ai_config_id                UUID,
    text_to_api_enabled         BOOLEAN         NOT NULL DEFAULT FALSE,
    require_review_reads        BOOLEAN         NOT NULL DEFAULT FALSE,
    require_review_writes       BOOLEAN         NOT NULL DEFAULT TRUE,
    max_response_bytes          BIGINT          NOT NULL DEFAULT 1048576,
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_api_connectors_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_api_connectors_org ON api_connectors (organization_id, is_active);

-- Uploaded schema documents per connector. `parsed_operations` caches the normalized operation
-- catalog (re-parsed on upload). Either raw_content (uploaded body) or source_url (fetched) is set.
CREATE TABLE api_schemas (
    id                 UUID            PRIMARY KEY,
    connector_id       UUID            NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    schema_type        api_schema_type NOT NULL,
    raw_content        TEXT,
    source_url         TEXT,
    parsed_operations  JSONB           NOT NULL DEFAULT '[]',
    operation_count    INTEGER         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_schemas_connector ON api_schemas (connector_id, created_at DESC);

-- "Share API connectivity with the team" — per user, per connector (mirror of
-- datasource_user_permissions). allowed_operations scopes which operations a user may call (null =
-- all); restricted_response_fields are dot-paths masked in the response for this user.
CREATE TABLE api_connector_user_permissions (
    id                        UUID        PRIMARY KEY,
    connector_id              UUID        NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    user_id                   UUID        NOT NULL,
    can_read                  BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write                 BOOLEAN     NOT NULL DEFAULT FALSE,
    can_break_glass           BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at                TIMESTAMPTZ,
    allowed_operations        TEXT[],
    restricted_response_fields TEXT[],
    created_by                UUID        NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_api_connector_user_perms UNIQUE (connector_id, user_id)
);

CREATE INDEX idx_api_connector_perms_user ON api_connector_user_permissions (user_id);
