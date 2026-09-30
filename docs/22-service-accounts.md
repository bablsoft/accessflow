# 22 — Service Accounts (epic #867)

A CI pipeline, a Terraform run, a scheduled script, or an AI agent needs to call AccessFlow without
a person at the keyboard. Before epic #867 the only option was to lend it a human's API key, or to
declare a key through the `bootstrap` module on an otherwise ordinary user. A **service account**
gives the machine an identity of its own, with its own role, keys, limits, and audit trail. A human
can never sign in as it, and it can never stand in for one when a decision is made.

This chapter is the feature reference. It lives in the `serviceaccounts` Spring Modulith module
(`com.bablsoft.accessflow.serviceaccounts`). The `mcp` module and the bootstrap reconciler depend on
`serviceaccounts.api`. `security` never does: it reads `principalType` off `core.api.UserView`.

> **Delivery status.** Complete on `main` for the v2.7 milestone:
> - the `principal_type` discriminator and the `service_accounts` detail table (#868)
> - the interactive sign-in block and the API-key id on the authentication (#869)
> - the admin service and endpoints (#871)
> - the MCP tool allow-list (#872)
> - the per-identity rate limit (#873)
> - on-behalf-of attribution (#874)
> - the admin UI (#875)
> - this chapter (#876)

> **The one sentence to remember.** A service account's API key is its **only** credential. The
> key carries **exactly** the account's own permissions, however it is used: the tool allow-list
> can only narrow them, and the on-behalf-of header never widens them.

Related chapters:
- [03-data-model.md](03-data-model.md): `users.principal_type`, `service_accounts`,
  `service_account_delegated_principals`, and the `on_behalf_of_user_id` columns
- [04-api-spec.md → Service Accounts](04-api-spec.md#service-accounts-adminservice-accounts-service_account_manage-871)
- [05-backend.md → Service accounts](05-backend.md)
- [07-security.md](07-security.md)
- [13-mcp.md](13-mcp.md)
- [16-iac.md](16-iac.md)

---

## 1. What a service account is

A service account is two rows:

- A `users` row with `principal_type = SERVICE_ACCOUNT`. It has an organization, a role, an
  `active` flag, and an unusable password hash, exactly like a person.
- A 1:1 `service_accounts` detail row (primary key `user_id`). It holds:
  - `managed_by` (`UI` / `BOOTSTRAP`)
  - an optional owner (the accountable human) and a description
  - the MCP tool allow-list
  - per-account rate limits

Because it *is* a user, every existing permission check, datasource grant, pipeline trigger grant,
and audit row works for it unchanged. There is no special `AGENT` role, and there never will be.

Only one place may turn a user into a service account:
`ServiceAccountProvisioningService.ensureRegistered`
(`backend/src/main/java/com/bablsoft/accessflow/serviceaccounts/internal/DefaultServiceAccountProvisioningService.java:25`).
It flips the discriminator through `core.api.UserAdminService.setPrincipalType` (line 29) and upserts
the detail row in the same transaction. An ArchUnit test, `PrincipalTypeChokepointTest`, fails the
build on any other caller.

**How it differs from a human user:**

| | Human | Service account |
|---|---|---|
| Password / SAML / OAuth sign-in | yes | **no**: `401 SERVICE_ACCOUNT_SIGN_IN_BLOCKED` on password login / refresh; SSO redirects back with that error code |
| Refresh token / session | yes | no |
| Password reset | yes | silently skipped (`DefaultPasswordResetService.java:129`) |
| Credential | password or IdP, plus optional personal API keys | API keys only, issued by an admin or declared in bootstrap |
| Can review / approve | per role | per role, but never with an on-behalf-of header ([§7](#7-on-behalf-of-attribution)) |
| Can be a review delegate | yes | no: `DefaultReviewDelegationService` refuses a non-`HUMAN` delegate (`core/internal/DefaultReviewDelegationService.java:178`) |
| Listed on `/admin/users` | yes | badged *Service account*, and its row opens `/admin/service-accounts/{id}` |
| Visible to SCIM (`/scim/v2`) | yes | **no**: see below |
| In-app notification inbox | yes | **no** rows are written; channel delivery (email, Slack, webhooks) is unchanged |

**SCIM never sees a service account.** An IdP push that could see agents would overwrite or
deactivate them. Deactivating one disables all of its API keys at once, so every pipeline and agent
using it stops, and it also revokes its JIT grants. So `core.api.ExternalUserDirectoryService`, the
one entry point the `scim` module uses, returns only `HUMAN` rows:

- `GET /scim/v2/Users` and its filters omit service accounts, and `totalResults` counts humans only.
- `GET`, `PUT`, `PATCH` and `DELETE` on a service account's id return `404`, and nothing changes.
- A SCIM group write never attaches a service account. `members` entries naming one are skipped,
  both in the orchestrator and in `DefaultUserGroupService` for `source=SCIM`. A `source=SCIM`
  membership an agent picked up before #867 is released on the next member replace. An admin's
  `MANUAL` membership survives, but the IdP's view of the group omits it.
- A SCIM create whose email belongs to a service account still gets `409 uniqueness`, because
  emails are globally unique. The service account is left untouched.

**No inbox for agents.** Every in-app writer (the notification dispatcher and the access-request
listener alike) goes through `UserNotificationService.recordForUsers`. That method drops
`SERVICE_ACCOUNT` recipients, so an agent never gets an inbox row or the WebSocket push that follows
one. Nobody reads an agent's inbox. Channel notifications still reach an agent that is an eligible
reviewer.

The sign-in block is enforced in several places:
- `LocalAuthenticationService` checks it **before** the password
  (`backend/src/main/java/com/bablsoft/accessflow/security/internal/LocalAuthenticationService.java:123`).
  The same check covers refresh and `issueForUser`.
- The SAML and OAuth2 success handlers redirect with the same code
  (`security/internal/saml/SamlLoginSuccessHandler.java:90`,
  `security/internal/oauth2/OAuth2LoginSuccessHandler.java:129`). They do this before group sync,
  exchange-code issue, or audit, so an IdP account matching a service account's email can never
  obtain a session.

---

## 2. Creating one

**UI:** go to **Security & Access → Identity → Service accounts** (`/admin/service-accounts`) and
select **Create service account**. The form asks for:
- an email (an identifier only, never mailed)
- a display name
- a role
- an optional owner and description
- optional per-minute and per-day request limits

On save, the account's settings page opens with seven tabs: *Overview*, *API keys*, *MCP tools*,
*Limits*, *Attributes*, *On-behalf-of principals*, and *Activity*.

The *Attributes* tab edits the account's row-security attributes — the values a row-security
predicate reads as `:user.<key>` (at most 50; key ≤ 128, value ≤ 512 characters). Saving replaces
the whole set. On the API they are the `attributes` field of `PUT
/api/v1/admin/service-accounts/{id}` (`{}` removes them all), and `GET` on the same path returns
them. This is their only write path: the generic users API refuses service accounts (`409
USER_IS_SERVICE_ACCOUNT`, #1130).

**API:** `POST /api/v1/admin/service-accounts` (201). The full contract, including update, key,
and delegation endpoints, is in
[04-api-spec.md → Service Accounts](04-api-spec.md#service-accounts-adminservice-accounts-service_account_manage-871).

Every endpoint needs the `SERVICE_ACCOUNT_MANAGE` permission (group *Users*, seeded to `ADMIN` only
by V174). The delegated-principal endpoints also accept `USER_MANAGE`.

Deactivating an account (`DELETE /api/v1/admin/service-accounts/{id}`, 204, or **Deactivate** in
the UI) stops every one of its keys from authenticating at once. No key is revoked, so reactivating
the account restores them.

---

## 3. Choosing a role — keep it narrow

A service account's key can do whatever its role and grants allow, unattended, from a runner you do
not watch. Give it the smallest role that works.

- **UI / API default: `READONLY`**
  (`DefaultServiceAccountAdminService.java:65`). The create form preselects it and warns when you
  pick a role that can review.
- **Declarative (bootstrap) default: `ADMIN`.** `ServiceAccountReconciler`
  (`backend/src/main/java/com/bablsoft/accessflow/bootstrap/internal/reconcile/ServiceAccountReconciler.java:84`)
  falls back to `ADMIN` when a `bootstrap.serviceAccounts[]` entry omits `role`. This predates
  service accounts and is a **poor default**. **Always set `role` explicitly** in a bootstrap spec.
  The spec owns `role` and `displayName`: the reconciler applies them when it creates the user, and
  whenever the spec's fingerprint changes it re-applies whichever of the two differs on the
  existing account through `UserAdminService.updateUser` (`reapplyDeclaredFields`,
  `ServiceAccountReconciler.java:167-195`), audited as the account's `BOOTSTRAP` upsert with
  `changed_fields`. To narrow an account that already came up as `ADMIN`, change `role` in the spec
  and restart. The service-accounts UI/API refuses the same edit on a `BOOTSTRAP` account (409),
  because the spec would own it anyway. The spec can only name a **system** role: a custom role the
  account already holds (one given before #1130 through the generic users API, or to a UI-created
  account the spec later adopts) is kept on every later reconcile (logged at WARN), never reset to the
  declared role, so a key rotation cannot widen it. The generic users API cannot change it either —
  `PUT` and `DELETE /api/v1/admin/users/{id}` refuse a service account with `409
  USER_IS_SERVICE_ACCOUNT` (#1130).
  Before upgrading, check that each declared `role` matches the role the account actually has — a
  system role changed outside the spec is reset on the next spec change.

| Machine | Suggested role | Plus |
|---|---|---|
| CI deployment gate (trigger, poll, confirm, report outcome) | `READONLY` | a `can_trigger` grant on the pipeline. The trigger endpoint has no role check of its own ([18-deployment-governance.md](18-deployment-governance.md)) |
| CI query runner (`run-query` action) | `READONLY` or `ANALYST` | per-datasource permissions for exactly the datasources it touches |
| Terraform / OpenTofu | a **custom role** holding only the `*_MANAGE` permissions for the resources you manage (`ADMIN` only if Terraform owns everything) | — |
| MCP agent that reads and submits queries | `READONLY` or `ANALYST` | a tool allow-list ([§5](#5-mcp-tool-allow-list)) and datasource permissions |

A service account that holds a reviewer role can still review in principle. It can never review
with an on-behalf-of header, and it can never approve its own submission. Prefer not to give an
agent `REVIEWER` at all. Two warnings keep this from happening by accident. The service-account
form warns when you pick a review-capable role. The review-plan editor warns on every role-based
approver rule whose role is currently held by at least one active service account, and names the
count. That count comes from `GET /review-plans/approver-role-service-accounts` (#1131).

---

## 4. API keys: issue, rotate, revoke

Keys are `af_`-prefixed tokens. Only their SHA-256 hash is stored. A request authenticates with
`X-API-Key: af_…` or `Authorization: ApiKey af_…`, on `/api/v1/**` and `/mcp/**` alike.

- **Issue.** On the account's *API keys* tab, select **Issue key**, or call
  `POST /admin/service-accounts/{id}/api-keys`. Give it a name, an optional expiry, and an optional
  application name (stamped on audit rows). The plaintext is shown **once**, in a copy dialog, and
  can never be read again.
- **Rotate.** Select **Rotate**, or call `POST …/api-keys/{keyId}/rotate`. This issues a
  replacement **and leaves the old key working until a grace window ends**
  (`DefaultServiceAccountAdminService.java:207-220`). A running agent or a queued pipeline is
  therefore never cut off mid-flight.
  - The window is the per-request `grace_period` (ISO-8601, e.g. `PT12H`), or
    `accessflow.serviceaccounts.rotation-grace` / `ACCESSFLOW_SERVICEACCOUNTS_ROTATION_GRACE`
    (default `PT24H`, [09-deployment.md](09-deployment.md)).
  - Rotation is implemented as issue plus `ApiKeyService.expireAt`. It never revokes the old key
    instantly.
  - The replacement must have a different name. The UI prefills `<name>-YYYY-MM-DD`.
- **Revoke.** Select **Revoke**, or call `DELETE …/api-keys/{keyId}` (204, idempotent). The key
  stops authenticating on the next request. Use revoke for a leaked key and rotation for a planned
  change.
- **Expiry.** A key past its `expires_at` fails exactly like a revoked one. It is checked on every
  resolve, so no job has to run for it to take effect.

Every issue, rotate, and revoke is audited as a `SERVICE_ACCOUNT_*` action against resource
`service_account`. A raw key never appears in any audit row.

---

## 5. MCP tool allow-list

`service_accounts.mcp_tool_allow_list` (`TEXT[]`) limits which of the twelve MCP tools
(`backend/src/main/java/com/bablsoft/accessflow/serviceaccounts/api/McpToolName.java:14-25`) the
account's keys may call:

| Value | Meaning |
|---|---|
| `NULL` (UI: *Every tool*) | every tool, subject to the account's own permissions |
| `{}` (UI: *Only the selected tools* with none ticked) | **no** tool: every call is denied |
| a list | only the listed tools |

The tools are `list_datasources`, `get_datasource_schema`, `list_my_queries`, `get_query_status`,
`get_query_result`, `submit_query`, `cancel_query`, `list_pending_reviews`, `review_query`,
`validate_sql`, `get_column_samples`, and `get_audit_log`.

**Enforcement.**
- `mcp.internal.tools.GuardedToolCallback` wraps every tool callback and consults
  `ServiceAccountToolPolicyService.isAllowed` on every `tools/call`
  (`backend/src/main/java/com/bablsoft/accessflow/mcp/internal/tools/GuardedToolCallback.java:83`).
  A denied call returns the documented `{"code":"permission_denied"}` result.
- An unknown tool name is denied before any lookup
  (`DefaultServiceAccountToolPolicyService.java:28`).
- A human's personal key has no detail row, so it is always allowed.

> **`tools/list` still advertises every tool.** The Spring AI stateless list handler ignores the
> transport context, so AccessFlow cannot filter the catalog per caller. An agent restricted to
> `validate_sql` still *sees* `submit_query`. It just cannot *call* it. Treat the allow-list as an
> enforcement boundary, never as a discovery filter, and tell agent authors so. The *MCP tools* tab
> says the same.

The allow-list only narrows. It never grants a tool the account's role could not already use.

---

## 6. Rate limits

Every API-key request is metered per identity, in two fixed windows checked minute first, then day.
This covers `/api/v1/**` and `/mcp/**`, but never a JWT browser session.

- **Per account:** `service_accounts.rate_limit_per_minute` / `rate_limit_per_day`, set on the
  *Limits* tab or through `PUT /admin/service-accounts/{id}`.
- **Deployment default** for everything else, including a **human's personal key**:
  `ACCESSFLOW_SERVICEACCOUNTS_RATE_LIMIT_REQUESTS_PER_MINUTE` (default `120`) and
  `…_REQUESTS_PER_DAY` (default `0`, meaning off). A `NULL` on the row means "use the deployment
  default". See [09-deployment.md](09-deployment.md).

Over the limit, the request gets `429 SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED` with a real
`Retry-After` header plus `limit` / `retryAfterSeconds` in the problem body. The response is
written by `serviceaccounts.internal.web.ApiKeyRequestFilter`, which is registered at filter order 0,
inside the Spring Security chain.

Every AccessFlow CI wrapper treats 429 as retryable and honours `Retry-After`, within its own
timeout:
- the four GitHub Actions
- the GitLab hidden jobs
- the Azure step template
- the Terraform provider's HTTP client (five attempts, each wait capped at two minutes)

> **The limiter fails open.** If Redis (or the database holding the per-account limits) is
> unreachable, the request is **allowed** and a throttled
> `WARN` is logged (`DefaultServiceAccountRateLimiter.java:72-75`, rationale at `:25-30`). This is
> deliberate. A rate limit is a resource guardrail, not an authorization decision. Failing closed
> would halt every agent and every CI pipeline on a Redis blip, while authentication and permission
> checks are unaffected either way. Do not "fix" it.

---

## 7. On-behalf-of attribution

An agent usually works *for* someone: a chat-ops bot submitting the query Alice asked for, or a
pipeline triggered by Bob's merge. An API-key request may name that human:

```
X-API-Key: af_…
X-AccessFlow-On-Behalf-Of: alice@example.com      # or her user uuid
```

**Delegation (consent) model.** The header resolves only if the named human has a live row in
`service_account_delegated_principals` for **this** service account
(`DefaultOnBehalfOfResolver.java:28-41`). The named human must also be an active `HUMAN` in the same
organization. Consent is granted in one of two ways:
- by the human themselves: `POST /api/v1/me/service-account-delegations`. There is no self-service
  screen yet, so this is API only.
- by an admin holding `SERVICE_ACCOUNT_MANAGE` or `USER_MANAGE`: the *On-behalf-of principals* tab,
  or `/admin/service-accounts/{id}/delegated-principals`.

Grants can expire and are soft-revoked. There is one live row per pair. Only a typed service account
can hold grants, so a human's personal key naming someone always fails.

A miss is **never silently dropped**. Any of these returns one opaque
`403 ON_BEHALF_OF_NOT_PERMITTED`:
- an unknown name
- a foreign organization
- a service account named as the principal
- no consent
- a JWT session sending the header

**It can never widen permissions.**
- The resolved human is parked in a request attribute
  (`ApiKeyRequestFilter.java:116`), **never** on the `Authentication`.
- The agent's JWT claims and permission set stay exactly its own.
- The human's roles, datasource grants, and pipeline grants are never consulted for authorization.

What the header *does* do:
- **Stamps provenance.** `on_behalf_of_user_id` is written on `query_requests`, `api_requests`,
  `deployment_requests` (and `deployment_rollback_reviews`), `request_groups`, and
  `break_glass_events`. Every audit row
  of the request gets `on_behalf_of_user_id`, `api_key_id`, and `service_account` through
  `ServiceAccountProvenanceContributor`
  (`backend/src/main/java/com/bablsoft/accessflow/serviceaccounts/internal/ServiceAccountProvenanceContributor.java:30-51`).
- **Narrows who may approve.** The named human becomes a **second submitter identity** for the
  self-approval ban (`isSubmitterIdentity`, e.g.
  `backend/src/main/java/com/bablsoft/accessflow/workflow/internal/DefaultReviewService.java:289`).
  Alice cannot approve what the agent submitted for her, and she cannot acknowledge the
  retro-review of a break-glass run the agent performed for her (`break_glass_events.on_behalf_of_user_id`,
  #1129). This closes the approval-laundering hole where a person could submit through a bot and
  then approve the bot's request.
- **Is refused on every review / decision path.** Query, API, deployment, and rollback reviews,
  group approve/reject, erasure reviews, access requests, and break-glass acknowledgement are listed
  in `OnBehalfOfDecisionPaths.java:21-29`. They return `403 ON_BEHALF_OF_REVIEW_FORBIDDEN`, and the
  MCP `review_query` tool is refused whenever a principal is present. An agent can never cast a
  decision "as" a human.

---

## 8. Declarative vs UI ownership

A service account is created in one of two places, and `service_accounts.managed_by` records which:

| | `UI` | `BOOTSTRAP` |
|---|---|---|
| Created by | an admin (UI or API) | `bootstrap.serviceAccounts[]` / `ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_<n>_*` on startup |
| Display name, role | editable | **declared by the spec**: applied at creation and re-applied to the existing account whenever the spec changes, except that a custom role assigned outside the spec is kept ([§3](#3-choosing-a-role--keep-it-narrow)); changing either from the service-accounts UI/API is `409 SERVICE_ACCOUNT_BOOTSTRAP_MANAGED` (an unchanged value is a no-op) |
| Owner, description, active, tool allow-list, rate limits, row-security attributes, delegations | editable | editable. The reconciler never touches them, so a UI edit survives every restart |
| Extra keys issued in the UI | rotate / revoke freely | rotate / revoke freely |
| The **declared** key (`api_keys.bootstrap_declared`) | — | **cannot be revoked or rotated** from any surface |

The settings page shows a *Managed by the bootstrap configuration* banner for bootstrap accounts
and disables the declared fields.

**The bootstrap-key re-import trap.** The reconciler re-imports the declared key through
`ApiKeyService.importOrUpdate` whenever the spec's fingerprint changes. That import **clears
`revoked_at` and re-asserts `expires_at`**
(`backend/src/main/java/com/bablsoft/accessflow/security/internal/apikey/DefaultApiKeyService.java:74-78`).
An admin revoke would therefore look like it worked, and then be silently undone on the next
changed reconcile. To prevent that:
- `DefaultApiKeyService.revoke` (`:108-110`) and `expireAt` (`:123-125`) refuse a declared key.
- The admin surface answers `409`.
- The UI disables *Rotate* / *Revoke* on the declared row, with a tooltip saying why.

The remediation lives at the source:
- **Rotate a declared key:** put the new `af_…` value in the Secret and restart. The reconciler
  upserts it in place.
- **Retire a declared key:** remove the account from the spec, or rename its `apiKeyName`. At most
  one key per account is declared, and a rename demotes the old row to an ordinary key you can then
  revoke. Alternatively, deactivate the account.
- **Emergency:** deactivate the account in the UI. Deactivation is honoured immediately and the
  reconciler does not re-activate it.

Rule of thumb: declare in bootstrap what must exist before anyone can sign in, such as the
Terraform identity that provisions everything else. Create everything else in the UI, where keys
can be rotated with grace and revoked on the spot.

---

## 9. Known limitations

- There is no self-service screen for a human to consent to being named. Use
  `/api/v1/me/service-account-delegations`, or ask an admin to grant it on the account.
- `tools/list` is not filtered ([§5](#5-mcp-tool-allow-list)).

---

## 10. Runbook: a service account for a CI pipeline

1. **Create it.** Go to **Service accounts → Create service account**, e.g. `ci-deploy@acme.com`,
   *CI deploy bot*, role `READONLY`, owner = the team lead.
2. **Grant what it needs.** Grant `can_trigger` on the deployment pipeline (pipeline →
   *Permissions*). Grant `can_break_glass` only if emergency deploys from CI are intended. For query
   runners, add per-datasource permissions.
3. **Issue a key.** On the *API keys* tab, select **Issue key** and name it after the consumer, e.g.
   `github-actions`. Copy the `af_…` value from the one-time dialog into the CI secret store (e.g.
   `ACCESSFLOW_API_KEY`).
4. **Optionally cap it.** On the *Limits* tab, set per-minute / per-day caps sized to the pipeline.
   The wrappers already retry a 429.
5. **Optionally attribute runs.** Grant the humans who trigger deploys on the *On-behalf-of
   principals* tab, and send `X-AccessFlow-On-Behalf-Of` from the pipeline. They then cannot approve
   their own releases.
6. **Rotate on a schedule.** Select **Rotate** with a grace window longer than your longest queued
   run, update the CI secret within the window, and let the old key lapse.

**Declarative alternative** (GitOps, or before any admin exists):

```yaml
bootstrap:
  serviceAccounts:
    - email: ci-deploy@acme.com
      displayName: CI deploy bot
      role: READONLY              # always set it — the default is ADMIN; a later change is re-applied on restart
      apiKeyName: ci
      apiKeySecretRef: { name: af-secrets, key: ci-api-key }
```

See [16-iac.md](16-iac.md) and `charts/accessflow/examples/values-bootstrap-service-account.yaml`.
Mind the re-import trap in [§8](#8-declarative-vs-ui-ownership).
