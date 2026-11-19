#!/usr/bin/env bash
# Refuse to develop from an accidentally cloned legacy Android repository.
set -euo pipefail
expected='jagaimoUK/PokemonIVReader-public-ci'
if [[ -n "${GITHUB_REPOSITORY:-}" && "${GITHUB_REPOSITORY}" != "${expected}" ]]; then
  echo "ERROR: GitHub Actions repository is ${GITHUB_REPOSITORY}, expected ${expected}" >&2
  exit 1
fi
remote="$(git remote get-url origin 2>/dev/null || true)"
case "${remote}" in
  "https://github.com/${expected}"|"https://github.com/${expected}.git"|\
  "git@github.com:${expected}"|"git@github.com:${expected}.git"|\
  "ssh://git@github.com/${expected}"|"ssh://git@github.com/${expected}.git")
    echo "OK: canonical Android repository (${expected})";;
  *)
    echo "ERROR: wrong Git origin (${remote:-<none>}); expected ${expected}" >&2
    exit 1;;
esac
