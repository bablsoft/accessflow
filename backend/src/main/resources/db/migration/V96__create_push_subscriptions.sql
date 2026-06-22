-- AF-444: Web Push (VAPID) subscriptions for the mobile/PWA one-tap approve/reject path.
--
-- push_subscriptions: one row per user *device/browser* (a user may hold several). The endpoint
-- + p256dh + auth tuple is the W3C Push API subscription. These are device push keys, not
-- AccessFlow credentials, so they are stored in clear (a DB read already exposes far more); the
-- self-approval and step-up guards live in the application, not here.
--
-- push_vapid_config: the single deployment-level VAPID keypair used to sign push requests. The
-- private key is AES-256-GCM encrypted with ENCRYPTION_KEY (mirrors saml_config). Auto-generated
-- and persisted on first use unless ACCESSFLOW_PUSH_VAPID_* env overrides are set.

CREATE TABLE push_subscriptions (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    endpoint         TEXT NOT NULL,
    p256dh_key       TEXT NOT NULL,
    auth_key         TEXT NOT NULL,
    user_agent       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at     TIMESTAMPTZ,
    CONSTRAINT uq_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);
CREATE INDEX idx_push_subscriptions_organization_id ON push_subscriptions (organization_id);

CREATE TABLE push_vapid_config (
    id                   UUID PRIMARY KEY,
    public_key           TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    subject              TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
