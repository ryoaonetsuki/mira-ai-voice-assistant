#!/usr/bin/env python3
"""Push a local directory to ryoaonetsuki/mira-ai-voice-assistant via GitHub
Contents API single-file PUTs (reliable; the full-tree blob POST path 500s).

Bodies are sent via urllib (NOT via argv) so large files (PNG icons) do not
hit the OS argument-length limit.

Usage:
  python3 push.py [root] [message] [--only path1,path2,...]

- Creates/updates each file individually (needs the file's current SHA for updates).
- Retries transient 5xx / network errors up to 5 times with backoff.
- Skips: .git, build/, .gradle/, local.properties, *.apk, __pycache__.
- Prints a summary; exits non-zero on any unrecovered failure.
"""
import base64, json, os, sys, time, urllib.request, urllib.error

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/templates/skill/bin")
from dynamic_credentials import add_surrogate_to_request, read_response_body, DynamicCredentialError

ROOT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else os.path.expanduser("~/workspace/mira-final")
MSG = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else "Mira AI update"
REPO = "ryoaonetsuki/mira-ai-voice-assistant"
BRANCH = "main"
BASE = "https://api.github.com"
CREDENTIAL = "custom.github-pat"

EXCLUDE_DIRS = {".git", "build", ".gradle", ".idea", "__pycache__", ".dart_tool"}
EXCLUDE_FILES = {"local.properties", "logo-alt-blue.png"}
EXCLUDE_SUFFIX = (".apk", ".pyc")

only = None
for a in sys.argv[1:]:
    if a.startswith("--only="):
        only = set(a[len("--only="):].split(","))


class GhError(Exception):
    def __init__(self, code, body):
        super().__init__(f"HTTP {code}: {body[:500]}")
        self.code = code
        self.body = body


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    add_surrogate_to_request(req, CREDENTIAL, allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            text = read_response_body(resp).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise GhError(exc.code, exc.read().decode("utf-8", "replace"))
    return json.loads(text) if text.strip() else {}


def gh(method, path, body=None, tries=5):
    for attempt in range(tries):
        try:
            return call(method, path, body)
        except GhError as exc:
            transient = exc.code >= 500 or exc.code == 429
            if attempt < tries - 1 and transient:
                time.sleep(2 ** attempt * 2)
                continue
            raise
        except (DynamicCredentialError, OSError) as exc:
            if attempt < tries - 1:
                time.sleep(2 ** attempt * 2)
                continue
            raise


def collect():
    files = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for fn in filenames:
            if fn in EXCLUDE_FILES or fn.endswith(EXCLUDE_SUFFIX):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            if only and rel not in only:
                continue
            files.append((rel, full))
    return sorted(files)


def main():
    files = collect()
    print(f"{len(files)} files to push to {REPO}@{BRANCH}")
    ok, skipped = 0, 0
    for rel, full in files:
        with open(full, "rb") as f:
            data = f.read()
        sha = None
        try:
            cur = call("GET", f"/repos/{REPO}/contents/{rel}?ref={BRANCH}")
            if isinstance(cur, dict) and cur.get("sha"):
                existing = base64.b64decode(cur.get("content", "").replace("\n", "")) if cur.get("content") else b""
                if existing == data:
                    skipped += 1
                    continue
                sha = cur["sha"]
        except GhError as exc:
            if exc.code != 404:
                raise
        body = {"message": MSG, "content": base64.b64encode(data).decode(), "branch": BRANCH}
        if sha:
            body["sha"] = sha
        res = gh("PUT", f"/repos/{REPO}/contents/{rel}", body)
        print(f"  {'updated' if sha else 'created'} {rel} -> {res.get('commit', {}).get('sha', '?')[:7]}", flush=True)
        ok += 1
    print(f"Done: {ok} pushed, {skipped} unchanged")


if __name__ == "__main__":
    main()
