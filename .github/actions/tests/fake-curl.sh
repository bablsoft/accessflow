#!/usr/bin/env bash
# curl test double for the action test harness (deployment-gate-test.sh). Serves canned
# responses in call order from $MOCK_DIR/responses/<n> (falling back to responses/default) and
# logs "<METHOD> <URL>" to $MOCK_DIR/calls.log. A response file's first line is the HTTP status
# code (or "EXIT <n>" to simulate a curl-level failure); the rest is the body. Honors the
# `-w '\n%{http_code}'` contract the action scripts use: prints body, newline, code.
set -euo pipefail
: "${MOCK_DIR:?}"

method="GET"
url=""
data=""
args=("$@")
i=0
while [ "$i" -lt "${#args[@]}" ]; do
  case "${args[$i]}" in
    -X) i=$((i + 1)); method="${args[$i]}" ;;
    -d) i=$((i + 1)); data="${args[$i]}" ;;
    -H | -w) i=$((i + 1)) ;;
    -*) ;;
    http://* | https://*) url="${args[$i]}" ;;
  esac
  i=$((i + 1))
done

n=$(( $(cat "$MOCK_DIR/n" 2>/dev/null || echo 0) + 1 ))
echo "$n" >"$MOCK_DIR/n"
echo "$method $url" >>"$MOCK_DIR/calls.log"
if [ -n "$data" ]; then
  mkdir -p "$MOCK_DIR/bodies"
  printf '%s' "$data" >"$MOCK_DIR/bodies/$n"
fi

f="$MOCK_DIR/responses/$n"
if [ ! -f "$f" ]; then f="$MOCK_DIR/responses/default"; fi
if [ ! -f "$f" ]; then
  echo "fake-curl: no scripted response #$n (and no default) for: $method $url" >&2
  exit 97
fi

code="$(head -n1 "$f")"
if [[ "$code" == EXIT* ]]; then
  exit "${code#EXIT }"
fi
body="$(tail -n +2 "$f")"
printf '%s\n%s' "$body" "$code"
