package com.goa.ui.settings

/**
 * Everything GOA ships or relies on, with the licence files (under assets/legal/licenses) that apply. This list drives
 * Settings → Open Source Licenses & Credits; THIRD_PARTY_NOTICES.md at the repository root mirrors it. Keep both in
 * step when a dependency, native library or bundled asset is added.
 */
data class ThirdPartyComponent(
    val name: String,
    val authors: String,
    val licenseLabel: String,
    val licenseFiles: List<String>,
    val usedFor: String,
    val source: String? = null
)

data class ThirdPartySection(val title: String, val components: List<ThirdPartyComponent>)

object ThirdPartyNotices {

    const val LICENSE_DIR = "legal/licenses"
    const val TERMS_ASSET = "legal/terms.txt"
    /** GOA's own source and the exact source of the bundled emulator cores (GPL). */
    const val SOURCE_URL = "https://goodoldandroid.app/source/"
    const val PRIVACY_ASSET = "legal/privacy.txt"
    /** The FAQ shown under Settings → Help & FAQ; the website's Support page is built from the same file. */
    const val HELP_ASSET = "legal/help.txt"

    val sections: List<ThirdPartySection> = listOf(
        ThirdPartySection(
            "GOA", listOf(
                ThirdPartyComponent(
                    "GOA (Good Old Android)", "GOA Team", "GPL-3.0+",
                    listOf("GPL-3.0-only"),
                    "This app. Free software: GPL version 3 or (at your option) any later version. The GOA name and " +
                        "logo are not covered by the licence.",
                    "https://goodoldandroid.app/source/"
                )
            )
        ),
        ThirdPartySection(
            "Emulation", listOf(
                ThirdPartyComponent(
                    "DOSBox Pure 1.0-preview7", "Bernhard Schelling, based on DOSBox by the DOSBox Team", "GPL-2.0+",
                    listOf("GPL-2.0-only"),
                    "Runs every DOS game: the PC, its sound cards and its graphics. GPL version 2 or (at your option) any later version.",
                    "https://goodoldandroid.app/source/"
                ),
                ThirdPartyComponent(
                    "Nuked OPL3 (inside DOSBox Pure)", "Nuke.YKT", "LGPL-2.1",
                    listOf("LGPL-2.1-only"),
                    "Accurate AdLib and Sound Blaster FM music.",
                    "https://github.com/nukeykt/Nuked-OPL3"
                ),
                ThirdPartyComponent(
                    "Munt mt32emu (inside DOSBox Pure)", "Munt team", "LGPL-2.1",
                    listOf("LGPL-2.1-only"),
                    "Roland MT-32 music.",
                    "https://github.com/munt/munt"
                ),
                ThirdPartyComponent(
                    "ScummVM (libretro core) 2026.4.1git", "The ScummVM Team and libretro contributors", "GPL-3.0+",
                    listOf("GPL-3.0-only"),
                    "Runs point-and-click adventures such as Beneath a Steel Sky and Flight of the Amazon Queen. " +
                        "Its themes, shaders and support data are distributed with it under their own licences.",
                    "https://goodoldandroid.app/source/"
                ),
                ThirdPartyComponent(
                    "TyrQuake (libretro core) 0.62", "id Software, Kevin Shanahan and libretro contributors", "GPL-2.0+",
                    listOf("GPL-2.0-only"),
                    "Runs Quake natively from the game's own files, instead of emulating a PC. GOA's build adds one " +
                        "patch, for gamepad view centring and gyro aiming; it is on the source page. GPL version 2 " +
                        "or (at your option) any later version.",
                    "https://goodoldandroid.app/source/"
                ),
                ThirdPartyComponent(
                    "OpenLara (libretro core)", "Timur \"XProger\" Gagiev and contributors", "BSD-2-Clause",
                    listOf("BSD-2-Clause"),
                    "Runs Tomb Raider natively from the game's own files, drawn with the phone's GPU.",
                    "https://github.com/libretro/OpenLara"
                ),
                ThirdPartyComponent(
                    "minimp3 (inside OpenLara)", "Fabrice Bellard and Martin J. Fiedler, from FFmpeg", "LGPL-2.1+",
                    listOf("LGPL-2.1-only"),
                    "MP3 decoding in OpenLara. GNU LGPL version 2.1 or (at your option) any later version.",
                    "https://github.com/libretro/OpenLara/tree/master/src/libs/minimp3"
                ),
                ThirdPartyComponent(
                    "stb_vorbis (inside OpenLara)", "Sean Barrett and contributors", "Public domain / MIT",
                    listOf("MIT"),
                    "Ogg Vorbis decoding in OpenLara.",
                    "https://github.com/nothings/stb"
                ),
                ThirdPartyComponent(
                    "tinf (inside OpenLara)", "Joergen Ibsen", "Zlib",
                    listOf("Zlib"),
                    "Decompression in OpenLara.",
                    "https://github.com/jibsen/tinf"
                )
            )
        ),
        ThirdPartySection(
            "Unpacking games", listOf(
                ThirdPartyComponent(
                    "7-Zip", "Igor Pavlov", "LGPL-2.1 + unRAR restriction + BSD",
                    listOf("7-Zip", "LGPL-2.1-only", "BSD-3-Clause"),
                    "Unpacks .7z archives, including the BCJ2-compressed programs found in DOS collections.",
                    "https://www.7-zip.org"
                ),
                ThirdPartyComponent(
                    "7-Zip-JBinding-4Android", "Boris Brodski and omicronapps", "LGPL-2.1",
                    listOf("LGPL-2.1-only"),
                    "Connects 7-Zip to Android.",
                    "https://github.com/omicronapps/7-Zip-JBinding-4Android"
                ),
                ThirdPartyComponent(
                    "innoextract", "Daniel Scharrer", "zlib",
                    listOf("Zlib"),
                    "Unpacks GOG's Inno Setup installers without running any Windows code.",
                    "https://github.com/dscharrer/innoextract"
                ),
                ThirdPartyComponent(
                    "innoextract-android", "a1studmuffin", "MIT",
                    listOf("MIT"),
                    "Builds innoextract for Android."
                ),
                ThirdPartyComponent(
                    "Boost C++ Libraries (inside innoextract)", "Boost contributors", "BSL-1.0",
                    listOf("BSL-1.0"),
                    "Support code for innoextract.",
                    "https://www.boost.org"
                ),
                ThirdPartyComponent(
                    "XZ Utils liblzma (inside innoextract)", "Lasse Collin and contributors", "0BSD / public domain",
                    listOf("0BSD"),
                    "LZMA decompression for installers.",
                    "https://tukaani.org/xz/"
                ),
                ThirdPartyComponent(
                    "bzip2 (inside innoextract)", "Julian Seward", "bzip2",
                    listOf("bzip2-1.0.6"),
                    "bzip2 decompression for installers.",
                    "https://sourceware.org/bzip2/"
                ),
                ThirdPartyComponent(
                    "GNU libiconv (inside innoextract)", "Free Software Foundation", "LGPL-2.1",
                    listOf("LGPL-2.1-only"),
                    "Converts file names in installers.",
                    "https://www.gnu.org/software/libiconv/"
                )
            )
        ),
        ThirdPartySection(
            "App libraries", listOf(
                ThirdPartyComponent(
                    "Android Jetpack: Compose, Material 3, Room, WorkManager, Navigation, Adaptive, Palette, Security",
                    "Google LLC and the Android Open Source Project", "Apache-2.0",
                    listOf("Apache-2.0"),
                    "The app's screens, game library database, background jobs and encrypted sign-in storage.",
                    "https://developer.android.com/jetpack"
                ),
                ThirdPartyComponent(
                    "Google Tink (inside Jetpack Security)", "Google LLC", "Apache-2.0",
                    listOf("Apache-2.0"),
                    "Encrypts your GOG sign-in on the device.",
                    "https://github.com/tink-crypto/tink-java"
                ),
                ThirdPartyComponent(
                    "Kotlin, kotlinx.coroutines, kotlinx.serialization and Ktor", "JetBrains s.r.o. and contributors", "Apache-2.0",
                    listOf("Apache-2.0"),
                    "The language runtime, background work, JSON and networking.",
                    "https://github.com/ktorio/ktor"
                ),
                ThirdPartyComponent(
                    "OkHttp and Okio", "Square, Inc.", "Apache-2.0",
                    listOf("Apache-2.0"),
                    "HTTP connections and downloads.",
                    "https://github.com/square/okhttp"
                ),
                ThirdPartyComponent(
                    "Coil", "Coil contributors", "Apache-2.0",
                    listOf("Apache-2.0"),
                    "Loads box art and banners.",
                    "https://github.com/coil-kt/coil"
                )
            )
        ),
        ThirdPartySection(
            "Fonts", listOf(
                ThirdPartyComponent(
                    "Modern DOS 8x16 font", "Jayvee Enaguas (HarvettFox96)", "CC0-1.0",
                    listOf("CC0-1.0"),
                    "The typeface used for the app's text, the same one goodoldandroid.app uses. Released into the " +
                        "public domain under Creative Commons Zero 1.0 (© 2009-2019, version 20190101.02)."
                )
            )
        ),
        ThirdPartySection(
            "Google services", listOf(
                ThirdPartyComponent(
                    "Google Play Billing Library", "Google LLC", "Android SDK License",
                    emptyList(),
                    "Handles the one-time GOA Pro purchase. Not open source; governed by the Android Software " +
                        "Development Kit License Agreement and the Google Play Developer Distribution Agreement.",
                    "https://developer.android.com/studio/terms"
                ),
                ThirdPartyComponent(
                    "Google Play services Auth", "Google LLC", "Android SDK License",
                    emptyList(),
                    "Asks Google for permission to keep save states in your own Google Drive (GOA Pro). Not open " +
                        "source; governed by the Android Software Development Kit License Agreement.",
                    "https://developer.android.com/studio/terms"
                )
            )
        )
    )

    /** Shown under the licences: people and projects GOA builds on without shipping their code. */
    val thanks: List<String> = listOf(
        "GOG.com, for championing classic PC game preservation and DRM-free games.",
        "Revolution Software, for releasing Beneath a Steel Sky and Lure of the Temptress as freeware.",
        "John Passfield and Steven Stamatiadis, for releasing Flight of the Amazon Queen as freeware.",
        "Epic MegaGames and Diversions Entertainment, for releasing One Must Fall: 2097 as freeware.",
        "Allen Pilgrim, for releasing Xargon as freeware.",
        "The ScummVM team, for hosting the freeware adventure releases and keeping them playable.",
        "The libretro project, for the DOSBox Pure and ScummVM cores and their build servers.",
        "AMD FidelityFX Super Resolution and Snapdragon Game Super Resolution, whose ideas inspired GOA's sharpening shader.",
        "OMF2097.com and ClassicDOSGames.com, for keeping freeware DOS games available.",
        "The Internet Archive and the DOS preservation community, for keeping software history alive."
    )
}
