#!/usr/bin/env bash
# One fake Bitbucket Cloud per scenario. Each server re-checks the request itself and stores exactly what it hands
# back, so the atom's read-back is a real read-back. A lying server must be caught; a rejected input must send nothing.
set -uo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="${TMPDIR:-/tmp}/bitbucket-cloud-smoke-$$"
mkdir -p "$work"
sha="a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
ckey="ck-123"
csecret="cs-456"
server_pid=""
port=""
store=""
logfile=""
base=""

export JOB_NAME=parcel BUILD_NUMBER=42 BUILD_URL="http://jenkins/job/parcel/42/display/redirect"
export GIT_URL="git@bitbucket.org:acme/parcel.git"
export GIT_COMMIT="$sha"
export BUILD_STATUS=SUCCESS

stop() {
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null
  wait "$server_pid" 2>/dev/null
  server_pid=""
  : >"$logfile" 2>/dev/null || true
}
trap stop EXIT

# serve <mode> [expect-sha] [direct-token]
serve() {
  local out
  port=""; store=""; logfile=""
  out="$work/server-$(date +%s%N).out"
  : >"$out"
  python3 tools/fake_bitbucket_cloud.py --port 0 --dir "$work" --mode "${1:-normal}" \
    --consumer-key "$ckey" --consumer-secret "$csecret" --expect-sha "${2:-$sha}" \
    --direct-token "${3:-}" >"$out" 2>&1 &
  server_pid=$!
  for _ in {1..100}; do
    port="$(sed -n 's/^listening \([0-9]*\)$/\1/p' "$out")"
    store="$(sed -n 's/^store \(.*\)$/\1/p' "$out")"
    logfile="$(sed -n 's/^log \(.*\)$/\1/p' "$out")"
    [ -n "$port" ] && [ -n "$store" ] && [ -n "$logfile" ] && break
    sleep 0.1
  done
  if [ -z "$port" ] || [ -z "$store" ] || [ -z "$logfile" ]; then
    cat "$out" >&2
    fail "the fake Bitbucket Cloud did not start"
  fi
  base="http://127.0.0.1:$port"
}

new_run() { dir="$work/$1"; mkdir -p "$dir"; }
check() { python3 tools/check.py --run "$dir" --store "$store" --log "$logfile" \
  --sha "${sha:-$GIT_COMMIT}" --consumer-key "$ckey" --consumer-secret "$csecret" "$@" \
  || fail "check failed for $dir"; }
contains() { grep -q -- "$2" "$1" || fail "$1 does not mention $2"; }

# input <dir> <json> — merges the shared connection fields into the scenario's own input
input() {
  python3 - "$1" "$2" "$base" "$ckey" <<'PY'
import json, sys
directory, extra, base, key = sys.argv[1], json.loads(sys.argv[2]), sys.argv[3], sys.argv[4]
doc = {"apiUrl": base, "tokenUrl": base + "/site/oauth2/access_token", "consumerKey": key}
doc.update(extra)
json.dump(doc, open(f"{directory}/input.json", "w", encoding="utf-8"), ensure_ascii=False)
PY
}
secret() { printf '{"consumerSecret": "%s"}' "$csecret" >"$dir/.secrets.json"; }

## 1. green build, explicit workspace/repo, client_credentials, plugin defaults for key and name
serve normal
new_run green
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >"$dir/log.txt" 2>&1 || fail "green run failed"
check --expect success --state SUCCESSFUL
contains "$dir/output.json" '"tokenSource"'
contains "$dir/output.json" 'client-credentials'
stop

## 2. UNSTABLE is FAILED — the plugin's guessBitbucketBuildState
export BUILD_STATUS=UNSTABLE
serve normal
new_run unstable
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || fail "unstable run failed"
check --expect success --state FAILED
contains "$store" '"state": "FAILED"'
stop

## 3. explicit state, key/name/description with ${VAR}, INPROGRESS stays INPROGRESS
export BUILD_STATUS=SUCCESS
serve normal
new_run expanded
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\", \"state\": \"INPROGRESS\", \"key\": \"parcel-\${BUILD_NUMBER}\", \"name\": \"parcel build \${BUILD_NUMBER}\", \"description\": \"running on \${JOB_NAME}\"}"
secret
run "$dir" >/dev/null 2>&1 || fail "expanded run failed"
check --expect success --state INPROGRESS
contains "$store" '"key": "parcel-42"'
contains "$store" '"description": "running on parcel"'
stop

