---
name: impl-gh-issue
description: Implement an AccessFlow GitHub issue end-to-end — fetch with gh, plan against docs/, follow CLAUDE.md conventions, update tests and docs, then open a PR. Trigger when the user says "implement issue #N", "work on AF-N / FE-N", or passes a GitHub issue URL/number.
---

# Implement a GitHub issue for AccessFlow

You are implementing a GitHub issue in the AccessFlow repo. **CLAUDE.md is the authoritative rulebook** — re-read it before writing code. This skill only adds a workflow on top.

## Inputs

The user passes one of:
- An issue number: `42`, `AF-58`, `FE-10`
- A full URL: `https://github.com/bablsoft/accessflow/issues/42`

Resolve the numeric issue with `gh issue view <n> --json number,title,body,labels,assignees,url,state`.

## Project map

- **Docs** (authoritative design):
  - `docs/01-overview.md` — product scope, goals, non-goals
  - `docs/02-architecture.md` — system architecture and request flow
  - `docs/03-data-model.md` — entities, columns, enums, indexes
  - `docs/04-api-spec.md` — REST + WebSocket spec (update before adding endpoints)
  - `docs/05-backend.md` — proxy engine, workflow state machine, AI analyzer, scheduled jobs
  - `docs/06-frontend.md` — directory layout, routing, state management
  - `docs/07-security.md` — auth, authorization matrix, encryption rules
  - `docs/08-notifications.md` — event types, channel configs, signed payloads
  - `docs/09-deployment.md` — Docker Compose, Helm, env-var reference
  - `docs/10-editions.md` — Community vs Enterprise feature matrix
  - `docs/11-development.md` — coding standards, testing strategy, Git workflow
  - `docs/12-roadmap.md` — milestone scope
- **Backend** at `backend/` — Java 25, Spring Boot 4, Spring Modulith. Modules under `com.bablsoft.accessflow.{core,proxy,workflow,ai,security,notifications,audit}`. Build: `./mvnw verify`.
- **Frontend** at `frontend/` — React 19 + Vite + TS + Ant Design 6 + TanStack Query + Zustand. Build: `npm run lint && npm run typecheck && npm run test:coverage && npm run build`.
- **End-to-end** at `e2e/` — Playwright suite with its own `docker-compose.e2e.yml` that builds backend + frontend from the working tree and seeds a deterministic admin via the `bootstrap` module. Owns auth and (over time) all critical user flows. Run: `cd e2e && npm ci && npx playwright install --with-deps chromium && npm run stack:up && npm test`.
- **Website** at `website/` — public marketing site, static HTML/CSS/JS, no build step. Edits land directly in HTML.
  - `website/index.html` — landing page (pitch, supported databases, AI providers, auth methods, feature list, roadmap, quick-start commands, tech stack, docs chapter list, top-level URLs).
  - `website/docs/index.html` — public user documentation page (deployment instructions, configuration entities — Review Plans, AI configs, datasources, OAuth, SAML, SMTP, notification channels, user creation — RBAC role matrix, operator-facing env vars).
  - `website/README.md` — content-source map describing which app/`docs/` sections each website section is derived from; keep in sync when adding new website sections.
- **README** at the repo root — public-facing project overview and quick start. Keep in sync when changes affect setup, tech stack, features, project structure, or top-level documentation.

## Workflow

### 1. Read the issue and pick docs
- `gh issue view <n>` to load title, body, labels.
- From labels and body, pick the docs to read **before coding**:
  - DB / migration / entity → `03-data-model.md`, `05-backend.md`
  - New REST or WS endpoint → `04-api-spec.md` (update first), `05-backend.md`
  - Workflow / proxy / AI / scheduled job → `05-backend.md`
  - Auth / RBAC / encryption → `07-security.md`
  - UI page / component / store → `06-frontend.md`
  - Notifications → `08-notifications.md`
  - Env vars / Docker / Helm → `09-deployment.md`
  - Edition gating → `10-editions.md`
- Always re-skim CLAUDE.md sections relevant to the layer you're touching (Modulith rules, validation parity, i18n, scheduled-job locking, JaCoCo gate).

### 2. Branch
Branch names follow `docs/11-development.md` and CLAUDE.md:
- `feature/AF-<n>-<kebab-summary>` for backend features
- `fix/AF-<n>-<kebab-summary>` for backend fixes
- `feature/FE-<n>-…` / `fix/FE-<n>-…` for frontend-only work
- Branch off `main` (the repo currently merges PRs into `main`; check `git remote show origin` if unsure).

