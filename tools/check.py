#!/usr/bin/env python3
"""Second implementation of the contract, in Python, used to judge one atom run.

It does not import the atom and does not trust output.json: it recomputes the workspace/repo, the token, the target URL
and the JSON body from the run's own input.json (the rules read out of bitbucket-build-status-notifier 1.4.2), then
compares that against

  * the requests the fake Bitbucket Cloud actually received (log-*.tsv),
  * the status the fake Bitbucket Cloud actually stored (store-*.json),
  * what the atom reported (output.json).

    python3 tools/check.py --run DIR --store store.json --log log.tsv --sha SHA \
        --expect success|error:<code> [--state S] [--no-verify] [--no-request] \
        [--consumer-key K --consumer-secret S] [--access-token T]

--state pins the state for runs whose input pins it; --no-verify asserts that no read-back was made; --no-request
asserts that nothing was written at all (credential failures).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import urllib.parse

STATES = {"SUCCESSFUL", "FAILED", "INPROGRESS"}
SCP_LIKE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(.+)$")
SCP = re.compile(r"^(?:[^@/]+@)?([^/:]+):(?!//)(.+)$")
VAR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.-]*)}")


class Fail(Exception):
    pass


def truthy(value):
    return str(value).strip().lower() == "true"


def load(path, default=None):
    if not path or not os.path.exists(path) or os.path.getsize(path) == 0:
        return default
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def expand(given, text):
    def value(match):
        return str(given.get(match.group(1), ""))
    return VAR.sub(value, text or "")


def split_repository(url):
    path = ""
    if url:
        candidate = url.strip()
        if "://" in candidate:
            match = SCP_LIKE.match(candidate)
            if match:
                path = match.group(2)
        elif SCP.match(candidate):
            path = SCP.match(candidate).group(2)
    path = path.strip("/")
    if path.endswith(".git"):
        path = path[:-4]
    parts = path.split("/")
    if len(parts) < 2:
        return "", ""
    return parts[-2], parts[-1]


def expected_state(raw):
    """BitbucketBuildStatusHelper.guessBitbucketBuildState + the step's pass-through, re-derived."""
    value = (raw or "").strip().upper()
    if value in ("", "INPROGRESS", "IN_PROGRESS", "BUILDING", "RUNNING", "PENDING"):
        return "INPROGRESS"
    if value in ("SUCCESS", "SUCCESSFUL", "OK", "PASSED"):
        return "SUCCESSFUL"
    if value in ("FAILED", "FAILURE", "ERROR", "UNSTABLE", "WARNING", "ABORTED", "CANCELED", "CANCELLED",
                 "NOT_BUILT", "NOTBUILT"):
        return "FAILED"
    return None


