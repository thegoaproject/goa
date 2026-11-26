import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

// Machine-local settings: a Gradle property (-P or ~/.gradle/gradle.properties) wins over local.properties
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun goaProp(name: String): String? = providers.gradleProperty(name).orNull ?: localProps.getProperty(name)

// Edition. "play" (the default here) is GOA as published on Google Play. "oss" is GOA OSS, what the public source
// builds: scripts/publish-public-source.sh writes goa.edition=oss into the published gradle.properties on every
// publish. GOA OSS has its own package, name, icon and a Material You colour scheme, so the two never get mixed up
// and can sit side by side on one phone, and it never carries GOA's private keys or Play publishing.
val ossEdition = goaProp("goa.edition")?.trim() == "oss"

// Google Cloud project number linked to the app in Play Console (App integrity > Play Integrity API). Not a secret.
// Play release builds need it: without Play Integrity the registry service refuses the app (no game patches).
val integrityCloudProject = if (ossEdition) 0L else goaProp("goa.integrityCloudProject")?.trim()?.toLongOrNull() ?: 0L

android {
    namespace = "com.goa"
    compileSdk = 36

    defaultConfig {
        // Play Store package name (permanent once uploaded). The code package/namespace stays com.goa.
        applicationId = if (ossEdition) "com.goateam.goa.oss" else "com.goateam.goa"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AndroidManifest.xml reads the app's name, icon and theme from here
        manifestPlaceholders += if (ossEdition) mapOf(
            "appLabel" to "GOA OSS", "appIcon" to "@mipmap/ic_launcher_oss", "appRoundIcon" to "@mipmap/ic_launcher_oss_round",
            "appTheme" to "@style/Theme.Goa.Oss"
        ) else mapOf(
            "appLabel" to "GOA", "appIcon" to "@mipmap/ic_launcher", "appRoundIcon" to "@mipmap/ic_launcher_round",
            "appTheme" to "@style/Theme.Goa"
        )
        // GOA OSS: Material You colours (ui/theme/Theme.kt) instead of GOA's own palette
        buildConfigField("boolean", "OSS_EDITION", "$ossEdition")

        // Public base URL of the "goa" R2 bucket holding the freeware archives (r2.dev or custom domain), e.g.
        // `-Pgoa.cdnBaseUrl=https://cdn.example.com`. Empty falls back to each archive's original download site.
        buildConfigField("String", "FREEWARE_CDN_BASE_URL", "\"${providers.gradleProperty("goa.cdnBaseUrl").orNull.orEmpty()}\"")
        // X-GOA-Client header value checked by the CDN Worker (infra/cdn-worker). Kept out of git: set it in
        // local.properties or as -Pgoa.cdnClientKey. Without it, downloads fall back to the original sites.
        buildConfigField("String", "FREEWARE_CDN_CLIENT_KEY", "\"${if (ossEdition) "" else goaProp("goa.cdnClientKey").orEmpty()}\"")
        // Game patch registry (infra/registry-worker): per-game controls and sound settings, fetched when the app opens.
        // Only Google Play installs get it: the app proves it is one with Play Integrity (data/repository/RegistryAccess.kt).
        buildConfigField("String", "REGISTRY_BASE_URL", "\"${providers.gradleProperty("goa.registryBaseUrl").orNull ?: "https://registry.goodoldandroid.app"}\"")
        buildConfigField("long", "PLAY_INTEGRITY_CLOUD_PROJECT", "${integrityCloudProject}L")
        // Debug builds skip Play Integrity with this key (the registry Worker's DEV_KEY secret); release builds never have it
        buildConfigField("String", "REGISTRY_DEV_KEY", "\"\"")
        // Opt-in telemetry collection service (infra/telemetry-worker); empty disables uploads
        buildConfigField("String", "TELEMETRY_BASE_URL", "\"${providers.gradleProperty("goa.telemetryBaseUrl").orNull.orEmpty()}\"")
        // Google Drive backup of save states (specs/009-drive-save-states, research R10). Never in GOA OSS (no Pro, no
        // OAuth client). Ships dark: on in debug builds only until the Google Cloud OAuth clients exist and the
        // cross-install check (quickstart step 1) has passed; then set it for release too.
        buildConfigField("boolean", "DRIVE_BACKUP", "false")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    // Play upload key. The keystore and its passwords live outside the repository (~/.goa-keys and ~/.gradle/gradle.properties:
    // goa.uploadStoreFile, goa.uploadStorePassword, goa.uploadKeyAlias, goa.uploadKeyPassword). Without them the release
    // build still works but is unsigned, so Play will not accept it. Google re-signs with the app signing key.
    val uploadStoreFile = providers.gradleProperty("goa.uploadStoreFile").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("goa.uploadStorePassword").orNull
                keyAlias = providers.gradleProperty("goa.uploadKeyAlias").orNull
                keyPassword = providers.gradleProperty("goa.uploadKeyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "REGISTRY_DEV_KEY", "\"${if (ossEdition) "" else goaProp("goa.registryDevKey").orEmpty()}\"")
            buildConfigField("boolean", "DRIVE_BACKUP", "${!ossEdition}")
            // `-Pgoa.nativeOptimized=true`: compile GOA's native code optimised, as release does, in a debuggable app
            // that installs over a debug build. Timing bugs between threads can hide in the unoptimised build.
            if (goaProp("goa.nativeOptimized")?.trim() == "true") {
                externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
            }
        }
        release {
            // GOA OSS is never signed with GOA's Play upload key
            if (!ossEdition) signingConfigs.findByName("upload")?.let { signingConfig = it }
            // Native symbol tables go into the bundle (not the installed app), so Play Console can symbolicate native
            // crash and ANR stacks from the DOSBox/ScummVM cores
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release builds ship arm64-v8a only: every core there has published source (scripts/cores/cores.lock), and
    // dependencies' extra ABIs (7-Zip's armeabi-v7a/x86) are dropped. x86_64 stays in debug builds for the emulator.
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(listOf("**/x86_64/**", "**/x86/**", "**/armeabi-v7a/**", "**/armeabi/**"))
    }

    // `-Pgoa.abi=x86_64` packages a single ABI (the ScummVM core is ~100 MB per ABI); handy for emulator test installs
    providers.gradleProperty("goa.abi").orNull?.let { onlyAbi ->
        packaging.jniLibs.excludes += listOf("arm64-v8a", "x86_64").filter { it != onlyAbi }.map { "**/$it/**" }
    }

    // Room schemas feed MigrationTestHelper in instrumented tests
    sourceSets.getByName("androidTest").assets.srcDir(file("schemas"))

    // Private experiments live on main in app/src/spike (constitution: Development Workflow): debug builds only, and
    // never in the public source (scripts/publish-public-source.sh publishes app/src/main only)
    if (file("src/spike").isDirectory) {
        sourceSets.getByName("debug") {
            java.srcDir("src/spike/java")
            res.srcDir("src/spike/res")
            assets.srcDir("src/spike/assets")
        }
    }

    // Optional ScummVM libretro core + support data, installed by scripts/cores/fetch-cores.sh (pinned in scripts/cores/cores.lock; not in git)
    val scummVmDir = rootProject.file("third_party/scummvm")
    if (scummVmDir.resolve("jniLibs").exists()) {
        sourceSets.getByName("main") {
            jniLibs.srcDir(scummVmDir.resolve("jniLibs"))
            assets.srcDir(scummVmDir.resolve("assets"))
        }
    }

    // Optional TyrQuake libretro core (Quake and its mission packs, run natively instead of in DOSBox), pinned in
    // scripts/cores/cores.lock; not in git
    val tyrQuakeDir = rootProject.file("third_party/tyrquake")
    if (tyrQuakeDir.resolve("jniLibs").exists()) {
        sourceSets.getByName("main") { jniLibs.srcDir(tyrQuakeDir.resolve("jniLibs")) }
    }
    defaultConfig { buildConfigField("boolean", "HAS_TYRQUAKE", "${tyrQuakeDir.resolve("jniLibs").exists()}") }

    // Optional OpenLara libretro core (Tomb Raider's engine rebuilt, drawn with the GPU instead of an emulated
    // Pentium's software renderer), pinned in scripts/cores/cores.lock; not in git
    val openLaraDir = rootProject.file("third_party/openlara")
    if (openLaraDir.resolve("jniLibs").exists()) {
        sourceSets.getByName("main") { jniLibs.srcDir(openLaraDir.resolve("jniLibs")) }
    }
    defaultConfig { buildConfigField("boolean", "HAS_OPENLARA", "${openLaraDir.resolve("jniLibs").exists()}") }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// A Play release without the Play Integrity project could never download game patches: refuse to build it
if (!ossEdition) tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val cloudProject = integrityCloudProject
    doFirst {
        if (cloudProject == 0L) throw GradleException("Set goa.integrityCloudProject (Google Cloud project number) in local.properties")
    }
}

// Publishing to Google Play from the command line (Gradle Play Publisher):
//   ./gradlew publishBundle                       upload a release to the internal testing track
//   ./gradlew publishBundle --track=production --release-status=inProgress --user-fraction=0.2   staged rollout
//   ./gradlew promoteArtifact --from-track=internal --promote-track=production                   promote a tested build
// The service account key (goa.playCredentials, a path outside the repo) needs release rights in Play Console. The
// version code is set to one above the highest on Play at upload time. Release notes: src/main/play/release-notes.
play {
    // GOA OSS is not a Play app: publishing tasks are switched off for it
    enabled.set(!ossEdition)
    if (!ossEdition) goaProp("goa.playCredentials")?.let { serviceAccountCredentials.set(file(it)) }
    track.set("internal")
    defaultToAppBundles.set(true)
    resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.AUTO)
}

// Room schema export: migration SQL and MigrationTestHelper baselines (app/schemas/)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose & Material 3 Expressive
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)

    // Adaptive & Navigation
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    implementation(libs.androidx.navigation.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager & Downloader
    implementation(libs.androidx.work.runtime.ktx)

    // Palette & Coil Media
    implementation(libs.androidx.palette.ktx)
    implementation(libs.coil.compose)

    // Ktor & Network
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Keystore / Crypto
    implementation(libs.androidx.security.crypto)

    // .7z archives (DOS game collections ship as 7z with BCJ2-filtered executables, which pure-Java readers can't open)
    implementation(libs.sevenzip.jbinding)

    // "GOA Pro": one-time Google Play purchase that lifts the free game-slot limit
    implementation(libs.billing.ktx)
    // Proves to the registry service that this copy was installed from Google Play
    implementation(libs.play.integrity)
    // GOA Pro: save states copied to the player's own Google Drive (drive.file scope, no sign-in, no server)
    implementation(libs.play.services.auth)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
