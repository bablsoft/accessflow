-- GitHub Enterprise Server and self-managed GitLab support.
--
-- Both variants reuse the URL conventions of their cloud counterparts but with a
-- configurable base URL ({base}/login/oauth/authorize, {base}/oauth/authorize, ...).
-- base_url is the only operator-editable URL surface; well-known sub-paths remain
-- compiled into OAuth2ProviderTemplate so admin-entered values cannot redirect users
-- to a hostile authorization server. base_url is nullable because the existing five
-- providers do not use it; the service-layer activation check enforces presence for
-- the two enterprise variants (mirroring how tenant_id is required for MICROSOFT).

ALTER TYPE oauth2_provider_type ADD VALUE IF NOT EXISTS 'GITHUB_ENTERPRISE';
ALTER TYPE oauth2_provider_type ADD VALUE IF NOT EXISTS 'GITLAB_ENTERPRISE';

ALTER TABLE oauth2_config
    ADD COLUMN base_url VARCHAR(2048);