## 4. overrideLatestBuild: one status per job, key=md5(job), name=job (the plugin's overrideLatestBuild)
serve normal
new_run override
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\", \"overrideLatestBuild\": \"true\"}"
secret
run "$dir" >/dev/null 2>&1 || fail "override run failed"
check --expect success --state SUCCESSFUL
stop

## 5. a pre-issued access token skips the token exchange entirely
serve normal "$sha" "direct-token"
new_run token
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
printf '{"accessToken": "direct-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "token run failed"
check --expect success --access-token direct-token
grep -q "oauth2/access_token" "$logfile" && fail "an access token was supplied but the atom still exchanged one"
stop

## 6. workspace/repo from GIT_URL (scp shape), sha from GIT_COMMIT
serve normal
new_run derived
input "$dir" "{}"
secret
run "$dir" >/dev/null 2>&1 || fail "derived run failed"
check --expect success
contains "$store" "\"key\": \"$(python3 -c 'import hashlib;print(hashlib.md5(b"parcel#42").hexdigest())')\""
stop

## 7. workspace/repo from an https clone URL with a .git suffix
serve normal
new_run derived-https
input "$dir" "{}"
secret
run "$dir" >/dev/null 2>&1 GIT_URL="https://user@bitbucket.org/acme/parcel.git" || fail "derived-https run failed"
check --expect success
stop

## 8. no GIT_URL and no workspace → USER 40001, nothing sent
serve normal
new_run no-repo
input "$dir" "{\"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 GIT_URL="" || true
check --expect error:40001 --no-request
stop

## 9. no sha and no GIT_COMMIT → USER 40001
serve normal
new_run no-sha
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\"}"
secret
run "$dir" >/dev/null 2>&1 GIT_COMMIT="" || true
check --expect error:40001 --no-request
stop

## 10. no credential at all → USER 40002
serve normal
new_run no-cred
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
printf '{}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || true
check --expect error:40002 --no-request
stop

## 11. an unknown state is rejected before anything is sent (the plugin would forward it to a 400)
serve normal
new_run bad-state
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\", \"state\": \"WAT\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40003 --no-request
stop

## 12. the token endpoint rejects the consumer secret → USER 40002, no status POST
serve bad-credentials
new_run bad-cred
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40002 --no-request
stop

## 13. the token endpoint is down → THIRD_PART 40004
serve token-500
new_run token-down
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40004 --no-request
stop

## 14. the consumer lacks the repository scope → THIRD_PART 40006
serve scope-403
new_run scope
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40006
stop

## 15. an expired token → THIRD_PART 40006
serve expired
new_run expired
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40006
stop

## 16. a 201 that stores another state is caught by the read-back
serve lying-state
new_run lying-state
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40008
stop

## 17. a 201 that stores nothing is caught by the read-back
serve lying-drop
new_run lying-drop
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40008
stop

## 18. a read-back that still shows the old state is caught
serve readback-stale
new_run stale
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40008
stop

## 19. a broken read-back endpoint fails the step (no silent success)
serve readback-500
new_run readback-broken
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40008
stop

## 20. verifyAfterWrite=false: one POST, no GET, verified=not-checked
serve normal
new_run no-verify
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\", \"verifyAfterWrite\": \"false\"}"
secret
run "$dir" >/dev/null 2>&1 || fail "no-verify run failed"
check --expect success --no-verify
stop

## 21. the commit is unknown to Bitbucket → THIRD_PART 40007 (with the server's body in the message)
serve normal "0000000000000000000000000000000000000000"
new_run no-commit
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >/dev/null 2>&1 || true
check --expect error:40007
stop

## 22. the secret must not reach the log, the output, or any request header other than Basic
serve normal
new_run no-leak
input "$dir" "{\"workspace\": \"acme\", \"repoSlug\": \"parcel\", \"sha\": \"$sha\"}"
secret
run "$dir" >"$dir/log.txt" 2>&1 || fail "no-leak run failed"
check --expect success
grep -q "$csecret" "$dir/log.txt" && fail "the consumer secret reached the build log"
grep -q "$csecret" "$dir/output.json" && fail "the consumer secret reached output.json"
grep -q "Bearer $csecret" "$logfile" && fail "the consumer secret was used as a bearer token"
stop

echo "smoke: all scenarios passed"
