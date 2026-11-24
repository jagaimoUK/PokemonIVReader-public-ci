#!/usr/bin/env python3
"""Validate a manual signed APK release before starting any expensive build."""
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "release/installed-apk-identity.properties"

def fail(message: str) -> None:
    raise ValueError(message)

def validate(release_tag: str, expected_code: str, installed_code: str,
             gradle: str, identity: str) -> None:
    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){2}", release_tag):
        fail("release_tag must be vMAJOR.MINOR.PATCH")
    if not re.fullmatch(r"[1-9][0-9]*", expected_code):
        fail("expected_version_code must be a positive integer")
    if not re.fullmatch(r"[1-9][0-9]*", installed_code):
        fail("installed_version_code must be a positive integer")
    vc = re.findall(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", gradle, re.M)
    vn = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.M)
    if len(vc) != 1 or len(vn) != 1 or vc[0] != expected_code or release_tag != "v" + vn[0]:
        fail("release inputs do not match app/build.gradle.kts")
    settings = {}
    for line in identity.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            settings[key.strip()] = value.strip()
    if settings.get("applicationId") != "com.example.pokemonivreader":
        fail("Pinned application ID missing or unexpected")
    cert = settings.get("certificateSha256", "").replace(":", "").lower()
    if not re.fullmatch("[0-9a-f]{64}", cert):
        fail("Pinned signing fingerprint missing or invalid")
    minimum = settings.get("baselineVersionCode", "")
    if not re.fullmatch(r"[0-9]+", minimum):
        fail("Pinned baseline versionCode missing")
    if int(installed_code) < int(minimum) or int(expected_code) <= int(installed_code):
        fail("New versionCode must be higher than installed versionCode")
    if int(expected_code) > 2_100_000_000:
        fail("versionCode exceeds Android-compatible upper limit")

if __name__ == "__main__":
    try:
        validate(
            os.environ.get("RELEASE_TAG", ""),
            os.environ.get("EXPECTED_VERSION_CODE", ""),
            os.environ.get("INSTALLED_VERSION_CODE", ""),
            (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"),
            CONFIG.read_text(encoding="utf-8"),
        )
    except (ValueError, OSError) as exc:
        print("::error::Release input validation failed:", exc, file=sys.stderr)
        sys.exit(1)
    print("PASS: tag, versions, pinned signing identity metadata")
