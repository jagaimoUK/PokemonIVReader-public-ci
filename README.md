# PokemonIVReader | Android development (Public)

**Canonical Android development repository:** https://github.com/jagaimoUK/PokemonIVReader-public-ci

All **new Android source changes, pull requests, code review and ordinary CI tests** belong here. The ongoing development branch is **`main`**. This repository has an independent, clean Git history, not the full history of the older private Android repo.

## Related repositories

| Repository | Role | Write policy |
| --- | --- | --- |
| [PokemonIVReader-public-ci](https://github.com/jagaimoUK/PokemonIVReader-public-ci) | **Current Android source of truth**, PRs and CI | New work goes here |
| [PokemonIVReader](https://github.com/jagaimoUK/PokemonIVReader) | Legacy **private** releases, signing identity and historical branches/PRs | Do not develop or merge here by default; retain as backup |
| [PokemonIVReader-public](https://github.com/jagaimoUK/PokemonIVReader-public) | Legacy **private** publication-preparation PR and staging history | Do not develop or publish this historical repo |
| [pokemoniv](https://github.com/jagaimoUK/pokemoniv) | **Different project**: Web IV selection / simulator | Do not confuse with the Android source |

Old private PR numbers are **not** current public PR numbers. In particular, the code from old PRs #27 and #28 was included in the fresh public source snapshot; those PRs remain open in the old repository because their Git history was intentionally not merged. See [MIGRATION_STATUS.md](MIGRATION_STATUS.md) for remaining reviews.

## Work in this repository

```bash
git clone https://github.com/jagaimoUK/PokemonIVReader-public-ci.git
cd PokemonIVReader-public-ci
bash tools/assert_canonical_repo.sh
git switch main
```

AI coding assistants (Codex, Antigravity, ChatGPT) must follow [AGENTS.md](AGENTS.md) and verify the repository owner/name **before reading or modifying source**. A similarly named old repository is not an acceptable substitute.

## Build, test and APK

The `Public Android reader checks` workflow builds Android **debug** output and runs unit/regression, catalog-parity and source safety checks on `main` and PRs. Its existing build job does **not yet upload** a downloadable debug APK.

The [`Signed Android APK (manual public release)`](.github/workflows/android-signed-release.yml) workflow prepares signed prereleases **only after the owner sets up protected GitHub Environment secrets**. It verifies the same Android `applicationId` and signing certificate as the installed app, checks an increased `versionCode`, and refuses to overwrite an existing release. Read [SIGNED_RELEASE_SETUP.md](SIGNED_RELEASE_SETUP.md) for the required one-time owner configuration and manual run. Until the signing secrets are configured and an on-device update is verified, signed updates still come from the older Private release workflow. **Never uninstall the existing signed app to install a CI debug APK:** that risks losing saved history, settings and learned data. Public source and Public APK distribution are separate milestones.

Bundled Pokémon GO button pixel examples and legacy raster launch icons are intentionally omitted from this public source. A fresh installation may require training the GO button recognizer; existing private-install device data are not tested by Public CI. No Android app release, store publishing or real-device upgrade test is implied by a successful CI build.

## Copyright, data and security

This is a personal development project with possible commercial use later. No license has yet been selected for the project's original source. Public visibility does not automatically grant third-party reuse rights. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSES/](LICENSES/).

Never commit keystores, signing passwords, API tokens, actual user data, privately trained images, or internal logs to this public repository. All new code must preserve existing saved histories, session tags, learned recognition data and signed-app update compatibility. Do not use the ChatGPT-connected PvPoke MCP in this project's development or testing.
