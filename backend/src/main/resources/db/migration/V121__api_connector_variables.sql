-- AF-613: dynamic variables for API connectors. An API connector could previously only send static
-- values — default_headers is a fixed map and ApiConnectorAuthApplier builds one of a fixed set of
-- auth headers — which rules out every API whose contract requires a value computed per request:
-- HMAC request signing, nonces, timestamps, correlation ids, idempotency keys, digests. A submitter
-- cannot hand-compute those for a governed request, because the signature covers a body the reviewer
-- saw minutes or hours earlier and any timestamp would be stale by execution time.
--
-- A variable is a named expression evaluated at execution time and substituted into headers, path,
-- query and body via {{name}} placeholders. Evaluation happens AFTER auth headers are resolved
-- (including a freshly minted OAuth2 token), so an expression may sign the finished Authorization
-- header — the motivating vendor scheme. Expressions are template substitution over a fixed function
-- set only: no scripting engine, no eval, mirroring the engine plugins' rejection of $where/Painless.
--
-- Ordering deliberately deviates from the AF-518 masking child-table idiom (ORDER BY created_at).
-- Masking policies are order-insensitive (all apply); variable evaluation order is observable, and
-- created_at defaults to CURRENT_TIMESTAMP which is transaction-constant in Postgres — two rows
-- inserted in one transaction would tie exactly. Hence an explicit sort_order, with (sort_order,
-- created_at, id) forming a total order for the topological tie-break.

CREATE TYPE api_variable_kind AS ENUM (
    'UUID', 'TIMESTAMP', 'EPOCH_MILLIS', 'RANDOM_HEX', 'HMAC', 'HASH', 'ENCODE', 'CONSTANT'
);

CREATE TYPE api_variable_algorithm AS ENUM ('HMAC_SHA256', 'HMAC_SHA512', 'SHA256', 'MD5');

CREATE TYPE api_variable_encoding AS ENUM ('HEX', 'BASE64', 'BASE64URL');

-- Per-connector, org-scoped dynamic variables. `expression` is the input template for the value and
-- is itself placeholder-expanded against the in-flight request ({{request.method|path|query|body}},
-- {{request.headers.<Name>}}) and other variables ({{var.<name>}}); resolution order is a DAG over
-- those references, with cycles rejected at save time rather than at execution time. `target`
-- optionally auto-injects the resolved value without an explicit placeholder: 'header:<Name>' or
-- 'query:<name>', applied after substitution. `secret_encrypted` holds the AES-256-GCM shared key
-- for kind=HMAC and follows the same rules as api_connectors.auth_credentials_encrypted — @JsonIgnore
-- on the entity, never returned in a GET, never logged.
--
-- `overridable` opts a variable into per-request overrides (see api_requests.variable_overrides).
-- Deny by default. The CHECK constraint is belt-and-braces with the service-layer validation: a
-- secret-bearing variable must never be overridable, and that rule must survive a manual data fix.
CREATE TABLE api_connector_variables (
    id               UUID                   PRIMARY KEY,
    organization_id  UUID                   NOT NULL,
    connector_id     UUID                   NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    name             TEXT                   NOT NULL,
    kind             api_variable_kind      NOT NULL,
    expression       TEXT,
    algorithm        api_variable_algorithm,
    encoding         api_variable_encoding,
    secret_encrypted TEXT,
    target           TEXT,
    overridable      BOOLEAN                NOT NULL DEFAULT false,
    description      TEXT,
    sort_order       INT                    NOT NULL DEFAULT 0,
    version          BIGINT                 NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_acv_override_no_secret CHECK (NOT (overridable AND secret_encrypted IS NOT NULL))
);

-- Names are the placeholder keys, so they must be unique per connector or {{name}} is ambiguous.
CREATE UNIQUE INDEX uq_api_connector_variable_name
    ON api_connector_variables (organization_id, connector_id, name);

-- Backs the per-execution resolution scan, already in evaluation order.
CREATE INDEX idx_api_connector_variable_connector
    ON api_connector_variables (organization_id, connector_id, sort_order);

-- Per-request overrides of connector variables marked overridable. Persisted (not transient) because
-- reviewers approve based on stored state and the submit -> review -> execute gap is asynchronous:
-- an override that wasn't stored and shown at review time would let a submitter change the effective
-- request after approval. Immutable after submit. Values are inserted verbatim and never rendered as
-- templates, so an override can never expand into another variable's value.
ALTER TABLE api_requests ADD COLUMN variable_overrides JSONB NOT NULL DEFAULT '{}';

-- Supplying overrides is a distinct capability from submitting, gated per connector exactly like
-- can_break_glass and OR-merged across user + group grants in EffectiveApiConnectorPermissionResolver.
ALTER TABLE api_connector_user_permissions
    ADD COLUMN can_override_variables BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE api_connector_group_permissions
    ADD COLUMN can_override_variables BOOLEAN NOT NULL DEFAULT FALSE;
