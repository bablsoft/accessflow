# Architectural Patterns

**Audience:** coding agents (Claude Code sessions, subagents) and humans working on AccessFlow.
If you're writing code that does X, find X in the table below and read that pattern before you type.

**Why this directory exists.** `CLAUDE.md` is loaded into *every* session — including ones that
only touch `website/`. Rules that only matter while you're writing a scheduled job, a Flyway
migration, or an engine plugin don't belong there; they belong here, loaded on demand. The
failure modes these patterns prevent are mostly silent: a `@Scheduled` method without
`@SchedulerLock` runs once per replica per tick and nobody notices until a retention job deletes
the same rows N times; a hand-maintained ArchUnit list quietly stops covering five modules; an
`ALTER TYPE … ADD VALUE` without its `.sql.conf` sidecar fails only on a real Postgres; a new
`RowSecurityOperator` compiles fine while nine engine plugins silently fall through to a
`default` branch.

**CLAUDE.md still wins.** These files expand on it — they never contradict it. If a pattern
disagrees with `CLAUDE.md` or with the code, the code won the last argument: fix the pattern.

## How to use

- **Before writing code in an area**, skim the relevant pattern.
- **Before reviewing code**, check it against the pattern's `## Required` checklist.
- **When you discover a new convention or a gap**, add a pattern here and index it below.
- **Patterns are versioned with the code.** A pattern that no longer matches reality is a bug.

## Pattern file format

```markdown
# <Pattern Name>

**When to use:** <one-line trigger>
**Canonical example:** `path/to/File.java:LINE`
**Tests:** `path/to/Test.java`
**Related:** <other pattern files, docs chapters>

## Shape
<minimal copy-pasteable code>

## Required (acceptance checklist)
- [ ] <check>

## Anti-patterns
- <what NOT to do> → <why it fails>

## Extending
<how to add a new instance>
```

Anchor every `Canonical example` on a **declaration line** (class, annotation, constant) — those
move rarely, and a stale line number still lands the reader in the right file.

## Index

### Backend

| Pattern | When to use | File |
|---|---|---|
| Modulith module | Adding a new top-level module under `com.bablsoft.accessflow` | [modulith-module.md](modulith-module.md) |
| REST controller | Any new endpoint under `/api/v1/` | [rest-controller.md](rest-controller.md) |
| JPA entity + migration | Any schema change or new persisted type | [jpa-entity-migration.md](jpa-entity-migration.md) |
| Scheduled job | Any `@Scheduled` method | [scheduled-job.md](scheduled-job.md) |
| i18n | Any user-facing string in Java | [backend-i18n.md](backend-i18n.md) |
| Test parity | Every new concrete backend class | [backend-test-parity.md](backend-test-parity.md) |

### Frontend, e2e, website

| Pattern | When to use | File |
|---|---|---|
| Frontend page | A new route/page/tab, or new server data in the UI | [frontend-page.md](frontend-page.md) |
| Frontend form | Any AntD `Form` that posts to the API — and any backend DTO constraint change | [frontend-form.md](frontend-form.md) |
| E2E spec | A new user-facing flow, or a change to a selector a spec uses | [e2e-spec.md](e2e-spec.md) |
| Website drift | Any `website/` edit, or an app change that alters the public pitch or docs | [website-drift.md](website-drift.md) |

### Engines

| Pattern | When to use | File |
|---|---|---|
| Engine plugin | Adding or modifying an `engines/<id>/` plugin | [engine-plugin.md](engine-plugin.md) |

### Cross-cutting fan-out

Enums whose every new value must be handled in many files at once. **Read these before adding a
value** — in both cases some of the switch sites have a `default` branch, so the compiler will
*not* find them all for you.

| Pattern | When to use | File |
|---|---|---|
| Engine fan-out | Adding a `RowSecurityOperator` / `DbType` / `ColumnMaskType` value | [engine-fanout.md](engine-fanout.md) |
| Notification fan-out | Adding a `NotificationEventType` / `NotificationChannelType` value | [notification-fanout.md](notification-fanout.md) |

## Source-of-truth files this directory mirrors

The runnable definitions live in code; pattern docs reference them, but the code wins on
disagreement.

| Runtime artifact | Pattern doc |
|---|---|
| `backend/src/main/java/com/bablsoft/accessflow/discovery/**` (the cleanest full-stack module) | modulith-module, rest-controller, jpa-entity-migration, scheduled-job |
| `backend/src/test/java/com/bablsoft/accessflow/{ApplicationModulesTest,ApiPackageDependencyTest}.java` | modulith-module |
| `backend/src/main/resources/db/migration/` + `V126__add_databricks_db_type.sql.conf` | jpa-entity-migration |
| `backend/src/main/resources/i18n/messages*.properties` + `MessagesParityTest.java` | backend-i18n |
| `backend/src/test/java/com/bablsoft/accessflow/TestcontainersConfig.java` | backend-test-parity |
| `engines/redis/` + `connectors/redis/connector.json` + `docs/15-engine-sdk.md` | engine-plugin |
| `backend/checkstyle.xml` + `.claude/hooks/` | all of them (edit-time enforcement) |

## Non-patterns

Things that look like they should be patterns but aren't, because a single correct
implementation exists and copying it would create duplication:

- **`security/internal/**/GlobalExceptionHandler`** — one global `@ControllerAdvice`. The
  *per-module* advice is the pattern; see rest-controller.md.
- **`frontend/src/api/client.ts`** — one Axios instance owning `withCredentials`, baseURL and the
  refresh interceptor. Mirror the existing functions.
- **`frontend/src/config/runtimeConfig.ts`** — one precedence chain. The rule ("never touch
  `import.meta.env` from a component") is a one-liner, not a pattern.
- **`AccessFlowApplication.java`, `OtlpTracingEnvironmentPostProcessor.java`** — bootstrap
  singletons.
- **`charts/accessflow/`** — one chart; its rules live in CLAUDE.md and CI.
- **`terraform-provider/`** — a Go module with its own conventions and release path. Revisit if a
  second resource family lands.

If you think you've found a pattern but only one instance exists, wait for the second. Premature
patterns rot.

## Escape hatch

The hooks in `.claude/hooks/` enforce a subset of these rules at edit time. If one ever
false-positives and blocks legitimate work, add an empty `"hooks": {}` block to
`.claude/settings.local.json` (local settings win over the tracked `settings.json`) for that
session — then file the false positive so the rule gets fixed. A wedged session should be a
ten-second fix, never a reason to abandon the work.
