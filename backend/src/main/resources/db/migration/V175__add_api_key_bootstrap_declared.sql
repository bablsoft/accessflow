-- #871 (epic #867): mark the one API key the bootstrap reconciler declares for a service account.
-- Nothing on api_keys told a declared key from a UI-issued one; the only link was (user_id, name)
-- against the YAML, which exists only while the reconciler runs. The flag is what lets the admin
-- surface (and /me/api-keys) refuse to revoke or rotate that key: importOrUpdate clears revoked_at
-- on every changed reconcile, so a revoke would only appear to succeed until the next restart.

ALTER TABLE api_keys ADD COLUMN bootstrap_declared BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill. importOrUpdate stamps the flag from now on, but the reconciler short-circuits on an
-- unchanged fingerprint and would never re-import (and so never re-flag) a key that already
-- exists — this migration is the only chance to mark them. Deliberately conservative: every key
-- owned by a BOOTSTRAP-managed account is flagged, which errs toward *refusing* a revoke. That
-- over-marks any extra key such an account holds (a personal key of a pre-#868 human adopted as a
-- service account, or a key the bot minted for itself through /me/api-keys); the fix is the same
-- one the 409 already names — rotate the declared secret in the bootstrap source and restart —
-- because that changed reconcile re-flags exactly the declared key and importOrUpdate clears the
-- flag on the user's other keys. EXISTS (not JOIN) for the same duplicate-proofing reason as V173.
UPDATE api_keys k
   SET bootstrap_declared = TRUE
 WHERE EXISTS (SELECT 1 FROM service_accounts s
                WHERE s.user_id = k.user_id AND s.managed_by = 'BOOTSTRAP');
