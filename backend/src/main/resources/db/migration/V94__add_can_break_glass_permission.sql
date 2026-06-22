-- AF-385: break-glass / emergency access. A per-user/per-datasource capability gate for the
-- break-glass submission mode. Time-boxed via the existing datasource_user_permissions.expires_at
-- column (a JIT grant can carry it too). Defaulted FALSE so existing rows and zero-downtime deploys
-- are unaffected — break-glass is opt-in and must be granted explicitly, even to admins.
ALTER TABLE datasource_user_permissions
    ADD COLUMN can_break_glass BOOLEAN NOT NULL DEFAULT FALSE;
