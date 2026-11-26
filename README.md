# GOA OSS – Good Old Android, open source edition

GOA plays classic MS-DOS games on Android: it sets each game up for you (controls, sound card, CPU speed and the
game's own settings) so you can just press Play. Website: https://goodoldandroid.app

This repository builds **GOA OSS** (`com.goateam.goa.oss`): the same app as GOA on Google Play, with its own name and
icon and Material You colours, so it can't be mistaken for the Play version and can be installed next to it. The
edition is set in `gradle.properties` (`goa.edition=oss`).

## Building

1. Install Android Studio (or the Android SDK and NDK).
2. Install the emulator cores (not stored in git): `scripts/cores/fetch-cores.sh`
3. Build: `./gradlew assembleDebug` (the app is `app/build/outputs/apk/debug/app-debug.apk`)

## Licence

GOA is free software under the GNU General Public License, version 3 or later: see [LICENSE](LICENSE). The GOA name and
logo are not covered by the licence; modified versions must use a different name and icon: see [TRADEMARKS.md](TRADEMARKS.md). Third-party components keep their own
licences: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The emulator cores' exact source is listed at
https://goodoldandroid.app/source/.

Questions: support@goodoldandroid.app
