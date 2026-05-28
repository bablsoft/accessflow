-- AF-353: IdP claim -> AccessFlow group mapping for SAML and OAuth2.
-- group_mappings JSONB shape: { "<idp-claim-value>": "<accessflow-group-uuid>", ... }.
-- The OAuth2 column reuses the existing `groups_attribute` for the claim name;
-- SAML adds a dedicated `attr_groups` since the OIDC equivalent did not exist.

ALTER TABLE oauth2_config
    ADD COLUMN group_mappings JSONB NOT NULL DEFAULT '{}'::JSONB;

ALTER TABLE saml_config
    ADD COLUMN attr_groups    VARCHAR(255),
    ADD COLUMN group_mappings JSONB NOT NULL DEFAULT '{}'::JSONB;
