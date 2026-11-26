# Third-party notices

GOA ships or depends on the software and content below. The app shows the same list, with full licence texts, under **Settings → Open Source Licenses & Credits**. That list comes from `app/src/main/java/com/goa/ui/settings/ThirdPartyNotices.kt`, and the licence texts live in `app/src/main/assets/legal/licenses/`. Update all three when a dependency, native library or bundled asset changes.

The terms of use are in `app/src/main/assets/legal/terms.txt`, and the privacy policy is in `app/src/main/assets/legal/privacy.txt`. The privacy policy must also be published at a public URL for the Play Console listing.

## Native libraries (prebuilt, shipped in the APK)

| Component | Shipped as | Licence | Source |
|---|---|---|---|
| DOSBox Pure 1.0-preview7 (includes Nuked OPL3 and Munt mt32emu) | `app/src/main/jniLibs/arm64-v8a/libdosbox_pure.so` (upstream's official release build) | GPL-2.0-or-later (Nuked OPL3 and mt32emu: LGPL-2.1-or-later) | https://codeberg.org/schelling/dosbox-pure @ `cdaea10c` |
| ScummVM libretro core 2026.4.1git, plus support data (themes, shaders, SoundFont) | `third_party/scummvm/` (libretro's build, installed by `scripts/cores/fetch-cores.sh`) | GPL-3.0-or-later; support data under ScummVM's licences | https://github.com/scummvm/scummvm @ `e0ddc60b` |
| TyrQuake libretro core 0.62 (runs Quake natively) | `third_party/tyrquake/` (GOA's build with its patch, installed by `scripts/cores/fetch-cores.sh`) | GPL-2.0-or-later | https://github.com/libretro/tyrquake @ `67db338f` |
| OpenLara libretro core (runs Tomb Raider natively; includes minimp3, stb_vorbis, tinf) | `third_party/openlara/` (libretro's build, installed by `scripts/cores/fetch-cores.sh`) | BSD-2-Clause (minimp3: LGPL-2.1-or-later; stb_vorbis: public domain or MIT; tinf: zlib) | https://github.com/libretro/OpenLara @ `e4ce52ed` |
| innoextract (statically includes Boost, liblzma, bzip2, GNU libiconv) | `app/src/main/jniLibs/*/libinnoextract.so` | zlib; Boost BSL-1.0; liblzma 0BSD or public domain; bzip2 licence; libiconv LGPL-2.1 | https://github.com/dscharrer/innoextract |
| innoextract-android | JNI bridge for the above | MIT | (a1studmuffin) |
| 7-Zip 16.02 via 7-Zip-JBinding-4Android 16.02-2.03 | Gradle dependency `com.github.omicronapps:7-Zip-JBinding-4Android` (JitPack) | 7-Zip: LGPL-2.1 with the unRAR licence restriction, plus BSD-3-Clause. JBinding: LGPL-2.1 | https://www.7-zip.org, https://github.com/omicronapps/7-Zip-JBinding-4Android |

## Java/Kotlin libraries (Gradle)

| Component | Licence |
|---|---|
| AndroidX / Jetpack: Compose, Material 3, Room, WorkManager, Navigation, Adaptive, Palette, Security Crypto | Apache-2.0 |
| Google Tink (through Security Crypto) | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization, Ktor | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| Coil | Apache-2.0 |
| Google Play Billing Library 8.3 | Proprietary: Android SDK License Agreement |
| Google Play services Auth 22.0 (Google Drive backup of save states) | Proprietary: Android SDK License Agreement |

## Fonts

| Component | Shipped as | Licence | Source |
|---|---|---|---|
| Modern DOS 8x16 (version 20190101.02), © 2009-2019 Jayvee Enaguas (HarvettFox96) | `app/src/main/res/font/modern_dos_8x16.ttf` (identical to `infra/website/static/fonts/ModernDOS8x16.ttf`, which the website also serves) | CC0-1.0 (public domain dedication); text in `app/src/main/assets/legal/licenses/CC0-1.0.txt` | Recorded in the font file's own metadata; no upstream URL is stated there |

Test-only dependencies (JUnit, AndroidX Test, Espresso) are not shipped.

## Bundled and mirrored games

These are distributed unmodified under their authors' freeware or shareware terms. The R2 bucket's `manifest.json` records the licence notes and original download URLs. `FreewareCdn.kt` pins each archive by SHA-256.

| Game | Where | Terms |
|---|---|---|
| Commander Keen: Episode 1 | APK `assets/bundled_games/keen1.zip` | Shareware (id Software / Apogee) |
| DOOM: Knee-Deep in the Dead | APK `assets/bundled_games/doom1.zip` | Shareware (id Software) |
| Wolfenstein 3D: Episode 1 | APK `assets/bundled_games/wolf3d.zip` | Shareware (id Software / Apogee) |
| One Must Fall: 2097 | APK `assets/bundled_games/omf2097.zip` | Freeware (Epic MegaGames / Diversions Entertainment) |
| Tyrian 2.1 | CDN | Freeware (Jason Emery) |
| Beneath a Steel Sky | CDN, from downloads.scummvm.org | Freeware (Revolution Software) |
| Flight of the Amazon Queen | CDN, from downloads.scummvm.org | Freeware (John Passfield and Steven Stamatiadis) |

## Licence obligations to keep in mind

- **GOA itself is GPL-3.0-or-later** (see `LICENSE`). DOSBox Pure and TyrQuake are GPL-2.0-or-later and ScummVM GPL-3.0-or-later, so they all combine under GPL-3.0. OpenLara's BSD-2-Clause licence only asks that its copyright notice and licence text ship with the binary (`legal/licenses/BSD-2-Clause.txt`).
- **Corresponding source:** the cores ship unmodified upstream builds, except TyrQuake, which GOA builds from its upstream commit with GOA's patch (`scripts/cores/patches/tyrquake`, built by `scripts/cores/build-tyrquake.sh`; both are also on the CDN next to the binaries). `scripts/cores/cores.lock` records each version, its exact upstream commit, any patch and the binary's SHA-256, and https://goodoldandroid.app/source/ (generated from it) links to that source. To update a core: replace the binary, update `cores.lock`, redeploy the website. GOA's own source must be public at the URL on that page for every release. Release builds ship arm64-v8a only.
- **LGPL-2.1 (7-Zip, 7-Zip-JBinding, Nuked OPL3, mt32emu, libiconv, minimp3):** keep them as separate, replaceable libraries, and provide their source or a written offer for it.
- **7-Zip's unRAR restriction:** the RAR code must not be used to re-create the RAR compression algorithm.
- **Apache-2.0:** include the licence text and any NOTICE files. The licence texts are already in the app.
