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
  - `website/index.html` — landing page (pitch, supported DBs, AI providers, auth methods, feature tiles, roadmap track, quick-start, tech stack, docs grid, footer).
  - `website/docs/index.html` — public operator docs (deployment, configuration entities, RBAC matrix, env vars).
  - `website/README.md` — content-source map. Authoritative for "which app/docs source feeds which website section". Read it before judging website coverage.
  - `website/images/docs/` — admin-SPA screenshots (light + dark pairs for most pages; light-only for editor / queries / reviews by precedent).

## Workflow

### 1. Resolve inputs and load roadmap section

- Normalize the version as above.
- Read [`docs/12-roadmap.md`](../../docs/12-roadmap.md). Locate the `## vX.Y` heading.
- If the section doesn't exist → fail fast: `RELEASE PREP BLOCKED — vX.Y is not in docs/12-roadmap.md.`
- If the section is already `✅ released` → exit immediately: `vX.Y is already released — nothing to prep.` No edits, no branch.
- Otherwise extract every `(AF-NNN)` reference from the bullet lines of that section. This is the "release contents" set.

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

- `website/index.html` — pitch, supported DBs, AI providers, auth methods, feature tiles, roadmap track bullets, quick-start commands, tech-stack callouts, docs grid, top-level URLs, footer status badge.
- `website/docs/index.html` — deployment instructions, configuration entities (Review Plans, AI configs, datasources, OAuth, SAML, SMTP, notification channels, user creation), RBAC role matrix, operator-facing env vars.

Record gaps as: `MISSING WEBSITE: <website section> does not reflect <source change>`.

#### 2f. Roadmap-track ↔ docs/12-roadmap.md parity

The bullets under `<div class="milestone" data-status="..."><span class="ver">vX.Y</span>` in `website/index.html` must be a faithful (possibly abbreviated) summary of the bullets under `## vX.Y` in `docs/12-roadmap.md`. If a bullet is present in the docs but not on the website, record: `MISSING WEBSITE ROADMAP: <feature> not in v X.Y milestone card`.

### 3. Screenshot refresh — the skill drives the app, never the user

After all gates in step 2 are green, regenerate every admin-SPA screenshot under [`website/images/docs/`](../../website/images/docs/). Do not ask the user to capture them by hand.

#### 3a. Boot the e2e stack

The e2e compose file already builds backend + frontend from the working tree and seeds a deterministic admin via the `bootstrap` module — reuse it:

```bash
cd e2e && npm ci && npm run stack:up
```

Stack listens on `http://localhost:5173` (frontend) and `http://localhost:8080` (backend). Seeded admin credentials live in [`e2e/README.md`](../../e2e/README.md). **Read the credentials from there at runtime** — do not hardcode them; if `bootstrap` changes them, the README is the source of truth.

#### 3b. Drive the app via preview tools

Attach with `preview_start http://localhost:5173`. If `preview_start` fails (no browser sidecar attached), abort the skill with a clear error: `Screenshot capture requires the preview MCP tools; aborting. Re-run from an environment where preview_start can attach.` Never silently skip captures or hand the task back to the user.

Log in with the seeded admin via `preview_fill` + `preview_click` (assert success with `preview_snapshot` showing the post-login layout).

#### 3c. Capture each page in light and dark

Canonical list — these are the PNGs that exist today plus the routes they were captured from. Cross-check against `git ls-files website/images/docs/` before each release; if extra PNGs exist, update this table and the `website/README.md` content-source map in the same PR.

