-- Calling application (#938): which application submitted a request. Identification and audit
-- only — never an authorization input.
--   API_KEY — the name stored on the api_keys row that authenticated the call. Trustworthy: it
--             cannot be forged without the key.
--   HEADER  — the caller-supplied X-AccessFlow-Application header. Entirely client-controlled.
-- audit_log gets NO column (AuditChainHasher canonicalises a fixed ten-field list); the name rides
-- in metadata as application_name / application_name_source, which the MAC already covers.
CREATE TYPE application_name_source AS ENUM ('API_KEY', 'HEADER');

ALTER TABLE api_keys ADD COLUMN application_name VARCHAR(100);

ALTER TABLE query_requests ADD COLUMN application_name VARCHAR(100);
ALTER TABLE query_requests ADD COLUMN application_name_source application_name_source;

CREATE INDEX idx_query_requests_application_name
    ON query_requests (application_name)
    WHERE application_name IS NOT NULL;
