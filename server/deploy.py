#!/usr/bin/env python3
"""Deploy the MBOX Store API worker + ensure its R2 bucket exists.

Usage: python3 deploy.py
- Creates R2 bucket "mbox-store" if missing.
- Injects the admin secret (server/admin_secret.txt) into worker.js.
- PUTs the module worker with the R2 binding (application/javascript+module).
- Enables the workers.dev subdomain on first deploy.
"""
import json
import sys
import urllib.request
import urllib.error
import uuid

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
from dynamic_credentials import dynamic_credential_entry

TOKEN = dynamic_credential_entry("custom.cloudflare")["surrogate"]
ACCT = "14fdcedecc6051d789579b7a0d6820c2"
NAME = "mbox-store-api"
BUCKET = "mbox-store"
BASE = f"https://api.cloudflare.com/client/v4/accounts/{ACCT}"


def api(method, url, data=None, ctype=None):
    r = urllib.request.Request(
        url,
        method=method,
        data=data,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            **({"Content-Type": ctype} if ctype else {}),
        },
    )
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read())
        except Exception:
            return {"success": False, "errors": [{"message": f"HTTP {e.code}"}]}


# 1) Ensure the R2 bucket exists.
buckets = api("GET", f"{BASE}/r2/buckets")
names = [b.get("name") for b in (buckets.get("result") or {}).get("buckets", [])]
print("existing buckets:", names)
if BUCKET not in names:
    res = api(
        "POST",
        f"{BASE}/r2/buckets",
        json.dumps({"name": BUCKET}).encode(),
        "application/json",
    )
    print("create bucket:", res.get("success"), res.get("errors"))

# 2) Build the worker source with the admin secret injected.
secret = open("admin_secret.txt").read().strip()
code = open("worker.js", "rb").read().replace(
    b"__ADMIN_SECRET__", secret.encode()
)
assert b"__ADMIN_SECRET__" not in code

metadata = {
    "main_module": "worker.js",
    "compatibility_date": "2024-11-01",
    "bindings": [
        {"type": "r2_bucket", "name": "FILES", "bucket_name": BUCKET},
    ],
}

bnd = uuid.uuid4().hex


def part(name, ctype, content, filename=None):
    h = f"--{bnd}\r\nContent-Disposition: form-data; name=\"{name}\""
    if filename:
        h += f'; filename="{filename}"'
    h += f"\r\nContent-Type: {ctype}\r\n\r\n"
    return h.encode() + content + b"\r\n"


body = (
    part("metadata", "application/json", json.dumps(metadata).encode())
    + part("worker.js", "application/javascript+module", code, "worker.js")
    + f"--{bnd}--\r\n".encode()
)
res = api("PUT", f"{BASE}/workers/scripts/{NAME}", body,
          f"multipart/form-data; boundary={bnd}")
print("deploy success:", res.get("success"), "| errors:", res.get("errors"))

# 3) Enable the workers.dev subdomain (needed once for a new script).
sub = api(
    "POST",
    f"{BASE}/workers/scripts/{NAME}/subdomain",
    json.dumps({"enabled": True}).encode(),
    "application/json",
)
print("subdomain:", sub.get("success"), (sub.get("result") or {}))
