#!/usr/bin/env bash
# Shared fixtures for the offline action-script tests (deployment-gate-test.sh,
# query-actions-test.sh, ci-templates-test.sh). Sourced, not executed: it stubs curl with
# fake-curl.sh (canned responses per call, call log) on PATH and provides the scenario / response /
# assertion helpers — no network, no backend. Each test file ends with `report`.
set -euo pipefail

tests_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cp "$tests_dir/fake-curl.sh" "$tmp/bin/curl"
chmod +x "$tmp/bin/curl"
export PATH="$tmp/bin:$PATH"

failures=0

# scenario NAME — start a fresh mock directory (call counter, call log, GITHUB_OUTPUT).
scenario() {
  MOCK_DIR="$tmp/scenario-$1"
  mkdir -p "$MOCK_DIR/responses"
  export MOCK_DIR
  export GITHUB_OUTPUT="$MOCK_DIR/github-output"
  : >"$GITHUB_OUTPUT"
  echo "--- $1"
}

# resp N CODE JSON… — script the Nth curl call's response (first line = HTTP code or "EXIT n").
resp() {
  local n="$1" code="$2"
  shift 2
  { echo "$code"; printf '%s\n' "$*"; } >"$MOCK_DIR/responses/$n"
}

# hdr N HEADER… — script the Nth call's response headers (served through curl's `-D`), e.g. the
# Retry-After a 429 carries (#873). Calls without a sidecar get a bare status line.
hdr() {
  local n="$1"
  shift
  mkdir -p "$MOCK_DIR/headers"
  { printf 'HTTP/1.1 429 Too Many Requests\r\n'; printf '%s\r\n' "$@"; printf '\r\n'; } >"$MOCK_DIR/headers/$n"
}

# run SCRIPT — execute it from the scenario's mock directory (so a script that writes a file
# into its working directory lands there), stdout+stderr captured in $run_log, exit code in
# $run_rc.
# shellcheck disable=SC2034  # read by the sourcing test file
run() {
  set +e
  run_log="$(cd "$MOCK_DIR" && "$1" 2>&1)"
  run_rc=$?
  set -e
}

# Indent a captured multi-line log for failure output (sed is the right tool here).
# shellcheck disable=SC2001
dump_log() { sed 's/^/    | /' <<<"$run_log"; }

assert_eq() {
  if [ "$2" = "$3" ]; then return 0; fi
  echo "FAIL: $1 — expected '$2', got '$3'"
  dump_log
  failures=$((failures + 1))
}

assert_log_contains() {
  if grep -qF "$1" <<<"$run_log"; then return 0; fi
  echo "FAIL: log does not contain '$1'"
  dump_log
  failures=$((failures + 1))
}

assert_log_not_contains() {
  if ! grep -qF "$1" <<<"$run_log"; then return 0; fi
  echo "FAIL: log unexpectedly contains '$1'"
  dump_log
  failures=$((failures + 1))
}

assert_output() {
  if grep -qxF "$1" "$GITHUB_OUTPUT"; then return 0; fi
  echo "FAIL: GITHUB_OUTPUT does not contain '$1' — got:"
  sed 's/^/    | /' "$GITHUB_OUTPUT"
  failures=$((failures + 1))
}

# calls PATTERN — how many logged "<METHOD> <URL>" lines match the extended regex.
calls() {
  if [ -f "$MOCK_DIR/calls.log" ]; then grep -cE "$1" "$MOCK_DIR/calls.log" || true; else echo 0; fi
}

# assert_body_field N JQ_FILTER EXPECTED — pin the wire format of the Nth call's JSON body.
assert_body_field() {
  local actual
  actual="$(jq -r "$2" "$MOCK_DIR/bodies/$1" 2>/dev/null || echo '<unparseable>')"
  assert_eq "body #$1 field $2" "$3" "$actual"
}

# report — exit non-zero when any assertion failed.
report() {
  if [ "$failures" -gt 0 ]; then
    echo "$failures assertion(s) failed"
    exit 1
  fi
  echo "All ${1:-action script} tests passed."
}
