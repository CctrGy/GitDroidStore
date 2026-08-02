#!/usr/bin/env python3
"""Generate GitDroidStore's static catalog from public GitHub releases."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
ICON_NAMES = ("icon.png", "icon.webp", "ic_launcher.png", "ic_launcher.webp")


class GitHub:
    def __init__(self, token: str = "") -> None:
        self.headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "GitDroidStore-catalog-generator/1",
        }
        if token:
            self.headers["Authorization"] = f"Bearer {token}"

    def json(self, url: str, allow_missing: bool = False):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=self.headers), timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            raise RuntimeError(f"GitHub API returned {error.code} for {url}") from error

    def text(self, url: str) -> str:
        with urllib.request.urlopen(urllib.request.Request(url, headers=self.headers), timeout=30) as response:
            return response.read().decode("utf-8")


def pages(client: GitHub, url: str):
    page = 1
    while True:
        separator = "&" if "?" in url else "?"
        batch = client.json(f"{url}{separator}per_page=100&page={page}")
        yield from batch
        if len(batch) < 100:
            return
        page += 1


def normalize_hash(value: str | None) -> str | None:
    return value.replace(":", "").lower() if value else None


def icon_score(path: str) -> int:
    normalized = path.lower()
    filename = normalized.rsplit("/", 1)[-1]
    if filename in ("ic_launcher.png", "ic_launcher.webp"):
        return 100
    if filename in ("icon.png", "icon.webp"):
        return 95
    if "app-icon" in filename or "developer-icon" in filename:
        return 90
    if "launcher" in filename:
        return 80
    if "icon" in filename:
        return 70
    return 0


def raw_url(owner: str, repo: str, branch: str, path: str) -> str:
    quoted_path = "/".join(urllib.parse.quote(part, safe="") for part in path.split("/"))
    return f"https://raw.githubusercontent.com/{owner}/{repo}/{urllib.parse.quote(branch, safe='')}/{quoted_path}"


def find_icon(client: GitHub, owner: str, repo: str, branch: str, root_files: dict) -> str | None:
    for name in ICON_NAMES:
        item = root_files.get(name)
        if item and item.get("download_url"):
            return item["download_url"]
    tree = client.json(f"{API}/repos/{owner}/{repo}/git/trees/{urllib.parse.quote(branch, safe='')}?recursive=1", True)
    if not tree:
        return None
    candidates = [
        item for item in tree.get("tree", [])
        if item.get("type") == "blob"
        and 0 < item.get("size", 0) <= 2 * 1024 * 1024
        and pathlib.PurePosixPath(item.get("path", "")).suffix.lower() in (".png", ".webp", ".jpg", ".jpeg")
        and icon_score(item.get("path", "")) > 0
    ]
    if not candidates:
        return None
    selected = max(candidates, key=lambda item: icon_score(item["path"]))
    return raw_url(owner, repo, branch, selected["path"])


def inspect_repo(client: GitHub, owner: str, repo: dict) -> dict | None:
    name = repo["name"]
    branch = repo.get("default_branch") or "main"
    release = client.json(f"{API}/repos/{owner}/{name}/releases/latest", True)
    if not release:
        return None
    apk = next((asset for asset in release.get("assets", []) if asset.get("name") == "app.apk" and asset.get("state") == "uploaded"), None)
    if not apk:
        return None
    root = client.json(f"{API}/repos/{owner}/{name}/contents?ref={urllib.parse.quote(branch, safe='')}", True) or []
    root_files = {item["name"]: item for item in root if item.get("type") == "file"}
    metadata = {}
    version_file = root_files.get("version.json")
    if version_file and version_file.get("download_url"):
        try:
            metadata = json.loads(client.text(version_file["download_url"]))
        except (ValueError, OSError, urllib.error.HTTPError):
            metadata = {}
    release_hash = normalize_hash((apk.get("digest") or "").removeprefix("sha256:"))
    metadata_hash = normalize_hash(metadata.get("sha256"))
    if release_hash and metadata_hash and release_hash != metadata_hash:
        print(f"Skipping {owner}/{name}: release and version.json SHA-256 differ")
        return None
    friendly_name = name
    name_file = root_files.get("appname.txt")
    if name_file and name_file.get("download_url"):
        try:
            friendly_name = client.text(name_file["download_url"]).strip()[:100] or name
        except (OSError, urllib.error.HTTPError):
            pass
    digest = release_hash or metadata_hash
    return {
        "owner": owner,
        "repo": name,
        "displayName": friendly_name,
        "description": repo.get("description") or "",
        "defaultBranch": branch,
        "packageName": metadata.get("packageName"),
        "versionName": metadata.get("versionName") or release.get("tag_name", "").removeprefix("v") or None,
        "versionCode": metadata.get("versionCode"),
        "sha256": digest,
        "certificateSha256": normalize_hash(metadata.get("certificateSha256")),
        "apkUrl": apk["browser_download_url"],
        "apkSize": apk.get("size"),
        "iconUrl": find_icon(client, owner, name, branch, root_files),
        "releaseTag": release.get("tag_name"),
        "releasePublishedAt": release.get("published_at"),
        "remoteSha": digest or f"{release.get('id')}:{apk.get('id')}:{apk.get('updated_at')}:{apk.get('size')}",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--output", default="catalog.json")
    args = parser.parse_args()
    client = GitHub(os.environ.get("GITHUB_TOKEN", ""))
    apps = []
    for repo in pages(client, f"{API}/users/{args.owner}/repos?sort=full_name"):
        app = inspect_repo(client, args.owner, repo)
        if app:
            apps.append(app)
    apps.sort(key=lambda app: (app["owner"].lower(), app["repo"].lower()))
    output = pathlib.Path(args.output)
    previous = {}
    if output.exists():
        try:
            previous = json.loads(output.read_text(encoding="utf-8"))
        except ValueError:
            pass
    generated_at = previous.get("generatedAt") if previous.get("apps") == apps else None
    catalog = {
        "schemaVersion": 1,
        "generatedAt": generated_at or dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "catalogOwner": args.owner,
        "apps": apps,
    }
    output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(apps)} applications to {output}")


if __name__ == "__main__":
    main()
