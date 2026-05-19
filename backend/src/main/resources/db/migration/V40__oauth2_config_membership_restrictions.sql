ALTER TABLE oauth2_config
    ADD COLUMN allowed_organizations TEXT[],
    ADD COLUMN allowed_email_domains TEXT[];
