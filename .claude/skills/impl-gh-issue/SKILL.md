---
name: impl-gh-issue
description: Implement an AccessFlow GitHub issue end-to-end — fetch with gh, plan against docs/, follow CLAUDE.md conventions, update tests and docs, then open a PR. Trigger when the user says "implement issue #N", "work on AF-N / FE-N", or passes a GitHub issue URL/number.
---

# Implement a GitHub issue for AccessFlow

You are implementing a GitHub issue in the AccessFlow repo. **CLAUDE.md is the authoritative rulebook** — re-read it before writing code. This skill only adds a workflow on top.

## Inputs

The user passes one of:
- An issue number: `42`, `AF-58`, `FE-10`
- A full URL: `https://github.com/partqam/accessflow/issues/42`

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
- **Backend** at `backend/` — Java 25, Spring Boot 4, Spring Modulith. Modules under `com.partqam.accessflow.{core,proxy,workflow,ai,security,notifications,audit}`. Build: `./mvnw verify`.
- **Frontend** at `frontend/` — React 19 + Vite + TS + Ant Design 6 + TanStack Query + Zustand. Build: `npm run lint && npm run typecheck && npm run test:coverage && npm run build`.

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

### 4. **Update docs in the same change**
Non-negotiable. The PR is incomplete until the matching `docs/*.md` reflects what you built:
- New endpoint / payload field → `04-api-spec.md`
- New entity / column / enum → `03-data-model.md`
- New scheduled job / module rule / proxy step → `05-backend.md`
- New page / route / store / hook → `06-frontend.md`
- New env var → `09-deployment.md` **and** the env-var table in CLAUDE.md
- Edition-gated feature → `10-editions.md`
- New dependency → bump the version snapshot in CLAUDE.md (per the `feedback_dependency_versions` memory rule).

### 5. Verify locally
- Backend: `cd backend && ./mvnw verify -Pcoverage` and `./mvnw test -Dtest=ApplicationModulesTest`.
- Frontend: `cd frontend && npm run lint && npm run typecheck && npm run test:coverage && npm run build`.
- For UI changes that render in a browser, use the `preview_*` tools (per the harness instructions) — don't ask the user to check manually.

### 6. Commit and PR
- Imperative subject ≤ 72 chars, prefixed by issue: `feat(AF-58): auto-reject queries past approval_timeout_hours` (match recent history with `git log --oneline -n 5`).
- PR title mirrors the subject; PR body links the issue (`Closes #<n>`) and lists doc files updated.
- Open with `gh pr create` per the harness's PR template.

## Definition of done

- [ ] Issue fetched and acceptance criteria extracted.
- [ ] All relevant `docs/*.md` updated in the same commit set.
- [ ] Backend: `./mvnw verify` green, including `ApplicationModulesTest` and JaCoCo gate.
- [ ] Frontend: lint + typecheck + `test:coverage` + build all green.
- [ ] New concrete classes / pure modules have their own test files (coverage parity rule).
- [ ] PR opened, links the issue, and lists touched docs in the description.
