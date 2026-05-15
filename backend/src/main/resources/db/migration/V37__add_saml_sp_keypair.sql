-- SP (Service Provider) signing keypair for SAML 2.0 SSO.
--
-- Holds the AES-256-GCM-encrypted PEM-encoded RSA private key and the (cleartext)
-- X.509 certificate used to sign AuthnRequest messages and ship in the SP metadata XML
-- returned from GET /api/v1/auth/saml/metadata/{registrationId}.
--
-- Both columns are nullable: if ACCESSFLOW_SAML_SP_SIGNING_KEY_PEM and
-- ACCESSFLOW_SAML_SP_SIGNING_CERT_PEM are not set, SamlSpKeyProvider auto-generates a
-- self-signed RSA-2048 keypair on first use and persists it here so it survives restarts.
ALTER TABLE saml_config
    ADD COLUMN sp_private_key_pem TEXT,
    ADD COLUMN sp_certificate_pem TEXT;
