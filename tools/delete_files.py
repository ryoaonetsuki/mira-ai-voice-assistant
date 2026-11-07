#!/usr/bin/env python3
"""Delete files from ryoaonetsuki/mira-ai-voice-assistant via GitHub Contents
API DELETE (push.py only creates/updates). Usage:
  python3 delete_files.py "path/one.kt" "path/two.kt" --message "msg"
Auth: stored custom.github-pat credential via authd surrogate (same pattern
as push.py). Never prints credentials."""
import json, sys, time, urllib.request, urllib.error
sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/templates/skill/bin")
from dynamic_credentials import add_surrogate_to_request, read_response_body, DynamicCredentialError

BASE = "https://api.github.com"
REPO = "ryoaonetsuki/mira-ai-voice-assistant"
BRANCH = "main"
CREDENTIAL = "custom.github-pat"

class GhError(Exception):
    def __init__(self, code, body):
        super().__init__(f"HTTP {code}: {body[:500]}")
        self.code = code; self.body = body

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None: headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            text = read_response_body(resp).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise GhError(exc.code, exc.read().decode("utf-8", "replace"))
    return json.loads(text) if text.strip() else {}

def main():
    args, msg = [], "Delete files"
    argv = sys.argv[1:]
    i = 0
    while i < len(argv):
        if argv[i] == "--message" and i + 1 < len(argv):
            msg = argv[i + 1]; i += 2
        else:
            args.append(argv[i]); i += 1
    if not args:
        print("usage: delete_files.py <path>... [--message msg]", file=sys.stderr); return 2
    for rel in args:
        try:
            cur = call("GET", f"/repos/{REPO}/contents/{rel}?ref={BRANCH}")
            sha = cur.get("sha")
        except GhError as exc:
            if exc.code == 404:
                print(f"  already gone: {rel}"); continue
            raise
        res = call("DELETE", f"/repos/{REPO}/contents/{rel}",
                   {"message": msg, "sha": sha, "branch": BRANCH})
        print(f"  deleted {rel} -> {res.get('commit', {}).get('sha', '?')[:7]}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
