# PokemonIVReader (source snapshot)

This source-only snapshot is derived from a reviewed commit of a private
Android project. It deliberately has a new, independent history and contains
no Git metadata, local IDE settings, personal development notes or signing keys.

Build for development with `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
GitHub Actions compiles and tests the code but does not upload APKs while storage quota is exhausted.
A debug APK cannot safely replace the existing, differently signed installation.
Do not uninstall the existing app just to install this APK: local data may be lost.

**License:** No license for this project's original code has yet been selected.
The source being visible does not itself grant permission to reuse it. Third-party
assets/data/dependencies are governed by their own terms. Review
`THIRD_PARTY_NOTICES.md` and complete the publication checklist before publishing.

**Not a released artifact.** Actual-device tests, independent safety review,
source rights review, and full secret scanning remain publication gates.

Before switching visibility to Public, complete the items in `PUBLICATION_CHECKLIST.md`.
Confirmed upstream copyright/license notices are stored in `LICENSES/`; this is
not a clearance of Pokémon-related intellectual property or third-party media.


## Public-source candidate: bundled game-image templates omitted
This publication candidate deliberately omits prelearned Pokémon GO UI button
pixel crops and legacy raster launcher icons. Fresh installations must use the
app's existing button-learning UI to teach relevant controls before relying
on automated input. This is a functional limitation compared with the
privately distributed application, and not a change to any existing user's
installed app or learned preferences. The adaptive launcher icon uses the
existing vector resources under `mipmap-anydpi`; the Android minimum SDK is 28.
Do not publish this branch or merge it into the current Private release.
Even after these files are deleted, earlier commits of this staging repository
still contain the original pixel data. Public release must originate from a
**new independent Git history** with those files absent from its very first commit.
