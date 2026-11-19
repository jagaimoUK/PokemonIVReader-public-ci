#!/usr/bin/env python3
"""Create a private, history-free source ZIP from a reviewed candidate HEAD.

This never creates/pushes a repository or changes its visibility. It does NOT
approve publication or settle third-party IP/model license obligations.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

CANDIDATE = 'publication/candidate-no-game-pixels-20261119'
ALLOWED_MODES = {'100644', '100755'}
BANNED_EXT = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key'}
BANNED_NAMES = {'.env', '.env.local', 'id_rsa', 'id_ed25519'}
CREDENTIALS = (
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    re.compile(rb'\b(?:github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9_]{30,})\b'),
    re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    re.compile(rb'\bAIza[A-Za-z0-9_-]{35}\b'),
)
MANDATORY = {
    'README.md', 'PUBLICATION_CHECKLIST.md', 'THIRD_PARTY_NOTICES.md',
    'LICENSES/PVPOKE-MIT.txt', 'LICENSES/POKEAPI-BSD-3-Clause.txt',
    'gradlew', 'app/build.gradle.kts',
}

def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE)

def unsafe(path: str) -> bool:
    p = PurePosixPath(path)
    if not path or str(p) != path or path.startswith('/') or any(x in ('', '.', '..') for x in p.parts):
        return True
    if any(ord(c) < 32 or ord(c) == 127 for c in path):
        return True
    if any(x in ('.git', '.idea') for x in p.parts) or path.startswith('docs/'):
        return True
    if path.startswith('app/src/main/assets/go_buttons/'):
        return True
    if re.fullmatch(r'app/src/main/res/mipmap-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)/ic_launcher(?:_round)?\.webp', path):
        return True
    if path.startswith('config/') or p.name in BANNED_NAMES or p.suffix.lower() in BANNED_EXT:
        return True
    if path.startswith('.github/workflows/') and path != '.github/workflows/android-reader-build.yml':
        return True
    return False

def export(root: Path, destination: Path) -> dict:
    root = Path(git(root, 'rev-parse', '--show-toplevel').decode().strip()).resolve()
    branch = git(root, 'symbolic-ref', '--quiet', '--short', 'HEAD').decode().strip()
    if branch != CANDIDATE:
        raise ValueError('Refusing to export from branch: ' + branch)
    dest = destination.expanduser().resolve()
    if not dest.name.endswith('.zip'):
        raise ValueError('Output must be a new .zip file')
    if dest == root or root in dest.parents:
        raise ValueError('Output cannot be inside the source repository')
    if dest.exists() or dest.with_suffix('.manifest.json').exists():
        raise FileExistsError('Output or manifest already exists. Refusing to overwrite.')
    if not dest.parent.is_dir():
        raise ValueError('Output directory must already exist')
    commit = git(root, 'rev-parse', 'HEAD').decode().strip()
    raw = git(root, 'ls-tree', '-rz', '--full-tree', 'HEAD')
    entries = []
    seen = set()
    for item in raw.split(b'\0'):
        if not item:
            continue
        meta, name = item.split(b'\t', 1)
        mode, typ, sha = meta.decode('ascii').split(' ')
        name = name.decode('utf-8', errors='strict')
        if typ != 'blob' or mode not in ALLOWED_MODES:
            raise ValueError('Unsafe Git entry (symlink/submodule/non-regular): ' + name)
        if unsafe(name) or name in seen:
            raise ValueError('Unsafe or duplicate path: ' + name)
        seen.add(name)
        entries.append((name, sha, mode))
    if missing := MANDATORY - seen:
        raise ValueError('Missing required files: ' + ', '.join(sorted(missing)))
    entries.sort()
    file_records = []
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=dest.parent, prefix='.private-export-', suffix='.zip', delete=False) as f:
            temporary = Path(f.name)
        with ZipFile(temporary, 'w', compression=ZIP_DEFLATED, compresslevel=6, allowZip64=True) as out:
            for name, sha, mode in entries:
                data = git(root, 'cat-file', 'blob', sha)
                if any(p.search(data) for p in CREDENTIALS):
                    raise ValueError('Potential credential in source file: ' + name)
                if len(data) > 10 * 1024 * 1024:
                    raise ValueError('Unexpectedly large source file: ' + name)
                z = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                z.compress_type = ZIP_DEFLATED
                z.external_attr = (0o100755 if mode == '100755' else 0o100644) << 16
                out.writestr(z, data, compress_type=ZIP_DEFLATED, compresslevel=6)
                file_records.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest()})
        with ZipFile(temporary) as verify:
            expected = [name for name, _, _ in entries]
            if verify.namelist() != expected or verify.testzip() is not None:
                raise ValueError('ZIP verification failed')
        manifest = {
            'status': 'PRIVATE PREPARATION ONLY: not approved for Public',
            'source_commit': commit, 'source_branch': branch,
            'file_count': len(file_records),
            'archive_sha256': hashlib.sha256(temporary.read_bytes()).hexdigest(),
            'files': file_records,
            'limitations': 'No git history included. Does not clear rights, models or runtime behavior.'
        }
        manifest_tmp = dest.with_name(dest.name + '.manifest.tmp')
        manifest_dest = dest.with_suffix('.manifest.json')
        with open(manifest_tmp, 'x', encoding='utf-8') as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(temporary, dest)
        temporary = None
        os.replace(manifest_tmp, manifest_dest)
        return manifest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        dest.with_name(dest.name + '.manifest.tmp').unlink(missing_ok=True)

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path.cwd())
    parser.add_argument('--output', type=Path, required=True,
                        help='New ZIP path outside the repository (never overwritten)')
    args = parser.parse_args()
    try:
        result = export(args.source, args.output)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print('Export blocked: ' + str(exc), file=sys.stderr)
        return 1
    print('Private source-only ZIP prepared. Files:', result['file_count'])
    print('SHA-256:', result['archive_sha256'])
    print('No Git history included. NOT CLEARED FOR PUBLICATION.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
