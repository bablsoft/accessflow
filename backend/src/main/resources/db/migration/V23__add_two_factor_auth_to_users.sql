ALTER TABLE users
    ADD COLUMN totp_secret_encrypted VARCHAR(512),
    ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN totp_backup_codes_encrypted TEXT;
