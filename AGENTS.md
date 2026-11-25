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

## User-facing test & operation instructions (2026-11-23)
- When guiding the user through physical device tests or manual operations, **do not omit subjects or objects**: explicitly state **which app** (`厳選アプリ (Web)` / `読み取りアプリ (Android)` / `Pokémon GO`), **which screen/collapsible/input/button**, and **where/what exact label or UI change indicates pass vs. block/conflict**.
- Focus concretely on points where the user could get stuck or confuse apps/screens, rather than making every trivial step verbose.

## Ask-question options: recommended model & thinking effort (2026-11-24)
- Whenever presenting options to the user via `ask_question` (or equivalent task-choice prompts), explicitly state the **recommended AI model, thinking effort, and environment** for each option based on 3-axis evaluation (accuracy, speed, quota savings), using only real models in the user's plan:
  - Codex: `GPT-6.2 Sol` (`High` / `Medium`), `Luna` (`Medium` / `Low`)
  - Antigravity: `Gemini 4 Hydrogen` (`High` / `Medium`), `Gemini 4 Neon` (`High` / `Medium`), `Orchestrator (Neon)`, `Gemini 3.8 Flash` (`Medium` / `Low`)
