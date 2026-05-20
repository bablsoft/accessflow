-- Generic OIDC provider type.
--
-- The four built-in providers (GOOGLE, GITHUB, MICROSOFT, GITLAB) supply their
-- URLs and userinfo-claim names from the hard-coded OAuth2ProviderTemplate
-- registry. OIDC has no such defaults; every row must carry its own URLs and
-- attribute mapping. All new columns are nullable so the fixed providers are
-- unaffected by this migration -- they continue to ignore these columns.

ALTER TYPE oauth2_provider_type ADD VALUE IF NOT EXISTS 'OIDC';

ALTER TABLE oauth2_config
    ADD COLUMN display_name              VARCHAR(255),
    ADD COLUMN authorization_uri         VARCHAR(2048),
    ADD COLUMN token_uri                 VARCHAR(2048),
    ADD COLUMN user_info_uri             VARCHAR(2048),
    ADD COLUMN jwk_set_uri               VARCHAR(2048),
    ADD COLUMN issuer_uri                VARCHAR(2048),
    ADD COLUMN user_name_attribute       VARCHAR(255),
    ADD COLUMN email_attribute           VARCHAR(255),
    ADD COLUMN email_verified_attribute  VARCHAR(255),
    ADD COLUMN display_name_attribute    VARCHAR(255),
    ADD COLUMN groups_attribute          VARCHAR(255);
