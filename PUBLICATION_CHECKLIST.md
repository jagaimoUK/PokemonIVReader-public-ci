# Public publication gate (2026-11-19)

Current status: **NOT APPROVED FOR PUBLICATION**.

## Verified
- [x] New repository has an independent Git history (single root commit).
- [x] Source tree compared against the pinned PR #28 snapshot.
- [x] Historical `.idea/`, private development docs, and signed-release
      workflows were excluded from the new repository.
- [x] Initial GitHub Actions Android build/unit tests and catalog checks passed.
- [x] Notices for PvPoke, PokéAPI, and RegiBot are identified and retained.

## Remaining requirements
- [x] Run Gitleaks worktree and fresh-history scans (0 findings; CI verified).
- [ ] Complete deeper secret review of encoded material and binary assets.
      Format, metadata and recognizable-key checks are not exhaustive.
- [ ] Verify ownership/permission or replace game-derived button pixel templates.
- [ ] Verify or replace launcher icons.
- [ ] Confirm model-weight terms for MediaPipe MobileNet V3.
- [ ] Complete third-party dependency license review.
- [ ] Establish publication/legal position for game-related content and
      automation features. A license on an upstream software repository is not
      permission to use all associated game IP.
- [ ] Decide whether and under what terms the project's own code is licensed.
- [ ] Re-run necessary checks after any changes to assets.
- [ ] Explicitly authorize changing repository visibility from Private to Public.

Never move original Private repository history/tags/signing keys into this repo.
Do not install the CI debug APK as an update to a release-signed app.

## Publication candidate isolation (not merged)
- [x] Fresh-install fallback behavior identified: no bundled exemplars means
      automatic button recognition requires the user to train each control.
- [x] Run Android build/tests against the asset-free candidate branch (commit f9305faf; three CI jobs successful).
- [x] Verify previous-version saved learning preferences remain readable in the
      asset-free candidate (regression test passed in GitHub Actions).
      This is not a signed APK upgrade test on a device.
- [ ] Verify Android adaptive launcher appearance on a supported physical device.
- [ ] Recheck template-learning and no-template fail-closed behavior on-device.
- [ ] Build a second fresh-history repository or independent root snapshot.
      Merely deleting files on a descendant branch does not remove history.
- [ ] Resolve remaining image/character rights, model weight licensing, and
      all relevant dependency obligations.

## Fresh-history source export (private preparation)
- [x] Added a read-only source ZIP exporter restricted to the asset-free candidate branch.
      It rejects game UI crop paths, legacy icon WebP, keys, symlinks, other
      non-regular Git objects, unexpected workflows, and overwriting output.
- [x] Five synthetic tests for safe export and blocked inputs passed locally.
- [ ] Review and execute the export against the final candidate HEAD.
- [ ] Compare the export manifest with the destination repository's initial
      root commit before any change to Public visibility.
- [ ] Rights/asset clearance and explicit approval are still required.

For Termux, clone this Private repository, check out branch
`publication/candidate-no-game-pixels-20261119`, then run:

```bash
python3 tools/export_public_snapshot.py --output "$HOME/PokemonIVReader-clean-source.zip"
```

The export is a history-free ZIP, not a public repository. It does not change
GitHub visibility, Git history, APK signing or existing phone data. Never
publish this ZIP before the remaining checklist items have been completed.

## Actual source export verification
- [ ] GitHub Actions clean-snapshot job: export real candidate contents,
      validate each SHA-256, then reconstruct exactly one root Git commit
      **offline**, without remote push or artifact upload (CI pending).
- [ ] Local Termux ZIP export is still optional and has not been confirmed.
- [ ] Do not create a Public repo or change visibility until legal/rights,
      full audit, and explicit publication approval are complete.

## Embedded flashcard bundle privacy screen
- [x] Removed the unverified `meta:legacy` 72-row custom-meta deck from the
      **candidate's bundled bootstrap**; protected saved IndexedDB records are
      not modified. The earlier staging Git history still includes the deck.
- [x] Added Android regression assertion that bootstrap decks in a source-only
      candidate are restricted to `meta:pvpoke:*` presets.
- [ ] Verify the candidate's CI test after this change.
- [ ] Review whether any remaining public-looking PvPoke meta presets embed
      user-specific selections before final Public approval.