### 3. Implement
Follow CLAUDE.md exactly. Highlights worth re-checking before each PR:
- **Backend**: constructor injection, `*Entity` suffix and `internal/persistence/entity/` placement, Flyway `V{n}__…sql` (never edit existing), `@SchedulerLock` on every `@Scheduled`, no string-concat SQL, `@JsonIgnore` on encrypted fields, i18n keys in `messages.properties`, ≥ 90% line coverage with a dedicated `*Test` per concrete class.
- **Frontend**: TanStack Query for all server data (no `useEffect` fetching), Zustand only for client state, `t()` for every user-visible string with the key in `src/locales/en.json`, validation parity with backend Bean Validation, `≥ 90%` line / `≥ 80%` branch coverage on included modules.
- **End-to-end (`e2e/`)** — non-negotiable, per CLAUDE.md's "Do not let `e2e/` drift" rule. Before opening the PR, scan the diff for changes that touch user flows:
  - **Frontend change touches a route, page, form, store, or selector that an existing Playwright spec uses?** Update the spec in the same commit set. Don't merge a frontend change that you know will flip an e2e green run to red. Common drift sources: renaming an `id` / `aria-label` / button text used by a selector, changing the redirect target after login or logout, altering the auth-store or apiClient interface, moving the logout entry in the user menu.
  - **Frontend change introduces a new user-facing flow** (new route, auth path, user-driven mutation — submit query, approve a review, create a datasource, change a setting that has a server effect)? Add a spec under `e2e/tests/` in the same PR. The default is "add a spec"; if you're skipping it, justify why in the PR description.
  - **Backend change flips behaviour for an e2e-covered flow** (login payload, refresh-cookie semantics, `bootstrap` reconciler, setup-status endpoint)? Update the spec accordingly.
  - **Pure presentational refactors** (CSS-only, internal rename with no selector impact) don't need an e2e update — but verify your refactor doesn't break any selector first.
  - When in doubt, run `cd e2e && npm run stack:up && npm test` locally before the PR. The `e2e` CI job is the load-bearing check.

### 4. **Update docs AND the website in the same change**
Non-negotiable. The PR is incomplete until the matching `docs/*.md`, `README.md`, and `website/` reflect what you built.

**`docs/*.md`:**
- New endpoint / payload field → `04-api-spec.md`
- New entity / column / enum → `03-data-model.md`
- New scheduled job / module rule / proxy step → `05-backend.md`
- New page / route / store / hook → `06-frontend.md`
- New env var → `09-deployment.md` **and** the env-var table in CLAUDE.md
- Edition-gated feature → `10-editions.md`
- New dependency → bump the version snapshot in CLAUDE.md (per the `feedback_dependency_versions` memory rule).

**`README.md`** (repo root) — update when the change affects setup, tech stack versions, features, project structure, or top-level documentation.

**Website updates are mandatory — do not skip.** Before opening the PR, ask explicitly: "does this change touch anything user-visible or operator-visible?" If yes, update the website file(s) in the same commit set:

- `website/index.html` — update when the change affects any of: user-facing pitch, supported databases, AI providers, authentication methods, feature list, roadmap milestones, quick-start commands, docs chapter list, tech stack versions, or top-level URLs.
- `website/docs/index.html` — update when the change affects any of: deployment instructions, configuration entities (Review Plans, AI configs, datasources, OAuth, SAML, SMTP, notification channels, user creation), the RBAC role matrix, or operator-facing env vars.
- `website/README.md` — update the content-source map when you add new website sections or change which app/docs sources they derive from.

The website has no build step — edits land directly in HTML. If you're unsure whether a change is user-visible, default to updating the website rather than skipping; a stale marketing site is a project-level rule violation (per CLAUDE.md → "Do not let `website/` drift").

### 5. Verify locally
- Backend: `cd backend && ./mvnw verify -Pcoverage` and `./mvnw test -Dtest=ApplicationModulesTest`.
- Frontend: `cd frontend && npm run lint && npm run typecheck && npm run test:coverage && npm run build`.
- **E2E (when frontend or auth/setup/proxy backend code changed):** `cd e2e && npm ci && npx playwright install --with-deps chromium && npm run stack:up && npm test && npm run stack:down`. The CI `e2e` job runs the same steps — fail-locally-first to keep PR turnaround tight.
- For UI changes that render in a browser, use the `preview_*` tools (per the harness instructions) — don't ask the user to check manually.

### 6. Commit and PR
- Imperative subject ≤ 72 chars, prefixed by issue: `feat(AF-58): auto-reject queries past approval_timeout_hours` (match recent history with `git log --oneline -n 5`).
- PR title mirrors the subject; PR body links the issue (`Closes #<n>`) and lists doc files updated.
- Open with `gh pr create` per the harness's PR template.

## Definition of done

- [ ] Issue fetched and acceptance criteria extracted.
- [ ] All relevant `docs/*.md` updated in the same commit set.
- [ ] `README.md` updated if the change affects setup, tech stack, features, project structure, or top-level docs.
- [ ] `website/index.html` updated if the change affects the pitch, supported databases, AI providers, auth methods, features, roadmap, quick-start, docs chapter list, tech stack, or top-level URLs.
- [ ] `website/docs/index.html` updated if the change affects deployment instructions, configuration entities, the RBAC role matrix, or operator-facing env vars.
- [ ] `website/README.md` content-source map updated if new website sections were added.
- [ ] Backend: `./mvnw verify` green, including `ApplicationModulesTest` and JaCoCo gate.
- [ ] Frontend: lint + typecheck + `test:coverage` + build all green.
- [ ] `e2e/tests/` updated to match: existing specs still pass against the change, and new user-facing flows (route, auth path, user-driven mutation) have a new spec — or the PR description states explicitly why one wasn't added. Specs ran locally via `npm run stack:up && npm test` when the change touched frontend or auth/setup/proxy backend code.
- [ ] New concrete classes / pure modules have their own test files (coverage parity rule).
- [ ] PR opened, links the issue, and lists touched docs **and website files** in the description.
