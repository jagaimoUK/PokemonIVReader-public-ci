#!/usr/bin/env python3
"""Read-only, conservative preflight for the source-only Public transition.

This is not a substitute for an independent secret scan, asset-rights review,
or a human approval before changing repository visibility.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWED_WORKFLOWS = {'.github/workflows/android-reader-build.yml', '.github/workflows/android-signed-release.yml'}
REQUIRED_NOTICES = {
    'LICENSES/PVPOKE-MIT.txt',
    'LICENSES/POKEAPI-BSD-3-Clause.txt',
    'app/src/main/assets/licenses/regibot-models.txt',
    'THIRD_PARTY_NOTICES.md',
}
HIGH_CONFIDENCE_PATTERNS = {
    'GitHub token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})\b'),
    'AWS access-key identifier': re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'Google API key': re.compile(rb'\bAIza[A-Za-z0-9_-]{35}\b'),
    'Private-key material': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    'Slack token': re.compile(rb'\bxox[baprs]-[A-Za-z0-9-]{20,}\b'),
}
BLOCKED_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.apk', '.aab'}
BLOCKED_NAMES = {'.env', '.env.local', 'id_rsa', 'id_ed25519'}


def check() -> int:
    raw = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT)
    files = [pathlib.Path(v.decode('utf-8', 'surrogateescape')) for v in raw.split(b'\0') if v]
    names = {p.as_posix() for p in files}
    findings: list[str] = []
    skipped_binary = 0
    for required in sorted(REQUIRED_NOTICES - names):
        findings.append(f'Missing attribution: {required}')
    for path in files:
        name = path.as_posix()
        if (name.startswith(('.idea/', 'docs/')) or name == '.git'
            or name.startswith('.git/') or name.startswith('config/')
            or name.startswith('.github/workflows/') and name not in ALLOWED_WORKFLOWS
            or path.suffix.lower() in BLOCKED_SUFFIXES
            or path.name in BLOCKED_NAMES):
            findings.append(f'Blocked source path: {name}')
            continue
        data = (ROOT / path).read_bytes()
        if b'\0' in data[:8192]:
            skipped_binary += 1
            continue
        for label, pattern in HIGH_CONFIDENCE_PATTERNS.items():
            match = pattern.search(data)
            if match:
                line = data.count(b'\n', 0, match.start()) + 1
                findings.append(f'{name}:{line}: potential {label} (value withheld)')
    print(f'Public-source preflight: tracked={len(files)}, binaries_skipped={skipped_binary}, findings={len(findings)}')
    for message in findings:
        print('ERROR:', message, file=sys.stderr)
    if findings:
        print('Do not change repository visibility to Public.', file=sys.stderr)
    else:
        print('Basic path/credential checks passed; NOT a public-release approval.')
    return int(bool(findings))


if __name__ == '__main__':
    sys.exit(check())
