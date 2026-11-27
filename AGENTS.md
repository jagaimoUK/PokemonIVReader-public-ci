# Android development repository authority

This file applies to all AI coding assistants and automation operating in this repository.

## Canonical source
- The **only current Android development source of truth** is `jagaimoUK/PokemonIVReader-public-ci`, development branch `main`.
- Before planning, editing, testing or pushing code, verify the full repository owner/name and the current branch. For a local checkout, run `bash tools/assert_canonical_repo.sh`. For GitHub tools, pass `repository_full_name: "jagaimoUK/PokemonIVReader-public-ci"` explicitly.
- Do not infer repository authority from the project name, previous conversation, a local folder name, or a PR number.
- `jagaimoUK/PokemonIVReader` is the historical PRIVATE Android source and signed-release backup. `jagaimoUK/PokemonIVReader-public` is the historical PRIVATE publication staging repo. Do not create or merge Android feature PRs in those repos by default.
- `jagaimoUK/pokemoniv` is a different Web application repository. Only work on it for explicitly requested Web work.
- Never bulk-merge or cherry-pick old PRs/branches: the Public initial commit already includes most current code, and unrelated old commits can reintroduce excluded assets or obsolete functionality. Compare actual features and port only demonstrated gaps to this repository. Track decisions in `MIGRATION_STATUS.md`.

## Release and security gates
- Use Public GitHub Actions for debug build/unit/regression tests; do not assert a test succeeded unless its exact run passed. Avoid duplicate CI runs.
- Normal Public CI does NOT publish installable APKs. A separate manual owner-only signed APK workflow is available, but it needs explicitly configured Environment secrets, version checks and a reviewed `main` branch; never run it on PRs or feature branches. Never claim a release-signed installed app was updated without a successful signed build and device installation.
- Never commit signing keys, passwords, tokens, private data or Pokémon GO button pixel crops. Keep original Android `applicationId`, certificate-compatible signing process and installed data intact.
- Do not store actual user data in tests/logs; prefer synthetic fixtures.
- Protect history, settings, OCR/recognition learning, tags, pending work and backup/restore compatibility. Fail closed on uncertain destructive automation.
- Do **not** use the ChatGPT-connected PvPoke MCP, including indirect tool calls.
- Never alter the original Private repos, production deployments, signing secrets or repository visibility without explicit authorization.

## PR, CI, and APK release batching policy (2026-11-27)
- **Batch related small fixes into 1 PR**: Group related small fixes for the same feature/screen (2–4 items) into a single feature branch and PR, separating each fix into its own logical commit so issues can be bisected or reverted cleanly while running Public CI only once.
- **Separate unrelated changes**: Keep distinct domains (e.g., CI/workflow changes vs. app features, or Web vs. Android) in separate PRs.
- **APK release on demand only**: Merge passing PRs to `main` without triggering `android-signed-release.yml` on every fix. Run `gh workflow run android-signed-release.yml --repo jagaimoUK/PokemonIVReader-public-ci --ref main` **only** when the user requests an APK for device testing.
- **No manual version-bump commits**: `android-signed-release.yml` auto-resolves `versionCode` / `versionName` via `github.run_number` (`ANDROID_VERSION_CODE` / `ANDROID_VERSION_NAME` env overrides in `app/build.gradle.kts`) and runs `:app:assembleRelease` + signing/certificate verification without re-running unit tests already verified by PR CI.

## User-facing test & operation instructions (2026-11-23)
- When guiding the user through physical device tests or manual operations, **do not omit subjects or objects**: explicitly state **which app** (`厳選アプリ (Web)` / `読み取りアプリ (Android)` / `Pokémon GO`), **which screen/collapsible/input/button**, and **where/what exact label or UI change indicates pass vs. block/conflict**.
- Focus concretely on points where the user could get stuck or confuse apps/screens, rather than making every trivial step verbose.

## Cross-app Mega and IV-selection design (documentation proposal, 2026-11-25)

- Canonical requirements and verification plan (15 user-decided questions): Web repository `docs/mega-iv-selection-cross-app-design.md`.
- After the Web documentation PR is merged, use the canonical **main** URL: https://github.com/jagaimoUK/pokemoniv/blob/main/docs/mega-iv-selection-cross-app-design.md
- Before that merge, use the verified **Draft branch** URL: https://github.com/jagaimoUK/pokemoniv/blob/docs/mega-iv-cross-app-spec-20261125/docs/mega-iv-selection-cross-app-design.md
- For IV recognition, nickname/tag automation, candidate history, Web sync, Mega CP, and relevant selection work, **read sections 2, 3, 5 and 8 of this document** and compare them with the latest code and the target PR's latest [AI-HANDOFF v1] before acting.
- The canonical design lives in the Web repository; do not copy it and create a second drifting specification in Android. This pointer does **not** change Android repository authority or weaken existing safety gates.
- Verify whether this pointer exists in Android `main:AGENTS.md` before claiming it is in force. The Web design document may be on `main` or only in Draft PR #131; fetch the actual referenced version. Do not claim it is automatically loaded by unrelated new sessions.
