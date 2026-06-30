# 07 — Security Design

## Authentication

### JWT (RS256)

- Access tokens signed with RSA-2048 private key (`JWT_PRIVATE_KEY` env var)
- Access token TTL: **15 minutes**
- Refresh token TTL: **7 days**, stored in `HttpOnly; Secure; SameSite=Strict` cookie
- Refresh token rotation: each use issues a new token and invalidates the old one
- Revocation: invalidated refresh tokens stored in Redis with TTL matching remaining lifetime
- On access token expiry, frontend automatically calls `POST /auth/refresh` via Axios interceptor

### Public (unauthenticated) endpoints

The `SecurityConfiguration` `permitAll()` list is short and intentional. Every other endpoint requires a valid JWT (or an API key, see below). The current public surface:

- `/auth/login`, `/auth/refresh`, `/auth/logout` — JWT lifecycle.
- `/auth/setup`, `/auth/setup-status` — first-run wizard.
- `/auth/localization-config` — read-only `{ available_languages, default_language }` consumed by the login-page language selector. Returns the **union** of allow-lists across all `localization_config` rows, never per-org identity, so a multi-tenant deployment is not forced to disclose which orgs exist. Falls back to `["en"]` / `"en"` on a fresh deployment.
- `/auth/oauth2/providers`, `/auth/oauth2/exchange`, `/auth/saml/enabled`, `/auth/saml/exchange` — SSO discovery + one-time exchange-code redemption.
- `/auth/invitations/*`, `/auth/invitations/*/accept`, `/auth/password/forgot`, `/auth/password/reset/*` — invitation + password-reset flows tied to single-use tokens.
- `/api-docs/**`, `/swagger-ui/**`, `/actuator/health`, `/actuator/info` — docs + probes.
- `/ws` — WebSocket upgrade (auth happens in `JwtHandshakeInterceptor`, not here).
- `/api/v1/integrations/slack/actions`, `/api/v1/integrations/slack/commands` — inbound Slack callbacks. JWT-exempt because Slack cannot attach an `Authorization` header to these server-to-server posts; they are authenticated instead by the `X-Slack-Signature` HMAC inside the controller (see [Slack request verification](#slack-request-verification-af-362)). The self-service linking endpoints (`/api/v1/integrations/slack/link-codes`, `/api/v1/integrations/slack/link`) stay JWT-authenticated.

### Two-factor authentication (TOTP)

Every LOCAL user can opt in to TOTP-based 2FA from `/profile`. SAML-provisioned users authenticate through their IdP and cannot enrol locally — they rely on the IdP's MFA controls instead.

- **Standard:** RFC 6238 TOTP, SHA-1, 6 digits, 30-second window. Implemented via `dev.samstevens.totp:totp` (current pin: 1.7.1) — re-verify against the latest stable on each bump.
- **Secret storage:** the shared secret is AES-256-GCM encrypted on the user row (`users.totp_secret_encrypted`, `@JsonIgnore`) via the existing `CredentialEncryptionService`. The plaintext exists only inside `DefaultTotpVerificationService.verify`, never on a response.
- **Enrolment flow:** `POST /me/totp/enroll` generates the secret and an otpauth URL (issuer `AccessFlow`, label `<email>`) plus a base64-PNG QR data URI. `totp_enabled` stays false until `POST /me/totp/confirm` proves possession by verifying a 6-digit code; the same call returns 10 single-use backup recovery codes (plaintext, **once**) and persists them as bcrypt hashes inside an AES-encrypted JSON array.
- **Login enforcement:** `LocalAuthenticationService.login` rejects 2FA-enabled accounts that present no `totp_code` with HTTP 401 `TOTP_REQUIRED`. A valid 6-digit code OR an unused backup code unlocks the session; a backup code is removed from the encrypted blob on first successful use.
- **Disable:** `POST /me/totp/disable` requires the caller's current password, then clears the secret, `totp_enabled`, and the backup-codes blob. All refresh tokens are revoked on disable (via `SessionRevocationService`).
- **Password change side effect:** `POST /me/password` also revokes all refresh tokens, forcing the user to sign in again on every device.
- **Self-service password reset:** the public `POST /api/v1/auth/password/forgot` issues a SHA-256-hashed, single-use token stored in `password_reset_tokens` with a 1-hour TTL (`ACCESSFLOW_SECURITY_PASSWORD_RESET_TTL`). The request endpoint always returns 202 and silently skips unknown / SSO-only / inactive / null-password-hash accounts so it does not leak which emails are registered. Issuing a new token revokes any prior pending token for the same user (enforced by a partial unique index on `(user_id) WHERE status = 'PENDING'`). On `POST /api/v1/auth/password/reset/{token}` success the user's password hash is rotated and **all refresh tokens are revoked** via `SessionRevocationService` — identical to the `POST /me/password` side effect.
- **Replay & rate limiting:** the underlying TOTP library tolerates the immediately preceding 30-second window for clock skew; outside that, replays are rejected. There is no application-level lockout yet — that's a deferred item.

### WebSocket handshake

The realtime endpoint at `/ws` is exempt from `JwtAuthenticationFilter` and authenticates the upgrade through `realtime/internal/ws/JwtHandshakeInterceptor` instead. Browsers do not allow custom headers on a WebSocket upgrade, so the access token is supplied as a query parameter: `ws://host/ws?token=<JWT>`.

- The same RSA signing key, expiry rules, and token-type checks apply — there is **no separate WS token**.
- The handshake interceptor calls the public `AccessTokenAuthenticator` (`security/api/`); on failure the upgrade is rejected with HTTP 403.
- After the handshake, no further per-frame auth is performed — the validated `JwtClaims` are stored on the session for the lifetime of the connection.
- The frontend reconnects whenever the access token rotates (after a `/auth/refresh` 200), so a long-running socket cannot outlive its credentials.
- `/ws` is added to the `permitAll()` list in `SecurityConfiguration` because the handshake interceptor — not the JWT filter — is the auth boundary here.

### SAML 2.0 SSO

All JWT mechanisms remain in place. Additionally:

- SP-initiated flow is exposed at `GET /api/v1/auth/saml/init/default` (302 to IdP). IdP-initiated flows also work because Spring Security SAML2 accepts unsolicited SAMLResponses at `POST /api/v1/auth/saml/acs`.
- Configuration is **fully DB-driven** (`saml_config` row per organization). Admins paste the IdP metadata URL + IdP signing certificate from `/admin/saml`; no `spring.security.saml2.*` properties.
- The SP signing keypair (used to sign AuthnRequests and shipped in `GET /api/v1/auth/saml/metadata/{registrationId}`) follows a **hybrid env-var override + auto-generate fallback** sourcing model:
  - When both `ACCESSFLOW_SAML_SP_SIGNING_KEY_PEM` and `ACCESSFLOW_SAML_SP_SIGNING_CERT_PEM` are populated, those values are used verbatim. The operator owns rotation; AccessFlow never persists this material.
  - Otherwise, on the first call that needs the keypair (`/init`, `/metadata`, or `/acs`) AccessFlow generates a self-signed RSA-2048 keypair, encrypts the private key with `ENCRYPTION_KEY` (AES-256-GCM via `CredentialEncryptionService`), and persists both PEMs into `saml_config.sp_private_key_pem` / `saml_config.sp_certificate_pem` so the values survive restarts.
- On successful SAML assertion:
  1. Extract `attr_email`, `attr_display_name`, optional `attr_role` per the org's mapping config (defaults `email` / `displayName`).
  2. Look up the user by email. If they already exist with `auth_provider=SAML`, the row is reused. If they exist with `auth_provider=LOCAL` and a populated `password_hash`, sign-in is rejected with `SAML_LOCAL_EMAIL_CONFLICT` — auto-linking is unsafe because anyone able to assert the same email at the IdP could otherwise take over the local account.
  3. Otherwise JIT-provision a new user with `auth_provider=SAML` and `role = saml_config.default_role` (or the asserted role when `attr_role` is configured and the value matches a known `UserRoleType`).
  4. Mint a one-time exchange code in Redis (`saml:exchange:` namespace, 60s default TTL configurable via `ACCESSFLOW_SAML_EXCHANGE_CODE_TTL`) and 302 to `${ACCESSFLOW_SAML_FRONTEND_CALLBACK_URL}?code=<code>`. The frontend posts the code to `/api/v1/auth/saml/exchange`, which consumes it (single-use) and returns the same JWT pair shape as `/auth/login`. Tokens never appear in the redirect URL.
- Both successful and failed sign-ins write to `audit_log` via `USER_LOGIN` / `USER_LOGIN_FAILED`.
- SAML session lifetime: when the IdP sends `SessionNotOnOrAfter`, the access token TTL is capped at that horizon; refresh tokens issued before that timestamp continue to work up to it.
- **Load-bearing regression check:** [`e2e/tests/auth-saml-login.spec.ts`](../e2e/tests/auth-saml-login.spec.ts) drives the full IdP roundtrip against a `kristophjunge/test-saml-idp` (SimpleSAMLphp) container in [`e2e/docker-compose.e2e.sso.yml`](../e2e/docker-compose.e2e.sso.yml). Run via `cd e2e && npm run test:sso`; CI runs it as part of the `e2e` job.

### OAuth 2.0 / OIDC SSO

All JWT mechanisms remain in place. Configuration is **fully DB-driven** (`oauth2_config`
table, one row per `(organization_id, provider)`); there are no `spring.security.oauth2.client.*`
properties. Four cloud providers ship with built-in templates: `GOOGLE`, `GITHUB`, `MICROSOFT`,
`GITLAB`. The admin enters only `client_id`, `client_secret`, optional `scopes_override`, and
(for Microsoft) `tenant_id`. Authorization / token / userinfo URLs come from
`OAuth2ProviderTemplate.TEMPLATES` and are never user-editable for these four, so a
misconfigured row cannot redirect the browser to a hostile authorization server.

Two enterprise variants — `GITHUB_ENTERPRISE` (GitHub Enterprise Server) and `GITLAB_ENTERPRISE`
(self-managed GitLab) — share the same URL conventions as their cloud counterparts but accept
a configurable `base_url` (e.g. `https://github.acme.corp`). The well-known sub-paths
(`/login/oauth/authorize`, `/api/v3/*` for GHES; `/oauth/authorize`, `/oauth/userinfo`,
`/oauth/discovery/keys` for self-managed GitLab) remain compiled into `OAuth2ProviderTemplate`
— only the origin is operator-editable. `base_url` is admin-only-editable, must be `https://`,
and is rejected on activation unless it parses as an origin with no path / query / fragment.
That preserves the "no admin-entered authorization URL routing" invariant — the worst an
operator can misconfigure is pointing the OAuth flow at the wrong corporate host (an outage,
not a credential exfiltration vector).

A seventh provider, `OIDC`, is generic: the admin supplies `display_name` and the IdP's
`authorization_uri`, `token_uri`, `user_info_uri`, `jwk_set_uri`, and `issuer_uri` (plus
optional attribute-name overrides) directly on the row. This is the integration surface for
Keycloak, Auth0, Okta, Authentik, Zitadel, and any other generic OIDC provider. Threat-model
note: OIDC URLs are **admin-only-editable** (RBAC role `ADMIN`, audit-logged via
`BootstrapResourceUpsertedEvent` and the standard `oauth2_config` audit trail). They are
never readable or writable from an unauthenticated endpoint, so the "never trust admin-entered
URLs" invariant the original four-provider design enforced at compile time is preserved for
unauthenticated traffic. Operators who delegate OIDC URL editing to non-admins are explicitly
trusting those operators with the equivalent of full SSO control.

**Account-linking model — verified email + safe rejection.** The success handler:

1. Pulls `email` (and `email_verified` when the provider supplies it) from the userinfo
   payload. For GitHub it falls back to `GET /user/emails` with the access token and picks
   the row where `primary=true AND verified=true`.
2. Rejects sign-in with `OAUTH2_EMAIL_UNVERIFIED` when the provider says the email is not
   verified. Google / Microsoft / GitLab include `email_verified`; GitHub uses the
   `/user/emails` filter described above. We never trust an unverified email.
3. Looks up the matching user by email. If they already exist with
   `auth_provider=OAUTH2` (or `auth_provider=LOCAL` **without** a password hash, i.e.
   admin-created shell account), the existing row is reused.
4. Rejects with `OAUTH2_LOCAL_EMAIL_CONFLICT` when an existing user has
   `auth_provider=LOCAL` **and** a populated `password_hash`. The admin must manually
   convert the account before the user can sign in via OAuth — auto-linking would let
   anyone who controls a provider account with the same email take over a local account.
5. JIT-provisions a new user otherwise, with `auth_provider=OAUTH2` and `role =
   oauth2_config.default_role` (per-provider).

**Redirect handshake.** Spring Security's `oauth2Login()` handles the browser redirect to
the provider and the code-for-token exchange. The custom success handler then issues a
one-time exchange code via `OAuth2ExchangeCodeStore` (Redis, 60 s default TTL) and redirects
the browser to `${ACCESSFLOW_OAUTH2_FRONTEND_CALLBACK_URL}?code=…`. The frontend posts the
code to `/api/v1/auth/oauth2/exchange`, which consumes it (single-use) and returns the same
JWT pair shape as `/auth/login`. Tokens never appear in the redirect URL itself.

**Membership and domain restrictions.** Two optional per-provider allowlists on
`oauth2_config` restrict who may complete sign-in once the email has been resolved:

- `allowed_email_domains` — case-insensitive match against the resolved email's domain. Empty
  / NULL means any domain is accepted. This is the surface used to lock down a Google
  Workspace deployment to its corporate domain.
- `allowed_organizations` — provider-native membership identifiers. Empty / NULL means
  AccessFlow does not call the provider for membership. The success handler computes the
  user's membership set via `OAuth2MembershipResolver` and rejects the login unless the
  allowlist intersects it. Per-provider semantics:
  - **GITHUB** — calls `GET https://api.github.com/user/orgs` with the issued access token
    and compares the returned `login` values (case-sensitive). The token must carry the
    `read:org` scope, otherwise only public memberships are visible. Activating a GitHub
    config with a non-empty `allowed_organizations` while `scopes_override` does not include
    `read:org` is rejected with `OAUTH2_CONFIG_INVALID` (HTTP 422) — operators must add
    `read:org` to the scopes-override field explicitly.
  - **GITHUB_ENTERPRISE** — same as `GITHUB` but the orgs call hits
    `{base_url}/api/v3/user/orgs` on the operator's self-hosted instance. The same `read:org`
    activation rule applies.
  - **GITLAB** — reads the OIDC `groups` claim from userinfo (full group paths, e.g.
    `acme/team`). Empty when the `groups` scope is not included.
  - **GITLAB_ENTERPRISE** — same as `GITLAB` (OIDC `groups` claim from self-managed GitLab's
    userinfo endpoint at `{base_url}/oauth/userinfo`).
  - **MICROSOFT** — reads the `groups` claim, which contains AAD group object IDs. Azure AD
    must be configured to emit it (App registration → Token configuration → groups claim).
  - **GOOGLE** — `allowed_organizations` is ignored; the equivalent surface is
    `allowed_email_domains` (matching the Workspace `hd` concept).
  - **OIDC** — reads the claim named by `oauth2_config.groups_attribute`. If that column is
    NULL/blank, no groups are extracted (allowlist effectively empty). Restrict OIDC sign-in
    by `allowed_email_domains` instead, or configure the IdP to emit a groups claim and set
    the column accordingly.

Failed restrictions redirect with `?error=OAUTH2_EMAIL_DOMAIN_NOT_ALLOWED` or
`?error=OAUTH2_ORG_NOT_ALLOWED`. The handler **fails closed**: a HTTP error from the GitHub
orgs API yields an empty membership set, so a configured allowlist will reject the login
rather than silently allowing it.

**Secret storage.** `oauth2_config.client_secret_encrypted` is AES-256-GCM ciphertext via
the existing `CredentialEncryptionService`. The entity field is `@JsonIgnore` and the admin
API returns `"********"` whenever a secret is stored — the plaintext never leaves
`DynamicClientRegistrationRepository.build`.

**Dynamic config refresh.** `DynamicClientRegistrationRepository` caches
`ClientRegistration`s per registration id and listens for `OAuth2ConfigUpdatedEvent` /
`OAuth2ConfigDeletedEvent` via `@ApplicationModuleListener`, mirroring
`AiAnalyzerStrategyHolder`. Config changes take effect on the next authorize request — no
application restart.

### API key authentication

Users may create personal API keys (under **Profile → API keys**) to authenticate the MCP
server and other programmatic clients without a browser session. The flow:

- **Format.** `af_<32-byte base64url, no padding>` — ~38 characters. Generated with
  `SecureRandom`; the `af_` prefix is informational.
- **Storage.** Only the `SHA-256` hash (hex, 64 chars) and a 12-char display prefix are
  persisted (`api_keys.key_hash`, `api_keys.key_prefix`). The plaintext is shown **once** on
  creation and is unrecoverable thereafter. Hashing is plain SHA-256 (not bcrypt) because lookup
  happens on every request and the keys carry 256 bits of entropy — brute force is infeasible.
- **Header parity.** The filter accepts either `X-API-Key: <key>` (preferred for MCP clients)
  or `Authorization: ApiKey <key>` (parity with `Authorization: Bearer <jwt>`). The CORS
  config exposes `X-API-Key` as an allowed header.
- **Scope.** A key acts as its owning user — same role, same datasource permissions, same
  review-self-approval block. There is no separate scope model: an API key can hit any endpoint
  the user can hit, including `/mcp/**`.
- **Lifecycle.** Per-user CRUD endpoints live at `/api/v1/me/api-keys` (see
  `docs/04-api-spec.md`). Revocation sets `revoked_at = now()` and is idempotent; revoked or
  expired keys never authenticate.
- **Filter placement.** `ApiKeyAuthenticationFilter` (in the `security` module, sibling to
  `JwtAuthenticationFilter`) runs before
  `JwtAuthenticationFilter` in the main security chain. If no API key header is present, the
  JWT filter still gets a chance. Both filters end up populating a `JwtAuthenticationToken`
  with the same `JwtClaims` shape, so downstream controllers and MCP tools are auth-agnostic.
- **Audit.** `api_keys.last_used_at` is bumped on each successful authentication. Bumps are
  best-effort and swallow exceptions to avoid impacting auth latency.

See `docs/13-mcp.md` for the end-user guide.

### Slack request verification (AF-362)

Inbound Slack callbacks (`/api/v1/integrations/slack/actions`, `/api/v1/integrations/slack/commands`) are JWT-exempt and authenticated by the Slack **signing secret** instead. `SlackRequestVerifier`:

- Reads the **raw** request body (the controller binds `@RequestBody String`; nothing accesses request parameters first, so the form stream stays intact for an exact-bytes HMAC).
- Looks up the org's `slack_app_config` by the payload's `api_app_id`, decrypts its signing secret, and recomputes `HMAC-SHA256` over the base string `v0:{X-Slack-Request-Timestamp}:{body}`. The result (`v0=<hex>`) is compared against `X-Slack-Signature` in **constant time** (`MessageDigest.isEqual`).
- **Rejects (401):** missing `X-Slack-Signature` / `X-Slack-Request-Timestamp`; a timestamp outside the ±`accessflow.notifications.slack.signature-tolerance` window (default 5 min); and an HMAC mismatch.
- **Replay protection:** every verified signature is recorded in Redis (`slack:sig:<sig>`, `SETNX` with TTL = the tolerance window) by `SlackReplayGuard`; a second sighting within the window is rejected as a replay.

Secrets at rest: `slack_app_config.bot_token_encrypted` and `signing_secret_encrypted` are AES-256-GCM ciphertext via `CredentialEncryptionService`, `@JsonIgnore` on the entity, and never returned by the admin API (only `has_bot_token` / `has_signing_secret` booleans). Approve/Reject clicks run through the same `ReviewService` path as REST, so the self-approval block and RBAC/stage checks apply identically — a Slack user can never approve their own query.

---

## Authorization — Role Matrix

| Capability | READONLY | ANALYST | REVIEWER | ADMIN | AUDITOR |
|-----------|----------|---------|----------|-------|---------|
| Submit SELECT queries | ✓ | ✓ | ✓ | ✓ | — |
| Submit DML queries (INSERT/UPDATE/DELETE) | — | ✓ | ✓ | ✓ | — |
| Submit DDL queries | — | — | — | ✓ | — |
| View own query history | ✓ | ✓ | ✓ | ✓ | — |
| View all query history | — | — | ✓ | ✓ | — |
| Approve / reject queries | — | — | ✓ | ✓ | — |
| Approve own submitted queries | — | — | — | — | — |
| Request time-bound datasource access (AF-378) | ✓ | ✓ | ✓ | ✓ | — |
| Review / approve / reject access requests | — | — | ✓ | ✓ | — |
| Approve own access request | — | — | — | — | — |
| Early-revoke an active grant | — | — | — | ✓ | — |
| View AI analysis results | ✓ | ✓ | ✓ | ✓ | — |
| Re-run AI analysis on a failed query (`POST /queries/{id}/reanalyze`) | — | — | ✓ | ✓ | — |
| Create / edit datasources | — | — | — | ✓ | — |
| Manage user permissions | — | — | — | ✓ | — |
| Create / edit review plans | — | — | — | ✓ | — |
| View audit log | — | — | — | ✓ | — |
| Manage notification channels | — | — | — | ✓ | — |
| Configure AI provider | — | — | — | ✓ | — |
| Manage users (create/deactivate) | — | — | — | ✓ | — |
| Configure SAML | — | — | — | ✓ | — |
| Configure OAuth providers | — | — | — | ✓ | — |
| View compliance reports (`/admin/compliance/*`, AF-459) | — | — | — | ✓ | ✓ |
| Export signed compliance reports (PDF/CSV) | — | — | — | ✓ | ✓ |
| Manage recertification campaigns (create/open/cancel, AF-384) | — | — | — | ✓ | — |
| Certify / revoke attestation items | — | — | ✓ | ✓ | — |
| Attest own access grant | — | — | — | — | — |
| Export attestation evidence CSV | — | — | — | ✓ | ✓ |

**Key rule:** A user can never approve their own query request, regardless of role.

**Access recertification (AF-384):** an `ADMIN` creates, opens, cancels, and exports evidence for
attestation campaigns; a `REVIEWER` or `ADMIN` certifies/revokes the individual items they are
eligible for (eligibility derives from the datasource's reviewers, falling back to org admins). A
reviewer can **never attest their own grant** — the self-review block is enforced in
`DefaultAttestationReviewService` at the service layer (403 `ATTESTATION_REVIEWER_NOT_ELIGIBLE`),
exactly as the query-review and access-request self-approval blocks are. A `REVOKE` decision (or the
end-of-campaign `REVOKE` default) hard-deletes the materialised `datasource_user_permissions` row via
the existing permission-revoke service. Evidence CSV export is additionally available to `AUDITOR`.

**AUDITOR (AF-459)** is a dedicated **read-only compliance role**. It is granted *only* the
compliance-reporting endpoints (`/api/v1/admin/compliance/*`, gated `hasAnyRole('AUDITOR','ADMIN')`)
and the auditor dashboard (`/admin/auditor`); it has no datasource permissions, so it cannot submit
queries, and it cannot reach any other admin surface. Its frontend home redirect is `/admin/auditor`
(not `/editor`). The role maps to the Spring Security authority `ROLE_AUDITOR` like every other
`user_role_type` value — no special-casing in `JwtAuthorities`.

### Platform admin (super-admin) — `PLATFORM_ADMIN` authority (AF-456)

`users.platform_admin` is an **orthogonal boolean flag, not a fifth role** — the four roles above are
unchanged. A platform admin keeps their home-org `role` (e.g. `ADMIN`) **and** is additionally granted
the extra Spring Security authority `PLATFORM_ADMIN`. The JWT carries a `platform_admin` claim and the
login / `GET /me` user object exposes a `platform_admin` boolean.

- **What it unlocks.** Only the cross-org tenant-management plane at `/api/v1/platform/organizations`
  (`@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")` — see [04-api-spec.md → Platform Organizations](04-api-spec.md#platform-organizations)).
  It grants **no** extra capability inside any single org — the role matrix above still governs every
  tenant-scoped action.
- **How it's granted.** The bootstrap admin and the first-run setup-wizard admin are provisioned as
  platform admins; a pre-existing bootstrap admin is promoted on an upgrade re-run. Otherwise the flag
  is set explicitly on the `users` row.
- **Why a flag, not a role.** It is genuinely orthogonal — a platform admin is still a normal member of
  their home org with whatever role that org assigns. Modelling it as a role would have forced an
  artificial choice between "org admin" and "platform admin".

**CSV export of query history** (`GET /queries/export.csv`) reuses the same org-scoping and
submitter rules as `GET /queries`: non-admin callers receive only their own queries; admins may
override `submitted_by` to scope to a specific user. No additional role is required, and the
endpoint never returns SQL text — only the metadata fields already visible on the list page.

---

## Multi-tenant isolation (AF-456)

A deployment hosts one or more `organizations`, each a fully isolated tenant. Isolation is
**defense-in-depth**, with the org boundary derived server-side rather than trusted from the client.

- **Org is always derived from the JWT principal.** Every tenant-scoped endpoint reads
  `organizationId` from the authenticated principal — never from a request body or path. A user
  cannot reference another org's data by guessing an id, because the queries are filtered by the
  principal's org. The **only** endpoints that legitimately take a foreign org id by path are the
  platform-admin management plane at `/api/v1/platform/organizations`, gated by the `PLATFORM_ADMIN`
  authority.
- **Disabled-org kill-switch.** `organizations.disabled` blocks a tenant in two places: at
  authentication (login, refresh, SSO exchange — local and SSO) and at request time. The JWT and
  API-key auth filters perform a lightweight per-request org-status lookup and reject any request
  whose org is disabled. There is **no cache**, so disabling a tenant takes effect immediately —
  in-flight sessions stop working on their next request, not at token expiry.
- **Per-org quotas — fail-on-breach (`409 QUOTA_EXCEEDED`).** Three nullable caps on the org row —
  `max_datasources`, `max_users`, `max_queries_per_day` (NULL or 0 = unlimited) — are enforced
  count-based at the service layer: datasource creation checks `max_datasources`; user creation and
  invitation issuance check `max_users` (active-user count); query submission checks
  `max_queries_per_day` (a rolling trailing-24h count over `query_requests` — no counter table, no
  reset job). A breach throws and the API responds `409 Conflict` with `error: "QUOTA_EXCEEDED"` and a
  localized `detail` naming the limit. Quotas bound consumption; they are not an access boundary.
- **Multi-org login routing is future work.** Per-org login pages / SSO routing across multiple orgs
  are explicitly out of scope for AF-456. Unauthenticated provider discovery degrades gracefully when
  more than one org exists (it never discloses per-org identity — see
  [`GET /auth/localization-config`](04-api-spec.md#get-authlocalization-config) and the OAuth2/SAML
  discovery endpoints).

All four organization lifecycle mutations are audited against the target org
(`ORGANIZATION_CREATED` / `ORGANIZATION_UPDATED` / `ORGANIZATION_DISABLED` / `ORGANIZATION_ENABLED`).

---

## Datasource-Level Access Control

Beyond platform roles, every action against a customer database is validated against `datasource_user_permissions`:

```
User attempts query on datasource
         │
         ▼
Does permission record exist for (user_id, datasource_id)?
  NO  → 403 Forbidden
  YES ↓
Does permission allow this query type?
  can_read=false + SELECT query → 403
  can_write=false + DML query   → 403
  can_ddl=false + DDL query     → 403
         ↓
Is access expired?
  expires_at < now → 403
         ↓
Are allowed_schemas / allowed_tables set?
  YES → walk parsed JSqlParser AST (TablesNamesFinder), normalise identifiers (strip quotes, lowercase),
        intersect with allow-list; reject (403, `error.permission.table_not_allowed`) on any miss.
        Unqualified references match `allowed_tables` only when the bare name is listed —
        a schemas-only allow-list does NOT cover them.
  Violation → 403
         ↓
Are restricted_columns set?
  YES → AI analyzer is told which columns are sensitive (informational — never auto-rejects)
        SELECT result rows have those values replaced with "***" before persistence
         ↓
  PROCEED to review plan
```

### Just-in-time (JIT) time-bound access requests (AF-378)

A user can self-request temporary, scoped access instead of an admin pre-granting it. The request flows through the **same reviewer-eligibility + multi-stage approval machinery** as query review, with these security invariants:

- **A requester can never approve their own request.** Enforced in `DefaultAccessReviewService.prepareDecision()` at the service layer (not just the UI) — `requesterId == reviewerId` raises `AccessDeniedException` (403), exactly as the query-review self-approval block does.
- **Eligibility is identical to query review.** The reviewer must be an approver at the request's current stage in the datasource's review plan *and* within the datasource's scoped-reviewer set (`datasource_reviewers`) when one is configured. `REVIEWER`/`ADMIN` role is necessary but not sufficient.
- **Grants are time-boxed.** On final-stage approval the system writes a `datasource_user_permissions` row with `expires_at = now + requested_duration` (bounded by `accessflow.access.min-duration` / `max-duration`). `AccessGrantExpiryJob` revokes it on expiry (`EXPIRED`); an admin may early-revoke (`REVOKED`). Once expired/revoked the permission row is gone, so the standard datasource-access check above returns 403.
- **Pre-existing-permission policy.** A JIT grant **never silently deletes a standing (admin-granted, non-expiring) permission** — approval fails with `ACCESS_GRANT_ALREADY_EXISTS` (409) in that case. An existing *time-boxed* permission is revoked and replaced (extend/widen). This preserves standing access as the source of truth while letting JIT grants stack predictably.

### Break-glass / emergency access (AF-385)

A distinct submission mode that **skips pre-approval** for genuine emergencies, with compensating controls and these non-negotiable security invariants:

- **Gated by an explicit `can_break_glass` permission, required for everyone — including admins.** Unlike normal submission (where admins bypass the per-datasource permission check), break-glass is enforced for all callers at the service layer (`DefaultBreakGlassService`): a non-null, non-expired `datasource_user_permissions` row with `can_break_glass=true` **and** the capability for the parsed query type **and** the table allow-list, else `BreakGlassNotPermittedException` (403). Time-boxed via the grant's `expires_at`.
- **All proxy guards still apply.** The query runs through the identical execution path — schema/table allow-list, dynamic masking, row-level security, and row caps are enforced exactly as for a reviewed query. Break-glass bypasses *approval*, never the *data-protection* controls.
- **Justification is mandatory** and captured on the `break_glass_events` row and in the audit metadata.
- **Compensating controls.** Instant fanout to every active org admin (incl. PagerDuty); a prominently distinct `QUERY_BREAK_GLASS_EXECUTED` audit row (not `QUERY_EXECUTED`); and a mandatory retro-review.
- **A submitter can never acknowledge their own break-glass event.** Enforced at the service layer (`SelfAcknowledgeNotAllowedException`, 403), mirroring the query-review and JIT self-approval blocks. The retrospective reconciliation (`BREAK_GLASS_REVIEWED`) must be performed by a different admin.
- **State-machine safety.** The break-glass path is `PENDING_AI → APPROVED → EXECUTED` (no `QuerySubmittedEvent`, `submission_reason=EMERGENCY_ACCESS`); the illegal-transition guard still rejects anything off-path. The executed query lands in its normal terminal state and is never re-opened — the retro-review is tracked alongside it.

### Column-level restrictions

`datasource_user_permissions.restricted_columns` is a `TEXT[]` of fully-qualified `schema.table.column` entries. This is a **defense-in-depth, value-masking** control — not a primary access boundary:

- Restricted columns can still be referenced in SQL (WHERE, JOIN, GROUP BY, etc.). The system does not reject the query; it masks the value in the SELECT response and informs the AI reviewer.
- Masking happens in `JdbcResultRowMapper` before rows are added to the in-memory result and before they are written to `query_request_results.rows`. The raw value never lands in our database. The sentinel is `"***"`; `null` stays `null`.
- The AI analyzer renders `*RESTRICTED*` markers next to flagged columns in the schema context and is instructed to emit `RESTRICTED_COLUMN_ACCESS` issues (severity `LOW`) — the workflow state machine ignores this category for auto-rejection logic.
- For high-confidentiality data where the value must never be retrievable at all, prefer an `allowed_tables` denial or a database-side view that excludes the column.

### Dynamic data masking policies (AF-381)

`masking_policy` rows extend the static masking above with **per-column strategies** and a
**conditional reveal** evaluated per query submitter. Same trust posture — a defense-in-depth
value-rendering control, not an access boundary:

- **Strategies:** `FULL` (`***`, the legacy behaviour), `PARTIAL` (keep last N chars), `HASH` (stable
  SHA-256 hex — same input always yields the same digest, enabling correlation without disclosure),
  `EMAIL` (`j***@domain`), `FORMAT_PRESERVING` (preserve length/shape).
- **Reveal is explicit only.** A submitter sees the unmasked value only when their role, one of their
  group ids, or their user id is listed in the policy's `reveal_to_*` columns. There is **no implicit
  ADMIN bypass** — admins are masked too unless explicitly revealed, so the rule is fully expressed in
  the row and auditable. Masking is keyed on the query **submitter** (`submittedByUserId`), consistent
  with `restricted_columns`.
- **No unmasked persistence.** Masking is applied at result-read time in the proxy (`ColumnMasker`),
  before serialization and before the `query_request_results` snapshot is stored. The raw value is read
  transiently into the masker and discarded; for `FULL` the raw value is never materialized at all.
- **Audit.** The ids of the policies that actually applied to a result are recorded in the
  `QUERY_EXECUTED` audit metadata (`applied_masking_policy_ids`). Unmasked values are never logged or
  stored. Policy create/update/delete emit `MASKING_POLICY_CREATED/UPDATED/DELETED` audit actions.
- **Precedence.** An explicit policy overrides the `FULL` default that a bare `restricted_columns`
  entry would apply to the same column; a `restricted_columns` entry with no covering policy is
  unchanged (backward compatible).

### API connector response masking & classification (AF-518)

`api_connector_masking_policy` brings the same model to API-connector responses (the apigov module),
adapted to non-tabular bodies — same trust posture, a defense-in-depth value-rendering control, not
an access boundary.

- **Matcher types.** A policy targets a response field by `api_masking_matcher_type`: `SCHEMA_FIELD`
  (operation + field via the parsed catalog, resolved to a JSON dot-path), `JSON_PATH` (dot-path into
  a JSON body), `XML_PATH` (XPath into an XML/SOAP body, evaluated with an XXE-hardened parser), or
  `REGEX` (regex over a JSON/text body; first capture group or whole match masked). Strategies and
  `reveal_to_*` semantics are identical to AF-381 (no implicit ADMIN bypass; keyed on the call
  **submitter**).
- **No unmasked persistence.** Resolved policies are merged with the legacy per-permission
  `restricted_response_fields` and applied by `ApiResponseMasker` (reusing `ColumnMasker`) **once**,
  before the immutable response snapshot is stored — the raw body never persists.
- **Audit.** The ids of the policies that applied are recorded in the `API_REQUEST_EXECUTED` audit
  metadata (`appliedMaskingPolicyIds`). Policy/tag mutations emit
  `API_CONNECTOR_MASKING_POLICY_CREATED/UPDATED/DELETED` and
  `API_CONNECTOR_CLASSIFICATION_TAG_ADDED/REMOVED` audit actions.
- **Classification.** `api_connector_classification_tag` tags a field with
  PII/PCI/PHI/GDPR/FINANCIAL/SENSITIVE; tagging auto-derives a masking policy and raises the apigov
  AI analyzer's risk for calls to the operation (fail-safe, never lowers the LLM verdict).

### Row-level security policies (AF-380)

`row_security_policy` rows filter **which rows** a submitter can see (SELECT) or change (UPDATE/DELETE)
on a table — a primary access boundary at the row grain, enforced in the proxy at the AST layer.

- **Parameter-bound, never concatenated.** The proxy builds the predicate AST from the policy's
  structured `column / operator / value` parts and binds the value(s) as JDBC parameters (`?`) — it
  never string-concatenates the value into SQL (CLAUDE.md security rule #1). Admins author a structured
  predicate, not raw SQL, so there is no injection surface from the policy definition itself.
- **`applies_to` polarity is inverted vs. masking.** Where masking `reveal_to_*` *exempts* the listed
  targets, row-security `applies_to_*` *applies* to them. All three empty ⇒ the policy filters **every**
  submitter (governance-safe default); non-empty narrows by role / group / user id. There is **no
  implicit ADMIN bypass** — with empty `applies_to_*`, admins are filtered too. Keyed on the query
  **submitter** (`submittedByUserId`), consistent with masking and `restricted_columns`.
- **Fail-closed.** A `VARIABLE` that cannot be resolved (a missing `users.attributes` key, or
  `user.groups` for a user in no groups) collapses to an always-false `1=0` predicate, so the submitter
  sees nothing rather than everything.
- **Reject, don't leak.** Query shapes the rewriter cannot provably filter (a policied table inside a
  `UNION`, a CTE, a sub-select, an `INSERT … SELECT`, or an `UPDATE … FROM` / `DELETE … USING` join onto
  another policied table) are rejected with **HTTP 422** (`ROW_SECURITY_UNREWRITABLE`), never run
  unfiltered. DML inside a `BEGIN…COMMIT` batch is rewritten per-statement so a user cannot wrap an
  UPDATE to bypass the predicate.
- **Composition.** Row security composes with the schema/table allow-list (checked at submission) and
  column masking (applied at result-read): the security-barrier subquery exposes all columns via
  `SELECT *`, so masking still finds them; rows are filtered first, then surviving rows' columns masked.
- **Predicate variables hold only what AccessFlow stores.** Built-ins resolve from the user record
  (`user.id` / `user.email` / `user.role`) and group memberships (`user.groups`); `:user.<key>` resolves
  from the admin-set `users.attributes` map. Attributes are **not** synced from the IdP — an admin sets
  them explicitly, so there is no implicit trust of arbitrary IdP claims.
- **Audit.** The ids of the policies actually applied to an execution ride on the `QUERY_EXECUTED`
  metadata (`applied_row_security_policy_ids`); no row data is stored. Policy create/update/delete emit
  `ROW_SECURITY_POLICY_CREATED/UPDATED/DELETED` audit actions.

### Data classification tags (AF-447)

`data_classification_tag` rows let admins tag tables/columns as `PII`, `PCI`, `PHI`, `GDPR`,
`FINANCIAL`, or `SENSITIVE` and have AccessFlow **derive stricter handling automatically**, rather than
configuring every masking/review rule by hand.

- **ADMIN-only.** All classification endpoints (`/datasources/{id}/classification-tags*` and the org-wide
  `/admin/data-classifications`) require the ADMIN role and are organization-scoped (datasource must
  belong to the caller's org, else `DATASOURCE_NOT_FOUND`).
- **Derived masking is additive and reversible-safe.** Tagging a column auto-creates a `masking_policy`
  (idempotent); **deleting a tag never deletes the derived policy**, so a classification change can never
  silently weaken an in-place masking control — removing the control is always an explicit, separate,
  audited action on the Masking tab.
- **Classification raises AI risk deterministically.** A query that references a tagged table gets a
  fixed risk-score bump on top of the LLM verdict (the level can only rise), so sensitive-data access is
  escalated by the routing engine even if the model under-rates it.
- **Audit.** Tag add/remove emit `DATA_CLASSIFICATION_TAG_ADDED` / `DATA_CLASSIFICATION_TAG_REMOVED`
  (resource `data_classification_tag`); the org-wide list endpoint is the evidence base for compliance
  reporting.

### Context-aware routing conditions (AF-446)

Routing policies (see [docs/05-backend.md → "Policy-as-code routing engine"](05-backend.md)) can match on the
**client context** of a submission as well as the query itself: `source_ip` (CIDR allow-list, IPv4/IPv6),
`user_agent` (glob), `time_since_last_approval` (recency on the same datasource), and `cicd_origin`.

- **Trust model of the captured context.** The source IP is taken from the `X-Forwarded-For` first hop
  (else the remote address), so it is only trustworthy behind a proxy that overwrites that header —
  deployments that expose AccessFlow directly should not rely on it for hard security decisions. The
  user-agent is fully client-controlled. `cicd_origin` is set from the **API-key authentication channel**
  (the `security.api.ApiKeyAuthentication` marker, which a client cannot forge without a valid key) **or**
  the `X-AccessFlow-CI` header (client-controlled, opt-in). These conditions are intended to *raise*
  scrutiny (escalate / require approvals), not to be the sole grant of trust.
- **Fail closed.** When a required signal is absent (no IP, no user-agent, no prior approval) the leaf
  evaluates to **false**, so a permissive `AUTO_APPROVE` policy never fires on missing context; express
  "escalate unknown origin" as `not(source_ip(<corporate CIDRs>)) → ESCALATE`, which stays true on a
  missing IP. CIDR syntax is validated at create/update (422 on a malformed block).
- **Captured at submission, evaluated later.** Routing runs asynchronously after AI analysis, so the IP,
  user-agent, and CI/CD flag are persisted on `query_requests` at submission time and read back when the
  condition context is built; they are never re-derived from a request that no longer exists.
- **Audit.** A matched `ESCALATE` / `REQUIRE_APPROVALS` policy records its id, resolved
  `effective_min_approvals`, and reason on the `QUERY_REVIEW_REQUESTED` audit row.

### Lifecycle pseudonymization & salt rotation (AF-499)

A `PSEUDONYMIZE` retention policy applies an **irreversible** read-time transform to its target
columns, enforced through the same post-fetch `ColumnMasker` as masking policies (the proxy returns
transformed values; the raw data is never sent over the wire).

- **Per-org salt.** `SHA256_SALTED` / `TOKENIZATION` transforms are salted with a per-organization
  secret held in `lifecycle_salt`, **AES-256-GCM encrypted** at rest (`CredentialEncryptionService`)
  and `@JsonIgnore`d — it is never serialized in any response. The plaintext salt is only ever passed
  to the masker as a transform parameter.
- **Rotation is one-way.** `LifecycleSaltService.rotate` issues a fresh salt and bumps `version`;
  values already hashed under a previous salt **stay hashed** and cannot be recovered — that
  irreversibility is the point. Rotating changes the digest of future reads, not the meaning of past
  ones.
- **Forget-but-keep-aggregates.** Pseudonymization preserves row presence, so counts and aggregates
  survive while the PII itself is irreversibly transformed.

---

## Database Credential Security

- Customer DB credentials stored in `datasource.password_encrypted` as AES-256-GCM ciphertext
- Encryption key: `ENCRYPTION_KEY` env var (32-byte hex) — never stored in database
- `password_encrypted` is **excluded from all API serialization** (`@JsonIgnore`)
- Credentials are decrypted only inside the `QueryProxyService` at JDBC pool creation time
- The decrypted password is passed directly to HikariCP and not retained in application memory beyond pool initialization
- A dedicated low-privilege service account is recommended on each customer database (SELECT only, or specific table grants matching `allowed_tables`)

---

## API Access Governance security (AF-500)

The `apigov` module governs outbound API calls (REST / SOAP / GraphQL / gRPC) with the same security
posture as the query proxy:

- **Auth-secret encryption.** A connector's auth material is supplied as a credential map
  (API key, bearer token, basic user/pass, OAuth2 client-credentials, custom header, or mTLS),
  serialized and AES-256-GCM encrypted via `CredentialEncryptionService` into
  `api_connectors.auth_credentials_encrypted`. The column is `@JsonIgnore`; read DTOs expose only
  `auth_method` + a `has_credentials` boolean — the secret is never returned. It is decrypted only
  inside `ApiExecutionService` at call time and passed straight to the outbound HTTP client.
- **Supported auth methods** (`api_auth_method`): `NONE`, `API_KEY`, `BEARER_TOKEN`, `BASIC`,
  `OAUTH2_CLIENT_CREDENTIALS`, `CUSTOM_HEADER`, `MTLS` (registration/schema supported; client-cert
  execution wiring is a documented follow-up).
- **Outbound OAuth2 token sourcing (#506).** For `OAUTH2_CLIENT_CREDENTIALS` connectors, AccessFlow
  fetches/caches/refreshes the upstream access token itself (grant types `CLIENT_CREDENTIALS` /
  `REFRESH_TOKEN` / `PASSWORD`; client auth `CLIENT_SECRET_BASIC` / `CLIENT_SECRET_POST`). The client
  secret, refresh token, and resource-owner password are AES-256-GCM encrypted at rest in dedicated
  `oauth2_*_encrypted` columns (`@JsonIgnore`, never returned — only `oauth2_*_configured` booleans
  are). The fetched access token is cached **encrypted** in Redis
  (`apigov:oauth2:token:<connectorId>`) with TTL = `expires_in − skew` and refreshed on expiry or a
  single upstream `401`. The token, secret, refresh token, and password are **never logged, audited,
  or serialized**; a real token fetch records only an `API_CONNECTOR_OAUTH2_TOKEN_REFRESHED` audit
  row (grant type only, no token).
- **Response-field masking.** Per-user `restricted_response_fields` (dot-paths) are redacted from the
  JSON response recursively (including through arrays and nested objects) via the shared
  `ColumnMasker` (FULL strategy) before the response snapshot is persisted — the unmasked body is
  never stored. Mirrors the query masking model and is keyed on the submitter.
- **Response-size cap.** The executor reads at most `max_response_bytes` and flags `truncated`,
  bounding memory and exfiltration blast radius.
- **A submitter can never approve their own API request.** Enforced in `DefaultApiReviewService`
  (`SelfApprovalNotAllowedException`, 403), exactly like the query-review / JIT / break-glass blocks.
- **Break-glass parity.** Emergency access requires a per-user/per-connector `can_break_glass` grant
  (required for everyone, including admins), executes immediately through all guards, writes a
  prominent `API_REQUEST_BREAK_GLASS_EXECUTED` audit row, and opens a mandatory retro-review.

---

## Request chaining & grouping security (AF-501)

The `requestgroups` module bundles several query members (across possibly different datasources) and
API-call members (AF-500 connectors) into one grouped request. Bundling **never weakens** a member's
security posture — every per-member control still fires, and the group aggregates them conservatively:

- **Per-member permission validation at build/submit time.** Each member is validated against the
  submitter's permission for its target — `datasource_user_permissions` for a query member,
  `api_connector_user_permissions` (AF-500) for an API member. A user can only bundle a datasource /
  connector they are permitted to use; an un-permitted target is rejected (403), exactly as a
  standalone submission would be. Read/write classification is enforced per member.
- **Break-glass requires every member target.** A break-glass group
  (`submission_reason = EMERGENCY_ACCESS`) requires a non-expired `can_break_glass` grant on **every**
  member target — datasources and connectors alike. A single member lacking it fails the whole group;
  the bundle cannot be used to smuggle one un-eligible target past emergency access.
- **Union of approvers, satisfy every plan.** The group's eligible approvers = the **union** across all
  member plans, and the group reaches `APPROVED` only when **every** member plan's per-stage
  `min_approvals_required` is satisfied. No member's review policy is loosened by being grouped.
- **A submitter can never approve their own group.** Enforced at the service layer
  (`SelfApprovalNotAllowedException`, 403), exactly like the query-review / JIT / break-glass / API
  blocks. One decision is recorded per reviewer/stage covering the whole group.
- **Per-member masking & row-security still apply.** Each query member resolves and applies its
  datasource's masking + row-security directives at execution; each API member applies the connector's
  per-user response-field masking. The group does not bypass any member's data-protection control.
- **No distributed rollback — an APPROVED group is *not* atomic.** Members run in `sequence_order`; on
  the first failure (with `continue_on_error=false`) the run stops and the remaining members are
  `SKIPPED`, but **already-applied members stay** — there is no cross-target rollback (one cannot roll
  back a committed Postgres DDL because a later Mongo write failed). This is surfaced explicitly in the
  UI and docs so reviewers and submitters understand that approving a bundle is not a transaction.

---

## Custom JDBC Driver Trust Boundary

Admin-uploaded JDBC driver JARs (see [`docs/05-backend.md`](05-backend.md#admin-uploaded-drivers-94--142)) live on the AccessFlow filesystem unencrypted. The trust anchors are:

- **Admin-only RBAC** — `POST /datasources/drivers`, `GET /datasources/drivers`, and the delete endpoint all require `hasRole('ADMIN')`. The upload flow is the only way to add a custom driver; the static `DriverRegistry` is compile-time and cannot be mutated at runtime.
- **Pinned SHA-256, verified twice** — The admin enters the expected SHA-256 on upload; the server computes the actual digest while streaming bytes to disk and refuses to persist on mismatch. Every subsequent `resolveCustom(...)` re-verifies the on-disk JAR against the persisted descriptor before instantiating the classloader, so an attacker who tampers with the file (e.g. via a privileged shell on the host) is caught at the next pool init.
- **Per-driver classloader isolation** — Each uploaded JAR loads into its own `URLClassLoader` keyed by `custom_jdbc_driver.id`. Two datasources targeting different uploaded drivers — even with the same `db_type` — cannot share static state. This also limits the blast radius of a malicious driver to its own classloader and the customer DB it connects to; it cannot reach AccessFlow beans (which live on the parent classloader and are not exported into the child).
- **No remote download path** — Unlike the bundled registry, uploaded drivers are never fetched from Maven Central or any remote URL. They are admin-supplied and verified locally.
- **Org-scoped visibility** — Every list / lookup / delete is filtered by `organization_id`. A driver uploaded by org A is invisible to org B, even at the `GET /datasources/types` catalog level.
- **Driver class probe** — At upload time the server instantiates the declared driver class in a throwaway classloader and asserts it implements `java.sql.Driver`. Uploads where the declared class is missing or wrong-typed are rejected with `422 CUSTOM_DRIVER_INVALID_JAR` so they cannot be referenced by a datasource later.
- **JARs are not encrypted at rest.** AccessFlow does not encrypt driver JARs because their contents are not secret (they typically come from public Maven coordinates) and the file is signed-by-content via SHA-256. Operators who need at-rest encryption should mount `${ACCESSFLOW_DRIVER_CACHE}` on a volume that provides it (e.g. dm-crypt / KMS-managed cloud volumes).

The 50 MB upload limit (`spring.servlet.multipart.max-file-size=50MB`) is a defence-in-depth bound — far above any legitimate driver (Snowflake's bundle, the largest in common use, is ~30 MB) and small enough that a runaway upload cannot fill an operator's storage volume.

---

## SQL Injection Prevention

AccessFlow uses defense-in-depth against injection attacks:

1. **JSqlParser validation** — All SQL is parsed before any execution path. Queries that fail to parse are rejected with HTTP 422. This blocks syntactically invalid injection attempts. Multi-statement input is rejected by default; the one exception is a `BEGIN; … COMMIT;` envelope wrapping a homogeneous INSERT/UPDATE/DELETE batch — these are accepted and executed under a single JDBC transaction. Inside a transaction, SELECT (whether SELECT-only or mixed with DML), DDL, `ROLLBACK`, `SAVEPOINT`, and nested `BEGIN` are all rejected as defense-in-depth — the proxy refuses any submission that doesn't fit the narrow "atomic DML batch" use case.

2. **PreparedStatement only** — The proxy engine uses `PreparedStatement` exclusively. No string concatenation or interpolation is used to build queries. Transactional batches use one `PreparedStatement` per inner statement, never `Statement.execute()` of a stacked string.

3. **Schema allow-listing at AST level** — If `allowed_schemas` or `allowed_tables` is configured, the parsed SQL AST is walked with JSqlParser's `TablesNamesFinder` to extract referenced tables. Identifiers are normalised (quotes stripped, ASCII-lowercased) before comparison; the union across `BEGIN; …; COMMIT;` envelopes is enforced as a single set. Violations are rejected (HTTP 403) without touching the database. See [docs/05-backend.md → "Schema / table allow-list enforcement"](05-backend.md#schema--table-allow-list-enforcement) for the full match algorithm.

4. **DDL blocked by default** — `can_ddl=false` (the default) prevents CREATE/ALTER/DROP from being executed even if submitted by an ANALYST or REVIEWER.

5. **Row cap enforcement** — `max_rows_per_query` is enforced via JDBC `setMaxRows()`, not by appending LIMIT to the query string. The executor reads one extra row beyond the cap to set a `truncated=true` flag on the result, then discards it.

6. **Statement timeout** — `accessflow.proxy.execution.statement-timeout` (default 30s) is applied via `PreparedStatement.setQueryTimeout()`. Driver-level cancellation paths (PostgreSQL SQLState `57014`, MySQL `HY008`/`70100`) are mapped to `QueryExecutionTimeoutException` → HTTP 504, distinct from generic execution failures (HTTP 422).

7. **Read-only flag for SELECT** — `Connection.setReadOnly(true)` is set for `SELECT` queries before execution. A driver-level hint that lets replicas/poolers refuse writes; not a substitute for JSqlParser validation but an extra defense if a misclassified statement somehow reaches the executor.

8. **Plaintext credentials never escape pool init** — The decrypted customer-database password is handed to HikariCP at pool creation and the local reference is dropped before `createPool` returns. The `QueryExecutor` never sees plaintext credentials; it acquires connections through `DatasourceConnectionPoolManager.resolve(...)`.

---

## Audit Log Integrity

The `audit_log` table is tamper-evident. The cryptographic chain (added in V26) makes any post-hoc edit, deletion, or reordering detectable, and deployment-level role separation (V38) enforces append-only writes at the database layer.

Implemented today:

- Audit writes go through `AuditLogService` (`audit/api/`). Writes are append-only — neither the entity nor the service exposes UPDATE or DELETE.
- User-initiated actions are audited synchronously from controllers so `ip_address` (honoring `X-Forwarded-For`) and `user_agent` from the HTTP request are captured on the row.
- System-driven state transitions are audited via `@ApplicationModuleListener` in `audit/internal/AuditEventListener` — these run after the publishing transaction commits, on a separate thread; `ip_address` / `user_agent` are NULL on those rows by design.
- **Audit actors.** `actor_id` is the authenticated user UUID for human-driven writes and NULL for system actors. The system-actor namespace is partitioned by `metadata.source`: `BOOTSTRAP` for env-driven reconciler writes ([AF-196](https://github.com/bablsoft/accessflow/issues/196)), unset for the existing query-lifecycle / datasource-deactivation listeners. Bootstrap writes also carry `metadata.change_kind` (`CREATE` / `UPDATE`) and a best-effort `metadata.changed_fields` list — encrypted fields (passwords, API keys, client secrets) are excluded from the diff because the persisted view masks them. Bootstrap rows participate in the same per-org HMAC chain as user-driven rows, so a mixed run (admin UI edit → restart with env vars → admin UI edit) verifies end-to-end.
- `metadata` JSONB contains context-specific information but **never** stores query result data (rows returned), passwords, or encryption keys.
- **HMAC-SHA256 hash chain.** Every new row carries `previous_hash` (the predecessor's `current_hash`, NULL only for the org's first chained row) and `current_hash = HMAC-SHA256(key, canonical(row) ‖ previous_hash)`. The canonical form is a length-prefixed concatenation of `id`, `organization_id`, `actor_id`, `action`, `resource_type`, `resource_id`, normalised JSON metadata, `ip_address`, `user_agent`, and ISO-8601 `created_at` — length-prefixed so the encoding is injective. The key is `AUDIT_HMAC_KEY` (hex-encoded, ≥ 32 bytes); when unset, the audit module derives the key from `ENCRYPTION_KEY` via HKDF-SHA256 with info string `accessflow-audit-hmac-v1` and logs a single WARN. Startup fails if neither key is available.
- **Per-organization insert serialization.** `DefaultAuditLogService.record(...)` takes a Postgres advisory lock (`pg_advisory_xact_lock(orgIdHigh ^ orgIdLow)`) inside the transaction before reading the prior row's hash, so concurrent writes to the same org cannot interleave and break the chain. The lock releases automatically on commit/rollback.
- **Verifier endpoint.** `GET /api/v1/admin/audit-log/verify` (ADMIN only) walks the chain in ASC order, recomputes each row's HMAC, and returns the first row whose recorded `previous_hash` or `current_hash` does not match — see `docs/04-api-spec.md`. The verifier is scoped to the caller's organization. Pre-V26 rows have NULL hashes and are skipped without counting.
- **Separate audit-writer DB role.** Issue #67 / V38. A dedicated Postgres role (`AUDIT_DB_USER`, default `accessflow_audit`) owns `audit_log` and is the only principal that can INSERT through application code. The general `DB_USER` keeps SELECT for the admin read endpoint, but UPDATE/DELETE/TRUNCATE are revoked at the database layer — a compromised general connection cannot rewrite or wipe history. Writes are routed through a separate Hikari pool (`auditDataSource` bean in `audit/internal/config`); reads continue through the primary JPA `DataSource`. The migration aborts startup if the audit role is not provisioned ahead of time (see `deploy/postgres-init/01-audit-role.sql` for the Compose path and `charts/accessflow/values.yaml` → `postgresql.primary.initdb.scripts` for Helm).

Deferred (tracked as separate GitHub issues):

- Exporting hashes in `GET /admin/audit-log` row responses (the verifier is the canonical tamper-detection surface today).

### Compliance reporting & signed exports (AF-459)

The `compliance` module produces pre-built compliance reports and signed exports for audit evidence. It is read-only and gated to the `AUDITOR` (and `ADMIN`) role.

- **Reports are computed from the immutable `query_snapshots` forensic record** (AF-449) — never from live, mutable query rows — so a report reflects exactly what executed. Two reports: **classified-data access** (executed queries joined to `data_classification_tag` by datasource + table name, surfacing which queries touched PII/PCI/PHI/GDPR/FINANCIAL/SENSITIVE objects) and a **regulatory audit trail** of DDL/DELETE operations whose approver names are read from the snapshot's embedded review-decision JSON (forensically correct as of execution time).
- **Digital signature.** `GET /api/v1/admin/compliance/reports/export?type=…&format=PDF|CSV` renders the report and returns a **detached RSA signature** (`SHA256withRSA`) over the exact delivered bytes, reusing the deployment's JWT RS256 key pair (`security.api.ExportSignatureService`) — no new secret. The signature, its algorithm, and the content SHA-256 are returned as response headers (`X-AccessFlow-Signature`, `X-AccessFlow-Signature-Algorithm`, `X-AccessFlow-Content-SHA256`). `GET /api/v1/admin/compliance/signing-certificate` publishes the PEM public key so an auditor verifies offline: `openssl dgst -sha256 -verify key.pem -signature sig.bin report.pdf`.
- **Hash chained into the audit log.** Every export records a `COMPLIANCE_REPORT_EXPORTED` audit entry (`resource_type=compliance_report`) whose `metadata.content_sha256` and `metadata.signature` capture the exported bytes — so the export's hash is embedded in the tamper-evident HMAC chain and is itself detectable against later edits via the audit verifier. This audit write is **integrity-critical: if it fails, the export fails** (it is not swallowed, unlike the best-effort audit-CSV meta-audit).
- **No new persisted data.** Reports reuse `query_snapshots` (V89) + `data_classification_tag` (V90, whose `idx_dct_org` index was added for this org-wide scan); the only schema change is the `AUDITOR` value added to the `user_role_type` enum (V91).

---

## HTTPS and Transport Security

- All production deployments must run behind TLS termination (nginx ingress or load balancer).
- The Spring Boot backend sets `server.ssl.enabled=false` by default — TLS is handled at the ingress layer.
- `HttpOnly; Secure; SameSite=Strict` cookies for refresh tokens prevent CSRF and XSS token theft.
- CORS is configured to allow only the configured frontend origin (`accessflow.cors.allowed-origin` env var).

---

## Security Headers

The Spring Boot API sets the following response headers:

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Strict-Transport-Security: max-age=31536000; includeSubDomains
Content-Security-Policy: default-src 'self'
```

---

## Secrets Management

| Secret | How to Supply |
|--------|--------------|
| `ENCRYPTION_KEY` | Environment variable / Kubernetes Secret |
| `JWT_PRIVATE_KEY` | Environment variable / Kubernetes Secret (PEM format) |
| `AUDIT_HMAC_KEY` | Environment variable / Kubernetes Secret (hex, ≥ 32 bytes). Optional — when unset, derived from `ENCRYPTION_KEY` via HKDF-SHA256. |
| `AI_API_KEY` | Environment variable / Kubernetes Secret |
| `DB_PASSWORD` | Environment variable / Kubernetes Secret |
| Customer DB credentials | Stored encrypted in DB; never in env vars |
| SAML keystore password | Environment variable / Kubernetes Secret |

For Kubernetes deployments, all secrets should be injected via `secretKeyRef` in the deployment manifest, not hardcoded in `values.yaml`.

---

## Recommended Customer Database Service Account Setup

```sql
-- PostgreSQL example: minimum privilege service account for AccessFlow
CREATE USER accessflow_svc WITH PASSWORD 'strong_random_password';

-- Read-only access (for datasources where only SELECT is needed)
GRANT CONNECT ON DATABASE app_prod TO accessflow_svc;
GRANT USAGE ON SCHEMA public TO accessflow_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO accessflow_svc;

-- Or for write access (AccessFlow enforces review before any write reaches here)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO accessflow_svc;

-- Do NOT grant SUPERUSER, CREATEDB, CREATEROLE, or DDL privileges
-- unless can_ddl is intentionally enabled in the datasource config
```