| Source page (admin SPA) | Output PNGs |
|---|---|
| `/admin/users` → invite drawer open | `users-invite-light.png`, `users-invite-dark.png` |
| `/datasources` → create wizard open | `datasources-create-light.png`, `datasources-create-dark.png` |
| `/admin/review-plans` → create drawer open (after picking a template) | `review-plans-create-light.png`, `review-plans-create-dark.png` |
| `/admin/ai-configs/new` (create wizard) | `ai-configs-create-light.png`, `ai-configs-create-dark.png` |
| `/admin/notifications` → create channel drawer open | `notification-channels-create-light.png`, `notification-channels-create-dark.png` |
| System SMTP edit form (rendered via `SystemSmtpCard`, on whichever route hosts it — confirm in `frontend/src/App.tsx` before navigating) | `system-smtp-edit-light.png`, `system-smtp-edit-dark.png` |
| `/admin/oauth` → Google provider form populated | `oauth2-google-light.png`, `oauth2-google-dark.png` |
| `/admin/saml` → config form populated | `saml-config-light.png`, `saml-config-dark.png` |
| `/editor` with a sample query and the AI hint panel visible | `editor-light.png` (light-only by precedent) |
| `/queries` list with seeded data | `queries-list-light.png` (light-only by precedent) |
| `/reviews` queue with seeded data | `reviews-queue-light.png` (light-only by precedent) |

For each row:

1. Navigate via `preview_eval` (`window.location.assign('/admin/users')`).
2. Open the drawer / wizard / panel via `preview_click`. Use accessible-name selectors (`role=button[name='Invite user']`), not brittle CSS.
3. Wait for the target element with `preview_snapshot` until the rendered drawer / form is fully present.
4. `preview_screenshot` → returns a temp path. Move it to `website/images/docs/<name>-light.png` (overwriting the existing file).
5. Flip the theme. The app uses Ant Design dark-mode tokens — confirm the exact toggle by reading [`frontend/src/utils/antdTheme.ts`](../../frontend/src/utils/antdTheme.ts) and any `preferencesStore` flag. Drive that toggle via `preview_eval` (whatever the source code exposes; do not invent a `data-theme` attribute that the app doesn't read). Re-capture and save as `<name>-dark.png`.
6. Reset to the previous state before moving on (close the drawer; flip back to light) so each capture is independent.

#### 3d. Verify the diff

After capturing all rows:

```bash
git status website/images/docs/
```

Exactly the expected PNGs (and only those) should be modified or added. If `git status` shows extra untracked PNGs, the screenshot table above is out of date — block and update the table + the `website/README.md` content-source map in the same PR before continuing.

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

#### 4b. `website/index.html`

- In `<div class="roadmap-track">`, change the matching milestone card's `data-status="in-progress"` to `data-status="published"` for `<span class="ver">vX.Y</span>`.
- If the hero strip has a `badge-tag` referencing the prior version (e.g. `<span class="badge-tag">v1.1</span>`), update it to `vX.Y`.
- In the footer bar, update `<span class="status">… <prev> generally available</span>` to `<span class="status">… vX.Y generally available</span>`.

#### 4c. `website/styles.css`

Only touch if a status-badge CSS class needs adjusting for the new "published" state. Diff against the previous release-prep commit to see whether colour tokens for `data-status="published"` / `data-status="in-progress"` need updating:

```bash
git show <previous-release-prep-commit-sha> -- website/styles.css
```

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
- Stage only the touched files (`docs/12-roadmap.md`, `website/index.html`, optionally `website/styles.css`, `README.md`, the regenerated PNGs under `website/images/docs/`). Never `git add -A`.
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
  - website/index.html roadmap track v1.2 milestone bullets do not match docs/12-roadmap.md
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
- [ ] Every admin-SPA screenshot under `website/images/docs/` was regenerated against the current frontend build via `preview_*` tools — no manual captures, no skipped pages.
- [ ] `git status website/images/docs/` shows only the expected PNGs touched; the screenshot table in step 3c matches the actual PNG set on disk.
- [ ] `docs/12-roadmap.md` flipped to `✅ released`.
- [ ] `website/index.html` roadmap track milestone flipped to `data-status="published"`; hero badge and footer status updated.
- [ ] `backend/pom.xml`, `frontend/package.json`, `charts/accessflow/Chart.yaml` untouched — the `Release` workflow owns those.
- [ ] Branch `chore/release-prep-vX.Y` pushed, PR opened, PR body lists release contents + regenerated PNGs + link to the `Release` workflow.
