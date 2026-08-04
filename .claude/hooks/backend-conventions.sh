#!/usr/bin/env bash
# backend-conventions.sh — PreToolUse (Write|Edit) on Java sources.
#
# Warn-level for the things review and CI eventually catch anyway; one block for
# the mistake that is silent in dev (single replica) and destructive in prod:
# @Scheduled without @SchedulerLock runs the job once PER REPLICA per tick.
#
# Implemented as a handful of whole-content grep passes, not a per-line bash
# loop — this repo has 500+ line services and a loop would spawn thousands of
# subprocesses on every edit.
#
# See .claude/patterns/{modulith-module,rest-controller,jpa-entity-migration,scheduled-job,backend-i18n,backend-test-parity}.md

set -uo pipefail
. "$(dirname "$0")/_common.sh"
hook_read_target "$@"

case "$REL" in
  backend/src/main/java/*.java|engines/*/src/main/java/*.java) ;;
  *) exit 0 ;;
esac
[ -z "$CONTENT" ] && exit 0

BASE="$(basename "$REL" .java)"
IS_API=0;  case "$REL" in */accessflow/*/api/*)            IS_API=1 ;; esac
IS_WEB=0;  case "$REL" in */internal/web/*)                IS_WEB=1 ;; esac
IS_ENT=0;  case "$REL" in */internal/persistence/entity/*) IS_ENT=1 ;; esac
IS_ENG=0;  case "$REL" in engines/*)                       IS_ENG=1 ;; esac

# Strip whole-line comments once so the greps below don't fire on prose.
CODE="$(printf '%s' "$CONTENT" | grep -vE '^[[:space:]]*(//|\*|/\*)' || true)"
# grep -q would SIGPIPE the producer under pipefail; match into a variable instead.
has() { local o; o="$(printf '%s' "$CODE" | grep -E "$1" || true)"; [ -n "$o" ]; }
lines_of() { printf '%s' "$CODE" | grep -nE "$1" | head -3 | cut -d: -f1 | paste -sd, -; }

# ---- B1 field injection -------------------------------------------------------
# A field declaration ends in ";"; a constructor/method opens "(". Only the former is banned.
# NB: capture into a variable rather than piping into `grep -q` — under `set -o pipefail`
# the early exit of `grep -q` SIGPIPEs the upstream grep and fails the whole pipeline.
AUTOWIRED_CTX="$(printf '%s' "$CODE" | grep -A2 -E '^[[:space:]]*@Autowired' || true)"
if printf '%s' "$AUTOWIRED_CTX" | grep -E ';[[:space:]]*$' | grep -qv '(' 2>/dev/null; then
  warn "@Autowired on a field — constructor injection only; use @RequiredArgsConstructor with final fields. Also enforced by backend/checkstyle.xml."
fi

# ---- B2 legacy Jackson namespace ----------------------------------------------
if has '^import[[:space:]]+com\.fasterxml\.jackson\.(databind|core|datatype)'; then
  warn "com.fasterxml.jackson.{databind,core,datatype} import — Jackson 3 is tools.jackson.*; migrate the whole file. (com.fasterxml.jackson.annotation.* is unaffected and stays.)"
fi

# ---- B3 api/ package purity ---------------------------------------------------
if [ "$IS_API" = 1 ] && [ "$IS_ENG" = 0 ]; then
  BADIMP="$(printf '%s' "$CODE" \
    | grep -E '^import[[:space:]]' \
    | grep -vE '^import[[:space:]]+(static[[:space:]]+)?(java|javax|com\.bablsoft\.accessflow)\.' \
    | grep -vE '^import[[:space:]]+org\.springframework\.modulith\.NamedInterface;' | head -3 || true)"
  if [ -n "$BADIMP" ]; then
    warn "third-party import in an api/ package — api/ may reference only java.*, javax.*, com.bablsoft.accessflow.*, and @NamedInterface. ApiPackageDependencyTest will fail. Offending: $(printf '%s' "$BADIMP" | tr '\n' ' ')"
  fi
fi

# ---- B4 BLOCK: unlocked scheduled method --------------------------------------
if has '@Scheduled' && ! has '@SchedulerLock'; then
  block "@Scheduled without @SchedulerLock — in a multi-replica deployment this job runs once PER REPLICA per tick. For ErasureExecutionJob / RetentionPolicyExecutionJob that is data loss. Add @SchedulerLock(name=\"<camelCaseJobName>\", lockAtMostFor=\"PT30M\", lockAtLeastFor=\"PT1M\") — see DiscoveryScanJob.java:37"
fi
if has '@Scheduled\((fixedDelay|fixedRate|initialDelay)[[:space:]]*=[[:space:]]*[0-9]'; then
  warn "hard-coded numeric cadence — use fixedDelayString = \"\${accessflow.<module>.<knob>:PT5M}\" (ISO-8601, default inline)"
fi

# ---- B5 controller OpenAPI ----------------------------------------------------
if [ "$IS_WEB" = 1 ] && has '@(Get|Post|Put|Patch|Delete)Mapping' && ! has '@Operation'; then
  warn "controller has @*Mapping methods but no @Operation — every endpoint needs @Operation + one @ApiResponse per reachable status"
fi

# ---- B6 coverage parity -------------------------------------------------------
case "$BASE" in
  Default*Service|*Specifications|*Mapper)
    SIB="$(dirname "$(printf '%s' "$REL" | sed 's|/src/main/java/|/src/test/java/|')")/${BASE}Test.java"
    [ -f "$REPO_ROOT/$SIB" ] || warn "no sibling test at $SIB — every concrete class ships its own test in the same change. Controller tests @MockitoBean the service, so coverage will NOT arrive from callers."
    ;;
esac

# ---- B7 entity placement + shape ----------------------------------------------
if has '^@Entity'; then
  [ "$IS_ENT" = 1 ] || warn "@Entity outside internal/persistence/entity/ — move it there"
  case "$BASE" in *Entity) ;; *) warn "@Entity class '$BASE' must carry the Entity suffix" ;; esac
  has 'FetchType\.EAGER' && warn "FetchType.EAGER — always LAZY; fetch via @EntityGraph or a join fetch"
fi
if has 'interface[[:space:]].*extends[[:space:]].*(JpaRepository|CrudRepository|JpaSpecificationExecutor)'; then
  case "$REL" in */internal/persistence/repo/*) ;; *) warn "Spring Data repository outside internal/persistence/repo/" ;; esac
fi

# ---- B8 logging + i18n --------------------------------------------------------
has 'System\.(out|err)\.print|\.printStackTrace\(\)' && warn "use SLF4J (LoggerFactory.getLogger), never System.out / printStackTrace"
has 'message[[:space:]]*=[[:space:]]*"[^{]' && warn "Bean Validation message must be \"{key}\" referencing i18n/messages.properties, and the key must exist in all six locale files"

hook_finish "backend-conventions" "Reference: .claude/patterns/README.md"
