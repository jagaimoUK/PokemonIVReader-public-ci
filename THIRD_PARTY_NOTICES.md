# Third-party notices and source provenance

This repository is a **private, source-only staging copy**. Adding upstream
license notices does not by itself authorize publication of artwork, game UI,
trademarks, or any other rights belonging to separate owners.

## Verified notices

- **PvPoke**, MIT, Copyright (c) 2019 pvpoke.
  Upstream: https://github.com/pvpoke/pvpoke/blob/master/LICENSE
  The build-catalog script reads PvPoke gamemaster species data. The app also
  bundles `app/src/main/assets/pvpoke_overall_1500.json` ranking data.
  Preserve the full upstream notice at `LICENSES/PVPOKE-MIT.txt`.
- **PokéAPI**, BSD-3-Clause, Copyright 2013–2023 Paul Hallett and PokéAPI
  contributors. Upstream: https://github.com/PokeAPI/pokeapi/blob/master/LICENSE.md
  The build-catalog script uses `pokemon_species_names.csv` for species names.
  Preserve the full upstream notice at `LICENSES/POKEAPI-BSD-3-Clause.txt`.
- **RegiBot models**, MIT, Copyright (c) 2025 Juancavr6.
  Pinned upstream source:
  https://github.com/Juancavr6/RegiBot/tree/f242ee151a5447e4cfe83db4ee9090459cc23503
  Existing full notice: `app/src/main/assets/licenses/regibot-models.txt`.
  The build fetches three pinned model files and checks their SHA-256 hashes.

## Not yet cleared or fully assessed

- **Pokémon intellectual property**: upstream software licenses do not grant
  rights to Pokémon characters, names, trademarks, or game artwork.
- **Button pixel templates**: `app/src/main/assets/go_buttons/*.json`
  contain numeric pixel data corresponding to game UI buttons. Exact
  ownership, derivation method, and publication permission remain unverified.
- **Android robot launcher artwork**: the vector icon source contains an Android robot motif. Google's official Android brand guidelines describe conditions under CC BY 3.0; see https://developer.android.com/distribute/marketing-tools/brand-guidelines . The actual bundled WebP icon pixels remain to be visually confirmed. The Android robot artwork is derived from material created and shared by Google LLC and is attributed under Creative Commons Attribution 3.0: https://creativecommons.org/licenses/by/3.0/ . This is not a grant to use the Android wordmark or other trademarks.
- **Android launcher icons**: `app/src/main/res/mipmap-*/ic_launcher*.webp`
  have not been verified as entirely original or licensed for publication.
- **Google MobileNet V3 model**: the build downloads
  `mobilenet_v3_small.tflite` from Google's MediaPipe model storage and
  checks a fixed digest. Its applicable model-weight redistribution terms
  have not yet been independently verified.
- **Other dependencies**: review the resolved MediaPipe, AndroidX, Google ML
  Kit, Kotlin, and remaining dependency licenses and notices.
- **Embedded flashcard page**:
  `app/src/main/assets/flashcards.html` has not received a separate
  third-party content/provenance review.

The presence of source code in a publicly accessible repository would not
itself license this project's original code. No project-wide license has been
selected, and this file is **not** a Public-release approval.

## Embedded flashcard bootstrap data (source-only candidate)

The first source-only publication candidate no longer bundles the previously
embedded **`meta:legacy`** custom-meta deck (72 rows). The source of that
legacy selection was not independently established as a generic public preset.
Previously stored user-specific decks, notes, and reviews are held in on-device
IndexedDB and are not part of this repository. Existing local data is merged
without dropping old entries, while custom meta can be synced after login.
The remaining `meta:pvpoke:*` presets and bundled move/species information
still require upstream game-data redistribution review. This change is **not**
a declaration that the remaining data is licensed for public redistribution.
