---
name: prep-gh-release
description: Prepare an AccessFlow release — verify every roadmap item is closed and documented, regenerate website screenshots, mark the milestone released in docs/website/README, then open a chore PR. Refuses to proceed when anything is missing. Trigger when the user says "prepare release vX.Y", "release prep for X.Y", or passes a target semver.
---

# Prepare an AccessFlow release

You are preparing the AccessFlow repo for a tagged release. **CLAUDE.md is the authoritative rulebook** — re-read it before editing anything. This skill formalises the pre-release checklist that used to live in maintainers' heads: every roadmap item must be closed *and* documented *and* reflected on the marketing site, every admin-SPA screenshot must be regenerated against the current build, and only then may the milestone be flipped to "released".

The skill **does not** kick off the `Release` workflow itself — that stays an explicit maintainer action from the Actions tab (see [`docs/09-deployment.md` → Cutting a release](../../docs/09-deployment.md)). This skill only prepares the repo so the workflow can run cleanly afterwards.

## Inputs

The user passes a target semver, in any of these forms:

- `1.2`
- `v1.2`
- `1.2.0`

Normalize to:
- `vX.Y` for branch / PR naming (`chore/release-prep-vX.Y`, `chore(release-prep): vX.Y`).
- The bare heading used in [`docs/12-roadmap.md`](../../docs/12-roadmap.md) — e.g. `## v1.2`.

If the user omits the version, ask for it once and stop. Do not guess from the roadmap.

## Project map

- **Roadmap source of truth:** [`docs/12-roadmap.md`](../../docs/12-roadmap.md). One `## vX.Y` heading per milestone, with an `✅ released` or `🚧 in progress` marker, then bullet lines that reference `(AF-NNN)` issues.
- **Docs chapters** (any feature must land in at least one of these):
  - `docs/03-data-model.md` — entities, columns, enums, indexes
  - `docs/04-api-spec.md` — REST + WebSocket spec
  - `docs/05-backend.md` — proxy engine, workflow, AI, scheduled jobs, MCP
  - `docs/06-frontend.md` — pages, routing, stores
  - `docs/07-security.md` — auth, RBAC, encryption
  - `docs/08-notifications.md` — channel types, payloads, retries
  - `docs/09-deployment.md` — Docker / Helm / env vars / releases
  - `docs/13-mcp.md` — MCP server
