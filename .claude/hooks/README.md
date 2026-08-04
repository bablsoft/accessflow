# Hooks

Edit-time guards wired in [`../settings.json`](../settings.json). They exist because
AccessFlow's most expensive mistakes are **silent**: they compile, they pass review, and they
surface in production or not at all.

| Hook | Fires on | What it catches |
|---|---|---|
| `flyway-guard.sh` | `Write\|Edit` on `db/migration/**` | Editing a migration that already shipped (Flyway checksums it — every existing deployment then fails to start). Plus version collisions, missing `.sql.conf` sidecars, `ADD COLUMN` without DEFAULT/NULL. |
| `backend-conventions.sh` | `Write\|Edit` on `backend/**` + `engines/*/**` `.java` | `@Scheduled` without `@SchedulerLock` (runs once per replica per tick), `@Autowired` on a field, legacy `com.fasterxml.jackson.databind`, third-party imports in a module `api/`, misplaced entities, missing sibling tests. |
| `frontend-conventions.sh` | `Write\|Edit` on `frontend/src/**` | JWT in web storage, `dangerouslySetInnerHTML`/`eval`, `as any`, `import.meta.env` outside `config/`, bare `fetch`, raw colour literals, `onError` that discards the server detail, inlined enum labels. |
| `website-drift.sh` | `Write\|Edit` on `website/**.html` | Stale `sitemap.xml` `<lastmod>` and JSON-LD `dateModified` — precisely the half `websiteDocs.test.ts` does *not* cover. Plus retired `HowTo`/`FAQPage` schema. |
| `pre-commit-check.sh` | `Bash` containing `git commit` | The "same commit set" rules: an i18n key missing from any of the six locale files, an engine version bump without its re-pinned `connector.json`, `website/*.html` without the sitemap bump, an enum migration without its `.sql.conf`, duplicate migration versions, frontend changes with no e2e spec, off-convention branch names. **Blocks** a staged `settings.local.json`, `.env`, or key file. Text-only — no compilation. |
| `mvn-guard.sh` | `Bash` containing `./mvnw` | There is no Maven wrapper in this repo. Blocks with the correct `mvn -f backend/pom.xml …` translation. |

## Enforcement

Everything except `mvn-guard` is **advisory by default**. Rules marked `block()` print
`WOULD BLOCK (advisory until ACCESSFLOW_HOOKS_ENFORCE=1)` and exit 0. Set

```bash
export ACCESSFLOW_HOOKS_ENFORCE=1
```

to make them deny the edit. The intended path is: run a week of normal work warn-only, count
false positives, then enforce.

`mvn-guard` blocks unconditionally because the command it catches is guaranteed to fail anyway —
blocking only improves the error message.

## Escape hatch

If a hook ever false-positives and blocks legitimate work, add an empty `hooks` block to
`.claude/settings.local.json` (local settings win over the tracked `settings.json`):

```json
{ "hooks": {} }
```

That disables all of them for your machine. Please file the false positive so the rule gets
fixed — a wedged session should be a ten-second fix, never a reason to abandon the work.

## Adding a rule

1. **Measure first.** Scan the whole repo before wiring anything:
   ```bash
   python3 - <<'PY'
   import json, subprocess, pathlib, collections
   root = pathlib.Path('.').resolve()
   tally = collections.Counter()
   for f in (root/'backend/src/main/java').rglob('*.java'):
       payload = json.dumps({'tool_input': {'file_path': str(f), 'content': f.read_text(errors='replace')}})
       r = subprocess.run([str(root/'.claude/hooks/backend-conventions.sh')], input=payload,
                          capture_output=True, text=True)
       for line in r.stderr.splitlines():
           if line.startswith('  - '): tally[line[4:60]] += 1
   for k, v in tally.most_common(): print(f'{v:5d}  {k}')
   PY
   ```
   A rule that fires on hundreds of existing files is describing an aspiration, not a convention.
   That is how the `@Access(AccessType.FIELD)` rule was dropped — only 4 of 78 entities have it.

2. **Test both directions**: a real clean file must produce no output, and a synthetic bad file
   must fire exactly once.

3. **Watch for these bash traps**, all of which bit this codebase:
   - `grep -q` inside a pipeline under `set -o pipefail` SIGPIPEs the upstream producer and fails
     the whole pipeline. Capture into a variable instead.
   - `${VAR/a\/b/c\/d}` does not escape the way `sed` does. Use `sed` for path rewriting.
   - **`grep -E` has no negative lookahead.** An inline `(?!…)` is a syntax *error*, so the rule
     silently never fires — and `2>/dev/null` hides it. Filter with a second `grep -v` pass, and
     never suppress a grep's stderr in a rule.
   - A rule that looks clean may just be broken. Prove each one fires on a synthetic violation
     *before* trusting a zero-finding scan.

4. **Prefer narrowing over dropping.** The raw-colour rule fired on 15 files at first; nearly all
   were legitimate `var(--token, #fallback)` fallbacks and neutral `rgba(0,0,0,α)` shadows.
   Stripping those two shapes first took it to 2 real findings, which is a usable warn-level rule.

5. Prefer a handful of whole-content `grep -E` passes over a per-line loop — the backend has
   500+ line services, and a loop spawns thousands of subprocesses per edit.
