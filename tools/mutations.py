#!/usr/bin/env python3
"""Prove the smoke can fail: plant one bug at a time in the atom and require the scenario that should notice to go red.

Each mutation is a text edit in BitbucketCloudStatusAtom.java; the smoke is run with it in place and must fail with a
message from the scenario named in `expected`. The file is always restored, even when a mutation slips through (that is
reported as a failure, not an exception).

    python3 tools/mutations.py
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "src/main/java/com/jenkinschina/atom/bitbucketcloudstatus/BitbucketCloudStatusAtom.java")


def always_send_optional_fields(source):
    old = ('        if (!name.isEmpty()) {\n'
           '            payload.put("name", name);\n'
           '        }\n'
           '        if (!description.isEmpty()) {\n'
           '            payload.put("description", description);\n'
           '        }')
    new = '        payload.put("name", name);\n        payload.put("description", description);'
    return source.replace(old, new)


MUTATIONS = [
    {
        "name": "trust-201",
        "why": "skip the read-back and believe the 201",
        "edit": lambda s: s.replace('context.getBooleanParam("verifyAfterWrite", true)',
                                    'context.getBooleanParam("verifyAfterWrite", false)'),
        "expected": ("green", "lying-state"),
    },
    {
        "name": "raw-state",
        "why": "send the Jenkins result verbatim instead of mapping it",
        "edit": lambda s: s.replace(
            '        String state = mapState(rawState);',
            '        String state = rawState.trim().toUpperCase(Locale.ROOT).isEmpty()'
            '\n                ? "INPROGRESS" : rawState.trim().toUpperCase(Locale.ROOT);'),
        "expected": ("green", "unstable"),
    },
    {
        "name": "plain-key",
        "why": "use the job name as the key instead of the plugin's md5",
        "edit": lambda s: s.replace('key = overrideLatest ? md5(job) : md5(job + "#" + number);', 'key = job;'),
        "expected": ("green",),
    },
    {
        "name": "empty-fields",
        "why": "always send name/description, even when empty (the plugin omits them)",
        "edit": always_send_optional_fields,
        "expected": ("green",),
    },
]


def run_smoke():
    proc = subprocess.run(["./tools/smoke.sh"], cwd=ROOT, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


def main():
    original = open(SOURCE, encoding="utf-8").read()
    failures = []
    try:
        for mutation in MUTATIONS:
            mutated = mutation["edit"](original)
            if mutated == original:
                failures.append(f"{mutation['name']}: the edit did not apply (the source moved)")
                print(f"FAIL {mutation['name']}: edit did not apply", flush=True)
                continue
            open(SOURCE, "w", encoding="utf-8").write(mutated)
            code, output = run_smoke()
            expected = mutation["expected"]
            found = [name for name in expected if name in output]
            if code == 0:
                failures.append(f"{mutation['name']}: the smoke passed with the bug in place")
                print(f"FAIL {mutation['name']}: smoke passed with the bug in place", flush=True)
            elif not found:
                failures.append(f"{mutation['name']}: smoke failed, but not on {expected}")
                print(f"FAIL {mutation['name']}: red, but not on {expected}\n{output[-800:]}", flush=True)
            else:
                line = [l for l in output.splitlines() if any(name in l for name in expected)][-1]
                print(f"ok {mutation['name']}: red on {found[0]} — {line.strip()[:120]}", flush=True)
    finally:
        open(SOURCE, "w", encoding="utf-8").write(original)
    build = subprocess.run(["./build.sh"], cwd=ROOT, capture_output=True, text=True)
    if build.returncode != 0:
        failures.append("the restored source does not build: " + build.stderr[-400:])
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("mutations: all four were caught by the scenario they should break")
    return 0


if __name__ == "__main__":
    sys.exit(main())