- **README** at the repo root — user-facing pitch, tech-stack versions, quick-start, project structure.
- **Marketing site** at [`website/`](../../website/) — static HTML/CSS/JS, no build step:
  - `website/index.html` — landing page (pitch, supported DBs, AI providers, auth methods, feature tiles, roadmap section, quick-start, tech stack, docs grid, footer).
  - `website/docs/index.html` — public operator docs (deployment, configuration entities, RBAC matrix, env vars).
  - `website/README.md` — content-source map. Authoritative for "which app/docs source feeds which website section". Read it before judging website coverage.
  - `website/images/docs/` — SPA screenshots, a light + dark pair for every page.
  - `website/changelog/index.html` — the public changelog: one `<section class="docs-section scroll-pad" id="vX-Y-0">` per stable release, newest first, plus a TOC link per release. **This skill is the only thing that writes a new entry** (#836).
  - `website/version.json` — `{"version","released_at","changelog_url"}`; every self-hosted install polls it daily and shows an update hint when it is newer than the running build. Stable releases only — never a `-beta.N`.
  - `website/sitemap.xml` — every website edit above needs its `<lastmod>` bumped here too (`websitePages.test.ts` pins it to the page's JSON-LD `dateModified`).

## Workflow

### 1. Resolve inputs and load roadmap section

- Normalize the version as above.
- Read [`docs/12-roadmap.md`](../../docs/12-roadmap.md). Locate the `## vX.Y` heading.
- If the section doesn't exist → fail fast: `RELEASE PREP BLOCKED — vX.Y is not in docs/12-roadmap.md.`
- If the section is already `✅ released` → exit immediately: `vX.Y is already released — nothing to prep.` No edits, no branch.
- Otherwise extract every `(AF-NNN)` reference from the bullet lines of that section. This is the "release contents" set. Keep the section's `**Theme:**` line and the full bullet text too — step 4a′ turns them into the changelog entry.

### 2. Pre-flight gates — all hard-stop, all reported together

Run every gate even if an earlier one failed. Collect all failures into a single structured report (see step 6) and **exit without writing any files** when any gate fails. Do **not** create a branch, do **not** capture screenshots, do **not** edit roadmap/website/README until every gate is green.

#### 2a. Issues are closed

For each `AF-NNN` in the release contents set:

```bash
gh issue view <n> --json number,state,title,closedAt
```

Any issue not in `state=CLOSED` is a blocker. Record as `OPEN: AF-NNN — <title>`.

#### 2b. No open PRs target this milestone

```bash
gh pr list --search "milestone:vX.Y" --state open --json number,title,url
```

Any result is a blocker. Record as `OPEN PR: #<n> <title> — <url>`.

#### 2c. Docs coverage per feature

For each closed `AF-NNN`, find the merged PR that closed it:

```bash
gh pr list --search "AF-NNN in:title is:merged" --json number,title,url,files
```

Inspect the file list. Map touched paths to the docs chapter(s) the feature must appear in:

| Code touched | Required doc chapter |
|---|---|
| `backend/src/main/resources/db/migration/V*.sql`, JPA entity / repo | `docs/03-data-model.md` |
| New controller endpoint, WS handler, request/response DTO under `internal/web/` | `docs/04-api-spec.md` |
| Proxy engine, workflow state machine, AI strategy, `@Scheduled` job | `docs/05-backend.md` |
| `frontend/src/pages/*`, `frontend/src/api/*`, new route, new Zustand store | `docs/06-frontend.md` |
| `security/` module, RBAC change, encryption change | `docs/07-security.md` |
| `notifications/` module, new channel, template, signature change | `docs/08-notifications.md` |
| New env var, Docker / Helm change, deployment topology | `docs/09-deployment.md` + the env-var table in `CLAUDE.md` |
| `mcp/` module, `@Tool` callback | `docs/13-mcp.md` |

For each issue, the target chapter must reference either the `AF-NNN` token or the feature name from the PR title. If neither is present, record as: `MISSING DOC: AF-NNN ("<feature>") not referenced in <expected chapter>`. List every missing chapter when the PR spans multiple.

#### 2d. README coverage

Determine the previous tag:

```bash
git describe --tags --abbrev=0 --match "v*"   # baseline = last released vN.M
```

Enumerate release-window changes:

```bash
git diff <prev-tag>..HEAD -- backend/pom.xml frontend/package.json
git log <prev-tag>..HEAD --oneline
```

For each of these classes of change, the README must reflect it:

- Tech-stack version bump in `backend/pom.xml` or `frontend/package.json` → README's tech-stack / version mentions.
- New `feat(...)` commits → features list / quick-start.
- Project-structure change (new top-level directory, renamed module) → README's project-structure / layout section.

Anything not mentioned is a blocker: `MISSING README: <what> ("<sample commit subject>") not reflected in README.md`.

#### 2e. Website coverage

Use [`website/README.md`](../../website/README.md)'s content-source map as the authoritative mapping. For every section in the map, if its source-of-truth file changed in the release window, the corresponding website section must also be updated in that window. Check both files:

- `website/index.html` — pitch, supported DBs, AI providers, auth methods, feature tiles, roadmap section items, quick-start commands, tech-stack callouts, docs grid, top-level URLs, footer status badge.
- `website/docs/index.html` — deployment instructions, configuration entities (Review Plans, AI configs, datasources, OAuth, SAML, SMTP, notification channels, user creation), RBAC role matrix, operator-facing env vars.

Record gaps as: `MISSING WEBSITE: <website section> does not reflect <source change>`.

`website/changelog/index.html` is deliberately **not** part of this gate — its entry for vX.Y is written by step 4a′ in this same run, so it cannot already reflect the release. Gate 2g covers it instead.

#### 2f. Roadmap section ↔ docs/12-roadmap.md parity

`website/index.html`'s roadmap section (`#roadmap`) is grouped by **capability, not by release**: an **Available now** grid of `<div class="rm-cell">` cards, each headed by an `<h4 class="rm-cat">` group name, above a compact `<div class="rm-planned">` band. It carries no version framing at all — there is no per-version milestone card to look in, and none should be re-introduced. Version history lives in `docs/12-roadmap.md`, which the section links to.

Check both directions:

- Every bullet under a **released** `## vX.Y` must be a faithful (possibly abbreviated) summary of an item somewhere in the Available now grid — in whichever `rm-cat` group fits the capability. Record: `MISSING WEBSITE ROADMAP: <feature> not in the available-now grid`.
- Nothing in the `rm-planned` band may correspond to a bullet under a released `## vX.Y`, and nothing in the grid may correspond to a bullet still under `## Backlog / Unscheduled`. Record: `STALE WEBSITE ROADMAP: <feature> is <released|backlog> in the docs but sits in the <planned band|available grid>`.

#### 2g. Changelog does not already carry the release

`website/changelog/index.html` must **not** yet contain `id="vX-Y-0"` (the per-release section anchor), and `website/version.json` must not already say `"version": "X.Y.0"`. Either one present means a previous run wrote the entry and stopped before flipping the roadmap, or someone hand-edited the page — in both cases the idempotent early exit at step 1 did not fire and this run would duplicate the entry. Record: `CHANGELOG ALREADY HAS vX.Y: <file> — reconcile by hand before re-running`.

### 3. Screenshot refresh — the skill drives the app, never the user

After all gates in step 2 are green, regenerate every admin-SPA screenshot under [`website/images/docs/`](../../website/images/docs/). Do not ask the user to capture them by hand.

#### 3a. Boot the e2e stack

The e2e compose file already builds backend + frontend from the working tree and seeds a deterministic admin via the `bootstrap` module — reuse it:

```bash
cd e2e && npm ci && npm run stack:up
```

Stack listens on `http://localhost:5173` (frontend) and `http://localhost:8080` (backend). Seeded admin credentials live in [`e2e/README.md`](../../e2e/README.md). **Read the credentials from there at runtime** — do not hardcode them; if `bootstrap` changes them, the README is the source of truth.

#### 3b. Run the capture script

Captures are driven by [`e2e/screenshots/capture.ts`](../../e2e/screenshots/capture.ts), **not** by the
preview MCP tools — it seeds its own data over the API, logs in as the bootstrap admin, and encodes
every shot through `sharp` into lossless WebP. Run it from `e2e/` once the stack is up:

```bash
npx -y tsx screenshots/capture.ts
```

Useful env knobs: `ONLY=name1,name2` captures a subset; `SKIP_SEED` + `SEEDED_DATASOURCE_ID` /
`SEEDED_DEPLOYMENT_PIPELINE_ID` / `SEEDED_DEPLOYMENT_REQUEST_ID` reuse an already-seeded stack.

Per-target failures are **non-fatal by design** — the script logs `FAIL: <name>` and continues. Read
the output for those lines; a silent gap otherwise looks like a successful run.

#### 3c. Capture each page in light and dark

Canonical list — these are the WebP files that exist today plus the routes they were captured from (the set has been lossless WebP, not PNG, since `7ffed087`). **The authoritative source is the `targets[]` array in [`e2e/screenshots/capture.ts`](../../e2e/screenshots/capture.ts)** — keep this table in sync with it. Cross-check against `git ls-files website/images/docs/` before each release; if extra files exist, update this table and the `website/README.md` content-source map in the same PR.

| Source page (admin SPA) | Output WebP |
|---|---|
| `/admin/users` → invite drawer open | `users-invite-light.webp`, `users-invite-dark.webp` |
| `/datasources` → create wizard open | `datasources-create-light.webp`, `datasources-create-dark.webp` |
| `/admin/review-plans` → create drawer open | `review-plans-create-light.webp`, `review-plans-create-dark.webp` |
| `/admin/review-plans` → templates dropdown open | `review-plans-templates-light.webp`, `review-plans-templates-dark.webp` |
| `/admin/ai-configs/new` (create wizard) | `ai-configs-create-light.webp`, `ai-configs-create-dark.webp` |
| `/admin/notifications` → create channel drawer open | `notification-channels-create-light.webp`, `notification-channels-create-dark.webp` |
| System SMTP edit form (rendered via `SystemSmtpCard`, on `/admin/notifications` — confirm in `frontend/src/App.tsx` before navigating) | `system-smtp-edit-light.webp`, `system-smtp-edit-dark.webp` |
| `/admin/oauth2` → Google provider form populated | `oauth2-google-light.webp`, `oauth2-google-dark.webp` |
| `/admin/saml` → config form populated | `saml-config-light.webp`, `saml-config-dark.webp` |
| `/admin/audit-log` with seeded data | `audit-log-light.webp`, `audit-log-dark.webp` |
| `/admin/ai-analyses` dashboard with seeded data | `ai-analyses-dashboard-light.webp`, `ai-analyses-dashboard-dark.webp` |
| `/admin/drivers` list | `drivers-list-light.webp`, `drivers-list-dark.webp` |
| `/datasources/<id>/settings` → ER diagram tab | `datasources-er-diagram-light.webp`, `datasources-er-diagram-dark.webp` |
| `/admin/datasource-health` dashboard | `datasource-health-light.webp`, `datasource-health-dark.webp` |
| `/admin/slack` config | `slack-config-light.webp`, `slack-config-dark.webp` |
| `/admin/groups` list | `groups-list-light.webp`, `groups-list-dark.webp` |
| `/admin/routing-policies` list (AF-379) | `routing-policies-light.webp`, `routing-policies-dark.webp` |
| `/admin/access-requests` queue (AF-378) | `access-requests-queue-light.webp`, `access-requests-queue-dark.webp` |
| `/datasources/<id>/settings` → Masking tab (AF-381) | `datasources-masking-light.webp`, `datasources-masking-dark.webp` |
| `/datasources/<id>/settings` → Row security tab (AF-380) | `datasources-row-security-light.webp`, `datasources-row-security-dark.webp` |
| `/admin/langfuse` config form (AF-333) | `langfuse-config-light.webp`, `langfuse-config-dark.webp` |
| `/admin/ai-configs/new` → Connection step, Enable RAG toggled (AF-336) | `ai-configs-rag-light.webp`, `ai-configs-rag-dark.webp` |
| `/admin/organizations` list (platform-admin multi-tenant management, AF-456) | `organizations-list-light.webp`, `organizations-list-dark.webp` |
| `/admin/auditor` dashboard (AF-459) | `auditor-dashboard-light.webp`, `auditor-dashboard-dark.webp` |
| `/admin/anomalies` dashboard (UBA, AF-383) | `anomalies-dashboard-light.webp`, `anomalies-dashboard-dark.webp` |
| `/admin/break-glass` log (AF-385) | `break-glass-log-light.webp`, `break-glass-log-dark.webp` |
| `/dashboard` personalized dashboard (AF-498) | `dashboard-light.webp`, `dashboard-dark.webp` |
| `/admin/attestation` campaign list (AF-384) | `attestation-campaigns-light.webp`, `attestation-campaigns-dark.webp` |
| `/api-connectors` connector catalog (AF-500) | `api-connectors-list-light.webp`, `api-connectors-list-dark.webp` |
| `/admin/lifecycle/policies` retention-policy list (AF-499) | `lifecycle-policies-light.webp`, `lifecycle-policies-dark.webp` |
| `/request-groups` list (AF-501) | `request-groups-list-light.webp`, `request-groups-list-dark.webp` |
| `/api-requests` list (AF-500) | `api-requests-list-light.webp`, `api-requests-list-dark.webp` |
| `/deployments` list with seeded data (AF-682) | `deployments-list-light.webp`, `deployments-list-dark.webp` |
| `/deployments/<id>` detail (AF-682) | `deployment-detail-light.webp`, `deployment-detail-dark.webp` |
| `/reviews?tab=deployments` queue (AF-682, unified hub since #772) | `deployment-reviews-queue-light.webp`, `deployment-reviews-queue-dark.webp` |
| `/reviews?tab=rollbacks` worklist (AF-682, unified hub since #772) | `deployment-rollback-reviews-light.webp`, `deployment-rollback-reviews-dark.webp` |
| `/admin/deployment-pipelines` list (AF-682) | `deployment-pipelines-list-light.webp`, `deployment-pipelines-list-dark.webp` |
| `/admin/deployment-pipelines/<id>` → Environments tab | `deployment-pipeline-environments-light.webp`, `deployment-pipeline-environments-dark.webp` |
| `/admin/deployment-pipelines/<id>` → Freeze windows tab | `deployment-pipeline-freeze-windows-light.webp`, `deployment-pipeline-freeze-windows-dark.webp` |
| `/admin/deployment-pipelines/<id>` → CI setup tab | `deployment-pipeline-ci-light.webp`, `deployment-pipeline-ci-dark.webp` |
| `/editor` with a sample query and the AI hint panel visible | `editor-light.webp`, `editor-dark.webp` |
| `/editor` → schedule date picker open | `editor-schedule-light.webp`, `editor-schedule-dark.webp` |
| `/editor` → query-templates drawer open | `editor-query-templates-light.webp`, `editor-query-templates-dark.webp` |
| `/editor` → text-to-SQL bar with a natural-language prompt (AF-335) | `editor-text-to-sql-light.webp`, `editor-text-to-sql-dark.webp` |
| `/queries` list with seeded data | `queries-list-light.webp`, `queries-list-dark.webp` |
| `/reviews` queue with seeded data | `reviews-queue-light.webp`, `reviews-queue-dark.webp` |
| `/reviews` queue → rows selected for bulk action | `reviews-queue-bulk-light.webp`, `reviews-queue-bulk-dark.webp` |

Adding a row means editing `capture.ts`, not doing anything by hand:

1. Add a `prep(page)` function — navigate with `gotoAndSettle`, then open the drawer / wizard / tab
   with accessible-name selectors (`getByRole('tab', { name: /Freeze windows/i })`), never brittle CSS.
2. Add a `{ name, prep }` entry to `targets[]`. Both theme variants are always captured — since
   #798 there is no per-target opt-out. An entry that needs a seeded id takes a closure over the
   `seedData()` return (`(p) => prepDeploymentDetail(p, seed.deploymentRequestId!)`) and is spread
   in only when the best-effort seed produced it.
3. If the page needs data, extend `seedData()` — wrapped in `try/catch`, so a failed seed leaves the
   page on its empty state rather than aborting the whole run.
4. Theme is flipped by writing the `af-preferences` localStorage key and reloading (`setTheme()`);
   `prep` is deliberately re-run per variant because the reload closes drawers. Do not invent a
   `data-theme` attribute — the app does not read one.
5. Embed the new figures in the matching `website/docs/**` page, or they will sit on disk referenced
   by nothing.

#### 3d. Verify the diff

After capturing all rows:

```bash
git status website/images/docs/
```

Exactly the expected WebP files (and only those) should be modified or added. If `git status` shows extra untracked files, the screenshot table above is out of date — block and update the table + the `website/README.md` content-source map in the same PR before continuing.

#### 3e. Tear the stack down

```bash
cd e2e && npm run stack:down
```

(This drops the Postgres volume so the next run re-seeds cleanly.)

### 4. Apply the release-prep edits

Only reached when steps 2 and 3 are green.

#### 4a. `docs/12-roadmap.md`

- Flip the target heading from `## vX.Y 🚧 in progress` to `## vX.Y ✅ released`.
- If the next milestone exists and was previously planned **and** the user passed `--promote-next` as a flag in the prompt, also flip the next milestone to `🚧 in progress`. Otherwise leave subsequent milestones alone.

#### 4a′. `website/changelog/index.html` and `website/version.json`

Runs right after 4a and **before 4b**, so the whole website edit set lands in one commit.

- Prepend a new release section to `website/changelog/index.html`, directly above the current newest `<section class="docs-section scroll-pad" id="…">`, shaped exactly like its siblings:
  ```html
  <section class="docs-section scroll-pad" id="vX-Y-0">
    <h2>vX.Y.0 — <the roadmap section's Theme line, without the trailing period></h2>
    <p class="changelog-meta">
      Released <time datetime="YYYY-MM-DD">D Month YYYY</time> ·
      <a href="https://github.com/bablsoft/accessflow/releases/tag/vX.Y.0">Release on GitHub</a>
    </p>
    <ul>
      <li><strong>Bold lead</strong> — one `<li>` per release-contents bullet from step 1, rewritten as release-note prose for a visitor: what it does and why it matters, no `AF-NNN` tokens, no class or table names.</li>
    </ul>
  </section>
  ```
  The date is today (the prep PR merges the same day the Release workflow is dispatched — see the ordering note in `docs/11-development.md`). Add `<a href="#vX-Y-0">vX.Y.0</a>` as the first link under the `Releases` label in the page's `docs-toc`. Zero inline `style=""` — the site-wide budget is asserted by equality.
- Bump the page's JSON-LD `dateModified` to today and the `/changelog/` `<lastmod>` in `website/sitemap.xml` to the same value (`websitePages.test.ts` fails when the two disagree; the `website-drift.sh` hook warns when they are not today).
- Overwrite `website/version.json`:
  ```json
  {
    "version": "X.Y.0",
    "released_at": "YYYY-MM-DD",
    "changelog_url": "https://accessflow.io/changelog/#vX-Y-0"
  }
  ```
  Stable releases only. Betas bypass this skill entirely and must never be written here — a `-beta.N` in this file would make every install nag about a pre-release. Patch releases (`X.Y.Z`, Z > 0) also bypass this skill; their manual `version.json` + `Patch release:` line procedure is in `docs/11-development.md` → "Version surfacing".
- 4b edits `website/index.html`, so bump its JSON-LD `dateModified` and the `/` `<lastmod>` in `sitemap.xml` too. (Pre-existing gap: the sitemap was missing from the staging list before #836.)

#### 4b. `website/index.html`

- The roadmap section carries no version framing, so there is **no milestone card to flip**. Instead, move any feature that this release promoted out of `## Backlog / Unscheduled` into `## vX.Y` out of the `<div class="rm-planned">` band and into the matching `<div class="rm-cell">` group in the Available now grid. If nothing was promoted, the section needs no edit.
- If the hero strip has a `badge-tag` referencing the prior version (e.g. `<span class="badge-tag">v1.1</span>`), update it to `vX.Y`.
- In the footer bar, update `<span class="status">… <prev> generally available</span>` to `<span class="status">… vX.Y generally available</span>`.

#### 4c. `website/styles.css`

Usually untouched. The roadmap section has no status-badge rules any more — the `data-status` PUBLISHED / IN PROGRESS / PLANNED badges went away with the release-column layout. Only touch it if moving an item between the `rm-cell` grid and the `rm-planned` band needs a layout adjustment.

The v1.1 prep commit (`e6a4089`) is a reference example.

#### 4d. `README.md`

- Update any stale version marker (e.g. "pre-v1.0" or a previous-version status line).
- If new features shipped in this release, append them to the features bullet list to match the roadmap section.
- If the README embeds any PNG that was regenerated in step 3, the link does not need to change (filename is stable), but smoke-check that the file paths still resolve.

#### 4e. Do **not** edit these files

- `backend/pom.xml`
- `frontend/package.json`
- `charts/accessflow/Chart.yaml`
- Any other version constant

The `Release` GitHub Action owns those — touching them here races with the action and pollutes `main`. The user explicitly opts into version bumps by running the workflow with a `version` input.

### 5. Branch, commit, PR

- Branch off `main`:
  ```bash
  git checkout main && git pull --ff-only && git checkout -b chore/release-prep-vX.Y
  ```
  (Not `feature/` or `fix/` — this isn't an `AF-NNN` issue.)
- Stage only the touched files (`docs/12-roadmap.md`, `website/changelog/index.html`, `website/version.json`, `website/sitemap.xml`, `website/index.html`, optionally `website/styles.css`, `README.md`, the regenerated PNGs under `website/images/docs/`). Never `git add -A`.
- Commit with an imperative subject ≤ 72 chars, matching the previous release-prep style:
  ```
  chore(release-prep): mark vX.Y released and refresh website screenshots
  ```
  Body lists each path category touched.
- Push:
  ```bash
  git push -u origin chore/release-prep-vX.Y
  ```
- Open the PR via `gh pr create`. PR body must include:
  - A "Release contents" section — one bullet per `AF-NNN`, copied verbatim from the `docs/12-roadmap.md` section.
  - A "Changelog" line linking the new entry: `https://accessflow.io/changelog/#vX-Y-0` (live once this PR merges), and the `version.json` value it publishes.
  - A "Screenshots regenerated" section — the PNG filenames from step 3.
  - A "Next step" section linking the [Release workflow](https://github.com/bablsoft/accessflow/actions/workflows/release.yml): "Once this PR is merged, run the Release workflow from the Actions tab with version input `X.Y.Z` to cut the actual release."

### 6. Pre-flight failure output

When any gate in step 2 fails, the **only** output is a single structured report. No partial edits, no branch, no screenshot captures. Format:

```
RELEASE PREP BLOCKED for vX.Y

Open roadmap items:
  - AF-360 — Read replica routing (state=OPEN)
  - AF-362 — Slack bot approve/reject (state=OPEN, PR #421 not merged)

Open PRs targeting this milestone:
  - PR #419 Query result diffing — https://github.com/bablsoft/accessflow/pull/419

Documentation gaps:
  - AF-361 — Query result diffing — not referenced in docs/05-backend.md
  - AF-363 — PagerDuty integration — env var ACCESSFLOW_NOTIFICATIONS_PAGERDUTY_* missing from docs/09-deployment.md and CLAUDE.md env-var table

README gaps:
  - feat(AF-363) added env var ACCESSFLOW_NOTIFICATIONS_PAGERDUTY_TOKEN not reflected in README.md

Website gaps:
  - website/index.html available-now roadmap grid is missing the v1.2 "<feature>" bullet from docs/12-roadmap.md
  - website/docs/index.html does not document the new ACCESSFLOW_NOTIFICATIONS_PAGERDUTY_* env vars

Re-run after closing the items above.
```

Suppress any section that has no entries — don't print empty headers.

## Definition of done

- [ ] Target version exists in `docs/12-roadmap.md` and was `🚧 in progress` (or unmarked) at the start of the run.
- [ ] Every `AF-NNN` in that section is `CLOSED` on GitHub.
- [ ] No open PRs target the milestone.
- [ ] Every closed `AF-NNN` is referenced in the right `docs/*.md` chapter (by `AF-NNN` token or feature name).
- [ ] `README.md` reflects all release-window changes (features, tech-stack version bumps, project-structure additions).
- [ ] `website/index.html` and `website/docs/index.html` reflect all release-window changes per [`website/README.md`](../../website/README.md)'s content-source map.
- [ ] Every admin-SPA screenshot under `website/images/docs/` was regenerated against the current frontend build by running `e2e/screenshots/capture.ts` — no manual captures, no skipped pages, and no `FAIL:` lines left unexplained in its output.
- [ ] `git status website/images/docs/` shows only the expected PNGs touched; the screenshot table in step 3c matches the actual PNG set on disk.
- [ ] `docs/12-roadmap.md` flipped to `✅ released`.
- [ ] `website/index.html` — every feature promoted out of the docs Backlog moved from the `rm-planned` band into its available-now group; hero badge and footer status updated.
- [ ] `website/changelog/index.html` gained exactly one new `id="vX-Y-0"` section (newest first, TOC link added, dated today, release-note prose, zero inline styles) and `website/version.json` says `X.Y.0` with the matching anchor; both pages' `dateModified` and their `sitemap.xml` `<lastmod>` agree on today, and `cd frontend && npm run test:coverage` is green.
- [ ] `backend/pom.xml`, `frontend/package.json`, `charts/accessflow/Chart.yaml` untouched — the `Release` workflow owns those.
- [ ] Branch `chore/release-prep-vX.Y` pushed, PR opened, PR body lists release contents + regenerated PNGs + link to the `Release` workflow.