def token_for(key, secret):
    return "tok-" + hashlib.sha256(f"{key}:{secret}".encode()).hexdigest()[:32]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--expect", required=True, help="success or error:<code>")
    parser.add_argument("--state", default="")
    parser.add_argument("--no-verify", action="store_true")
    parser.add_argument("--no-request", action="store_true")
    parser.add_argument("--consumer-key", default="")
    parser.add_argument("--consumer-secret", default="")
    parser.add_argument("--access-token", default="")
    args = parser.parse_args()

    given = load(os.path.join(args.run, "input.json"), {})
    output = load(os.path.join(args.run, "output.json"), {})
    status = output.get("status")
    message = output.get("message") or ""
    data = {k: (v.get("value") if isinstance(v, dict) else v) for k, v in (output.get("data") or {}).items()}

    lines = []
    if os.path.exists(args.log):
        with open(args.log, encoding="utf-8") as handle:
            lines = [line.rstrip("\n").split("\t") for line in handle if line.strip()]

    if args.expect.startswith("error:"):
        code = args.expect.split(":", 1)[1]
        if status != "error":
            raise Fail(f"expected error {code}, got {status}: {message}")
        if str(output.get("errorCode")) != code:
            raise Fail(f"expected error code {code}, got {output.get('errorCode')}: {message}")
        if args.no_request:
            writes = [line for line in lines if line[0] == "POST" and "/statuses/build" in line[1]]
            if writes:
                raise Fail(f"nothing should have been written, saw {len(writes)} status POST(s)")
        print(f"ok: error {code} - {message[:90]}")
        return

    if status != "success":
        raise Fail(f"expected success, got {status}: {message}")

    api = (given.get("apiUrl") or "https://api.bitbucket.org").rstrip("/")
    workspace = given.get("workspace") or given.get("owner") or given.get("account") or ""
    repo = given.get("repoSlug") or given.get("repo") or ""
    url = given.get("GIT_URL") or ""
    git_owner, git_repo = split_repository(url)
    workspace = workspace or git_owner
    repo = repo or git_repo
    sha = given.get("sha") or given.get("commitId") or given.get("GIT_COMMIT") or args.sha

    raw_state = args.state or given.get("state") or given.get("BUILD_STATUS") or ""
    state = expected_state(raw_state)
    if state is None:
        raise Fail(f"the input state {raw_state!r} has no Bitbucket state; the atom was supposed to reject it")

    override = truthy(given.get("overrideLatestBuild", "false"))
    job = given.get("JOB_NAME") or ""
    number = given.get("BUILD_NUMBER") or ""
    key = expand(given, given.get("key") or "")
    name = expand(given, given.get("name") or "")
    description = expand(given, given.get("description") or "")
    target_url = expand(given, given.get("buildUrl") or given.get("BUILD_URL") or "")
    if not key:
        seed = job if override else f"{job}#{number}"
        key = hashlib.md5(seed.encode()).hexdigest()
    if not name:
        name = job if override else (job if not number else f"{job} #{number}")
    if not target_url:
        raise Fail("this scenario has no build url; the atom was supposed to reject it")

    expected = {"state": state, "key": key, "url": target_url}
    if name:
        expected["name"] = name
    if description:
        expected["description"] = description

    target = (f"{api}/2.0/repositories/{urllib.parse.quote(workspace, safe='')}/"
              f"{urllib.parse.quote(repo, safe='')}/commit/{urllib.parse.quote(sha, safe='')}/statuses/build")
    readback = target + "/" + urllib.parse.quote(key, safe="")

    if data.get("state") != state:
        raise Fail(f"output.json says state={data.get('state')!r}, expected {state!r}")
    if data.get("key") != key:
        raise Fail(f"output.json key={data.get('key')!r}, expected {key!r}")
    if data.get("apiUrl") != target:
        raise Fail(f"output.json apiUrl={data.get('apiUrl')!r}, expected {target!r}")
    expected_verified = "not-checked" if args.no_verify else state
    if data.get("verified") != expected_verified:
        raise Fail(f"output.json verified={data.get('verified')!r}, expected {expected_verified!r}")
    if data.get("repo") != f"{workspace}/{repo}":
        raise Fail(f"output.json repo={data.get('repo')!r}, expected {workspace}/{repo}")

    posts = [line for line in lines if line[0] == "POST" and line[1] == urllib.parse.urlsplit(target).path]
    if len(posts) != 1:
        raise Fail(f"expected exactly one POST to {target}, saw {len(posts)}: {[l[1] for l in lines]}")
    gets = [line for line in lines if line[0] == "GET" and line[1] == urllib.parse.urlsplit(readback).path]
    expected_gets = 0 if args.no_verify else 1
    if len(gets) != expected_gets:
        raise Fail(f"expected {expected_gets} read-back GET(s), saw {len(gets)}")

    if args.access_token:
        tokens = [line for line in lines if line[0] == "POST" and line[1].endswith("/oauth2/access_token")]
        if tokens:
            raise Fail("an access token was supplied, but the atom still exchanged one")
        expected_bearer = args.access_token
    else:
        tokens = [line for line in lines if line[0] == "POST" and line[1] == "/site/oauth2/access_token"]
        if len(tokens) != 1:
            raise Fail(f"expected one token request, saw {len(tokens)}")
        basic = "Basic " + base64.b64encode(f"{args.consumer_key}:{args.consumer_secret}".encode()).decode()
        if tokens[0][2] != basic:
            raise Fail(f"the token request used {tokens[0][2]!r}, expected {basic!r}")
        if tokens[0][4] != "grant_type=client_credentials":
            raise Fail(f"the token request body is {tokens[0][4]!r}")
        expected_bearer = token_for(args.consumer_key, args.consumer_secret)

    if posts[0][2] != "Bearer " + expected_bearer:
        raise Fail(f"the status POST carried {posts[0][2]!r}")
    if "application/json" not in posts[0][3]:
        raise Fail(f"the status POST content-type is {posts[0][3]!r}")
    sent = json.loads(posts[0][4])
    if sent != expected:
        raise Fail(f"sent payload {json.dumps(sent, sort_keys=True)} != expected {json.dumps(expected, sort_keys=True)}")

    stored = (load(args.store, {}) or {}).get(f"{sha}/{key}")
    if stored is None:
        raise Fail("the server stored nothing for this commit/key")
    stored_payload = {k: stored[k] for k in expected}
    if stored_payload != expected:
        raise Fail(f"stored payload {json.dumps(stored_payload, sort_keys=True)}"
                   f" != expected {json.dumps(expected, sort_keys=True)}")
    print(f"ok: {state} key={key} url={target} verified={expected_verified}")


if __name__ == "__main__":
    try:
        main()
    except Fail as failure:
        print(f"check failed: {failure}", file=sys.stderr)
        sys.exit(1)
