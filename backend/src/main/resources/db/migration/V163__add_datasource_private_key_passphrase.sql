-- Passphrase for a passphrase-protected (encrypted PKCS#8) private key used by Snowflake key-pair
-- JWT authentication. Snowflake's own documentation generates an encrypted key
-- (openssl pkcs8 -topk8 -v2 des3 ...), so first-time setups could not store the key AccessFlow's
-- own tooling handed them. AES-256-GCM encrypted before persistence (like password_encrypted),
-- @JsonIgnore on the entity, never returned in an API response. Nullable for every dialect
-- (password credentials and unencrypted PEMs leave it null), so this is a zero-downtime additive
-- change (issue #632).
ALTER TABLE datasources ADD COLUMN private_key_passphrase_encrypted TEXT;
