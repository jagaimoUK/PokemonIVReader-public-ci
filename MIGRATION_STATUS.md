# Legacy Android development reconciliation (2026-11-19)

**Canonical new Android repository:** `jagaimoUK/PokemonIVReader-public-ci`, branch `main`.

The public project began with one independent root commit copied from the private Android source around `feature/evolution-prev-subprev-20261118` (`ec2095afa63e939b334bfe927881af4e95b8bcd1`), with sensitive/nonpublic assets and old Git history excluded. The public CI run `44526374659` passed all applicable jobs on its initial imported source. This is not an on-device verification or signed APK release.

## Old Private PR disposition

- **Old #27** (species-name queries and IME search preservation): ancestry of old #28; its corresponding source is included in Public snapshot. Do not re-merge.
- **Old #28** (`prev` / `subprev`, tag-protection reservations): baseline for imported Public source. Old Draft remains unmerged by design. Real-device safety and signed-upgrade checks remain.
- **Old #2–5, #7–15**: head commits are ancestors of old #28. Their source history is represented in the new snapshot; do not batch-merge.
- **Old #1** (history deletion button placement): head diverged, but the current Public MainActivity already has confirmation-based `confirmDeleteAll` and matching button. Treat as implemented unless a concrete UI gap is shown.
- **Old #6** (per-IV/CP favorite checks): diverged; compare exact current search semantics when modifying favorites/cleanup. Do not import old implementation blindly.
- **Old #16** (thumb-area button placement): diverged; assess current layout against real device needs before porting, rather than restoring an obsolete layout.
- **Old #17** (direct Web handoff and clipboard masking): diverged; current Public code has `ReaderWebTransfer.send`, `厳選アプリへ送る` and `ClipboardCopy`. Do not add obsolete `ReaderUiEnhancements` without identifying a missing requirement.
- **Old #18** (overlapping overlay UI/window screenshot): diverged; current Public code has `pauseVisualOverlay`, `captureGameScreen` and `takeScreenshotOfWindow`. Avoid re-applying old screenshot logic.
- **Old `experiment/catch-assist-20261107`**: contains three non-ancestral commits concerning older v1.5.68 search confirmation/release notes. Keep as historical until a concrete missing behavior is confirmed.
- **Old `feature/android-safe-full-backup-20261102`**: ancestor of imported development source; no full-branch merge required.

These classifications compare Git ancestry or identifiable code paths; they **do not certify full behavioral equivalence** or justify deleting old branches. Remaining old PRs stay as historical reference until explicitly closed after a per-feature review.

## Release and continuity

The old private signed APK workflow is retained for now. Public CI currently compiles/tests but does not upload APKs or sign a user-installable release. Before transferring signing to Public, configure protected secrets/environment, verify certificate + application ID, use a higher versionCode, and test an update **without uninstalling**. Do not copy key material into Git or expose it in Actions logs.

**Rule for future work:** inspect the Public repository first; open an old PR only if needed to recover a specific missing behavior. Commit fixes to Public `main` through a PR when appropriate.
