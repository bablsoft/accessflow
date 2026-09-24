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
  2. Look up the user by email. If they already exist with `auth_provider=SAML`, the row is reused. If they exist with `auth_provider=LOCAL` and a populated `password_hash`, sign-in is rejected with `SAML_LOCAL_EMAIL_CONFLICT` — auto-linking is unsafe because anyone able to assert the same email at the IdP could otherwise take over the local account. If the matched user is a `SERVICE_ACCOUNT` (#869), `findOrProvision` throws `ServiceAccountUserException` — checked **ahead of** the LOCAL-conflict rule, because a bootstrap-seeded bot is `LOCAL` with an unusable hash and would otherwise surface as a misleading conflict — and the handler redirects with `SERVICE_ACCOUNT_SIGN_IN_BLOCKED` before any group sync, exchange code or audit row. An IdP that happens to own a bot's email must never mint it an interactive session.
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
5. Rejects with `SERVICE_ACCOUNT_SIGN_IN_BLOCKED` when the matched user is a
   `SERVICE_ACCOUNT` (#869) — `findOrProvision` checks this **before** the LOCAL-conflict rule
   in step 4 (a bootstrap-seeded bot is `LOCAL` with an unusable hash), and the handler
   redirects before group sync, the exchange code and the audit row. A service account
   authenticates by API key only; an IdP account with the same email is not a way around that.
6. JIT-provisions a new user otherwise, with `auth_provider=OAUTH2` and `role =
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

### SCIM 2.0 provisioning (#621)

Identity providers (Okta, Microsoft Entra ID, Keycloak, OneLogin) drive user and group
lifecycle over `/scim/v2/Users` and `/scim/v2/Groups` — the joiner/mover/leaver follow-on to
SSO. Implemented by the standalone `scim` module.

- **Authentication.** A long-lived per-organization bearer token, never a JWT. Format
  `af_scim_<32-byte base64url>`; only the SHA-256 hex hash and a 12-char display prefix are
  stored (`scim_tokens.token_hash`, `token_prefix`), the plaintext is shown **once** on
  creation (same reasoning as API keys: 256 bits of entropy make the unsalted hash safe to
  look up per request). Multiple named tokens per org allow zero-downtime rotation; revocation
  sets `revoked_at` and takes effect on the next request.
- **Filter chain.** `/scim/v2/**` has its own `SecurityFilterChain` (`@Order(0)`, ahead of the
  SAML/OAuth2/catch-all chains) with `ScimTokenAuthenticationFilter` and a dedicated entry
  point that answers 401 in the SCIM error envelope
  (`urn:ietf:params:scim:api:messages:2.0:Error`) — IdP provisioning engines do not parse
  ProblemDetail. CSRF and CORS are disabled: SCIM is server-to-server, a browser never calls
  it. The org is **derived from the token**, never from the request; every request re-checks
  `scim_config.enabled` and the org-disabled kill-switch, exactly like the JWT and API-key
  filters. The filter's `SCIM` authority never overlaps `PERM_*`/`ROLE_*`, so a SCIM token can
  never reach a JWT-guarded endpoint.
- **Write boundary.** SCIM owns exactly: the mapped email, display name, `externalId`,
  `active`, and group memberships. It can never write roles, `platform_admin`, passwords, TOTP
  settings, or row-security attributes — there is no role-escalation surface. User responses
  never contain password-shaped fields (the wire records have none). SCIM-provisioned users
  carry `auth_provider = SCIM` and a NULL password hash: local login is impossible and they
  sign in through the org's SAML/OIDC SSO (whose email-match provisioning accepts non-LOCAL
  rows without tripping the local-account takeover guard).
- **Deactivation fan-out.** `active=false` (PATCH, PUT, or DELETE — AccessFlow never
  hard-deletes users) flips `is_active` and publishes `core.events.UserDeactivatedEvent`; the
  security module revokes all refresh tokens and the access module revokes the user's APPROVED
  JIT grants through the ordinary revocation path (system-attributed). Outstanding access
  tokens expire naturally within `ACCESSFLOW_JWT_ACCESS_TOKEN_EXPIRY` (default 15 minutes).
  The same event unifies admin-UI deactivation, so all paths behave identically.
- **Group provenance.** SCIM-pushed memberships carry `source = SCIM` in
  `user_group_memberships`, disjoint from admin `MANUAL` rows and SSO-login `IDP` rows — no
  path can overwrite another's memberships. Deleting a group over SCIM cascades its
  memberships **and** its group-based grants (`datasource_group_permissions`,
  `api_connector_group_permissions`) — the correct semantics for "group removed at the IdP",
  and audited with member counts.
- **Failure modes.** Unknown/revoked token, disabled config, disabled org → 401 (SCIM
  envelope). Duplicate email/externalId/group name → 409 `scimType=uniqueness` (emails are
  globally unique across orgs; the IdP then adopts the existing user via its `userName eq`
  lookup). User quota exhausted (create or reactivation) → 403. Unsupported filter → 400
  `invalidFilter`; unsupported PatchOp path/op → 400 `invalidPath`/`invalidValue`. SCIM error
  details are intentionally not localized — the consumer is a machine.
- **Audit.** Every SCIM mutation writes a synchronous audit row (`SCIM_USER_PROVISIONED`,
  `SCIM_USER_UPDATED`, `SCIM_USER_DEACTIVATED`, `SCIM_GROUP_SYNCED`, `SCIM_GROUP_DELETED`)
  with `actor_id = NULL` and the token identity (`scim_token_id`, `scim_token_name`) in the
  metadata. Admin config/token changes audit as `SCIM_CONFIG_UPDATED` / `SCIM_TOKEN_CREATED` /
  `SCIM_TOKEN_REVOKED` with the caller as actor.
- **Load-bearing regression check:** `e2e/tests/admin-scim-config.spec.ts` — config CRUD,
  show-once token issue/revoke, Okta-shaped provisioning, Entra-shaped deactivation, and the
  401 envelope after revocation.

Operator setup guide (Okta / Entra ID walkthroughs): `website/docs/configuration/auth/#cfg-scim`.

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
  the user can hit, including `/mcp/**`. The one narrowing is a service account's
  `mcp_tool_allow_list` (#872): `GuardedToolCallback` wraps every MCP tool and returns
  `permission_denied` for a tool outside the list before the tool runs. It only ever removes
  tools — never adds permissions — and REST endpoints are unaffected.
- **Rate limiting (#873).** Every API-key-authenticated request is counted per identity by the
  `serviceaccounts` module's `ApiKeyRequestFilter` (a servlet filter at order 0, inside the
  security chain, after authentication *and* authorization) — a `service_accounts` row's
  `rate_limit_per_minute` / `rate_limit_per_day` when set, otherwise the deployment defaults
  (`ACCESSFLOW_SERVICEACCOUNTS_RATE_LIMIT_REQUESTS_PER_MINUTE`, 120; `…_PER_DAY`, 0 = unlimited).
  Over the cap: `429 SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED` with a `Retry-After` header. JWT
  sessions are never limited. It is a **resource guardrail, not an authorization control**, so it
  fails open when Redis is unreachable — the request is still authenticated, permission-bounded
  and audited, and failing closed would take every agent and CI pipeline down on a Redis blip.
- **On-behalf-of attribution (#874).** An API-key request may name the human it acts for with
  `X-AccessFlow-On-Behalf-Of: <uuid | email>`. Three security properties hold by construction:
  1. **Zero privilege change.** The header is resolved by the same order-0 `ApiKeyRequestFilter`
     and parked in a request attribute — never on the `Authentication`. `JwtClaims` is never
     rebuilt, re-resolved or widened from the named human, and nothing in `security` or any
     permission resolver reads the attribute; the only reader is
     `serviceaccounts.api.OnBehalfOfPrincipalService`, consumed by submission controllers and the
     audit contributor. `OnBehalfOfIntegrationTest` pins it: a READONLY bot naming an ADMIN gets the
     same 403s and the same `/me` profile with and without the header.
  2. **Consent, validated, fail-loud.** The named human must be an active `HUMAN` of the same
     organization holding a live `service_account_delegated_principals` grant for that account
     (given by the human at `/me/service-account-delegations` or by a `USER_MANAGE` /
     `SERVICE_ACCOUNT_MANAGE` admin). Any miss is one opaque `403 ON_BEHALF_OF_NOT_PERMITTED
     reason=not_permitted` — the header cannot enumerate emails — and a JWT session sending it gets
     `reason=not_api_key`. Nothing is ever silently dropped, so a misconfigured agent is obvious on
     its first request.
  3. **Never a vote.** On every review / decision surface the header is refused outright
     (`403 ON_BEHALF_OF_REVIEW_FORBIDDEN`), the MCP `review_query` tool answers `permission_denied`
     when a principal is present, and `*_review_decisions.on_behalf_of_user_id` — the #622
     provenance column that *confers reviewer eligibility* — is never written from it. What the
     header does do is close the approval-laundering hole: the named human becomes a second
     submitter identity under the self-approval ban (below).
  The attribution itself rides in `audit_log.metadata` (`on_behalf_of_user_id`, plus
  `api_key_id` and `service_account` on every API-key request), inside the HMAC chain — see
  [Audit Log Integrity](#audit-log-integrity).
- **Lifecycle.** Per-user CRUD endpoints live at `/api/v1/me/api-keys` (see
  `docs/04-api-spec.md`). Revocation sets `revoked_at = now()` and is idempotent; revoked or
  expired keys never authenticate.
- **Filter placement.** `ApiKeyAuthenticationFilter` (in the `security` module, sibling to
  `JwtAuthenticationFilter`) runs before
  `JwtAuthenticationFilter` in the main security chain. If no API key header is present, the
  JWT filter still gets a chance. The API-key filter resolves the key to
  `ResolvedApiKey(apiKeyId, userId)` and populates an `ApiKeyAuthenticationToken` — a token that
  implements the public `security.api.ApiKeyAuthentication` marker (exposing `apiKeyId()`, #869)
  and carries the **same `JwtClaims` principal** the JWT path mints, so downstream controllers
  and MCP tools are auth-agnostic. The key id lives on the token beside the claims, never inside
  them: `JwtClaims` is the permission-carrying principal and is deliberately unchanged, so nothing
  read during permission resolution can ever see which key (or, later, which on-behalf-of human)
  made the request.
- **Audit.** `api_keys.last_used_at` is bumped on each successful authentication. Bumps are
  best-effort and swallow exceptions to avoid impacting auth latency.
- **Service accounts are API-key-only (#869).** A `users` row with
  `principal_type = SERVICE_ACCOUNT` (#868) can never hold an interactive session:
  `POST /auth/login`, `POST /auth/refresh` and both SSO exchanges (`/auth/oauth2/exchange`,
  `/auth/saml/exchange`) reject it with HTTP 401 `SERVICE_ACCOUNT_SIGN_IN_BLOCKED`
  (`security.api.ServiceAccountSignInException`, thrown by `LocalAuthenticationService` at the
  same three chokepoints as the disabled-tenant guard), and the SAML / OAuth2 success handlers
  redirect with `error=SERVICE_ACCOUNT_SIGN_IN_BLOCKED` before issuing an exchange code. The
  password-login check runs before the password is verified, so the rule holds regardless of
  the (bootstrap-seeded, unusable) hash. **Accepted trade-off:** this makes `/auth/login` the one
  pre-credential outcome that is not collapsed into the generic `UNAUTHORIZED` envelope — an
  anonymous caller who already knows a bot's email can confirm it is a service account. That
  reveals nothing usable (a bot has no password to guess and its key carries 256 bits of entropy)
  and was chosen over a generic 401 so the rule is observable and testable; flip the order of the
  guard and the password check in `LocalAuthenticationService.login` if the oracle ever matters.
  A password reset is likewise never emailed for a service account. The refresh guard means a person adopted as a service account by bootstrap loses their
  session on the next token rotation. The key itself keeps working — it is the only credential a
  service account has.

See `docs/13-mcp.md` for the end-user guide.

### Slack request verification (AF-362)

Inbound Slack callbacks (`/api/v1/integrations/slack/actions`, `/api/v1/integrations/slack/commands`) are JWT-exempt and authenticated by the Slack **signing secret** instead. `SlackRequestVerifier`:

- Reads the **raw** request body (the controller binds `@RequestBody String`; nothing accesses request parameters first, so the form stream stays intact for an exact-bytes HMAC).
- Looks up the org's `slack_app_config` by the payload's `api_app_id`, decrypts its signing secret, and recomputes `HMAC-SHA256` over the base string `v0:{X-Slack-Request-Timestamp}:{body}`. The result (`v0=<hex>`) is compared against `X-Slack-Signature` in **constant time** (`MessageDigest.isEqual`).
- **Rejects (401):** missing `X-Slack-Signature` / `X-Slack-Request-Timestamp`; a timestamp outside the ±`accessflow.notifications.slack.signature-tolerance` window (default 5 min); and an HMAC mismatch.
- **Replay protection:** every verified signature is recorded in Redis (`slack:sig:<sig>`, `SETNX` with TTL = the tolerance window) by `SlackReplayGuard`; a second sighting within the window is rejected as a replay.

Secrets at rest: `slack_app_config.bot_token_encrypted` and `signing_secret_encrypted` are AES-256-GCM ciphertext via `CredentialEncryptionService`, `@JsonIgnore` on the entity, and never returned by the admin API (only `has_bot_token` / `has_signing_secret` booleans). Approve/Reject clicks run through the same `ReviewService` path as REST, so the self-approval block and RBAC/stage checks apply identically — a Slack user can never approve their own query.

---

## Authorization — Roles & the permission catalog (AF-522)

Functional authorization is **permission-based**. A fixed, code-defined catalog of functional
permissions (`core.api.Permission`, 46 values grouped for display — see
`GET /api/v1/admin/permissions`) is composed into **roles**:

- The **5 system roles** (`ADMIN`, `REVIEWER`, `ANALYST`, `READONLY`, `AUDITOR`) are immutable
  **global** rows in the `roles` table (`organization_id IS NULL`, `is_system = true`), seeded by
  `V114` with permission sets that reproduce the matrix below exactly. Their authoritative
  definition is `core.api.SystemRolePermissions` (the DB seeds mirror it; runtime resolution for
  system roles always answers from the code map).
- **Custom roles** are org-scoped rows an admin composes from the catalog at `/admin/roles`
  (`ROLE_MANAGE` permission). Admins cannot invent permissions — only combine them. A custom role's
  name must not collide (case-insensitively) with a system name or another custom role in the org.
  Deleting a role still assigned to users is rejected (`409 ROLE_IN_USE`); editing/deleting a
  system role is rejected (`409 ROLE_SYSTEM_IMMUTABLE`).

**Enforcement.** Each permission maps to the Spring Security authority `PERM_<name>`; endpoints are
gated `@PreAuthorize("hasAuthority('PERM_…')")` and service-layer checks test the caller's resolved
permission set. The **access token** carries the role's resolved `permissions` claim (plus
`role_id`/`role_name`), minted at login/refresh — a role edit propagates within the 15-minute
access-token TTL. API-key callers get **per-request** resolution (the key filter already loads the
live profile). Tokens minted before AF-522 (no permissions claim) fall back to deriving the set
from their system-role claim. A principal on a system role also keeps the legacy `ROLE_<name>`
authority during the transition.

**Authenticated-only endpoints.** A handful of install-level reads carry
`@PreAuthorize("isAuthenticated()")` and no permission at all, because the answer is the same for
every user and contains no organization data. The one added by #836 is
`GET /api/v1/system/update-status` (is a newer stable release out, and where is its changelog
entry) — any signed-in user, any role, sees it; it is what the sidebar version chip renders. The
JWT filter chain still rejects anonymous callers (`401`).

**Role-name matching.** Users carry `role_id` → `roles`; the legacy `users.role` enum column stays
populated for system-role users (and NULL for custom-role users) for backward compatibility.
Everything that targets roles *by name* — masking `reveal_to_roles`, row-security
`applies_to_roles` (and its `user.role` predicate variable), routing-policy `requester_role`
conditions, and review-plan approver rules — matches the user's effective role **name**
case-insensitively, so custom roles participate in those policies exactly like system roles.
Admin-side validation resolves the names against the org's role catalog.

**Invariants unchanged:** the self-approval bans (query/access/attestation/group review) are
identity-based and hold regardless of permissions; the `platform_admin` flag stays orthogonal;
per-datasource permissions (can_read/can_write/can_ddl/can_break_glass, allow-lists, masking, row
security, JIT) are a separate system untouched by the catalog.

**v1 limitations:** SSO JIT-provisioning `default_role` (SAML/OAuth2) still selects a *system*
role; admin notification fan-outs (anomaly alerts, break-glass alerts) and setup detection still
target the system `ADMIN` role — custom-role users with admin-like permissions do not receive
them.

### Reviewer delegation (#622)

A reviewer may name a delegate to cover their review duty for a window. Three properties are
enforced in the service layer, not the UI:

1. **A delegation never grants a permission.** The delegate still needs `QUERY_REVIEW` /
   `API_REQUEST_REVIEW` in their own right — the permission check runs before delegation is ever
   resolved. Delegation widens *which requests* an already-permitted reviewer may act on.
2. **The self-approval ban covers both identities.** A delegate can never decide their own request,
   and can never use a delegation from A to decide a request **A** submitted — or had submitted
   *for* them by an agent (#874). Such a request does not appear in their queue and returns 403 if
   decided directly.
3. **No transitivity.** Resolution is exactly one hop: A→B→C confers nothing on C. Enforced by
   construction (the lookup never traverses) rather than by validation on write, which creating the
   two delegations in the other order would defeat.
4. **Never to an agent.** A delegation cannot *target* a service account (#874,
   `422 ILLEGAL_REVIEW_DELEGATION`; agents are omitted from the candidate picker): review authority
   is not handed to non-human identities. The opposite direction — a human letting an agent act
   *for* them with no authority at all — is `service_account_delegated_principals`.

Both parties' `is_active` flags are re-checked on every resolution, so deactivating either one — by
an admin or by SCIM deprovisioning — stops the delegation conferring eligibility immediately,
without waiting for a cleanup job. Every decision taken under a delegation records both identities
plus the delegation id, and `GET /admin/review-delegations` (`QUERY_ADMIN`) is the oversight surface
that lets an auditor interpret an `on_behalf_of` entry.

**Governance change in this release:** API-request review previously had *no* approver check — any
holder of `API_REQUEST_REVIEW` could decide any pending request in the organization. It now honours
the connector review plan's approver rules. This is opt-in by configuration: a connector with no
review plan, or a plan with no approver rules, behaves exactly as before. Only a connector whose plan
an admin actually configured with approvers now restricts who may decide its requests.

---

### System-role matrix

The matrix below defines the **system roles'** permission sets (the pre-AF-522 behaviour,
preserved exactly). Capability ↔ permission mapping examples: submit SELECT/DML/DDL →
`QUERY_SUBMIT_SELECT`/`QUERY_SUBMIT_DML`/`QUERY_SUBMIT_DDL`; approve/reject queries →
`QUERY_REVIEW`; view all history → `QUERY_VIEW_ALL`; the ADMIN-only oversight surfaces
(list/export any user's queries, view any results, execute/replay any approved query, submit
without a per-datasource grant) → `QUERY_ADMIN`; "always an eligible approver" → `REVIEW_OVERRIDE`.

| Capability | READONLY | ANALYST | REVIEWER | ADMIN | AUDITOR |
|-----------|----------|---------|----------|-------|---------|
| Delegate own review duty (#622) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Submit SELECT queries | ✓ | ✓ | ✓ | ✓ | — |
| Submit DML queries (INSERT/UPDATE/DELETE) | — | ✓ | ✓ | ✓ | — |
| Submit DDL queries | — | — | — | ✓ | — |
| View own query history | ✓ | ✓ | ✓ | ✓ | — |
| View all query history | — | — | ✓ | ✓ | — |
| Approve / reject queries | — | — | ✓ | ✓ | — |
| Approve own submitted queries | — | — | — | — | — |
| View approval likelihood on a query (AF-645) | — | — | ✓ | ✓ | — |
| View approval likelihood on own submitted query | — | — | — | — | — |
| Request time-bound datasource access (AF-378) | ✓ | ✓ | ✓ | ✓ | — |
| Review / approve / reject access requests | — | — | ✓ | ✓ | — |
| Approve own access request | — | — | — | — | — |
| Early-revoke an active grant | — | — | — | ✓ | — |
| View / export the over-provisioned access report (`ACCESS_USAGE_REPORT_VIEW`, #625) | — | — | — | ✓ | ✓ |
| Trace a hypothetical request through the live evaluators (`DATASOURCE_PERMISSION_MANAGE`, AF-859) | — | — | — | ✓ | — |
| Read who can reach a table (`DATASOURCE_PERMISSION_MANAGE` or `ACCESS_USAGE_REPORT_VIEW`, AF-859) | — | — | — | ✓ | ✓ |
| Read the privileged-access report — who can reach data with no permission row (`DATASOURCE_PERMISSION_MANAGE` or `ACCESS_USAGE_REPORT_VIEW`, #968) | — | — | — | ✓ | ✓ |
| View AI analysis results | ✓ | ✓ | ✓ | ✓ | — |
| Re-run AI analysis on a failed query (`POST /queries/{id}/reanalyze`) | — | — | ✓ | ✓ | — |
| Create / edit datasources | — | — | — | ✓ | — |
| Manage user permissions | — | — | — | ✓ | — |
| Create / edit review plans | — | — | — | ✓ | — |
| View audit log | — | — | — | ✓ | — |
| Manage external audit sinks (`AUDIT_SINK_MANAGE`, #628) | — | — | — | ✓ | — |
| Manage deployment pipelines (`DEPLOYMENT_PIPELINE_MANAGE`, #684) | — | — | — | ✓ | — |
| Review deployment requests (`DEPLOYMENT_REVIEW`, #684) | — | — | ✓ | ✓ | — |
| Manage SQL review rulesets + read the rule catalog (`SQL_REVIEW_MANAGE`, #861/#863) | — | — | — | ✓ | — |
| Manage service accounts (`SERVICE_ACCOUNT_MANAGE`, #868) | — | — | — | ✓ | — |
| Manage schema change sets, promotions and drift findings (`SCHEMA_CHANGE_MANAGE`, seeded by #878; gates `/schema-change-sets`, `/schema-change-promotions` and `/schema-drift` — #879–#881) | — | — | — | ✓ | — |
| Lint SQL against a visible datasource's ruleset (`POST /sql-review/evaluate`, #863) | ✓ | ✓ | ✓ | ✓ | — |
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

**Key rule:** A user can never approve their own query request, regardless of role — and since
#874 "their own" includes a request an agent or CI job submitted **on their behalf**
(`on_behalf_of_user_id`). The same widening applies to API requests, deployment requests, rollback
reviews and request groups: the named human is a submitter identity for the ban, is excluded from
the review queue, and cannot be reached through a reviewer delegation either. Without it, "Alice
tells her agent to submit, then Alice approves" passes the `reviewerId != submittedBy` check with
two different UUIDs.

**Approval likelihood (AF-645):** the advisory approval-outcome prediction is served only to callers
holding `QUERY_REVIEW` who are not the query's submitter — a reviewer reading their own request is
excluded too. `QueryReadController` omits the `approval_prediction` block from the response for
everyone else rather than relying on the client to hide it, and `RealtimeEventDispatcher` keeps the
submitter out of the `query.prediction_complete` fan-out for the same reason. The rule exists so
nobody can read the likely verdict on their own open request and cancel-and-resubmit against it.

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
(not `/editor`) — since AF-522 the redirect is permission-driven (a user holding
`COMPLIANCE_REPORT_VIEW` but not `QUERY_SUBMIT_SELECT` lands on the auditor dashboard). The role's
permission set is `{COMPLIANCE_REPORT_VIEW, ATTESTATION_EVIDENCE_EXPORT, ACCESS_USAGE_REPORT_VIEW,
BREAK_GLASS_VIEW, ANOMALY_VIEW}`.

**Over-provisioned access (#625):** `ACCESS_USAGE_REPORT_VIEW` gates both
`/api/v1/admin/over-provisioned-access` and its CSV export
(`@PreAuthorize("hasAuthority('PERM_ACCESS_USAGE_REPORT_VIEW')")`). It is held by `ADMIN` (which holds
the whole catalog) and `AUDITOR`, seeded by `V134` — the `role_permissions.permission` column is
`VARCHAR` against a code-defined catalog, so a new value needs no DDL. The report exposes *who holds
which standing grant and when they last used it*, which is activity data about other users; that is
why it is an admin/auditor surface and not something a grant holder can read about themselves or
anyone else. The recommendation it carries is **advisory only** — no authorization decision anywhere
reads it, and nothing is revoked on its strength.

**Privileged access (#968):** the org-wide complement of that report — the identities that can reach
data *without* any standing grant to report on. `GET /api/v1/admin/privileged-access` lists every
active user whose effective role carries `QUERY_ADMIN` (the submission service skips the per-datasource
gate for them outright, so no permission screen ever shows them) and every holder of an unexpired
`can_break_glass` grant, direct or inherited through a group, one row per identity with the role that
carries the bypass, the datasources and expiry of each break-glass grant, and the user's submission
history read from `query_requests`. Gated exactly as the effective-access explainer is
(`hasAnyAuthority('PERM_DATASOURCE_PERMISSION_MANAGE','PERM_ACCESS_USAGE_REPORT_VIEW')`) — no new
`Permission` value — and audited on **every** read as `PRIVILEGED_ACCESS_REPORT_VIEWED` against the
organization, with the row count and the filters applied but never an email or a role name. A custom
role carrying `QUERY_ADMIN` resolves identically to the system `ADMIN` role, because the same
`RolePermissionHolderLookupService` that the explainer uses inverts the catalog. **Advisory only:**
nothing revokes on its strength; a bypass ends through a role change, an attestation campaign, or an
explicit permission edit.

**Per-table row limits (#934):** `ROW_LIMIT_POLICY_MANAGE` gates the per-datasource row-limit-policy
CRUD (`/api/v1/datasources/{id}/row-limit-policies`,
`@PreAuthorize("hasAuthority('PERM_ROW_LIMIT_POLICY_MANAGE')")`). It sits in the `DATA_POLICIES`
group and is held by `ADMIN` (seeded by `V185`).

**Result-export governance (#626):** `EXPORT_POLICY_MANAGE` gates the per-datasource export-policy
CRUD (`/api/v1/datasources/{id}/export-policies`,
`@PreAuthorize("hasAuthority('PERM_EXPORT_POLICY_MANAGE')")`); it sits in the `DATA_POLICIES` group
beside masking / row security and is held by `ADMIN` (seeded by `V146`, same `VARCHAR`-catalog
convention as `V134`). The **export endpoints themselves are not admin surfaces**:
`GET /queries/{id}/results/export` and `/export-decision` carry the results endpoint's own
authorization — `QUERY_ADMIN` or the query's submitter, anyone else receiving an indistinguishable
404. Policy enforcement has **no implicit ADMIN bypass**: a policy whose `applies_to_*` lists are
empty denies, caps, or watermarks admins exactly as it does everyone else. Every export writes a
`RESULT_EXPORTED` audit row (fail-hard on the download path — no audit row, no bytes), and the
watermark is baked into the signed bytes, so removing it invalidates the RS256 signature.

**SIEM & WORM audit streaming (#628):** `AUDIT_SINK_MANAGE` gates the external-audit-sink CRUD and
test endpoints (`/api/v1/admin/audit-sinks`,
`@PreAuthorize("hasAuthority('PERM_AUDIT_SINK_MANAGE')")`); it sits in the `COMPLIANCE` group and is
held by `ADMIN` (seeded by `V148`, same `VARCHAR`-catalog convention as `V134`/`V146`). Sink secrets
(Splunk HEC token, HTTPS-batch HMAC secret, S3 secret access key) are AES-256-GCM encrypted at rest
and masked as `********` on read — the notification-channel contract — and never appear in the
`AUDIT_SINK_*` audit rows. Streaming is read-only over the append-only `audit_log` and strictly
downstream of the synchronous write path: a compromised or dead sink can never block, mutate, or
truncate the in-database chain, and every exported event carries its `previous_hash`/`current_hash`
so an exported window is independently chain-verifiable (the S3 WORM segments are additionally
RS256-signed with the same key as compliance exports).

**Deployment governance (#684, epic #682):** two permissions in the new `DEPLOYMENT_GOVERNANCE`
group, seeded by `V151` (same `VARCHAR`-catalog convention as `V134`/`V146`/`V148`) —
`DEPLOYMENT_PIPELINE_MANAGE` (held by `ADMIN`) and `DEPLOYMENT_REVIEW` (held by `ADMIN` and
`REVIEWER`). What each one gates, and why triggering a deployment deliberately has no functional
permission at all, is in
[Deployment governance security](#deployment-governance-security-epic-af-682) below.

**Service accounts (#868, epic #867):** `SERVICE_ACCOUNT_MANAGE` sits in the `USERS` group beside
`USER_MANAGE` / `GROUP_MANAGE` / `ROLE_MANAGE` and is held by `ADMIN` only (seeded by `V174`, same
`VARCHAR`-catalog convention as `V134`/`V146`/`V148`/`V151`/`V171`). Since #871 it gates the whole
`/admin/service-accounts` surface — create / update / deactivate and issuing, rotating and revoking
API keys **on behalf of** an account — every mutation audited as `SERVICE_ACCOUNT_*` against
resource `service_account`. Three rules there are security-relevant: (1) a UI-created account
defaults to `READONLY`, not the bootstrap reconciler's `ADMIN`; (2) rotation never revokes — it
issues the replacement and *expires* the old key after a grace window (`ACCESSFLOW_SERVICEACCOUNTS_ROTATION_GRACE`,
default 24 h, or a per-request `grace_period`), so a leaked key is handled by **revoke**, not rotate;
(3) the one key bootstrap declares (`api_keys.bootstrap_declared`) can be neither revoked nor
rotated, on the admin surface *or* through the account's own `/me/api-keys` — `importOrUpdate`
clears `revoked_at` on every changed reconcile, so a revoke would only appear to work until the
next restart; the 409 names the real remediation (rotate the secret at the bootstrap source, then
restart). **Known limitation:** `PUT /admin/users/{id}` (`USER_MANAGE`) does not consult
`principal_type`, so it can still change a `BOOTSTRAP` account's display name or role — the
bootstrap-managed 409 is enforced on the service-account surface only. The users page (#875)
therefore lists service accounts with a *Service account* badge and a principal-type filter
(default: everyone) but routes their row action to `/admin/service-accounts/{id}` instead of the
user edit modal, so the UI never offers the unguarded path; `GET /admin/users` exposes
`principal_type` and accepts `?principal_type=` for exactly this. Since #869 the discriminator itself is enforced on the sign-in
surface — password, refresh, SAML and OAuth2 all reject a `SERVICE_ACCOUNT` (see "API key
authentication" above) — while by API key a service account still authenticates and is authorized
exactly as the role on its **own** `users` row dictates — `owner_user_id` (the human it acts for)
confers nothing. The discriminator is set only through `UserAdminService.setPrincipalType`,
called from the `serviceaccounts` module in the same transaction as the detail row — no other code
path can type a user (`PrincipalTypeChokepointTest`, an ArchUnit rule, fails the build on any other
caller), and `security` never depends on `serviceaccounts` (it reads `principalType`
off `core.api.UserView`).

**Deterministic SQL review (#861, epic #860 — full chapter: [docs/19-sql-review.md](19-sql-review.md)):** `SQL_REVIEW_MANAGE` sits in the `WORKFLOW_ADMIN`
group beside `ROUTING_POLICY_MANAGE` and is held by `ADMIN` only (seeded by `V171`, same
`VARCHAR`-catalog convention as `V134`/`V146`/`V148`/`V151`). Since #863 it gates the ruleset CRUD
(`/admin/sql-review-rulesets`, every mutation audited as `SQL_REVIEW_RULESET_*`) and the localized
rule catalog (`GET /sql-review/rules`). The read-only evaluation endpoint
(`POST /sql-review/evaluate`) is deliberately **not** behind it: any signed-in user may lint SQL
against a datasource they can see, authorized exactly as `POST /queries/analyze` and
`POST /queries/dry-run` — a direct or group permission row, or `QUERY_ADMIN` — and a datasource the
caller cannot see is a **404**, never a 403, so the endpoint cannot be used to learn which ruleset
(or which protected-table globs) a hidden datasource carries. It persists nothing and writes no audit
row. A datasource's `environment` attribute is written under the existing `DATASOURCE_MANAGE`
permission — it is datasource configuration, not policy.

Since #864 a `BLOCK` finding is **enforced**, and the enforcement is a strengthening only: at the
`PENDING_AI` decision point it suppresses every path that would have approved the query without a
person — routing `AUTO_APPROVE`, the grant-covered fast path (#582) and the review plan's
`requires_human_approval=false` / `auto_approve_reads` — and the query lands in `PENDING_REVIEW`.
It never rejects (a routing `AUTO_REJECT` still rejects), never bypasses a reviewer, and never
loosens anything. Findings are evaluated and persisted synchronously at submission, so they bind
even when AI analysis is skipped or fails, and the same guard is applied to every query member of
a request group. Every suppression is system-attributed in the audit log as `SQL_REVIEW_BLOCKED`
(null actor, `trigger=sql_review`, the rule ids and the paths it closed).

**Break-glass is the documented exemption**: an emergency execution records its findings on the mandatory
`break_glass_events` retro-review but is not gated by them — emergency access stays an emergency
path, and the compensating controls (admin fan-out, `QUERY_BREAK_GLASS_EXECUTED`, the
admin-only acknowledgement) are unchanged. Findings are stored as `rule_id` + `args` and rendered
per reader, so no English text is ever persisted; the reviewer-facing fields (`sql_review_findings`
on the query detail, break-glass log and request-group detail; `sql_review_blocking_count` on the
review queue) are subject to the same read authorization as the objects they hang off.

**Schema change governance (#878, epic #870):** `SCHEMA_CHANGE_MANAGE` sits in the
`WORKFLOW_ADMIN` group beside `ROUTING_POLICY_MANAGE` and `SQL_REVIEW_MANAGE` and is held by
`ADMIN` only (seeded by `V179`, the same `VARCHAR`-catalog convention as `V171`/`V174`). It gates
change-set authoring (#879), promotion (#880) and the drift worklist, scan-now and configuration
(#881). It is deliberately a functional permission, not a bypass — see
[Schema change promotion security](#schema-change-promotion-security-880) below.

**Schema drift exposes schema across the ladder (#881).** Drift introspects through
`DatasourceAdminService.introspectSchemaForSystem`, which is organization-scoped but **not**
per-datasource permission-gated, and records schema, table and column names in its findings. A
holder of `SCHEMA_CHANGE_MANAGE` can therefore trigger a scan of — and read the structure of — any
datasource bound to an environment of their organization's pipelines, including one they hold no
query grant on. Promotion is the only schemachange path with a per-datasource check (`can_ddl`, no
admin exemption). This is intended — the permission governs schema across the whole ladder — but it
makes `SCHEMA_CHANGE_MANAGE` a schema-disclosure grant as well as a workflow one, which matters when
adding it to a custom role. Drift never writes to the databases it reads.

### Platform admin (super-admin) — `PLATFORM_ADMIN` authority (AF-456)

`users.platform_admin` is an **orthogonal boolean flag, not a fifth role** — the four roles above are
unchanged. A platform admin keeps their home-org `role` (e.g. `ADMIN`) **and** is additionally granted
the extra Spring Security authority `PLATFORM_ADMIN`. The JWT carries a `platform_admin` claim and the
login / `GET /me` user object exposes a `platform_admin` boolean.

- **What it unlocks.** Only the cross-org tenant-management plane at `/api/v1/platform/organizations`
  (`@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")` — see [04-api-spec.md → Platform Organizations](04-api-spec.md#platform-organizations))
  and the read-only scheduled-job monitor at `/api/v1/platform/jobs` (#923 — see
  [04-api-spec.md → Platform Jobs](04-api-spec.md#platform-jobs-923)), which is process-wide rather
  than per-tenant and so has no `Permission` catalog value.
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

Beyond platform roles, every action against a customer database is validated against the caller's
**effective permission** — the most-permissive union of their direct `datasource_user_permissions` row
and every unexpired `datasource_group_permissions` grant for a group they belong to (AF-530). Boolean
capabilities are OR-ed, allow-lists (`allowed_schemas`/`allowed_tables`) unioned, and `restricted_columns`
intersected (a column is masked only when **every** contributing grant masks it), each grant's `expires_at`
honoured independently. `row_limit_override` is the one deliberate inversion: the **smallest** non-null value
wins, so a wide group grant can never raise a tight per-user cap, and the proxy clamps it to the datasource
cap and the global ceiling (#933). The union is computed once in `DefaultDatasourceUserPermissionLookupService.findFor`,
the single choke-point every enforcement path (proxy, JIT/break-glass gates, masking/row-security scoping,
`requestgroups` checks) reads through, so groups behave here exactly as they already do for
masking-reveal and row-security. Granting a group access lets an admin onboard a whole team without a
row per member; JIT materialisation still manages the per-user row specifically (via `findDirectFor`,
which ignores group grants). The check below runs against that resolved effective permission:

```
User attempts query on datasource
         │
         ▼
Does an effective permission exist for the user (direct grant OR any group grant)?
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

### Automatic query suggestion visibility (#776)

The editor's suggestion rail offers **other analysts' approved SQL**. That makes visibility the
whole security question: showing someone a query against a table they are not allow-listed for would
disclose that the table exists, which is precisely what the allow-list is for. The read service —
not the controller — enforces the following, in this order, and each step answers a different
question:

1. **Can the caller see the datasource at all?** Resolved through `DatasourceAdminService`
   (`getForAdmin` for a `QUERY_ADMIN` caller, `getForUser` otherwise), which answers **404, never
   403**, so the endpoint cannot be used to probe for datasources. Because visibility is itself
   grant-based, a caller with no unexpired grant stops here.
2. **Do they hold an unexpired grant?** If not, an empty rail. In practice step 1 has already
   answered 404 for such a caller; this is the fail-closed guard for the narrow race where a grant
   lapses between the two reads.
3. **Does the grant carry the capability the query type needs?** A read-only analyst is never shown
   a DDL suggestion they could not submit.
4. **Is every referenced table inside their allow-list?** `DatasourcePermissionChecker.rejectedTables`
   must come back empty. This is safe **only** because the aggregation guarantees
   `referenced_tables` is never empty — that method reports "nothing rejected" for an empty set, so
   a row whose tables could not be resolved would clear this check for everyone. The aggregation
   drops such rows rather than storing them; do not relax that guard.

A `QUERY_ADMIN` caller skips steps 2–4, mirroring the submission path: that permission already means
"submit against any datasource without a per-resource grant", so filtering their rail would hide
queries they can run today.

Two further properties are worth stating plainly.

**A suggestion inherits the literals of the query it was mined from.** A mined `WHERE email =
'…'` predicate carries a value someone typed. That is acceptable precisely because of step 4: the
caller is allow-listed for the tables involved and could author the same query themselves, so the
suggestion reveals nothing they could not already reach. It is not acceptable without that filter,
which is why the filter is in the service and not the UI.

**The corpus excludes what went around the approval path.** `EMERGENCY_ACCESS` (break-glass,
AF-385) is excluded because it bypassed review entirely — break-glass SQL is exactly the SQL that
must not be recommended onward. The corpus does *not* require a human decision: a routing policy's
`AUTO_APPROVE`, a `pre_approve_queries` grant, and a plan needing no human approval all reach
`APPROVED` and are all included, because each is the organisation's own configured judgement about
that shape. The line drawn is "went through the approval path", not "a person read it". `RECURRING` occurrences and recurring-series parents are excluded as
machine-generated. Rejected, timed-out, cancelled and still-pending queries never enter, because
only `APPROVED` and `EXECUTED` are read.

Suggestions are **advisory only**: nothing in the path touches routing policies, grant-covered
auto-approval, or any decision. A suggestion that is submitted is analysed, routed and reviewed like
any other query, and carries `submission_reason = HISTORY_SUGGESTION` in the audit trail. Submitter
identities are never returned — the API exposes counts.

### Just-in-time (JIT) time-bound access requests (AF-378, AF-567)

A user can self-request temporary, scoped access — to a datasource or an API connector (AF-567) — instead of an admin pre-granting it. The request flows through the **same reviewer-eligibility + multi-stage approval machinery** as query review, with these security invariants:

- **A requester can never approve their own request.** Enforced in `DefaultAccessReviewService.prepareDecision()` at the service layer (not just the UI) — `requesterId == reviewerId` raises `AccessDeniedException` (403), exactly as the query-review self-approval block does.
- **Eligibility is identical to query review.** The reviewer must be an approver at the request's current stage in the resource's review plan (the datasource's plan, or the connector's `review_plan_id`) *and* — for datasource requests — within the datasource's scoped-reviewer set (`datasource_reviewers`) when one is configured (reviewer scoping is a datasource-only concept). `REVIEWER`/`ADMIN` role is necessary but not sufficient.
- **Grants are time-boxed.** On final-stage approval the system writes a `datasource_user_permissions` or `api_connector_user_permissions` row with `expires_at = now + requested_duration` (bounded by `accessflow.access.min-duration` / `max-duration`). `AccessGrantExpiryJob` revokes it on expiry (`EXPIRED`); an admin may early-revoke (`REVOKED`). Once expired/revoked the permission row is gone, so the standard access checks return 403 — and the connector-side effective-permission resolver already excludes rows past `expires_at` even before deletion.
- **Pre-existing-permission policy.** A JIT grant **never silently deletes a standing (admin-granted, non-expiring) direct permission** — approval fails with `ACCESS_GRANT_ALREADY_EXISTS` (409) in that case. An existing *time-boxed* direct permission is revoked and replaced (extend/widen); group grants are never considered or touched. This preserves standing access as the source of truth while letting JIT grants stack predictably.
- **Privilege ceiling on connector requests (AF-567).** A connector access request can only convey `can_read`/`can_write` plus an operation allow-list validated against the connector's catalog — `can_break_glass` and response-field-restriction changes are never self-requestable, and the materialised grant always carries `can_break_glass = false`.

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

### Per-table row-limit policies (#934)

`row_limit_policy` rows cap how many rows a SELECT may return when it reads a given table, optionally
only for the listed roles, groups or users. They are a data-exfiltration guardrail at the table grain,
layered on the existing caps.

- **Only ever lowers the cap.** The effective limit is the minimum of the global
  `ACCESSFLOW_PROXY_EXECUTION_MAX_ROWS`, the datasource's `max_rows_per_query`, the grantee's merged
  `row_limit_override` (#933) and every matching policy. No policy can raise a limit, so a
  misconfigured policy can only make results smaller.
- **Most restrictive wins.** A query that joins several limited tables takes the lowest cap among the
  policies that both name one of its tables and apply to the submitter.
- **Lenient matching fails safe.** A policy on `crm.customer` also matches an unqualified `customer` in
  the SQL, a database-qualified `mydb.crm.customer` still matches it, names compare case-insensitively,
  and a schema-less policy matches the table in any schema. Over-matching can only tighten the cap, so
  neither leaving the schema off nor adding a database name gets a user more rows. This is the opposite choice
  from the table allow-list, where an exact match is the safe reading.
- **Unattributed queries fall back, not open.** When the parser cannot determine a query's tables, no
  policy matches and the datasource cap and grant override still apply — never "unlimited".
- **Same scope rules as row security.** `applies_to_*` empty ⇒ the policy applies to every submitter,
  admins included, keyed on the query submitter. Enforcement covers direct, scheduled, recurring,
  break-glass and grouped executions and the table preview (`/sample-rows`).
- **Audit.** The ids of the lowest-cap matching policies ride on the `QUERY_EXECUTED` metadata
  (`applied_row_limit_policy_ids`) when that cap is the binding one — at or below both the grant
  override and the datasource cap; grouped executions write only their group-level audit row.
  Create/update/delete emit
  `ROW_LIMIT_POLICY_CREATED/UPDATED/DELETED`.

### Policy simulator (AF-630)

A dry run of a **draft** routing / row-security / masking policy against the organization's own
historical traffic. Its security posture is defined by three deliberate choices.

- **Strictly read-only, and it never touches a customer database.** The simulator opens no
  connection, resolves no credentials, and executes nothing. Its only inputs are AccessFlow's own
  tables (`query_requests`, plus `query_request_results` for masking); row-security shapes are
  classified **statically** — relational dialects through the same `RowSecurityRewriter` that governs
  real execution, engine-managed ones through the plugin's offline
  `QueryEngine.classifyRowSecurity(...)` SPI. Nothing is written: the draft is a detached,
  never-persisted entity, and the result exists only in the HTTP response.
- **No new permission, no new privilege.** Each endpoint is gated by the permission that already
  governs the policy kind it simulates — `ROUTING_POLICY_MANAGE`, `ROW_SECURITY_MANAGE`,
  `MASKING_POLICY_MANAGE`. There is no umbrella endpoint: it would have to be gated by the union of
  the three, handing (say) a masking admin a routing preview they cannot otherwise obtain. Everything
  is organization-scoped, and a `datasource_id` outside the caller's org is `DATASOURCE_NOT_FOUND`.
- **A bounded, deliberate disclosure.** A holder of one of those permissions can already read the
  policy set; the simulation additionally shows them **aggregate counts over other people's
  historical queries** and a **per-user impact list**. That is inherent to the feature — "these four
  users would lose access to these tables" is the answer an admin is asking for — and it is the
  reason the response stops there. **Drill-down rows carry no SQL text**: those permissions do not
  otherwise grant read access to other people's queries, and a simulation must not become a side
  channel for them. Samples carry the query id only, so a caller who *also* holds `QUERY_VIEW_ALL`
  can follow the link and be authorized for the statement on that endpoint.

Two safety properties follow the fail-closed rule elsewhere in the proxy. An engine that cannot
classify offline (Cassandra / ScyllaDB without a live `CqlSession`, or an unresolvable plugin JAR)
returns `UNKNOWN`, which is counted as **unclassifiable** and is never reported as unaffected — an
admin must not read "we could not tell" as "nothing breaks". And the replay's unavoidable
approximations (memberships and the UBA signal read as of *now*, masking matched on bare column
names) are returned as explicit `caveats` rather than smoothed over, so the UI cannot imply a
precision the data does not have. Mechanism:
[docs/05-backend.md → Policy simulator](05-backend.md#policy-simulator-af-630).

### Access explainer (AF-859)

Two read-only admin endpoints — `POST /admin/access-simulations` (trace one hypothetical request
through the live evaluators) and `GET /admin/effective-access` (who could submit a statement class
against one table). Distinct from the policy simulator above: that one replays *historical traffic*
against a *draft policy*; this one replays *current policy* against a *hypothetical request*.

- **Read-only, and structurally so.** A simulation creates no `query_requests` row, opens no
  connection to a customer database, publishes no event, sends no notification, and makes no AI call.
  That is enforced by construction rather than by discipline: the simulation service is not wired to a
  persistence service, a state service, an event publisher, an AI analyzer or a notification
  dispatcher, and a test asserts its declared dependencies contain none of them. Row security is
  classified through the same offline `RowSecurityClassificationService` the policy simulator uses.
- **The AI verdict is an input, never a call.** `risk_level` / `risk_score` are supplied by the
  caller as the *hypothetical* verdict. Calling the provider would spend the organization's AI budget
  against the AF-55 guardrails and make the endpoint non-deterministic, so the simulator never does.
- **No new permission.** `access-simulations` requires `DATASOURCE_PERMISSION_MANAGE`.
  `effective-access` requires `DATASOURCE_PERMISSION_MANAGE` **or** `ACCESS_USAGE_REPORT_VIEW`, so
  auditors — who already read this class of data at `/admin/over-provisioned-access` (#625) — can use
  it read-only. Adding a `Permission` value would fan out to `SystemRolePermissions`, the `V114` seed
  rows and their parity test, and the frontend union, for no capability those two do not already
  describe. Both are organization-scoped; a `datasource_id` or `user_id` outside the caller's
  organization is `DATASOURCE_NOT_FOUND` / `USER_NOT_FOUND`, never `403`.
- **The org-wide view of the same two bypasses** is the privileged-access report (#968):
  `GET /admin/privileged-access` lists every identity whose row here would carry a
  `QUERY_ADMIN_BYPASS` or `BREAK_GLASS` source, across every datasource at once, under the same
  permission gate as `effective-access`.
- **The same two guarantees now cover the other two request kinds** (AF-967).
  `POST /admin/api-call-simulations` (`API_CONNECTOR_MANAGE`) and
  `POST /admin/deployment-simulations` (`DEPLOYMENT_PIPELINE_MANAGE`) trace a hypothetical API call
  and a hypothetical deployment through their own evaluators, under the permission that already
  governs each kind — again no new `Permission` value. Both are read-only by construction and assert
  it against their declared field types, and the API-call one additionally **never contacts the
  governed third-party API**: a simulator wired to an HTTP client would turn an explainer into an
  unaudited outbound request. Both write an `ACCESS_SIMULATION_RUN` row — against the connector and
  the pipeline respectively — and the API-call row deliberately carries no request path, headers or
  body, for the same reason the query row carries no SQL.
- **Simulating *as* another user reads their access and grants nothing.** The endpoints return no data
  from the customer database and confer no capability on the caller or the simulated user. What they
  do disclose is the organization's access topology — allow-lists, group provenance, row-security
  shapes, masking policies and reviewer sets — which is why an analyst or a reviewer cannot reach
  either one, and why both write an `ACCESS_SIMULATION_RUN` audit row naming the subject user,
  datasource and table, following the `AUDIT_LOG_EXPORTED` / `OVER_PROVISIONED_ACCESS_EXPORTED`
  precedent for sensitive reads. **Neither audit row carries the SQL**: `DATASOURCE_PERMISSION_MANAGE`
  does not otherwise grant read access to query text, and an audit row must not become a side channel.

The explainer's honesty rule is the same one the policy simulator follows. A hypothetical request
carries no client context and no cost estimate, so conditions keyed on those evaluate to `false`; a
policy that would fire on the real submission can therefore report as unmatched. Those approximations
are returned as explicit `caveats` (`CLIENT_CONTEXT_ABSENT`, `COST_ESTIMATE_ABSENT`) rather than
smoothed over. Mechanism:
[docs/05-backend.md → Access explainer](05-backend.md#access-explainer-af-859).

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

### External secret stores (AF-448)

High-security deployments can keep datasource credentials in an external secret manager instead
of the local AES layer. When a provider is enabled (`accessflow.secrets.*` — see
[docs/09-deployment.md → Secrets Manager](09-deployment.md)), the datasource credential fields
(`password`, each `read_replicas[].password`, `api_key`, `private_key_passphrase`) accept a **secret reference** that is stored
verbatim in the credential column and resolved through the store at credential-use time:

| Provider | Reference syntax | Resolution |
|----------|------------------|------------|
| HashiCorp Vault | `vault:<mount>/<path>#<field>` | KV v2 (default) reads `<mount>/data/<path>` and extracts `data.data.<field>`; KV v1 reads `<mount>/<path>`. Auth: static token, AppRole (renewed by spring-vault's session manager), or Kubernetes service-account JWT. |
| AWS Secrets Manager | `aws:<name-or-arn>[#jsonField]` | `GetSecretValue` on the `SecretId`; without `#jsonField` the whole `SecretString` is the value, with it the string is parsed as a JSON object. Credentials via the SDK default chain (env vars, IRSA, instance profile) or explicit static keys. |
| Azure Key Vault | `azure:<secret-name>` | Latest version of the named secret from the configured vault URL. Credentials via `DefaultAzureCredential` (workload/managed identity) or an explicit client-secret credential. |

Semantics and guarantees:

- **Detection is unambiguous** — AES-GCM ciphertext is Base64 and can never contain `:`, so a
  lowercase `vault:` / `aws:` / `azure:` prefix always means "reference". Anything else is
  encrypted locally exactly as before (the local AES layer remains the default and fallback).
- **Resolve-at-use, never cached.** References are resolved at JDBC pool init, native-engine
  client construction, test-connection, and schema introspection. The resolved plaintext follows
  the same drop-after-pool-init discipline as decryption (Security rule #4); AccessFlow never
  caches resolved secret values. Provider auth tokens are managed and refreshed by the SDKs.
- **Write-time validation.** Saving a datasource with a malformed reference returns
  `400 INVALID_SECRET_REFERENCE`; a reference to a provider that is not enabled returns
  `400 SECRET_PROVIDER_DISABLED`. Store failures at use time surface as
  `502 SECRET_RESOLUTION_FAILED`.
- **Every external resolve is audited** — `DATASOURCE_SECRET_RESOLVED` /
  `DATASOURCE_SECRET_RESOLUTION_FAILED` rows carry the provider and the reference (a store path,
  never the secret value).
- **Rotation caveat.** Rotating the secret in the external store does not restart live
  connection pools — the new value is picked up on the next pool creation (credential change,
  pool eviction, or restart). Re-save or test the datasource to force a refresh.

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
- **Group-based access (AF-530).** An admin may grant a **user group** access to a connector
  (`api_connector_group_permissions`); members inherit it. The effective permission a submitter is
  checked against is the most-permissive union of their direct grant and every unexpired group grant —
  `can_*` OR-ed, `allowed_operations` unioned, `restricted_response_fields` intersected — computed by
  `EffectiveApiConnectorPermissionResolver`, the one component every connector enforcement point routes
  through (submit-time permission check, response-field masking, text-to-API access, admin visibility).
- **Response-field masking.** A submitter's effective `restricted_response_fields` (dot-paths) are
  redacted from the JSON response recursively (including through arrays and nested objects) via the
  shared `ColumnMasker` (FULL strategy) before the response snapshot is persisted — the unmasked body is
  never stored. Mirrors the query masking model and is keyed on the submitter's effective grant.
- **Response-size cap.** The executor reads at most `max_response_bytes` and flags `truncated`,
  bounding memory and exfiltration blast radius.
- **A submitter can never approve their own API request.** Enforced in `DefaultApiReviewService`
  (`SelfApprovalNotAllowedException`, 403), exactly like the query-review / JIT / break-glass blocks.
- **Reviewers and admins may view any API request's detail + response snapshot** in their org —
  `DefaultApiRequestService.get`/`downloadResponse` allow the submitter, any `REVIEWER`, or any
  `ADMIN` (parity with the "View all query history" row); everyone else gets `404`. Viewing is not
  execution: `execute` stays submitter/admin and `cancel` stays submitter-only, so a reviewer can
  read a request from the review queue (`/reviews?tab=api`) without being able to run or cancel it.
- **Break-glass parity.** Emergency access requires a per-user/per-connector `can_break_glass` grant
  (required for everyone, including admins), executes immediately through all guards, writes a
  prominent `API_REQUEST_BREAK_GLASS_EXECUTED` audit row, and opens a mandatory retro-review.
- **Dynamic-variable secrets (AF-613).** A connector variable of kind `HMAC` stores its shared key in
  `api_connector_variables.secret_encrypted`, AES-256-GCM encrypted via `CredentialEncryptionService`
  under the same rules as `auth_credentials_encrypted`: `@JsonIgnore`, never returned by a GET (the
  view exposes only `has_secret`), never logged, decrypted only inside the resolver at call time.
- **Resolved values are never persisted or logged.** The resolver cannot distinguish a signature
  (harmless) from a `CONSTANT` holding a shared secret (not harmless), so every resolved value is
  treated as sensitive: none reach `api_requests`, the response snapshot, or the logs. They are also
  scrubbed out of any upstream failure message before it lands in the persisted, reviewer-visible
  `error_message` — the JDK's `IOException` embeds the full URI, which may carry a `query:`-targeted
  signature.
- **No scripting.** Variable evaluation is template substitution plus a fixed function set only — no
  expression language, no `eval`, no user-supplied code — deliberately mirroring the engine plugins'
  rejection of server-side scripting (`$where`, Painless, CQL UDFs).
- **Single-pass substitution.** A resolved value is never re-scanned for further placeholders. This
  is the containment property behind per-request overrides: an override of `{{signingKey}}` stays
  eleven literal characters and can never expand into that variable's value.
- **CRLF rejection.** No resolved value — computed or submitter-supplied — may contain CR, LF or NUL.
  Any of them landing in a header is request splitting, and an override on a `header:`-targeted
  variable is the natural delivery mechanism. Rejected both at submit time (immediate 422) and in the
  resolver.
- **Overrides are deny-by-default.** Supplying a per-request override needs `can_override_variables`
  on the connector grant, a capability distinct from submitting and never conferred by a JIT access
  grant. A secret-bearing variable can never be marked overridable — enforced by the admin service
  *and* a database CHECK constraint, so the rule survives a manual data fix. A name outside the
  connector's overridable set is rejected with a single uniform message regardless of whether the
  variable is unknown, not overridable, or secret-bearing, so the set cannot be enumerated. Overrides
  are persisted and shown to reviewers, so an approval covers exactly what will execute; the resolved
  *outputs* (nonce, signature) are never surfaced.
- **Accepted gap: config TOCTOU.** A reviewer approves, an admin then edits the connector's
  variables, and the scheduled run executes against the new config. The identical gap already exists
  for `default_headers`, masking policies and auth credentials, so it is documented rather than fixed
  here alone; `API_CONNECTOR_VARIABLE_UPDATED` audit rows make the change traceable.

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

## Deployment governance security (epic AF-682)

Deployments are the third governed request surface, and the one whose caller is a machine — see
[18-deployment-governance.md](18-deployment-governance.md) for the feature as a whole.

**Permissions.** Two, in the `DEPLOYMENT_GOVERNANCE`
group, seeded by `V151` (same `VARCHAR`-catalog convention as `V134`/`V146`/`V148`).
`DEPLOYMENT_PIPELINE_MANAGE` (held by `ADMIN`) gates pipeline / environment / freeze-window /
trigger-grant administration — since #688 it guards the whole `/api/v1/deployment-pipelines/**`
and `/api/v1/deployment-freeze-windows/**` admin surface, and since #691 also
`/api/v1/admin/deployment-routing-policies/**` (class-level `@PreAuthorize` on all three).
`DEPLOYMENT_REVIEW` (held by `ADMIN` and `REVIEWER`) is, since #691, an **active read-visibility
gate**: together with `QUERY_ADMIN` it decides who may read a deployment request they did not
submit, on both `GET /deployment-requests` and `GET /deployment-requests/{id}` (a caller with
neither sees only their own submissions, and an id they may not read returns `404`, never `403`).
Since #692 it also gates the deployment review surface (`/api/v1/deployment-reviews/**`,
per-method `@PreAuthorize` plus the same service-layer re-check the API-review path uses). The
review service enforces the **never-approve-your-own-request invariant** — the API key's owning
user is the submitter, and their self-decision is a `409 DEPLOYMENT_REQUEST_SELF_APPROVAL`
regardless of role — and honours review-plan approver rules opt-in (a plan with approver rules
restricts deciding to its stage-1 approvers, by user id or role name; `REVIEW_OVERRIDE` bypasses;
no plan/no rules stays open to any `DEPLOYMENT_REVIEW` holder). Review delegation (#622) does not
apply to deployments.

**Deployment break-glass (#692, AF-385 mirror).** `breakGlass: true` on the trigger requires the
effective per-pipeline **`can_break_glass`** grant **and** `allow_break_glass = true` on the
target environment — both, for everyone, with **no admin bypass** (unlike `can_trigger`, which
`QUERY_ADMIN` bypasses). It force-approves with no AI analysis, routing, or reviewer fan-out, and
bypasses freeze windows (`HOLD` and `REJECT`). Compensating controls: a prominent
`DEPLOYMENT_BREAK_GLASS_EXECUTED` audit row whose metadata records any bypassed freeze window,
and a mandatory retro-review row in `break_glass_events` (V153 adds `deployment_request_id` +
`pipeline_id`) written synchronously in the same transaction — acknowledged on the AF-385 admin
worklist by an admin who is never the submitter.

**Triggering a deployment is deliberately governed by no functional permission (#691).**
`POST /api/v1/deployment-requests` is the one authenticated `/api/v1` surface with no class-level
`@PreAuthorize`: authorization is the per-pipeline `can_trigger` grant resolved by
`EffectiveDeploymentPermissionResolver` (most-permissive union of the caller's direct grant and
every unexpired group grant), with `QUERY_ADMIN` holders bypassing it. CI runners reach it with an
**AccessFlow API key** (`X-API-Key` / `Authorization: ApiKey …`), which
`ApiKeyAuthenticationFilter` resolves into the same `JwtClaims` principal as the JWT path — so the
API-key user is the submitter for every downstream rule, including "a submitter can never approve
their own deployment". The grant is checked *before* the idempotent-replay lookup, so a caller
without one cannot use a repeated trigger to probe whether a given CI run exists.

**The deployment gate, confirm-execution and outcome endpoints (#693) share that model.**
`GET /api/v1/deployment-gate` and the two `POST /deployment-requests/{id}/…` mutations carry no
class-level `@PreAuthorize` either — authorization lives in the services. The gate read uses a
**visibility** predicate (submitter ∨ effective `can_trigger` ∨ `DEPLOYMENT_REVIEW` ∨
`QUERY_ADMIN`) whose failure is a **404, never a 403**: an under-permissioned poll reads exactly
like an unknown tuple, the CI wrappers treat any 404 as not-releasable, and the endpoint cannot be
used to probe which versions or requests exist — a request miss and a not-visible request share
one error code. (Pipeline and environment name resolution reports distinct 404 codes, matching
the trigger endpoint's behaviour — those names are org-internal configuration, not request
state.) The mutations use an **actor** rule (submitter ∨
`can_trigger` holder ∨ `QUERY_ADMIN`) with a 403 — acting on a request is a permission matter.
Releasability itself is fail-closed: one pure function whose default answer is not-releasable,
with any lookup/evaluation error answering `releasable: false`, and **any** active freeze window
(`HOLD` or `REJECT`) blocking release — except for break-glass requests, which already bypassed
the freeze at submission. The rollback follow-up worklist
(`/api/v1/deployment-rollback-reviews`) is JWT-side `PERM_DEPLOYMENT_REVIEW`, and the
deployment's submitter can never acknowledge their own rollback — the same "never the submitter"
rule as break-glass, enforced in the service.

---

## Schema change promotion security (#880)

Promoting a change set applies DDL to a real database, so `schemachange` keeps three guarantees of
its own rather than inheriting them from the request group it delegates to. The full narrative is
[20-schema-change-governance.md → Promotion](20-schema-change-governance.md#6-promotion-880).

- **`can_ddl` on the target datasource, for everyone.** `SCHEMA_CHANGE_MANAGE` lets a user author
  and promote; it never grants schema authority on a database. Every promotion additionally
  requires an active `can_ddl` grant for the **promoting user** on the environment's bound
  datasource, checked in `DefaultSchemaChangePromotionService` against
  `DatasourceUserPermissionLookupService.findFor` — a pure grant merge with **no admin
  exemption**. An organization admin without the grant is refused with
  `403 SCHEMA_CHANGE_PROMOTION_DDL_FORBIDDEN` before anything is written.
- **The group is then created as an admin, deliberately.** `requestgroups`' own per-member check
  returns early for `QUERY_ADMIN` holders — precisely the bypass this module must not inherit —
  and, for a statement classified `OTHER` (`GRANT`, `COMMENT ON`, `REFRESH MATERIALIZED VIEW`), it
  would demand `can_write`, a DML permission that says nothing about schema authority. So
  `schemachange` makes the authorization decision itself and applies it to every statement, which
  is strictly stronger than delegating would have been. The flag is not persisted and never
  reaches routing, review or execution. Do not "fix" it by passing `admin = false`.
- **The ladder and freeze windows fail closed.** A promotion to an environment is refused until
  every lower-ordered environment *that binds a datasource* records an `APPLIED` promotion of the
  same change set, and refused outright while any freeze window is in effect — `HOLD` as well as
  `REJECT`, since an unevaluable window degrades to `HOLD`. The gate also asserts that the
  pipeline's `sort_order` values are distinct: over an all-equal column "every lower-ordered
  environment" is the empty set and the check would pass vacuously.

Two properties of this path are worth stating plainly because they are not what a reader might
assume:

- **Approval strictness is a property of the target datasource, not the environment.** A group's
  review plan is resolved from the datasource alone, so a per-environment `required_approvals` or
  `review_plan_id` override is not honoured. To stop that from silently weakening a production
  rung, a promotion to an environment with `require_review = true` whose datasource has no plan
  requiring human approval is refused with
  `422 SCHEMA_CHANGE_PROMOTION_REVIEW_UNENFORCEABLE` rather than auto-approved.
- **Cancel attribution is split.** The group's own cancel is submitter-only, so it is invoked as
  the promoter; the `REQUEST_GROUP_CANCELLED` audit row therefore names the promoter while the
  `SCHEMA_CHANGE_PROMOTION_CANCELLED` row names the real caller and carries
  `cancelled_on_behalf_of_submitter: true`.

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

   **Writes hidden inside a query shape are refused, not reclassified.** JSqlParser models a statement by its outer shape, so `WITH d AS (DELETE FROM t RETURNING *) SELECT count(*) FROM d` is a `Select` and `SELECT * INTO new_t FROM t` is a plain select — classified `SELECT`, they would need only `can_read`, ride the auto-approve-reads paths, and execute on the `SELECT` path. `SqlParserServiceImpl` therefore walks every statement (`SqlStatementInspector`) and rejects with HTTP 422 (`error.sql_embedded_write_not_allowed`) any statement containing, at any depth: a data-modifying `WITH` item (`INSERT` / `UPDATE` / `DELETE … RETURNING`, also on an outer `INSERT` / `UPDATE` / `DELETE`), `SELECT … INTO <table>` (PostgreSQL, SQL Server `#temp`), an `INTO TEMP` table, or MySQL `INTO OUTFILE` / `INTO DUMPFILE` (a server-side file write when the pool account holds `FILE`). Rejection rather than reclassification keeps the permission model fail-closed: a statement that both reads and writes has no single capability that describes it, and the plain `INSERT` / `UPDATE` / `DELETE` / `CREATE TABLE … AS` it wraps can be submitted instead. The check sits in the one parser every relational path shares, so query submission, break-glass, request-group members, the access simulator, dry-run and `POST /sql-review/evaluate` all inherit it. If the walk itself fails on a `SELECT` / `INSERT` / `UPDATE` / `DELETE`, the statement is rejected (`error.sql_analysis_failed`, 422) rather than passed with an empty table set.

2. **PreparedStatement only** — The proxy engine uses `PreparedStatement` exclusively for everything that executes. No string concatenation or interpolation is used to build queries. Transactional batches use one `PreparedStatement` per inner statement, never `Statement.execute()` of a stacked string, and `QueryExecutor.dryRun` refuses a transactional envelope outright rather than planning one, so no stacked string reaches a dialect planner either.

   There is exactly one carve-out, and it never executes anything: **the SQL Server dry-run plan read** (`SqlServerDryRunPlanner`, AF-762). `mssql-jdbc` returns no SHOWPLAN rows at all over the prepared/RPC path, so the plan query is issued as a plain `Statement` language batch. Four properties bound it: the statement text is the caller's own SQL, already JSqlParser-validated and allow-list-checked; nothing is concatenated into it; the planner refuses to run when the row-security rewrite produced binds, so no value is ever interpolated; and `SET SHOWPLAN_ALL ON` means SQL Server plans the statement rather than running it — for DDL and `SET` as much as for DML. All four are verified against a real SQL Server 2022 in `DefaultQueryExecutorMssqlIntegrationTest`, which asserts that an `UPDATE`/`DELETE` dry-run leaves every row untouched and that a `CREATE TABLE` / `DROP TABLE` dry-run changes no schema.

3. **Schema allow-listing at AST level** — If `allowed_schemas` or `allowed_tables` is configured, the parsed SQL AST is walked by `SqlStatementInspector` (a `TablesNamesFinder` subclass) to extract referenced tables. It records tables itself instead of trusting the finder's final set, which drops every name that matches *any* derived-table, LATERAL or `WITH` alias in the statement (`… EXISTS (SELECT 1 FROM (SELECT 1) AS secret)` used to erase the real `secret`): a derived-table alias never hides a table, and a `WITH` name hides an unqualified table only inside the statement that declares it and only after its own body (every name of a `WITH RECURSIVE` list is visible throughout the list), so `WITH secret AS (SELECT * FROM secret) …` still reports `secret`. It also descends into positions the finder skips — window `PARTITION BY`, a function call's aggregate `ORDER BY` / `LIMIT` / `HAVING` / keyword arguments, array subscripts, `TOP`, and `XMLTABLE` / `JSON_TABLE` arguments in `FROM`. Row security (`RowSecurityRewriter`) uses the same walker, so both controls see the same table set. Identifiers are normalised (quotes stripped, ASCII-lowercased) before comparison; the union across `BEGIN; …; COMMIT;` envelopes is enforced as a single set. Violations are rejected (HTTP 403) without touching the database. **Known limit:** functions that take SQL *text* and run it server-side — PostgreSQL `query_to_xml('select … from t', …)`, `dblink(…)`, SQL Server `OPENQUERY(…)` / `OPENROWSET(…)` — are opaque to an AST walk, so the tables they read are not in the set. Block them with the deterministic SQL review `disallowed_function` rule (or revoke `EXECUTE` on them for the pool account); on PostgreSQL the read-only session in (7) stops their write variants on the `SELECT` path. See [docs/05-backend.md → "Schema / table allow-list enforcement"](05-backend.md#schema--table-allow-list-enforcement) for the full match algorithm.

4. **DDL blocked by default** — `can_ddl=false` (the default) prevents CREATE/ALTER/DROP from being executed even if submitted by an ANALYST or REVIEWER.

5. **Row cap enforcement** — `max_rows_per_query` is enforced via JDBC `setMaxRows()`, not by appending LIMIT to the query string. The executor reads one extra row beyond the cap to set a `truncated=true` flag on the result, then discards it.

6. **Statement timeout** — `accessflow.proxy.execution.statement-timeout` (default 30s) is applied via `PreparedStatement.setQueryTimeout()`. Driver-level cancellation paths (PostgreSQL SQLState `57014`, MySQL `HY008`/`70100`) are mapped to `QueryExecutionTimeoutException` → HTTP 504, distinct from generic execution failures (HTTP 422).

7. **Read-only connection for SELECT** — `Connection.setReadOnly(true)` is set for `SELECT` queries before execution, as a backstop if a misclassified statement ever reaches the executor (for example a `SELECT` approved before the check in (1) existed). The proxy runs with `autoCommit=true`, and pgjdbc's default `readOnlyMode=transaction` **ignores** `setReadOnly(true)` under autocommit, so every PostgreSQL pool (primary and read replicas, bundled or uploaded driver) is built with the driver property `readOnlyMode=always`: the session is then genuinely read-only and a write on the `SELECT` path fails with SQLSTATE `25006`. Side effects of that: on the `SELECT` path, PostgreSQL also refuses `SELECT … FOR UPDATE / FOR SHARE` and functions that write (`nextval`, `setval`, user functions that modify data). A `jdbc_url_override` that sets its own `readOnlyMode` query parameter wins over the pool property — do not set one. `DefaultQueryExecutorPostgresIntegrationTest` pins the behaviour against a real PostgreSQL. pgjdbc applies it with a separate `SET SESSION CHARACTERISTICS` round trip, so **behind a transaction-mode pooler (PgBouncer `pool_mode=transaction`, RDS Proxy) it is not guaranteed** — the next statement may run on a different server connection; point AccessFlow at the database directly or at a session-mode pool if you rely on this layer. On other engines the read-only flag is only as strong as the driver makes it (MySQL Connector/J propagates it to the server session by default; other drivers may treat it as a hint), so there the parser checks in (1) and (3) are the control.

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
- **Request provenance is inside the chain (#874).** `audit.api.AuditMetadataContributor` beans are merged into `metadata` *before* the row is serialised and hashed, so a contributed key is exactly as tamper-evident as an explicit one, and adding keys this way is hash-stable for every historical row (a new column would not be: `AuditChainHasher` canonicalises a fixed ten-field list). Explicit metadata wins on a collision, so `trigger=` is never clobbered; a throwing contributor never loses the row. The `serviceaccounts` contributor stamps every row written on an API-key request with `api_key_id`, `service_account` and, when the request named a human, `on_behalf_of_user_id`. A synchronous null-actor row written inside such a request (e.g. `SQL_REVIEW_BLOCKED`) carries the same keys — they describe the request the system action ran in, not an actor claim. Rows written off the request thread get nothing from the contributor; the query-lifecycle rows carry `on_behalf_of_user_id` explicitly from the request row.
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
| Customer DB credentials | Stored encrypted in DB; never in env vars. Optionally a secret **reference** resolved from HashiCorp Vault / AWS Secrets Manager / Azure Key Vault at credential-use time (AF-448 — see "External secret stores" above) |
| `ACCESSFLOW_SECRETS_VAULT_TOKEN` / `_APP_ROLE_SECRET_ID`, `ACCESSFLOW_SECRETS_AWS_SECRET_ACCESS_KEY`, `ACCESSFLOW_SECRETS_AZURE_CLIENT_SECRET` | Environment variable / Kubernetes Secret (only when the respective secret-store provider is enabled with explicit credentials; cloud-native identity needs none) |
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
