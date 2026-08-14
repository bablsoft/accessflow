-- #621: SCIM-provisioned users carry their own auth provider. They have no password (local login
-- impossible) and sign in through the org's SAML/OIDC SSO, whose email match accepts non-LOCAL
-- rows without the local-account takeover guard firing.
ALTER TYPE auth_provider_type ADD VALUE IF NOT EXISTS 'SCIM';
