#!/usr/bin/env python3
"""
cleanup_releases.py
GitHub Releases Cleanup Script for Mushaf Qiyam:
1. Deletes all releases prior to the current FastConformer model (releases before v5.6.0).
2. Deletes git tag references for those deleted releases.
3. Hides all remaining FastConformer-era releases (v5.6.0 to v0.5.0.1) by converting them to drafts.
4. Keeps only the latest target release (v0.5.1.0) published publicly.
"""

import os
import sys
import json
import urllib.request
import urllib.error

TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
REPO = os.environ.get("GITHUB_REPOSITORY", "kh-saadany/mushaf-qiyam")
TARGET_ACTIVE_TAG = "v0.7.0.5"

# Releases using the current FastConformer model that must be HIDDEN (converted to draft)
FASTCONFORMER_TAGS_TO_HIDE = {
    "v5.6.0",
    "v5.6.1",
    "v5.7.0",
    "v5.8.0",
    "v5.8.1",
    "v5.8.2",
    "v5.9.0",
    "v5.9.1",
    "v0.5.0-beta",
    "v0.5.0.1",
    "v0.5.1.0",
    "v0.7.0.0",
    "v0.7.0.1",
    "v0.7.0.2",
    "v0.7.0.3",
    "v0.7.0.4",
}

if not TOKEN:
    print("ERROR: Neither GH_TOKEN nor GITHUB_TOKEN is set. Cannot clean releases.")
    sys.exit(1)

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "MushafQiyam-Cleaner"
}

def api_request(endpoint, method="GET", data=None):
    url = f"https://api.github.com/repos/{REPO}/{endpoint}"
    req = urllib.request.Request(url, method=method, headers=HEADERS)
    if data is not None:
        req.data = json.dumps(data).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            if resp.status == 204:
                return True
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else True
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8") if e.fp else ""
        print(f"[{method}] {url} failed with {e.code}: {err_msg}")
        return None
    except Exception as ex:
        print(f"[{method}] {url} exception: {ex}")
        return None

def fetch_all_releases():
    all_releases = []
    page = 1
    while True:
        data = api_request(f"releases?per_page=100&page={page}")
        if not data or not isinstance(data, list) or len(data) == 0:
            break
        all_releases.extend(data)
        if len(data) < 100:
            break
        page += 1
    return all_releases

def main():
    print(f"=== Starting GitHub Releases Cleanup for {REPO} ===")
    releases = fetch_all_releases()
    print(f"Total releases found on remote: {len(releases)}")

    deleted_count = 0
    hidden_count = 0

    for r in releases:
        rel_id = r.get("id")
        tag = r.get("tag_name", "")
        name = r.get("name", tag)
        is_draft = r.get("draft", False)

        if tag == TARGET_ACTIVE_TAG:
            print(f"[PRESERVE] Skipping current target release: {tag} ({name})")
            continue

        if tag in FASTCONFORMER_TAGS_TO_HIDE:
            # Action: Hide by converting to draft
            if is_draft:
                print(f"[ALREADY HIDDEN] {tag} (ID {rel_id}) is already a draft.")
            else:
                print(f"[HIDE] Converting {tag} (ID {rel_id}) to draft...")
                patch_res = api_request(f"releases/{rel_id}", method="PATCH", data={"draft": True})
                if patch_res:
                    print(f"  -> Successfully converted {tag} to draft.")
                    hidden_count += 1
                else:
                    print(f"  -> Failed to convert {tag} to draft.")
        else:
            # Action: Delete releases prior to current model
            print(f"[DELETE] Deleting pre-FastConformer release {tag} (ID {rel_id})...")
            del_res = api_request(f"releases/{rel_id}", method="DELETE")
            if del_res:
                print(f"  -> Successfully deleted release {tag}.")
                deleted_count += 1
            else:
                print(f"  -> Failed to delete release {tag}.")

            # Also delete remote git tag reference
            if tag:
                del_tag_res = api_request(f"git/refs/tags/{tag}", method="DELETE")
                if del_tag_res:
                    print(f"  -> Deleted git tag reference: refs/tags/{tag}")

    print("\n=== Cleanup Summary ===")
    print(f"Permanently Deleted: {deleted_count} releases")
    print(f"Converted to Draft (Hidden): {hidden_count} releases")
    print(f"Target Public Release: {TARGET_ACTIVE_TAG}")
    print("=== Finished Releases Cleanup ===")

if __name__ == "__main__":
    main()
