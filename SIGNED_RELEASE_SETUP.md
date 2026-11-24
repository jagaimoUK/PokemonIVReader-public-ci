# Signed APK releases from the Public Android repository

Canonical source: `jagaimoUK/PokemonIVReader-public-ci` / `main`. This workflow
is manual only, never starts on PRs or pushes, and only accepts the owner on
the exact canonical `main` branch.

## One-time owner setup (do not paste credentials into an issue or chat)

1. In the Public repository, navigate to **Settings → Environments → New environment**.
   Create an environment named exactly `android-signed-release`.
2. Under **Deployment branches and tags**, restrict the environment to **Selected
   branches and tags** and add exactly `main`. Other branches and tags must
   not deploy to this environment. Reviewers are optional: for a one-person
   account do **not** enable a policy that blocks the author from approving
   their own release, because that can prevent signing entirely. Manual
   workflow invocation is already required.
3. Under **Environment secrets** (not source files or repository variables),
   securely configure these four secrets using the *existing* keystore:
   `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_STORE_PASSWORD`,
   `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`.
   A secret's value cannot be read back from another repository in GitHub.
   Retrieve the keystore from your own secure backup; do not create a new key.
   If the old keystore is unavailable, **stop** and keep signing in the old
   Private repository until a safe, explicitly approved migration is possible.
4. The tracked `release/installed-apk-identity.properties` is public
   **certificate metadata only**, copied from the older Private project.
   Its fingerprint is not the signing key. Never commit a keystore,
   password, base64 key or private training images here.
5. Ensure the GitHub Actions workflow can run and that `main` modifications
   are reviewed before manual signing. Consider branch protection or a
   ruleset preventing unreviewed direct pushes to `main`.

For a **local securely held keystore** on Windows, to prepare the base64
secret *without printing it to a terminal*, run this in PowerShell
(only on your own device, never in a shared shell or CI):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\\secure\\existing-signing.jks")) | Set-Clipboard
```

Paste **only into GitHub's Environment secret input** for the key base64, then
clear your clipboard using `Set-Clipboard -Value ''`. Replace the example
path with your real secure backup location. Never upload the file to the
repository and never paste it into ChatGPT.

## Publish a new version

1. Update `app/build.gradle.kts` on a reviewed PR and merge into Public
   `main` with a **new** `versionName` and strictly greater
   `versionCode`. At migration time, source is **1.5.73 / 98**; do not
   rerun v1.5.73. Check the real version installed on your phone.
2. Confirm the normal Public Android reader checks are successful for the
   exact new `main` commit.
3. Go to **Actions → Signed Android APK (manual public release) → Run workflow**.
   Choose **main**, input `release_tag` (e.g. `v1.5.74`),
   `expected_version_code` (e.g. `99`) and
   `installed_version_code` (the actual current phone version, e.g. `98`).
   Do not guess the installed code if unknown.
4. The job checks the repository identity, inputs, old-app fingerprint,
   tests, build, signing certificate, application ID, APK version and unused
   release tag. Any mismatch blocks publication. Secret values are used only
   by the signing step and are not uploaded.
5. If successful, the APK and `SHA256SUMS.txt` appear at
   https://github.com/jagaimoUK/PokemonIVReader-public-ci/releases
   as a **PUBLIC prerelease**, not as an Actions artifact. This avoids
   Actions artifact quota. Release access is public: do not include private
   data in the APK or notes.
6. Verify the signer and version and test on your Motorola edge 50 by
   **installing over** the existing app. Never uninstall to force a mismatched
   debug APK to install; history, preferences and learned data could be lost.

## Important limits

Public CI build success does not prove a real-device upgrade or GO automation.
The new Public snapshot deliberately omits bundled game-image button templates
and old raster assets. Fresh installs may require button learning. Existing
device data should remain untouched by the packaging process, but real-device
testing is still required before claiming compatibility. This workflow does
not modify the old Private repository, its secrets or its Releases.

Do not run the signed workflow until the environment secrets, restrictions,
current version and candidate source are verified. This document does not
itself authorize publication of an APK.
