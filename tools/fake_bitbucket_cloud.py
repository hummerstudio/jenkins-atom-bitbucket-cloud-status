#!/usr/bin/env python3
"""A Bitbucket Cloud stand-in for the smoke: it re-derives the contract instead of believing the atom.

Two endpoints, the two the plugin uses:

    POST /site/oauth2/access_token
         Authorization: Basic base64(consumerKey:consumerSecret)
         grant_type=client_credentials                       -> {"access_token", "expires_in", "scopes", "token_type"}
    POST /2.0/repositories/{workspace}/{repo}/commit/{sha}/statuses/build
         Authorization: Bearer <token>, application/json
         {"state", "key", "url"[, "name"][, "description"]}   -> 201, the stored status
    GET  /2.0/repositories/{workspace}/{repo}/commit/{sha}/statuses/build/{key}
         Authorization: Bearer <token>                        -> 200 the stored status / 404

On every request it checks the path, the credential, the JSON shape, the state vocabulary and the field set itself, and
stores exactly what it hands back — so a read-back by the client is a real read-back. The token is derived from the
consumer (never a constant) so check.py can recompute it. --mode injects the ways a server or a proxy lies:

    lying-state      201, echo the sent body, store state FAILED
    lying-drop       201, echo the sent body, store nothing
    readback-stale   store the new state, but hand out INPROGRESS on read-back
    readback-500     the write is fine, the read-back endpoint is broken
    bad-credentials  the token endpoint answers 401
    token-500        the token endpoint answers 500
    scope-403        the status POST answers 403
    expired          the status POST answers 401

    python3 tools/fake_bitbucket_cloud.py --port 0 --dir DIR --consumer-key K --consumer-secret S [--mode M]
prints "listening <port>" once it is up.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATES = {"SUCCESSFUL", "FAILED", "INPROGRESS"}
FIELDS = {"state", "key", "url", "name", "description"}
REQUIRED = {"state", "key", "url"}
BUILD = re.compile(r"^/2\.0/repositories/([^/]+)/([^/]+)/commit/([^/]+)/statuses/build$")
SINGLE = re.compile(r"^/2\.0/repositories/([^/]+)/([^/]+)/commit/([^/]+)/statuses/build/([^/]+)$")
TOKEN_PATH = "/site/oauth2/access_token"

LOCK = threading.Lock()


def token_for(key, secret):
    return "tok-" + hashlib.sha256(f"{key}:{secret}".encode("utf-8")).hexdigest()[:32]


class Store:
    def __init__(self, path):
        self.path = path
        self.entries = {}
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                self.entries = json.load(handle)

    def put(self, sha, entry):
        with LOCK:
            self.entries[f"{sha}/{entry['key']}"] = entry
            with open(self.path, "w", encoding="utf-8") as handle:
                json.dump(self.entries, handle, ensure_ascii=False, indent=2, sort_keys=True)

    def get(self, sha, key):
        with LOCK:
            return self.entries.get(f"{sha}/{key}")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    args = None
    store = None
    logpath = None

    def log_message(self, fmt, *args):  # keep the stub's stdout for "listening"
        pass

    # ---- plumbing ----------------------------------------------------------------------------------------------
    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode("utf-8") if length else ""

    def record(self, body):
        line = "\t".join([
            self.command,
            self.path,
            self.headers.get("Authorization", ""),
            self.headers.get("Content-Type", ""),
            (body or "").replace("\t", " ").replace("\n", " "),
        ])
        with LOCK:
            with open(self.logpath, "a", encoding="utf-8") as handle:
                handle.write(line + "\n")

    def reply(self, code, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def error(self, code, message, error_code="stub"):
        self.reply(code, {"type": "error", "error": {"message": message, "code": error_code}})

    def bearer(self):
        header = self.headers.get("Authorization", "")
        return header[7:] if header.startswith("Bearer ") else ""

    # ---- endpoints ---------------------------------------------------------------------------------------------
    def do_POST(self):
        body = self.body()
        self.record(body)
        if urllib.parse.urlsplit(self.path).path == TOKEN_PATH:
            return self.token(body)
        match = BUILD.match(urllib.parse.urlsplit(self.path).path)
        if match:
            return self.status(match, body)
        return self.error(404, f"no route for {self.path}")

    def do_GET(self):
        self.record("")
        match = SINGLE.match(urllib.parse.urlsplit(self.path).path)
        if not match:
            return self.error(404, f"no route for {self.path}")
        if self.bearer() != self.expected_token:
            return self.error(401, "Invalid token")
        workspace, repo, sha, key = (urllib.parse.unquote(part) for part in match.groups())
        if self.args.mode == "readback-500":
            return self.error(500, "read-back is broken in this mode")
        entry = self.store.get(sha, key)
        if entry is None:
            return self.error(404, f"No build status found for key {key}")
        if self.args.mode == "readback-stale":
            entry = dict(entry)
            entry["state"] = "INPROGRESS"
        return self.reply(200, entry)

    def token(self, body):
        if self.args.mode == "bad-credentials":
            return self.error(401, "Invalid OAuth client credentials")
        if self.args.mode == "token-500":
            return self.error(500, "the token endpoint is down")
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return self.error(401, "Basic authentication with the consumer key/secret is required")
        try:
            decoded = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return self.error(401, "Basic header is not base64")
        if decoded != f"{self.args.consumer_key}:{self.args.consumer_secret}":
            return self.error(401, "Unknown consumer")
        if "application/x-www-form-urlencoded" not in (self.headers.get("Content-Type") or ""):
            return self.error(400, "the token request must be form-encoded")
        form = urllib.parse.parse_qs(body, keep_blank_values=True)
        if form.get("grant_type") != ["client_credentials"]:
            return self.error(400, "grant_type must be client_credentials")
        return self.reply(200, {
            "access_token": self.expected_token,
            "expires_in": 7200,
            "scopes": "repository",
            "token_type": "bearer",
        })

    def status(self, match, body):
        workspace, repo, sha = (urllib.parse.unquote(part) for part in match.groups()[:3])
        if self.args.mode == "expired":
            return self.error(401, "Token is expired")
        if self.args.mode == "scope-403":
            return self.error(403, "Your credentials lack one or more required privilege scopes.")
        if self.bearer() != self.expected_token:
            return self.error(401, "Invalid token")
        if "application/json" not in (self.headers.get("Content-Type") or ""):
            return self.error(415, "Content-Type must be application/json")
        try:
            payload = json.loads(body)
        except Exception:
            return self.error(400, "Body is not JSON")
        if not isinstance(payload, dict):
            return self.error(400, "Body must be a JSON object")
        unknown = set(payload) - FIELDS
        if unknown:
            return self.error(400, f"Unexpected fields: {sorted(unknown)}")
        missing = REQUIRED - set(payload)
        if missing:
            return self.error(400, f"Missing fields: {sorted(missing)}")
        if payload["state"] not in STATES:
            return self.error(400, f"state must be one of {sorted(STATES)}")
        if not isinstance(payload["key"], str) or not payload["key"]:
            return self.error(400, "key must be a non-empty string")
        if not isinstance(payload["url"], str) or not payload["url"]:
            return self.error(400, "url must be a non-empty string")
        for field in ("name", "description"):
            if field in payload and not isinstance(payload[field], str):
                return self.error(400, f"{field} must be a string")

        if self.args.expect_sha and sha != self.args.expect_sha:
            return self.error(404, f"Repository {workspace}/{repo} has no commit {sha}")

        stored = dict(payload)
        stored.update({
            "type": "build",
            "created_on": "2026-11-01T02:00:00.000000+00:00",
            "updated_on": "2026-11-01T02:00:00.000000+00:00",
            "links": {"self": {"href": f"http://127.0.0.1/{workspace}/{repo}/{sha}/{payload['key']}"}},
        })
        mode = self.args.mode
        if mode == "lying-state":
            stored["state"] = "FAILED" if payload["state"] != "FAILED" else "SUCCESSFUL"
            self.store.put(sha, stored)
        elif mode == "lying-drop":
            pass
        elif mode != "readback-500":
            self.store.put(sha, stored)
        return self.reply(201, stored)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--mode", default="normal")
    parser.add_argument("--consumer-key", required=True)
    parser.add_argument("--consumer-secret", required=True)
    parser.add_argument("--expect-sha", default="")
    parser.add_argument("--direct-token", default="", help="accept this pre-issued token instead of a derived one")
    args = parser.parse_args()

    os.makedirs(args.dir, exist_ok=True)
    Handler.args = args
    Handler.expected_token = args.direct_token or token_for(args.consumer_key, args.consumer_secret)
    Handler.logpath = os.path.join(args.dir, f"log-{os.getpid()}.tsv")
    Handler.store = Store(os.path.join(args.dir, f"store-{os.getpid()}.json"))
    open(Handler.logpath, "a", encoding="utf-8").close()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    port = server.server_address[1]
    # the harness reads these two lines: the port, and the store/log files this server will write
    print(f"listening {port}", flush=True)
    print(f"store {Handler.store.path}", flush=True)
    print(f"log {Handler.logpath}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
