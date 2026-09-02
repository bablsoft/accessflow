# 18 — Deployment Approval Governance (epic AF-682)

AccessFlow governs **database** access at query time and **outbound API calls** through
[API Access Governance](17-api-governance.md). **Deployment Approval Governance** adds a third
governed surface: **CI/CD deployments**. A pipeline asks AccessFlow for permission to release,
the request flows through the same submit → AI risk → review → approve machinery as a query, and
the job blocks on a **deployment gate** until every required approval is granted.

It lives in the `deploygov` Spring Modulith module (`com.bablsoft.accessflow.deploygov`) and, like
`apigov` before it, deliberately **reuses existing primitives** — the `query_status` state enum,
`review_plans` and `ReviewPlanLookupService`, the routing-policy shape, `api_keys` + the API-key
authentication filter, the break-glass retro-review in `break_glass_events`, the notification
fan-out, and the tamper-evident audit log — rather than reinventing them. The only genuinely new
concepts are the **pipeline / environment** hierarchy, **freeze windows**, and the **gate**.

> **Delivery status.** Complete and shipped in v2.4: pipeline / environment / freeze-window /
> trigger-grant administration (#688), the CI trigger API with AI release-risk analysis, routing
> and the enforced state machine (#691), the reviewer decision endpoints with break-glass,
> scheduled deploys and the review-timeout job (#692), the fail-closed gate with execution
> confirmation, outcome reporting and rollback follow-up reviews (#693), the GitHub / GitLab /
> Azure / generic-curl CI wrappers (#694), the notification and audit fan-out (#695), and the web
> UI (#696). The multi-environment version-tracking foundation — environment tags and the
> per-environment deployed-version projection (#741) — landed after v2.4, followed by the
> read-only version inventory & drift API (#742, section 9 below); the version-matrix UI (#743)
> is **not yet shipped**.

---

## Module layout

```
com.bablsoft.accessflow.deploygov/
├── api/         # PipelineProvider, FreezeBehavior, DeploymentOutcome, DeploymentRoutingAction,
│                # DeploymentRollbackReviewStatus, eleven service interfaces, view + command records,
│                # the exception hierarchy (JDK + project types only)
├── events/      # DeploymentSubmitted/AnalysisCompleted/AnalysisSkipped/AnalysisFailed/
│                # StatusChanged/Decided/BreakGlassExecuted/OutcomeReported/ReleasableEvent
└── internal/
    ├── Default*Service (one per api/ interface), DeploymentRequestStateService,
    │   DeploymentReviewStateMachine, EffectiveDeploymentPermissionResolver,
    │   FreezeWindowEvaluator, DeploymentAnalysisListener, DeploygovAuditWriter,
    │   DeploymentVersionTrackerService (module-private, #741)
    ├── persistence/{entity,repo}/   # DeploymentPipelineEntity, DeploymentEnvironmentEntity,
    │                                # DeploymentFreezeWindowEntity, DeploymentRequestEntity,
    │                                # DeploymentReviewDecisionEntity, DeploymentRollbackReviewEntity,
    │                                # DeploymentRoutingPolicyEntity,
    │                                # DeploymentEnvironmentVersionEntity (#741),
    │                                # the two grant entities + repos
    ├── routing/     # DeploymentRoutingPolicyEngine
    ├── scheduled/   # DeploymentTimeoutJob, ScheduledDeploymentReleaseJob
    └── web/         # eight controllers, request/response records,
                     # DeploygovExceptionHandler, SpringPageableAdapter
```

Cross-module references (`organization_id`, `review_plan_id`, `ai_config_id`, `submitted_by`,
`user_id`, `created_by`) are **bare UUID columns**, not JPA relationships — matching `apigov` — so a
deploygov row survives the deletion of the aggregate it names. The one exception is
`deployment_pipeline_group_permissions`, which keeps the real foreign keys of the V111 group-grant
template it was cloned from. The `deploygov.api` package imports only JDK + project types, enforced
by `ApiPackageDependencyTest`.

Schema and column detail live in
[03-data-model.md → Deployment governance](03-data-model.md); the endpoint reference is
[04-api-spec.md → Deployment Governance](04-api-spec.md); the service-by-service walkthrough is
[05-backend.md → Deployment Governance](05-backend.md).

---

## 1. Pipelines, environments & trigger grants

A **deployment pipeline** is the governed unit: a name unique within the organization, a
`provider` (`GITHUB_ACTIONS`, `GITLAB_CI`, `AZURE_PIPELINES`, `JENKINS`, `CIRCLECI`,
`BITBUCKET_PIPELINES`, `GENERIC`), an optional repository URL and project ref, an optional
`review_plan_id`, and its own AI switch (`ai_analysis_enabled` + optional `ai_config_id`).
Deleting a pipeline cascades to its **configuration** — environments, freeze windows and grants.
Its governed deployment history does not go with it: `deployment_requests.pipeline_id` is a bare
UUID with no foreign key, so past requests, decisions and rollback reviews survive as evidence.

**Environments** are the ordered promotion targets under a pipeline — `dev`, `staging`,
`production` — each with a `sort_order`, a `require_review` flag, optional `required_approvals`
and `review_plan_id` **overrides** that win over the pipeline's, and an `allow_break_glass`
opt-in that defaults to `false`. Approval count resolves in a fixed precedence:
`environment.required_approvals` → the resolved plan's `minApprovalsRequired()` → 1. Plan
resolution follows the same shape: the environment override wins over the pipeline's.

Environments also carry free-form **tags** (#741) — at most 10, each ≤ 32 chars, no fixed
semantics — for grouping across pipelines by customer, region, or tier; the "same application,
different versions, different customers" case is modelled as one environment row per customer
(`prod-acme` tagged `acme`). Each environment that has ever been deployed to also carries a
`deployment_environment_versions` row (created on its first execution): a current/previous
projection of what is deployed there, maintained by the module-private version tracker inside
the EXECUTED and outcome transactions.
Execution shifts current → previous; a `FAILED`/`ROLLED_BACK` outcome for the current deploy
reverts to previous (single-level undo — a second consecutive rollback leaves the current
version honestly unknown); an outcome for a non-current request changes nothing. The projection
is a **read model only** — it never feeds gate, approval, or routing decisions, and history
stays derived from `deployment_requests`. The read API over it — the version matrices, history,
and the drift indicator — is section 9 below (#742); the UI arrives with #743.

**Who may trigger** is a per-pipeline grant, not a functional permission. Both a per-user table
and a per-group table carry `can_trigger`, `can_break_glass` and an optional `expires_at`, and
`EffectiveDeploymentPermissionResolver` collapses them into one answer: the **most-permissive
union** of the direct grant and every unexpired group grant — the two flags OR-ed, and `expires_at`
null when any contributing grant never expires, otherwise the latest contributing expiry. Every
enforcement site routes through that single resolver, so the trigger endpoint, the gate and the
outcome endpoint can never disagree about what a caller may do.

**Administration** is gated by `DEPLOYMENT_PIPELINE_MANAGE` (held by `ADMIN`); reviewing is gated
by `DEPLOYMENT_REVIEW` (held by `ADMIN` and `REVIEWER`). Both live in the new
`DEPLOYMENT_GOVERNANCE` permission group and are seeded by V151. Every read is org-scoped: an id
belonging to another organization reads as `404`, never `403`, so no endpoint is an existence
oracle.

---

## 2. Triggering a deployment from CI

`POST /api/v1/deployment-requests` is the machine entry point. It authenticates with an existing
AccessFlow **API key** — `X-API-Key: <rawKey>` or `Authorization: ApiKey <rawKey>` — resolved by
`security/internal/filter/ApiKeyAuthenticationFilter` into the same principal a JWT produces, so
there is **no new auth code and no deployment-specific token**. The key's owning user is the
submitter, which is what makes the self-approval ban meaningful for a machine-triggered request.

**Authorization is the grant, deliberately not a permission.** The endpoint carries no
class-level `@PreAuthorize`; the service requires an effective `can_trigger` on the named
pipeline. A caller with `QUERY_ADMIN` short-circuits the trigger check, but **never** the
break-glass check.

**Idempotency is a database guarantee, not a best effort.** A CI job that retries a step, or a
re-run of the same workflow, must not open a second review. Send `external_run_id` (the CI run
id) and the partial unique index
`uq_deployment_requests_trigger_idem (pipeline_id, environment_id, version, external_run_id)`
makes the tuple unique: the first call returns **`202 Accepted`** with the new request, a repeat
returns **`200 OK`** with the existing one. Requests submitted without an `external_run_id` are
never deduplicated — the index is partial. The CI wrappers always send one.

**Payload.** `pipeline_id`, `environment` (by name) and `version` (the semantic artifact version)
are required; `commit_sha`, `artifact_ref`, `run_url`, `external_run_id`, a free-form `metadata`
object, `justification`, `scheduled_for` and `break_glass` are optional. The wrappers also send
`X-AccessFlow-CI: true` for consistency with the query-side CI Actions, but **deployment routing
does not read it** — its conditions are the typed set in §3, and every deployment request is
machine-triggered anyway, so a CI-origin leaf would match everything.

**Visibility of the resulting request** is a predicate, not a role: the submitter, an effective
`can_trigger` holder, `DEPLOYMENT_REVIEW`, or `QUERY_ADMIN`. Everything else reads `404`. A
non-privileged caller listing `/deployment-requests` is hard-scoped to their own submissions —
passing `submitted_by` is honoured only for callers who may already see everything.

---

## 3. AI release-risk analysis & routing

**Analysis.** When the pipeline has `ai_analysis_enabled`, submission publishes
`DeploymentSubmittedEvent` and `ai.api.DeploymentAnalyzer` — the sibling of `ApiCallAnalyzer` —
scores the release metadata (environment, version, commit sha, artifact ref, and a bounded
16 000-character slice of the `metadata` object) into a standard `ai_analyses` row with a risk
level and score. **The listener lives in `deploygov`, not in `ai`.** `deploygov` may depend on
`ai.api`; the reverse would make the module graph cyclic, so an `@ApplicationModuleListener` for
`deploygov.events` inside `ai` is not an option.

**AI failure fails safe.** A provider outage, a malformed response, or a disabled analyzer never
blocks a deployment and never decides one: `DeploymentAnalysisFailedEvent` forces human review, and
a failed analysis **never reaches the routing engine** — so an outage can neither auto-approve nor
auto-reject a release.

**Routing.** `DeploymentRoutingPolicyEngine` walks the organization's enabled policies in
ascending `priority` and the first match wins. `priority` is unique per organization
(`uq_deployment_routing_policies_org_priority`); the API returns `409` ahead of it and the index is
the concurrency backstop. Conditions are a typed JSON record — `environments`, `providers`,
`minRiskLevel`, `versionGlobs`, `daysOfWeek` + `startTime`/`endTime`/`timezone` — evaluated as a
conjunction in the order environment → provider → risk → version glob → time window. An empty
condition object matches everything. A policy scoped to a `pipeline_id` that does not match is
skipped, and a policy whose conditions cannot be read or evaluated is **skipped with a warning,
never matched**.

Four actions: `AUTO_APPROVE` (`PENDING_AI → APPROVED`), `AUTO_REJECT` (`PENDING_AI → REJECTED`,
with no `deployment_review_decisions` row), `REQUIRE_APPROVALS` (**replaces** the resolved approval
count) and `ESCALATE` (**adds** to it). An unrecognised action falls through to ordinary human
review. Routing wins outright over the environment's own `require_review` policy.

---

## 4. Human review, break-glass & scheduled deploys

**Review** is single-stage. `POST /api/v1/deployment-reviews/{id}/approve` and `/reject` are gated
by `DEPLOYMENT_REVIEW` and run their guards in a fixed order: **self-approval → state → permission
→ `REVIEW_OVERRIDE` → plan-approver eligibility**. Self-approval is checked first and always: the
submitter — including the API key's owning user, and including an admin — gets
`409 DEPLOYMENT_REQUEST_SELF_APPROVAL`. It is enforced in the service, not only by the annotation
and not only in the UI. Review delegation (#622) deliberately does **not** extend to deployments.

Eligibility is **opt-in by configuration**: when the resolved review plan names approvers, only
those users may decide (`403 DEPLOYMENT_REVIEWER_NOT_ELIGIBLE`); when the environment has no plan,
or the plan has no approver rules, the request stays open to any `DEPLOYMENT_REVIEW` holder.
Approve is idempotent per `(request, reviewer, stage)` and counts toward the request's folded
`required_approvals`; **reject is immediately terminal** — one rejection ends the request with no
quorum.

**Break-glass** mirrors AF-385 and is gated twice, with **no admin bypass**: the submitter needs an
effective `can_break_glass` grant on the pipeline **and** the target environment must have
`allow_break_glass = true`. Either failure is `403 DEPLOYMENT_BREAK_GLASS_NOT_ALLOWED`, and the
permission is checked *before* the idempotency lookup so a repeated trigger cannot be used to probe
for existing requests. A break-glass deploy is persisted with
`submission_reason = EMERGENCY_ACCESS`, force-approved `PENDING_AI → APPROVED` with no AI analysis,
no routing and no reviewer fan-out, and — because it is by definition immediate — may not carry a
`scheduled_for`. The compensating controls are real: an instant fan-out to every org admin
(including PagerDuty), a `DEPLOYMENT_BREAK_GLASS_EXECUTED` audit row recording any bypassed freeze
window, and a **mandatory retro-review** in `break_glass_events` that an admin — never the
submitter — must acknowledge. The event that opens it is published **synchronously**, so the
retro-review exists in the same transaction as the approval.

**Scheduled deploys** mirror AF-345: a `scheduled_for` in the future leaves the request `APPROVED`
but not releasable until that instant passes. **`DeploymentTimeoutJob`**
(`accessflow.deploygov.timeout-check`, default `PT5M`) auto-rejects `PENDING_REVIEW` requests whose
resolved review plan's `approval_timeout_hours` has elapsed, moving them to `TIMED_OUT`. A request
whose resolved plan is absent never times out.

**The state machine is enforced**, not merely documented. `DeploymentRequestStateService` is the
single transition chokepoint and rejects anything not on this list:

```
PENDING_AI     → PENDING_REVIEW | APPROVED | REJECTED | CANCELLED
PENDING_REVIEW → APPROVED | REJECTED | TIMED_OUT | CANCELLED
APPROVED       → EXECUTED | FAILED | TIMED_OUT | CANCELLED
EXECUTED       → FAILED          (the one post-terminal flip — a reported failure)
REJECTED / TIMED_OUT / FAILED / CANCELLED are terminal
```

Re-applying the current status is a silent no-op, so a redelivered event never produces a spurious
`409`.

---

## 5. The deployment gate

`GET /api/v1/deployment-gate` is the contract the pipeline blocks on, and it is **fail-closed by
construction**. Ask it either by `request_id`, or by the full tuple `pipeline` + `version` +
`environment` — mixing the two, or supplying neither completely, is
`400 DEPLOYMENT_GATE_QUERY_INVALID`.

Releasability is one pure function whose default return is *not* releasable:

```java
if (status != QueryStatus.APPROVED || frozen) { return false; }
return scheduledFor == null || !scheduledFor.isAfter(now);
```

Three conjuncts, all required: the status is **exactly `APPROVED`**, **no freeze window is
active**, and **`scheduled_for` is null or already passed**. Everything else answers
`releasable: false` — a `PENDING_REVIEW` request, a rejected one, an unknown pipeline / version /
environment tuple, a caller who may not see the request, and **any internal error**: the freeze
evaluation is wrapped so that a thrown exception is logged and degrades to *not releasable*, never
to an accidental yes. Note the error path reports `frozen: false` with no `freeze_reason` — it
makes no claim about a window it could not evaluate — so `releasable`, not `frozen`, is the field
a pipeline branches on.

**`frozen` means any active window, `HOLD` or `REJECT`.** A `REJECT` window normally auto-rejects
at submission, but a request approved *before* the window opened must not sail through mid-freeze.
Break-glass requests skip the freeze check entirely — they already bypassed it, audibly, at
submission.

**Visibility failures are `404`, never `403`.** An under-permissioned poll must be
indistinguishable from an unknown tuple, and the CI wrappers treat both identically: not
releasable. The response carries `status`, `releasable`, `approvals: { required, granted }`, the
decisions, `frozen` + `freeze_reason`, `scheduled_for` and `ai_risk_level` — enough for a job to
log *why* it is still waiting.

Two resolution details matter when a name is ambiguous. Pipeline names are unique per organization
**case-sensitively**, so `deploy-api` and `Deploy-API` may coexist; the gate looks up
case-insensitively and then prefers the exact-case match, falling back to the alphabetically first,
rather than throwing and turning the gate into a permanent `500` for both. For a
`(pipeline, environment, version)` tuple with several requests, the **newest** one answers.

**Confirming execution.** Once the gate opens, the pipeline calls
`POST /api/v1/deployment-requests/{id}/confirm-execution`, which **re-evaluates releasability at
that instant** — a freeze window that opened during the poll loop still stops the release with
`409 DEPLOYMENT_NOT_RELEASABLE` — then moves `APPROVED → EXECUTED` and audits `DEPLOYMENT_EXECUTED`
with `trigger = pipeline`. A redelivered confirmation on an already-`EXECUTED` request is
idempotent, not a conflict.

**`ScheduledDeploymentReleaseJob`** (`accessflow.deploygov.release-check`, default `PT1M`) is
**notify-only**: it announces a request that has become releasable exactly once, latched by
`release_notified_at`. It never opens a gate — the gate remains the single source of truth, and a
pipeline that never polls simply never releases.

---

## 6. Freeze windows

A freeze window suspends releases for an organization, a pipeline, or a single environment (both
scope columns null means org-wide). It takes exactly one of two shapes, enforced by the
`chk_deployment_freeze_window_shape` database constraint and re-validated in the service:

- **One-off** — `starts_at` + `ends_at`, active for `starts_at <= t < ends_at`.
- **Recurring** — `days_of_week` (ISO 1 = Monday … 7 = Sunday) + `start_time` + `end_time` +
  an IANA `timezone`, evaluated as **local wall-clock** in that zone. An `end_time` before
  `start_time` **spans midnight**, and day membership belongs to the day the window *starts* — so
  the early-morning tail matches when the *previous* local day is listed.

Supplying both shapes, or neither completely, is `400 DEPLOYMENT_FREEZE_WINDOW_INVALID`; updates
are a **full replacement**, because a partial patch across a two-shape constraint is a trap.

**Precedence is deterministic.** Among simultaneously active windows the most specific scope wins
(environment > pipeline > org-wide); within a tier `REJECT` beats `HOLD`, then the oldest window,
then a total-order tiebreak on the id.

**An unevaluable window fails closed to `HOLD`** — never to `REJECT`. A bad zone id, a day number
outside 1–7, or a shape that somehow slipped past the check counts as an active hold, logged at
`WARN`, and recovers the moment an admin fixes the row. A broken definition can therefore stall
deployments but can never auto-destroy requests.

**The two behaviours act at different moments.** `REJECT` auto-rejects at **submission** (audited
with `trigger = freeze` and the window id). `HOLD` deliberately does **not** block submission — the
request is analyzed, reviewed and approved as usual, and the window only withholds releasability at
the **gate**, so the release proceeds the instant the window closes with no re-approval.

---

## 7. Outcome reporting & rollback follow-up reviews

After the deploy runs, the pipeline reports what happened:
`POST /api/v1/deployment-requests/{id}/outcome` with `SUCCEEDED`, `FAILED` or `ROLLED_BACK`.

**Reportable states** are `EXECUTED`, or `FAILED` with an outcome already recorded — the post-flip
state, so a redelivered `FAILED` report resolves idempotently instead of conflicting on status.
Anything else is `409 DEPLOYMENT_REQUEST_INVALID_STATE`.

**Repeat reports are idempotent when identical and a conflict when not.** A second report of the
same outcome returns the existing record; a *different* outcome is
`409 DEPLOYMENT_OUTCOME_CONFLICT`. Two concurrent first reports both pass the null guard, but the
loser fails on the optimistic lock and its retry lands on the idempotent-or-conflict branch.

**`FAILED` flips `EXECUTED → FAILED`** — the single post-terminal transition the state machine
allows. `SUCCEEDED` and `ROLLED_BACK` record the outcome without changing status.

**`ROLLED_BACK` on a `require_review` environment opens a follow-up review** in
`deployment_rollback_reviews`, in the same transaction — so a governed rollback can never exist
without its review. The environment's flag defaults to `true` when the row cannot be read, which is
the fail-closed direction. The **submitter can never acknowledge** their own rollback review
(`409 DEPLOYMENT_ROLLBACK_REVIEW_SELF_ACKNOWLEDGE`), and that check runs *before* the
already-reviewed latch so the submitter always gets a clear error rather than a silent no-op. An
acknowledgement by anyone else is a latch, not a contested decision: repeating it is idempotent.

The rollback review is a deploygov-owned table rather than a reuse of `break_glass_events`, because
that table's unique `deployment_request_id` is already claimed by break-glass deploys — and a
break-glass deployment that later rolls back must still get its own follow-up.

---

## 8. CI wrappers & service-account setup

Every provider integration follows the same four beats — **submit idempotently by run id → poll the
gate → confirm execution → report the outcome** — and every one of them **fails the job** on a
`404`, a terminal status, or the configured wait timeout. A pipeline is identified by UUID, since a
trigger-only key cannot resolve names.

> **Operator runbook.** The end-to-end setup — service account, API key, pipeline, environments,
> the `can_trigger` grant, approver scoping and the first gated release — is written up as a
> step-by-step guide at
> [accessflow.io/docs/guides/deployment-approval/](https://accessflow.io/docs/guides/deployment-approval/)
> (AF-773). This chapter is the architecture behind it.

| Provider | Wrapper |
|---|---|
| GitHub Actions | `.github/actions/deployment-gate` + `.github/actions/deployment-outcome` composite actions |
| GitLab CI | `.accessflow_deployment_gate` / `.accessflow_deployment_outcome` hidden jobs in `ci-templates/gitlab/accessflow-deployment.gitlab-ci.yml` |
| Azure Pipelines | the step template `ci-templates/azure/accessflow-deployment.yml`, taking a `deploySteps` list |
| Anything else | the plain-curl walkthrough in `ci-templates/examples/generic-curl-deployment.md` |

Copy-paste snippets and input reference live in
[16-iac.md → Deployment gate](16-iac.md); the admin UI additionally renders a ready-made CI setup
panel for each pipeline. The wrappers are exercised offline by a fake-curl harness under
`.github/actions/tests/`.

**Service-account setup.** Mint an AccessFlow API key for a dedicated service-account user
(`POST /api/v1/me/api-keys`, or declaratively through the `bootstrap` module's
`ApiKeyService.importOrUpdate`), grant that user `can_trigger` on the pipeline — and
`can_break_glass` only if emergency deploys should be possible from CI — and store the raw key as a
CI secret. Because the key's owning user is the submitter, that account can never approve its own
deployments.

---

## 9. Version inventory & drift (#742)

Once deployments flow through the gate, the tracking projection (section 1) can answer the
operational question the epic promised: **which version runs where right now** — including the
per-customer view, where "customer" is one use of the free-form environment tags
(`prod-acme` tagged `acme`). Three read-only endpoints serve it
([04-api-spec.md](04-api-spec.md) → "Version inventory & drift" for the full contract):

- **`GET /deployment-pipelines/{id}/environment-versions`** — the matrix for one pipeline: every
  environment in promotion order, each row carrying the current and previous version, the
  deploying request, the last reported outcome, and a **drift block**.
- **`GET /deployment-environment-versions`** — the org-wide matrix, filterable by pipeline, tag
  (`?tag=acme` — exactly the per-customer view), environment name, and `drifted=true`.
- **`GET /deployment-pipelines/{id}/environments/{envId}/history`** — the environment's
  deployment timeline, derived straight from the request table; the full request detail (AI
  analysis, decisions) lives on the request endpoint, whose visibility is narrower — submitter,
  reviewer, or admin, so a trigger-only caller sees the timeline but not the drill-down.

**Drift** is deliberately modest: computed at read time only (no scheduled job, no
notifications), with no semver parsing — version strings are free-form, so "drifted" is plain
string inequality against the pipeline's newest successfully deployed version. The drift block
quantifies the lag two ways: `days_behind` (whole days since the newer deploy) and
`deployments_behind` (distinct newer versions successfully deployed on the pipeline), timed by
`deployment_requests.executed_at` — stamped on the `APPROVED → EXECUTED` transition. An
environment whose current version is unknown after consecutive rollbacks reports `drifted: true`
with the null surfaced honestly.

**Visibility** mirrors the gate: pipeline admins, deployment reviewers, and admins see
everything; a `can_trigger` grant holder sees their pipelines' matrices and history (they watch
these environments from CI, after all). Anything else — including a pipeline in another org —
reads as a 404, never a 403. The org-wide matrix is the one functional-permission-only surface:
trigger-only callers get 403 there.

---

## Audit & notifications

Every transition lands in the INSERT-only `audit_log`: `DEPLOYMENT_SUBMITTED`,
`DEPLOYMENT_APPROVED`, `DEPLOYMENT_REJECTED`, `DEPLOYMENT_TIMED_OUT`, `DEPLOYMENT_CANCELLED`,
`DEPLOYMENT_EXECUTED`, `DEPLOYMENT_OUTCOME_REPORTED`, `DEPLOYMENT_BREAK_GLASS_EXECUTED`,
`DEPLOYMENT_BREAK_GLASS_REVIEWED` and `DEPLOYMENT_ROLLBACK_REVIEWED`, over the resource types
`deployment_request` and `deployment_rollback_review`. Reviewer decisions are audited **per
verdict, before quorum**, so the record shows who decided what and when, not merely the outcome.
System-attributed rows carry a **null actor** plus a `trigger` key naming the mechanism —
`routing` (with `policy_id`), `environment_policy`, `freeze` (with `freeze_window_id`), `timeout`,
or `pipeline`. A failed audit write is logged and swallowed rather than breaking the governed
operation.

Five notification event types fan out across every configured channel (see
[08-notifications.md](08-notifications.md)): **`DEPLOYMENT_SUBMITTED`** to the eligible reviewers —
fired on the `PENDING_REVIEW` *transition*, not on submission, so an auto-decided deployment never
pings anyone; **`DEPLOYMENT_APPROVED`** and **`DEPLOYMENT_REJECTED`** to the submitter (a timeout
folds into the rejection with reason `review_timeout`); **`DEPLOYMENT_OUTCOME_FAILED`** to the
approvers who granted the release when the pipeline reports `FAILED` or `ROLLED_BACK`; and
**`DEPLOYMENT_BREAK_GLASS_EXECUTED`** to every org admin, routed to PagerDuty through the existing
`BREAK_GLASS` trigger. Notifications carry the pipeline, environment, version and outcome, and the
bell links straight to the request — `user_notifications.deployment_request_id` (V155) is the third
mutually-exclusive target column alongside queries and API requests. The frontend additionally
receives a `deployment.status_changed` WebSocket event on every transition.
