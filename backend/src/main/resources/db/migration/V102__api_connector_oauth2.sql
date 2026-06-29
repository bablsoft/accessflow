-- AF-500 / #506: OAuth2 token sourcing for API connectors. When a connector authenticates to its
-- upstream with auth_method = OAUTH2_CLIENT_CREDENTIALS, AccessFlow obtains the access token itself
-- from the configured token endpoint (client-credentials, refresh-token, or resource-owner password
-- grant), caches it, and refreshes it automatically — no more hand-pasted bearer tokens.
--
-- Non-secret config (token_uri, client_id, scopes, audience, username, grant_type, client_auth) lives
-- in returnable columns; the client secret, refresh token, and resource-owner password are AES-256-GCM
-- ciphertext (@JsonIgnore, never serialized). The existing auth_credentials_encrypted map is untouched
-- and still backs the API_KEY / BEARER_TOKEN / BASIC / CUSTOM_HEADER methods.

CREATE TYPE oauth2_grant_type  AS ENUM ('CLIENT_CREDENTIALS', 'REFRESH_TOKEN', 'PASSWORD');
CREATE TYPE oauth2_client_auth AS ENUM ('CLIENT_SECRET_BASIC', 'CLIENT_SECRET_POST');

ALTER TABLE api_connectors
    ADD COLUMN oauth2_token_uri               TEXT,
    ADD COLUMN oauth2_client_id               TEXT,
    ADD COLUMN oauth2_client_secret_encrypted TEXT,
    ADD COLUMN oauth2_scopes                  TEXT,
    ADD COLUMN oauth2_audience                TEXT,
    ADD COLUMN oauth2_refresh_token_encrypted TEXT,
    ADD COLUMN oauth2_username                TEXT,
    ADD COLUMN oauth2_password_encrypted      TEXT,
    ADD COLUMN oauth2_grant_type   oauth2_grant_type  NOT NULL DEFAULT 'CLIENT_CREDENTIALS',
    ADD COLUMN oauth2_client_auth  oauth2_client_auth NOT NULL DEFAULT 'CLIENT_SECRET_BASIC';
