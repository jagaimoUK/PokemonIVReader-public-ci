#!/usr/bin/env python3
"""Read-only binary preflight for the source-only public-release staging repo.

This checks formats, metadata, suspicious bytes and unexpected binary paths.
It does NOT determine copyright, all encoded secrets, or model licenses.
"""
from __future__ import annotations
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
WEBP_PATH = re.compile(r'app/src/main/res/mipmap-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)/ic_launcher(?:_round)?\.webp\Z')
ALLOWED_BINARY = {'gradle/wrapper/gradle-wrapper.jar'}
CREDENTIALS = (
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    re.compile(rb'\b(?:github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9_]{30,})\b'),
    re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    re.compile(rb'\bAIza[A-Za-z0-9_-]{35}\b'),
)
BINARY_SUFFIXES = {
    '.jar', '.webp', '.png', '.jpg', '.jpeg', '.gif', '.pdf', '.so',
    '.ttf', '.otf', '.tflite', '.zip', '.apk', '.aab', '.p12', '.jks', '.keystore',
}
MAX_FILE = 8 * 1024 * 1024

def file_names(root: pathlib.Path) -> list[str]:
    result = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root)
    return [x.decode('utf-8', 'surrogateescape') for x in result.split(b'\0') if x]

def scan_credentials(raw: bytes) -> bool:
    return any(pattern.search(raw) for pattern in CREDENTIALS)

def inspect_webp(raw: bytes) -> list[str]:
    errors = []
    if len(raw) < 20 or raw[:4] != b'RIFF' or raw[8:12] != b'WEBP':
        return ['invalid RIFF/WebP signature']
    if int.from_bytes(raw[4:8], 'little') + 8 != len(raw):
        errors.append('RIFF size mismatch or trailing bytes')
    offset = 12
    chunks = []
    while offset + 8 <= len(raw):
        typ = raw[offset:offset+4]
        size = int.from_bytes(raw[offset+4:offset+8], 'little')
        end = offset + 8 + size
        if end > len(raw):
            errors.append('invalid WebP chunk size')
            break
        chunks.append(typ)
        if typ in (b'EXIF', b'XMP ', b'ICCP'):
            errors.append('embedded image metadata: ' + typ.decode('ascii'))
        elif typ not in (b'VP8 ', b'VP8L', b'VP8X', b'ALPH', b'ANIM', b'ANMF'):
            errors.append('unrecognized WebP chunk type')
        offset = end + size % 2
    if offset != len(raw):
        errors.append('WebP chunks do not cover entire file')
    if not any(c in chunks for c in (b'VP8 ', b'VP8L', b'ANMF')):
        errors.append('WebP missing image data')
    return errors

def inspect_jar(path: pathlib.Path) -> list[str]:
    errors = []
    try:
        with zipfile.ZipFile(path) as z:
            if z.testzip() is not None:
                errors.append('corrupt JAR member')
            for entry in z.infolist():
                name = entry.filename
                parts = pathlib.PurePosixPath(name).parts
                if name.startswith('/') or '..' in parts or '\\' in name:
                    errors.append('unsafe JAR entry path')
                    continue
                if pathlib.PurePosixPath(name).suffix.lower() in {
                    '.pem', '.p12', '.jks', '.keystore', '.key'
                }:
                    errors.append('unexpected signing material inside JAR')
                if entry.file_size > MAX_FILE:
                    errors.append('oversized JAR member')
                    continue
                if scan_credentials(z.read(entry)):
                    errors.append('potential embedded credential in JAR member')
    except (OSError, zipfile.BadZipFile, RuntimeError, ValueError) as exc:
        errors.append('invalid JAR: ' + type(exc).__name__)
    return errors

def check(root: pathlib.Path = ROOT) -> int:
    files = file_names(root)
    errors = []
    count = 0
    for name in files:
        path = root / name
        raw = path.read_bytes()
        is_binary = b'\0' in raw[:8192] or path.suffix.lower() in BINARY_SUFFIXES
        if not is_binary:
            continue
        count += 1
        if len(raw) > MAX_FILE:
            errors.append(f'{name}: unexpected large binary')
            continue
        if name not in ALLOWED_BINARY and not WEBP_PATH.fullmatch(name):
            errors.append(f'{name}: binary path not reviewed')
            continue
        if scan_credentials(raw):
            errors.append(f'{name}: recognizable credential bytes')
        if WEBP_PATH.fullmatch(name):
            errors += [f'{name}: {e}' for e in inspect_webp(raw)]
        else:
            errors += [f'{name}: {e}' for e in inspect_jar(path)]
    print(f'Binary preflight: tracked={len(files)}, binary_checked={count}, findings={len(errors)}')
    for message in errors:
        print('BLOCKED:', message, file=sys.stderr)
    print('Binary format checks do NOT clear visual content or publication rights.')
    return int(bool(errors))

if __name__ == '__main__':
    sys.exit(check())
