#!/usr/bin/env python3
"""Check latest GitHub Actions runs for mira-ai-voice-assistant and download the
debug APK artifact. Reuses the push.py credential helper (custom.github-pat).
Usage:
  python3 gh_actions.py status            -> print latest runs
  python3 gh_actions.py download [run_id] -> download app-debug artifact of latest
                                             successful run (or given run) to
                                             ~/workspace/user/files/mira-ai-debug.apk
"""
import json, os, sys, urllib.request, urllib.error, zipfile, io

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/templates/skill/bin")
from dynamic_credentials import add_surrogate_to_request, read_response_body

REPO = "ryoaonetsuki/mira-ai-voice-assistant"
BASE = "https://api.github.com"
CREDENTIAL = "custom.github-pat"
OUT = os.path.expanduser("~/workspace/user/files/mira-ai-debug.apk")

def call(method, path, accept="application/vnd.github+json", raw=False):
    headers = {"Accept": accept, "X-GitHub-Api-Version": "2022-11-28"}
    req = urllib.request.Request(BASE + path, headers=headers, method=method)
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = read_response_body(resp)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} on {path}: {exc.read().decode('utf-8','replace')[:500]}")
    if raw:
        return body
    return json.loads(body.decode("utf-8", "replace")) if body.strip() else {}

def status():
    data = call("GET", f"/repos/{REPO}/actions/runs?per_page=5")
    for r in data.get("workflow_runs", []):
        print(f"run {r['id']} status={r['status']} conclusion={r.get('conclusion')} branch={r['head_branch']} sha={r['head_sha'][:7]} created={r['created_at']} name={r['name']}")
    return data

def download(run_id=None):
    if run_id is None:
        data = call("GET", f"/repos/{REPO}/actions/runs?per_page=10")
        runs = [r for r in data.get("workflow_runs", []) if r.get("conclusion") == "success"]
        if not runs:
            print("No successful runs found. Latest runs:")
            status()
            return 1
        run_id = runs[0]["id"]
        print(f"Using latest successful run {run_id}")
    arts = call("GET", f"/repos/{REPO}/actions/runs/{run_id}/artifacts")
    names = [a["name"] for a in arts.get("artifacts", [])]
    print("Artifacts:", names)
    art = next((a for a in arts.get("artifacts", []) if a["name"] == "app-debug"), None)
    if not art:
        print("No app-debug artifact on this run")
        return 1
    # Download artifact zip (302 redirect to blob storage; fetch the redirect
    # target WITHOUT the GitHub auth header, which blob storage rejects).
    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    _opener = urllib.request.build_opener(_NoRedirect)
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    req = urllib.request.Request(BASE + f"/repos/{REPO}/actions/artifacts/{art['id']}/zip", headers=headers, method="GET")
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        _opener.open(req, timeout=120)
        raise RuntimeError("Artifact download did not redirect as expected")
    except urllib.error.HTTPError as exc:
        loc = exc.headers.get("Location")
        if not loc:
            raise
    with urllib.request.urlopen(loc, timeout=300) as resp:
        blob = resp.read()
    print(f"Downloaded {len(blob)} bytes")
    z = zipfile.ZipFile(io.BytesIO(blob))
    print("Zip contents:", z.namelist())
    apk = next((n for n in z.namelist() if n.endswith(".apk")), None)
    if not apk:
        print("No APK inside artifact zip")
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(z.read(apk))
    print(f"Saved to {OUT} ({os.path.getsize(OUT)} bytes)")
    return 0

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        status()
    elif cmd == "download":
        sys.exit(download(sys.argv[2] if len(sys.argv) > 2 else None))
    else:
        print("usage: gh_actions.py [status|download [run_id]]")
        sys.exit(2)
