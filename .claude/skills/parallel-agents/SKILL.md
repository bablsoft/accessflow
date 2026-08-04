---
name: parallel-agents
description: Fan N genuinely independent AccessFlow jobs out to N sub-agents in one message, then consolidate. Use for work that repeats the same edit across disjoint files — the nine engine row-security appliers, one i18n key across seven locale files, a pattern file per area. Trigger when the user says "in parallel", "fan out", "one agent per engine", "do these at the same time", or asks for a change that obviously repeats across engines/*.
---

# parallel-agents

AccessFlow has an unusual amount of genuinely parallel work, because the ten engine plugins are
standalone projects that repeat the same shapes. This skill is for that — and **only** that.

## When it fits

- **The nine `*RowSecurityApplier` classes.** The canonical job. Disjoint files, identical task
  shape, and two of them (Cassandra, Neo4j) have a `default` branch so the compiler will not find
  them for you → [`patterns/engine-fanout.md`](../../patterns/engine-fanout.md).
- **Per-engine changes** that follow a `core.api` SPI change — one agent per `engines/<id>/`.
- **Writing several pattern or doc files** in one pass.
- **Adding the same i18n key** to six locale files (though a script is usually better).
- **Per-module refactors** where each module's `internal/` is touched independently.

## When it does not

- **Anything touching a shared file** — see the conflict list below. Two agents editing
  `types/api.ts` will clobber each other, and neither will notice.
- **Work with a dependency chain.** If B needs A's output, that is sequential.
- **Fewer than three jobs.** The coordination overhead exceeds the saving.
- **Anything that needs a shared decision.** Decide first, then fan out the mechanical part.

## Shared files — never edit these from more than one agent

| File | Why |
|---|---|
| `backend/src/main/resources/db/migration/` | **Version collision** — two agents both pick `V130`. Assign the number yourself, up front. |
| `backend/src/main/resources/i18n/messages.properties` + the 6 locales | Every agent appends; last write wins |
| `frontend/src/types/api.ts` | One big union/interface file |
| `frontend/src/utils/enumLabels.ts` | Same |
| `frontend/src/locales/*.json` | Same |
| `backend/pom.xml`, `frontend/package.json` | Dependency edits serialize |
| `website/sitemap.xml` | One `<url>` list |
| `.github/workflows/ci.yml` | One matrix |
| `CLAUDE.md`, `.claude/patterns/README.md` | Index files |

**The pattern that works:** fan out the per-file work, then do the shared-file edits yourself in
the consolidation step. Tell each agent explicitly that the shared files are off-limits, and have
it *report* what it would have added instead.

## Procedure

1. **Enumerate the jobs and prove they are disjoint.** List the exact files each agent owns. If
   two lists intersect, the split is wrong.
2. **Write the shared context once** — the goal, the pattern file to follow, the acceptance
   criteria, and the verify command. Every prompt gets it verbatim.
3. **Per-agent prompt** = shared context + "you own exactly these files" + "do not touch:
   \<shared list\>" + the report format.
4. **Spawn them in a single message**, one tool call each, so they actually run concurrently.
5. **Consolidate**: apply the shared-file edits yourself, then run the full verify.

## Prompt template

```
<shared context: what we're changing and why, one paragraph>

Read .claude/patterns/<relevant>.md before you start.

YOU OWN EXACTLY THESE FILES:
  engines/<id>/src/main/java/.../<Id>RowSecurityApplier.java
  engines/<id>/src/test/java/.../<Id>RowSecurityApplierTest.java

DO NOT EDIT (another agent or the coordinator owns them):
  backend/src/main/resources/i18n/*, frontend/src/types/api.ts,
  frontend/src/utils/enumLabels.ts, frontend/src/locales/*, db/migration/*

TASK: <the specific change>

VERIFY: mvn -q -f engines/<id>/pom.xml test

REPORT (exactly this shape):
  - files changed:
  - verify result:
  - anything you would have added to a shared file, verbatim:
  - anything that surprised you:
```

## Verify after consolidating

```bash
mvn -f backend/pom.xml install -DskipTests
for d in engines/*/; do mvn -q -f "$d/pom.xml" test || echo "FAILED $d"; done
mvn -q -f backend/pom.xml test -Dtest='ApplicationModulesTest,ApiPackageDependencyTest,MessagesParityTest'
cd frontend && npm run typecheck && npm run test:coverage
```

## Anti-patterns

- **Splitting by "area" instead of by file** → two agents both decide `types/api.ts` is theirs.
- **Letting each agent pick its own migration version** → two `V130__*.sql`, and Flyway refuses to
  start. Assign it before fanning out.
- **Fanning out a decision** → N agents make N different choices and you reconcile by hand.
  Decide, then fan out.
- **Skipping the per-agent verify** → failures surface only in the consolidated run, where you
  cannot tell which agent caused them.
- **Trusting a sub-agent's "done"** → run the verify command yourself. A report is a claim.
