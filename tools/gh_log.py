#!/usr/bin/env python3
"""Fetch the failing job log for a GitHub Actions run (mira-ai-voice-assistant).
Usage: python3 gh_log.py <run_id>
Uses the same PAT surrogate as gh_actions.py. Prints the failed job name and
the compile-error lines from its log."""
import json, os, re, sys, urllib.request, urllib.error

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/templates/skill/bin")
from dynamic_credentials import add_surrogate_to_request, read_response_body

REPO = "ryoaonetsuki/mira-ai-voice-assistant"
BASE = "https://api.github.com"
CREDENTIAL = "custom.github-pat"


def call(method, path, accept="application/vnd.github+json"):
    headers = {"Accept": accept, "X-GitHub-Api-Version": "2022-11-28"}
    req = urllib.request.Request(BASE + path, headers=headers, method=method)
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return read_response_body(resp)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} on {path}: {exc.read().decode('utf-8', 'replace')[:500]}")


def main():
    run_id = sys.argv[1]
    jobs = json.loads(call("GET", f"/repos/{REPO}/actions/runs/{run_id}/jobs?per_page=20").decode())
    for job in jobs.get("jobs", []):
        print(f"job {job['id']} {job['name']} conclusion={job.get('conclusion')}")
    failed = [j for j in jobs.get("jobs", []) if j.get("conclusion") == "failure"]
    if not failed:
        print("no failed jobs")
        return 0
    job = failed[0]
    # logs endpoint returns 302 to blob storage; follow without auth header
    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(_NoRedirect)
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    req = urllib.request.Request(BASE + f"/repos/{REPO}/actions/jobs/{job['id']}/logs", headers=headers, method="GET")
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        opener.open(req, timeout=120)
        raise RuntimeError("log download did not redirect")
    except urllib.error.HTTPError as exc:
        loc = exc.headers.get("Location")
        if not loc:
            raise
    with urllib.request.urlopen(loc, timeout=300) as resp:
        log = resp.read().decode("utf-8", "replace")
    print(f"log bytes: {len(log)}")
    open(f"/tmp/gh_log_{run_id}.txt", "w").write(log)
    # print error lines
    err_lines = [l for l in log.splitlines() if re.search(r"\be: |error:|FAILED|FAILURE|BUILD FAILED|Unresolved reference|Type mismatch|Overload resolution", l)]
    print(f"--- error lines ({len(err_lines)}) ---")
    for l in err_lines[:40]:
        print(l[:500])
    return 0


if __name__ == "__main__":
    sys.exit(main())
