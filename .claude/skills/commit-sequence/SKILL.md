---
name: commit-sequence
description: Split a large AccessFlow working tree into a sequence of logical, individually-reviewable commits — proposing the groups, confirming each with the user, then staging and committing. Knows which file sets must never be split apart (an engine version bump and its connector pin, a DTO constraint and its form rule). Trigger when the user says "commit these changes", "split this into commits", "commit sequence", or has a large diff and asks to land it.
---

# commit-sequence

## When to use

A working tree with several unrelated or separable changes. If everything is one change, just
commit it — this skill exists to avoid the 40-file "misc" commit nobody can review.

## Procedure

### 1. Read the tree

```bash
git status --short
git diff --stat
git diff --cached --stat
```

Note anything **untracked that you did not create** — never sweep those in. Ask, or leave them.

### 2. Propose groups

Present a numbered list: group name, conventional-commit subject, and the files. Then stop and let
the user adjust before anything is staged.

### 3. Group by change, not by directory

AccessFlow's real logical units cut across the tree:

| Unit | Files that belong together |
|---|---|
| A backend feature | the module's `api/` + `internal/` + its tests + its migration (+ `.sql.conf`) + `messages.properties` + the six locales |
| **An engine change** | `engines/<id>/**` **and** `connectors/<id>/connector.json` — **never split**: a version bump without the re-pinned SHA fails CI and leaves the catalog pointing at a jar whose hash does not match |
| A frontend feature | `src/api/<domain>.ts` + the page/components + `en.json` + the six locales + `e2e/tests/<flow>.spec.ts` |
| **A validation change** | the backend DTO constraint **and** the mirroring `Form.Item` rule — the parity rule makes these one commit by definition |
| A website change | `website/**.html` + `sitemap.xml` (+ `frontend/src/config/docs.ts` when anchors moved) |
| A config knob | the `*Properties` record + `application.yml` + the `docs/09-deployment.md` row |

### 4. Never split these

- An engine `pom.xml` version bump from its `connectors/<id>/connector.json` pin.
- A migration from its `.sql.conf` sidecar.
- An i18n key from its six translations.
- A backend constraint from its frontend rule.

Splitting any of these leaves an intermediate commit that fails CI — which breaks bisect.

### 5. Commit each group

Stage explicitly by path (never `git add -A`), then commit with a heredoc so the body survives:

```bash
git add <explicit paths>
git commit -F - <<'MSG'
<type>(<scope>): <imperative subject, <= 72 chars>

<body: what changed and WHY. The diff shows what; the body explains the
reasoning, the constraint, or the measurement behind it.>
MSG
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`, `perf`.
Subject in imperative mood, ≤ 72 chars, no trailing period.

### 6. Report

List each commit's hash and subject, then `git status --short` to show what is deliberately left.

## Anti-patterns

- **`git add -A` / `git add .`** → sweeps in `settings.local.json`, stray untracked dirs, and
  other people's work-in-progress. The pre-commit hook blocks some of it; explicit paths prevent
  all of it.
- **`--no-verify`** → the hooks are the point.
- **Amending or force-pushing a pushed commit** without being asked.
- **Committing someone else's untracked files** — if you did not create it, leave it.
- **A subject that restates the diff** ("update DiscoveryController.java") → say what changed and
  why.
- **Splitting for its own sake** → three commits that must land together are one commit.
- **Committing on `main`** → cut a `{feature,fix,chore}/AF-<n>-<slug>` branch first.

## Interaction with the hooks

`.claude/hooks/pre-commit-check.sh` fires on each `git commit` and warns on exactly the
"must land together" rules above — a missing locale translation, an engine pin left behind, a
website edit without its sitemap bump. Treat a warning as a signal that your grouping is wrong,
not as noise to push past.

## Acceptance checklist

- [ ] Every group is independently reviewable and independently green.
- [ ] No group leaves CI failing at that commit (bisect stays useful).
- [ ] Nothing untracked-and-not-yours was staged.
- [ ] Each subject is imperative and ≤ 72 chars; each body says *why*.
- [ ] The final `git status --short` was shown, and anything left behind was called out.
