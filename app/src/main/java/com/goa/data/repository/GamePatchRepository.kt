package com.goa.data.repository

import android.content.Context
import android.util.Log
import com.goa.domain.parser.SoundHardware
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class ConfigFormat {
    WHITESPACE_KV,
    INI_EQUALS,
    INI_SECTION_EQUALS,
    QUAKE_CFG
}

data class PatchMatchRule(
    val gogIds: Set<Long>,
    val slugPatterns: List<String>,
    val signatureFiles: Set<String>,
    // How many listed signature files must be present (capped at the list size). Harvested profiles use 2 so a
    // single common file such as HELPME.EXE in another game's install can't trigger a false match.
    val minSignatureHits: Int = 1
)

data class DosBoxPatchOverrides(
    val sbtype: String = "sb16",
    val sbbase: String = "220",
    val irq: String = "7",
    val dma: String = "1",
    val hdma: String = "5",
    val oplmode: String = "auto",
    val oplemu: String = "default",
    val rate: String = "48000",
    val gus: String = "false",
    val cycles: String = "auto",
    val bypassChoiceMenu: Boolean = false
)

data class ConfigPatchRule(
    val filePatterns: Set<String>,
    val format: ConfigFormat,
    val entries: Map<String, String>
)

data class ControlPatchOverrides(
    val stickMode: String? = null,
    val buttonBindings: Map<String, String>? = null,
    /**
     * The game's own name for what a button does ("Jump", "Cast spell"), from its documentation. Shown instead of GOA's
     * generic action name, which only names the key ("Fire (Ctrl)" for a game that jumps on Ctrl).
     */
    val buttonLabels: Map<String, String>? = null,
    /**
     * The action that centres the view (Hexen, Heretic: "key_kp7", the scancode of the Home key their config puts
     * look-center on; Duke, Shadow Warrior: "key_kp5", their Center_View). Set, GOA taps it once the right stick has
     * been at rest a moment after looking.
     */
    val lookRecenterKey: String? = null,
    /**
     * The action whose key the game's config binds to hold-to-mouse-look (DOS Quake: "key_f8", bound to +mlook). Set,
     * the right stick and gyro look up and down through vertical mouse motion, in proportion, with GOA holding this
     * key only while they move: the game walks with vertical mouse otherwise, and centres the view only once it is up.
     */
    val mouseLookKey: String? = null
)

/**
 * A byte-level edit to one of the game's own files, such as the settings a game's SETUP program saves inside a data
 * file. Applied only while the file has [size] bytes (when given) and still holds [expect] at [offset], so it runs
 * safely before every launch and never overwrites a choice the player made later in the game's own setup.
 */
data class BinaryPatch(
    val file: String,
    val size: Long?,
    val offset: Long,
    val expect: ByteArray,
    val bytes: ByteArray
)

/**
 * A file the game would otherwise create by asking the player questions on first run (sound card, music), written
 * into the folder holding [nextTo] (or the install folder) only when no file of that name exists yet.
 */
class SeedFile(
    val file: String,
    val nextTo: String?,
    val bytes: ByteArray
)

/** Sound devices the profile configures (sfx/music ids such as "sb16", "opl2", "pcspeaker"). */
data class SoundPresetInfo(
    val sfx: String?,
    val music: String?
)

data class GamePatchProfile(
    val id: String,
    val name: String,
    val description: String,
    val match: PatchMatchRule,
    val dosboxOverrides: DosBoxPatchOverrides,
    val configRules: List<ConfigPatchRule>,
    val controlOverrides: ControlPatchOverrides? = null,
    val binaryPatches: List<BinaryPatch> = emptyList(),
    val seedFiles: List<SeedFile> = emptyList(),
    // Catch-all profiles (e.g. HMI/Miles audio) still apply their config rules but never supply the
    // primary DOSBox overrides, so a game-specific profile further down the list wins
    val universal: Boolean = false,
    val soundPreset: SoundPresetInfo? = null,
    // Harvested profiles only: "readme" | "config" | "mixed" | "manual" | "web" | "genre" | "knowledge" | "default"; null for curated profiles
    val evidence: String? = null,
    val confidence: Float? = null
)

class GamePatchRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "GamePatchRepository"
        /** A button's game-given name fits on one line of the controls list. */
        private const val MAX_LABEL = 32

        /** Bumped when device-written profiles change, so UI showing matches can refresh. */
        val profilesVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        fun markProfilesChanged() { profilesVersion.value = profilesVersion.value + 1 }

        /**
         * The CDN's published defaults (PublishedDefaultsSync) and the game patch registry (RegistrySync), under
         * filesDir. The registry is the only source of the curated and harvested per-game profiles: they are not
         * bundled in the app. (The parked AI troubleshooter, branch park/ai-troubleshooter, also wrote
         * patches/ai_overrides.json here; it is no longer read.)
         */
        const val PUBLISHED_DEFAULTS_PATH = "patches/published_defaults.json"
        const val REGISTRY_PATH = "patches/registry_remote.json"

        @Volatile
        private var cachedRegistry: Pair<String, List<GamePatchProfile>>? = null
        private const val LOCAL_PATCH_FILENAME = "game_patches_override.json"
        /** A Build CFG's Center_View with no primary key (`Center_View = "" ""`): the secondary key is group 2. */
        internal val CENTER_VIEW_UNBOUND = Regex("""(?i)(Center_View\s*=\s*)""(.*)""")

        /** [line] with an unbound Center_View's primary key set to keypad 5 (its secondary kept), else null. */
        internal fun bindCenterView(line: String): String? =
            CENTER_VIEW_UNBOUND.matchEntire(line.trim())?.let { "${it.groupValues[1]}\"KPad5\"${it.groupValues[2]}" }

        /**
         * True when [pattern] appears in [slug] as whole underscore-separated words, so "doom_2" matches
         * "doom_2_hell_on_earth" but "nam" no longer matches "dynamite" and "blood" no longer matches "bloodnet".
         */
        /** A profile matches by GOG id, a whole-word slug pattern, or enough of its signature files being installed. */
        fun profileMatches(match: PatchMatchRule, gameId: Long, slug: String?, installedUpperNames: Set<String>): Boolean {
            if (match.gogIds.contains(gameId)) return true
            if (match.slugPatterns.any { pattern -> slugMatchesPattern(slug, pattern) }) return true
            val signatures = match.signatureFiles
            if (signatures.isEmpty()) return false
            val hits = signatures.count { sig -> installedUpperNames.contains(sig.uppercase(Locale.ROOT)) }
            return hits >= match.minSignatureHits.coerceAtMost(signatures.size)
        }

        fun slugMatchesPattern(slug: String?, pattern: String): Boolean {
            if (slug.isNullOrBlank() || pattern.isBlank()) return false
            val normalize = { s: String -> s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_') }
            return "_${normalize(slug)}_".contains("_${normalize(pattern)}_")
        }

        private fun hexBytes(text: String): ByteArray? {
            val hex = text.filterNot { it.isWhitespace() }
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            return runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
        }

        /** `[{file, nextTo?, base64}]`; malformed entries and names with a path are skipped. */
        internal fun parseSeedFiles(arr: org.json.JSONArray?): List<SeedFile> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') && it != ".." }
                    ?: return@mapNotNull null
                val bytes = runCatching { java.util.Base64.getDecoder().decode(o.optString("base64")) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SeedFile(file, o.optString("nextTo").takeIf { it.isNotBlank() }, bytes)
            }
        }

        /**
         * Writes [seed] into the folder holding a file named `nextTo` (any depth; the install folder when unset or
         * absent). Returns the new file, or null when one by that name was already there (the player's own settings).
         */
        internal fun applySeedFile(installDir: File, allFiles: List<File>, seed: SeedFile): File? = runCatching {
            val dir = seed.nextTo?.let { anchor -> allFiles.firstOrNull { it.name.equals(anchor, ignoreCase = true) }?.parentFile }
                ?: installDir
            if (dir.listFiles()?.any { it.name.equals(seed.file, ignoreCase = true) } == true) return null
            File(dir, seed.file).also {
                it.writeBytes(seed.bytes)
                Log.i(TAG, "Seeded ${it.absolutePath} (${seed.bytes.size} bytes)")
            }
        }.getOrElse { err ->
            Log.e(TAG, "Seeding ${seed.file} failed", err)
            null
        }

        /** `[{file, size?, offset, expect, bytes}]` with hex byte strings; malformed entries are skipped. */
        internal fun parseBinaryPatches(arr: org.json.JSONArray?): List<BinaryPatch> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val offset = o.optLong("offset", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
                val expect = hexBytes(o.optString("expect")) ?: return@mapNotNull null
                val bytes = hexBytes(o.optString("bytes")) ?: return@mapNotNull null
                if (expect.size != bytes.size) return@mapNotNull null
                BinaryPatch(file, o.optLong("size", -1L).takeIf { it > 0 }, offset, expect, bytes)
            }
        }

        /**
         * Writes [patch] into [file] when the file still holds the expected bytes. Returns true when the file now
         * carries the patched bytes (written now or earlier), false when it is a different file or the player
         * changed those bytes themselves.
         */
        internal fun applyBinaryPatch(file: File, patch: BinaryPatch): Boolean = runCatching {
            if (patch.size != null && file.length() != patch.size) return false
            if (patch.offset + patch.bytes.size > file.length()) return false
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val current = ByteArray(patch.bytes.size)
                raf.seek(patch.offset)
                raf.readFully(current)
                when {
                    current.contentEquals(patch.bytes) -> true
                    current.contentEquals(patch.expect) -> {
                        raf.seek(patch.offset)
                        raf.write(patch.bytes)
                        Log.i(TAG, "Binary patch: ${file.name} @${patch.offset} (${patch.bytes.size} bytes)")
                        true
                    }
                    else -> false
                }
            }
        }.getOrElse { err ->
            Log.e(TAG, "Binary patch failed: ${file.absolutePath}", err)
            false
        }

        fun parseProfilesJson(jsonText: String): List<GamePatchProfile> {
            val result = mutableListOf<GamePatchProfile>()
            val root = JSONObject(jsonText)
            val profilesArr = root.optJSONArray("profiles") ?: return result

            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.optString("name", id)
                val description = obj.optString("description", "")

                val matchObj = obj.optJSONObject("match") ?: JSONObject()
                val gogIds = mutableSetOf<Long>()
                matchObj.optJSONArray("gogIds")?.let { arr ->
                    for (j in 0 until arr.length()) gogIds.add(arr.getLong(j))
                }
                val slugPatterns = mutableListOf<String>()
                matchObj.optJSONArray("slugPatterns")?.let { arr ->
                    for (j in 0 until arr.length()) slugPatterns.add(arr.getString(j))
                }
                val signatureFiles = mutableSetOf<String>()
                matchObj.optJSONArray("signatureFiles")?.let { arr ->
                    for (j in 0 until arr.length()) signatureFiles.add(arr.getString(j))
                }

                val dbObj = obj.optJSONObject("dosboxOverrides") ?: JSONObject()
                val dosboxOverrides = DosBoxPatchOverrides(
                    sbtype = dbObj.optString("sbtype", "sb16"),
                    sbbase = dbObj.optString("sbbase", "220"),
                    irq = dbObj.optString("irq", "7"),
                    dma = dbObj.optString("dma", "1"),
                    hdma = dbObj.optString("hdma", "5"),
                    oplmode = dbObj.optString("oplmode", "auto"),
                    oplemu = dbObj.optString("oplemu", "default"),
                    rate = dbObj.optString("rate", "48000"),
                    gus = dbObj.optString("gus", "false"),
                    cycles = dbObj.optString("cycles", "auto"),
                    bypassChoiceMenu = dbObj.optBoolean("bypassChoiceMenu", false)
                )

                val rulesList = mutableListOf<ConfigPatchRule>()
                obj.optJSONArray("configRules")?.let { rulesArr ->
                    for (j in 0 until rulesArr.length()) {
                        val rObj = rulesArr.getJSONObject(j)
                        val filePatterns = mutableSetOf<String>()
                        rObj.optJSONArray("filePatterns")?.let { fArr ->
                            for (k in 0 until fArr.length()) filePatterns.add(fArr.getString(k))
                        }
                        val formatStr = rObj.optString("format", "WHITESPACE_KV")
                        val format = when {
                            formatStr.equals("INI_SECTION_EQUALS", ignoreCase = true) -> ConfigFormat.INI_SECTION_EQUALS
                            formatStr.equals("INI_EQUALS", ignoreCase = true) -> ConfigFormat.INI_EQUALS
                            formatStr.equals("QUAKE_CFG", ignoreCase = true) -> ConfigFormat.QUAKE_CFG
                            else -> ConfigFormat.WHITESPACE_KV
                        }
                        val entries = mutableMapOf<String, String>()
                        rObj.optJSONObject("entries")?.let { eObj ->
                            val keys = eObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                entries[k] = eObj.getString(k)
                            }
                        }
                        rulesList.add(ConfigPatchRule(filePatterns, format, entries))
                    }
                }

                val ctrlObj = obj.optJSONObject("controlOverrides")
                val controlOverrides = if (ctrlObj != null) {
                    val stickMode = ctrlObj.optString("stickMode").takeIf { it.isNotBlank() }
                    val bindings = mutableMapOf<String, String>()
                    ctrlObj.optJSONObject("buttonBindings")?.let { bObj ->
                        val keys = bObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            bindings[k] = bObj.getString(k)
                        }
                    }
                    val labels = mutableMapOf<String, String>()
                    ctrlObj.optJSONObject("buttonLabels")?.let { lObj ->
                        val keys = lObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            lObj.optString(k).trim().take(MAX_LABEL).takeIf { it.isNotEmpty() }?.let { labels[k] = it }
                        }
                    }
                    val recenter = ctrlObj.optString("lookRecenterKey").takeIf { it.isNotBlank() }
                    val mouseLook = ctrlObj.optString("mouseLookKey").takeIf { it.isNotBlank() }
                    ControlPatchOverrides(stickMode, bindings.ifEmpty { null }, labels.ifEmpty { null }, recenter, mouseLook)
                } else null

                result.add(
                    GamePatchProfile(
                        id = id,
                        name = name,
                        description = description,
                        match = PatchMatchRule(
                            gogIds,
                            slugPatterns,
                            signatureFiles,
                            minSignatureHits = matchObj.optInt("minSignatureHits", 1).coerceAtLeast(1)
                        ),
                        dosboxOverrides = dosboxOverrides,
                        configRules = rulesList,
                        controlOverrides = controlOverrides,
                        binaryPatches = parseBinaryPatches(obj.optJSONArray("binaryPatches")),
                        seedFiles = parseSeedFiles(obj.optJSONArray("seedFiles")),
                        universal = obj.optBoolean("universal", false),
                        soundPreset = obj.optJSONObject("soundPreset")?.let { sp ->
                            SoundPresetInfo(
                                sfx = sp.optString("sfx").takeIf { it.isNotBlank() && it != "unknown" },
                                music = sp.optString("music").takeIf { it.isNotBlank() && it != "unknown" }
                            )
                        },
                        evidence = obj.optJSONObject("harvest")?.optString("evidence")?.takeIf { it.isNotBlank() },
                        confidence = obj.optJSONObject("harvest")?.optDouble("confidence")?.takeIf { !it.isNaN() }?.toFloat()
                    )
                )
            }
            return result
        }
    }

    // The device-written files (AI fixes, published defaults, registry, local override) change while the app runs, so
    // the list is rebuilt whenever one of them does.
    @Volatile private var cachedProfiles: Pair<String, List<GamePatchProfile>>? = null

    private val profiles: List<GamePatchProfile>
        get() {
            val stamp = listOf(PUBLISHED_DEFAULTS_PATH, REGISTRY_PATH, LOCAL_PATCH_FILENAME)
                .joinToString("|") { File(context.filesDir, it).let { f -> if (f.exists()) "${f.lastModified()}:${f.length()}" else "-" } }
            cachedProfiles?.takeIf { it.first == stamp }?.let { return it.second }
            return loadProfiles().also { cachedProfiles = stamp to it }
        }

    fun getAllProfiles(): List<GamePatchProfile> = profiles

    /**
     * Resolves all matching patch profiles for a given game by checking:
     * 1. Exact GOG product ID match
     * 2. Slug pattern match
     * 3. Signature files present in the extracted installation directory
     */
    fun findMatchingProfiles(
        gameId: Long,
        slug: String?,
        installDir: File
    ): List<GamePatchProfile> {
        val installedUpperNames = if (installDir.exists()) {
            installDir.walkTopDown()
                .filter { it.isFile }
                .map { it.name.uppercase(Locale.ROOT) }
                .toSet()
        } else {
            emptySet()
        }

        return profiles.filter { profile -> profileMatches(profile.match, gameId, slug, installedUpperNames) }
    }

    /**
     * Applies all matching patch profiles + universal DOSBox hardware & audio sanitization
     * to an installed game directory. Safe to run both after extraction and before every launch.
     */
    fun applyPatches(
        gameId: Long,
        slug: String?,
        installDir: File,
        confFile: File,
        preferredSoundHardware: SoundHardware = SoundHardware.SOUND_BLASTER_16
    ): List<GamePatchProfile> {
        if (!installDir.exists()) return emptyList()

        // 0. Flatten InnoSetup 'app/' subdirectory into installDir if needed (e.g. Shadow Warrior)
        flattenAppSubdirIfNeeded(installDir)
        normalizeCueSheetPaths(installDir)

        val matched = findMatchingProfiles(gameId, slug, installDir)
        val primaryOverride = (matched.firstOrNull { !it.universal } ?: matched.firstOrNull())?.dosboxOverrides
            ?: DosBoxPatchOverrides()

        // 1. Apply DOSBox .conf hardware, mount path, & autoexec patches
        patchDosBoxConf(confFile, installDir, primaryOverride)

        // 2. Apply all matched profile config file rules and binary patches
        var allFiles = installDir.walkTopDown().filter { it.isFile }.toList()
        val hasUltraDir = allFiles.any { it.parentFile?.name.equals("ULTRASND", ignoreCase = true) }

        for (profile in matched) {
            if (profile.seedFiles.isNotEmpty()) {
                val seeded = profile.seedFiles.mapNotNull { applySeedFile(installDir, allFiles, it) }
                if (seeded.isNotEmpty()) allFiles = installDir.walkTopDown().filter { it.isFile }.toList()
            }
            when (profile.id) {
                "dmx-id-tech1-engine" -> {
                    seedMissingDmxConfigIfNeeded(allFiles)
                    allFiles = installDir.walkTopDown().filter { it.isFile }.toList()
                }
                "id-tech2-quake-engine" -> {
                    seedMissingQuakeConfigIfNeeded(allFiles)
                    allFiles = installDir.walkTopDown().filter { it.isFile }.toList()
                }
                "bethesda-xngine-tes" -> {
                    patchBethesdaEngineFiles(allFiles)
                }
                "parallax-descent-engine" -> {
                    seedMissingDescentConfigIfNeeded(allFiles)
                    allFiles = installDir.walkTopDown().filter { it.isFile }.toList()
                }
                "build-engine-audio-controls" -> {
                    repairBuildEngineConfigs(allFiles)
                }
            }

            for (rule in profile.configRules) {
                val matchingFiles = allFiles.filter { file ->
                    rule.filePatterns.any { pat -> file.name.equals(pat, ignoreCase = true) }
                }
                for (targetFile in matchingFiles) {
                    applyConfigRuleToFile(targetFile, rule, preferredSoundHardware, hasUltraDir)
                }
            }
            for (patch in profile.binaryPatches) {
                allFiles.filter { it.name.equals(patch.file, ignoreCase = true) }.forEach { applyBinaryPatch(it, patch) }
            }
            if (profile.id == "build-engine-audio-controls") {
                repairBuildEngineConfigs(allFiles)
            }
            Log.i(TAG, "Applied patch profile '${profile.id}' (${profile.name}) to game $gameId")
        }

        return matched
    }

    /**
     * Automatically scans and patches all currently installed games in the user's library.
     */
    fun patchAllInstalledGames(gamesBaseDir: File) {
        if (!gamesBaseDir.exists()) return
        val gameDirs = gamesBaseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (gameDir in gameDirs) {
            val gameId = gameDir.name.toLongOrNull() ?: -1L
            val confFile = File(gameDir, "dosbox.conf").takeIf { it.exists() }
                ?: gameDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("conf", ignoreCase = true) && !it.name.contains(".goa-") }
                ?: File(gameDir, "dosbox.conf")
            applyPatches(
                gameId = gameId,
                slug = null,
                installDir = gameDir,
                confFile = confFile
            )
        }
    }

    private fun seedMissingDmxConfigIfNeeded(allFiles: List<File>) {
        val dmxConfigNames = setOf("DEFAULT.CFG", "DOOM.CFG", "DOOM2.CFG", "HERETIC.CFG", "HEXEN.CFG", "STRIFE.CFG")
        val wadDirs = allFiles
            .filter { it.extension.equals("WAD", ignoreCase = true) }
            .mapNotNull { it.parentFile }
            .distinct()

        for (dir in wadDirs) {
            val hasCfg = dir.listFiles()?.any { f ->
                dmxConfigNames.any { it.equals(f.name, ignoreCase = true) }
            } == true
            if (!hasCfg) {
                val defaultCfg = File(dir, "DEFAULT.CFG")
                defaultCfg.writeText(
                    buildString {
                        appendLine("mouse_sensitivity\t\t6")
                        appendLine("sfx_volume\t\t8")
                        appendLine("music_volume\t\t8")
                        appendLine("show_messages\t\t1")
                        appendLine("key_right\t\t77")
                        appendLine("key_left\t\t75")
                        appendLine("key_up\t\t72")
                        appendLine("key_down\t\t80")
                        appendLine("key_strafeleft\t\t51")
                        appendLine("key_straferight\t\t52")
                        appendLine("key_fire\t\t29")
                        appendLine("key_use\t\t57")
                        appendLine("key_strafe\t\t56")
                        appendLine("key_speed\t\t54")
                        appendLine("key_lookup\t\t73")
                        appendLine("key_lookdown\t\t81")
                        appendLine("key_lookcenter\t\t71")
                        appendLine("key_jump\t\t47")
                        appendLine("use_mouse\t\t1")
                        appendLine("mouseb_fire\t\t0")
                        appendLine("mouseb_strafe\t\t1")
                        appendLine("mouseb_forward\t\t2")
                        appendLine("use_joystick\t\t0")
                        appendLine("joyb_fire\t\t0")
                        appendLine("joyb_strafe\t\t3")
                        appendLine("joyb_use\t\t1")
                        appendLine("joyb_speed\t\t2")
                        appendLine("screenblocks\t\t10")
                        appendLine("detaillevel\t\t0")
                        appendLine("snd_channels\t\t8")
                        appendLine("snd_musicdevice\t\t3")
                        appendLine("snd_sfxdevice\t\t3")
                        appendLine("snd_sbport\t\t544")
                        appendLine("snd_sbirq\t\t7")
                        appendLine("snd_sbdma\t\t1")
                        appendLine("snd_mport\t\t816")
                        appendLine("usegamma\t\t0")
                    }
                )
            }
        }
    }

    private fun seedMissingQuakeConfigIfNeeded(allFiles: List<File>) {
        val pakDirs = allFiles
            .filter { it.name.equals("PAK0.PAK", ignoreCase = true) }
            .mapNotNull { it.parentFile }
            .distinct()

        for (dir in pakDirs) {
            val cfgFile = dir.listFiles()?.firstOrNull { it.name.equals("CONFIG.CFG", ignoreCase = true) }
                ?: File(dir, "CONFIG.CFG")
            if (!cfgFile.exists()) {
                cfgFile.writeText("// Generated by GOA Patch Repository\r\n")
            }
            val autoexecCfg = dir.listFiles()?.firstOrNull { it.name.equals("AUTOEXEC.CFG", ignoreCase = true) }
                ?: File(dir, "AUTOEXEC.CFG")
            if (!autoexecCfg.exists()) {
                // Mouse look off: the right stick looks with PgUp/PgDn, and with mlook held DOS Quake cancels view
                // centring every frame, so neither Home (centerview) nor walking forward could level the view
                autoexecCfg.writeText("-mlook\r\nlookspring \"0\"\r\nlookstrafe \"0\"\r\n")
            }
        }
    }

    private fun seedMissingDescentConfigIfNeeded(allFiles: List<File>) {
        val hogDirs = allFiles
            .filter { it.name.equals("DESCENT.HOG", ignoreCase = true) || it.name.equals("DESCENT2.HOG", ignoreCase = true) }
            .mapNotNull { it.parentFile }
            .distinct()

        for (dir in hogDirs) {
            val isDescent2 = dir.listFiles()?.any { it.name.equals("DESCENT2.HOG", ignoreCase = true) } == true
            val cfgName = if (isDescent2) "DESCENT2.CFG" else "DESCENT.CFG"
            val existing = dir.listFiles()?.firstOrNull { it.name.equals(cfgName, ignoreCase = true) }
            if (existing == null) {
                File(dir, cfgName).writeText(
                    buildString {
                        appendLine("DigiDeviceID8 = 57366")
                        appendLine("DigiDeviceID16 = 57366")
                        appendLine("DigiPort = 544")
                        appendLine("DigiIrq = 7")
                        appendLine("DigiDma8 = 1")
                        appendLine("DigiDma16 = 5")
                        appendLine("MidiDeviceID = 40969")
                        appendLine("MidiPort = 904")
                        appendLine("StereoReverse = 0")
                    }
                )
            }
        }
    }

    private fun repairBuildEngineConfigs(allFiles: List<File>) {
        val cfgFiles = allFiles.filter { file ->
            file.name.equals("SW.CFG", ignoreCase = true) ||
            file.name.equals("DUKE3D.CFG", ignoreCase = true) ||
            file.name.equals("BLOOD.CFG", ignoreCase = true) ||
            file.name.equals("REDNECK.CFG", ignoreCase = true)
        }
        for (file in cfgFiles) {
            runCatching {
                val lines = file.readText().lines().toMutableList()
                var inKeyDefs = false
                var inControls = false
                var inOptions = false
                var modified = false

                for (i in lines.indices) {
                    val trimmed = lines[i].trim()
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inKeyDefs = trimmed.equals("[KeyDefinitions]", ignoreCase = true)
                        inControls = trimmed.equals("[Controls]", ignoreCase = true)
                        inOptions = trimmed.equals("[Options]", ignoreCase = true)
                        continue
                    }
                    // The look-spring taps keypad 5, the games' default Center_View: a config that leaves it unbound gets it
                    if (inKeyDefs) bindCenterView(trimmed)?.let { lines[i] = it; modified = true }
                    if (inKeyDefs && trimmed.startsWith("AutoRun", ignoreCase = true)) {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx > 0) {
                            val value = trimmed.substring(eqIdx + 1).trim()
                            if (!value.contains("\"")) {
                                lines[i] = "AutoRun = \"CapLck\" \"\""
                                modified = true
                            }
                        }
                    }
                    // DOS Shadow Warrior / Duke 3D take mouse aim from the forward/back axis (SW: aimvel = info.dz >> 10), so
                    // vertical mouse must stay on analog_moving; aiming is saved as on, so it pitches instead of walking.
                    if (inControls && trimmed.startsWith("MouseAnalogAxes1", ignoreCase = true)) {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx > 0 && trimmed.substring(eqIdx + 1).trim() != "\"analog_moving\"") {
                            lines[i] = "MouseAnalogAxes1 = \"analog_moving\""
                            modified = true
                        }
                    }
                    // Toggle-type aiming (0) saved as on (MouseAimingOn / AimingFlag = 1): the game starts with vertical mouse
                    // mapped to pitch and nothing has to be held. Momentary (1) would switch aiming off without a held key.
                    if (inControls && trimmed.startsWith("MouseAiming", ignoreCase = true) && !trimmed.startsWith("MouseAimingFlipped", ignoreCase = true)) {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx > 0 && trimmed.substring(eqIdx + 1).trim() != "0") {
                            lines[i] = "MouseAiming = 0"
                            modified = true
                        }
                    }
                    if (inOptions && trimmed.startsWith("MouseAimingOn", ignoreCase = true)) {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx > 0 && trimmed.substring(eqIdx + 1).trim() != "1") {
                            lines[i] = "MouseAimingOn = 1"
                            modified = true
                        }
                    }
                }
                if (modified) {
                    file.writeText(lines.joinToString("\r\n"))
                    Log.i(TAG, "Repaired Build Engine config in ${file.absolutePath}")
                }
            }
        }
    }

    /**
     * Applies engine-specific binary and batch patches for Bethesda XnGine / TES games:
     * 1. Daggerfall (`BETAPLYR.DAT`): Enables View Mode (freelook) and binds Slide Left/Right (`,`/`.`),
     *    Look Up/Down (`PgUp`/`PgDn`), Jump (`A`), Crouch (`Z`), Run (`LShift`), Activate (`Space`),
     *    Swing Weapon (`LCtrl`), and AutoMap (`Tab`) to match our Dual-Stick Gravis GamePad.
     * 2. Arena (`ARENA.BAT`): Switches music driver from `genmidi.adv` (which requires external MPU-401)
     *    to `sbp2fm.adv` (Sound Blaster Pro 2 / 16 OPL3 FM synthesis on port 220, IRQ 7, DMA 1).
     */
    private fun patchBethesdaEngineFiles(allFiles: List<File>) {
        // 1. Patch Daggerfall's 92-byte BETAPLYR.DAT control table
        val betaPlyrFiles = allFiles.filter { it.name.equals("BETAPLYR.DAT", ignoreCase = true) }
        for (file in betaPlyrFiles) {
            runCatching {
                val bytes = file.readBytes()
                if (bytes.size >= 0x5C) {
                    // Byte 0x00: 0x01 = View Mode (Mouse-Look freelook instead of cursor dragging)
                    bytes[0x00] = 0x01
                    // Primary keyboard scancode table at 0x36..0x5B (38 actions)
                    bytes[0x36] = 0x48 // Move Forward: Up Arrow (72)
                    bytes[0x37] = 0x50 // Move Backward: Down Arrow (80)
                    bytes[0x38] = 0x4B // Turn Left: Left Arrow (75)
                    bytes[0x39] = 0x33 // Slide (Strafe) Left: ',' (51) -> Left Stick Left
                    bytes[0x3A] = 0x4D // Turn Right: Right Arrow (77)
                    bytes[0x3B] = 0x34 // Slide (Strafe) Right: '.' (52) -> Left Stick Right
                    bytes[0x3C] = 0x52 // Float Up: Insert (82)
                    bytes[0x3D] = 0x53 // Float Down: Delete (83)
                    bytes[0x3E] = 0x1E // Jump: 'A' (30) -> Gamepad Jump (Btn 2 / X)
                    bytes[0x3F] = 0x2C // Crouch / Duck: 'Z' (44) -> Gamepad Crouch (Btn 4 / B)
                    bytes[0x40] = 0x20 // Sneak: 'D' (32)
                    bytes[0x41] = 0x2A // Run: Left Shift (42) -> Gamepad Sprint (Btn 3 / L2)
                    bytes[0x48] = 0x39 // Activate Center Object: Space (57) -> Gamepad Use (L1 / Btn A)
                    bytes[0x4A] = 0x49 // Look Up: PageUp (73) -> Right Stick Up
                    bytes[0x4B] = 0x51 // Look Down: PageDown (81) -> Right Stick Down
                    bytes[0x4C] = 0x47 // Center View: Home (71)
                    bytes[0x50] = 0x0F // AutoMap: Tab (15) -> Center MAP Button
                    bytes[0x56] = 0x24 // Ready Spell: 'J' (36)
                    bytes[0x57] = 0x1D // Swing Weapon: Left Ctrl (29) -> Gamepad Fire (Btn 1 / R1 / R2)
                    file.writeBytes(bytes)
                    Log.i(TAG, "Patched Daggerfall BETAPLYR.DAT for View-Mode freelook & dual-stick controls: ${file.absolutePath}")
                }
            }.onFailure { err ->
                Log.e(TAG, "Failed to patch BETAPLYR.DAT: ${file.absolutePath}", err)
            }
        }

        // 2. Patch Arena's ARENA.BAT to use Sound Blaster Pro/16 OPL3 (sbp2fm.adv) instead of silent genmidi.adv
        val arenaBatFiles = allFiles.filter { it.name.equals("ARENA.BAT", ignoreCase = true) }
        for (batFile in arenaBatFiles) {
            runCatching {
                val text = batFile.readText()
                val updated = text.replace(
                    Regex("""-Mgenmidi\.adv\s+-IOM\d+\s+-IRQM\d+\s+-DMAM\d+""", RegexOption.IGNORE_CASE),
                    "-Msbp2fm.adv -IOM220 -IRQM7 -DMAM1"
                )
                if (updated != text) {
                    batFile.writeText(updated)
                }
            }
        }
    }

    private fun flattenAppSubdirIfNeeded(installDir: File) {
        val appSubdir = File(installDir, "app")
        if (appSubdir.isDirectory) {
            appSubdir.listFiles()?.forEach { child ->
                val target = File(installDir, child.name)
                if (!target.exists()) {
                    child.renameTo(target)
                }
            }
            if (appSubdir.listFiles().isNullOrEmpty()) {
                appSubdir.delete()
            }
        }
    }

    private fun patchDosBoxConf(
        confFile: File,
        installDir: File,
        overrides: DosBoxPatchOverrides
    ) {
        if (!confFile.exists()) return
        runCatching {
            var content = confFile.readText()

            // 1. Clean up any sections accidentally placed after [autoexec]
            val autoexecIndex = content.indexOf("[autoexec]", ignoreCase = true)
            if (autoexecIndex >= 0) {
                val afterAutoexec = content.substring(autoexecIndex)
                val misplacedSectionMatch = Regex("""(?im)^\[(mixer|sblaster|gus|speaker|glide)\]""").find(afterAutoexec)
                if (misplacedSectionMatch != null) {
                    val cutIndex = autoexecIndex + misplacedSectionMatch.range.first
                    content = content.substring(0, cutIndex).trimEnd() + "\n"
                }
            }

            // 2a. Installs whose CD mount an earlier GOA broke ("img -t iso", or nothing mounted on C): rebuild the
            // autoexec from GOG's own conf (Tomb Raider, Mortal Kombat 3)
            com.goa.domain.usecase.GogConfAdapter.repair(content, installDir)?.let { content = it }

            // 2b. Normalize relative mount paths in [autoexec] so '..' (from __support/app) or '.app' points to '.'
            content = content
                .replace(Regex("""(?im)^(\s*mount\s+[cd]\s+)(?:"\.\."|\.\.|"\.app"|"app"|"\."|\.)(\s+-t\s+cdrom.*)$"""), "$1\".\"$2")
                .replace(Regex("""(?im)^(\s*mount\s+c\s+)(?:"\.\."|\.\.|"\.app"|"app"|"\."|\.)(\s*(?:-t\s+dir)?\s*)$"""), "$1\".\"$2")
                .replace(Regex("""(?im)^(\s*imgmount\s+[d-z]\s+.*?)\s+-t\s+cdrom"""), "$1 -t iso")
                // Fix Elder Scrolls: Arena [autoexec] to use OPL3 FM synth (sbp2fm.adv) instead of silent genmidi.adv
                .replace(
                    Regex("""-Mgenmidi\.adv\s+-IOM\d+\s+-IRQM\d+\s+-DMAM\d+""", RegexOption.IGNORE_CASE),
                    "-Msbp2fm.adv -IOM220 -IRQM7 -DMAM1"
                )

            // GOG's keyboard launcher menu (overrides.bypassChoiceMenu) is no longer cut out of the conf: every launch
            // answers it in a copy (LaunchConf), so the menu stays readable and its Setup entry can be offered as a button

            if (!content.contains(Regex("""(?im)^\s*memsize\s*=""", RegexOption.MULTILINE))) {
                val origConf = installDir.walkTopDown().firstOrNull {
                    it.isFile && it.extension.equals("conf", ignoreCase = true) && !it.name.equals("dosbox.conf", ignoreCase = true) && !it.name.contains("single", ignoreCase = true) && !it.name.contains(".goa-")
                }
                val memMatch = origConf?.readText()?.let { Regex("""(?im)^\s*memsize\s*=\s*(\d+)""").find(it) }
                val mem = memMatch?.groupValues?.get(1)?.toIntOrNull() ?: 32
                content = "[dosbox]\nmemsize=$mem\n\n" + content
            }

            content = ConfCycles.apply(content, overrides.cycles)

            val hasUltraDir = installDir.walkTopDown().any { it.isDirectory && it.name.equals("ULTRASND", ignoreCase = true) }
            val gusEnabled = if (overrides.gus == "true" && hasUltraDir) "true" else "false"

            val hasSound = content.contains("[sblaster]", ignoreCase = true)
            val hasGus = content.contains("[gus]", ignoreCase = true)
            val hasGlide = content.contains("[glide]", ignoreCase = true)
            val hasSpeaker = content.contains("[speaker]", ignoreCase = true)
            val hasMixer = content.contains("[mixer]", ignoreCase = true)

            // Normalize existing hardware rates to 48000 Hz and GUS status
            content = content
                .replace("rate=44100", "rate=${overrides.rate}")
                .replace("oplrate=44100", "oplrate=${overrides.rate}")
                .replace("gusrate=44100", "gusrate=${overrides.rate}")
                .replace("pcrate=44100", "pcrate=${overrides.rate}")
                .replace("tandyrate=44100", "tandyrate=${overrides.rate}")
                .replace("grate=44100", "grate=${overrides.rate}")
                .replace(Regex("""(?im)^gus\s*=\s*(true|false)"""), "gus=$gusEnabled")
                .replace(Regex("""(?im)^sbtype\s*=\s*\S+"""), "sbtype=${overrides.sbtype}")
                .replace(Regex("""(?im)^sbbase\s*=\s*\S+"""), "sbbase=${overrides.sbbase}")
                .replace(Regex("""(?im)^irq\s*=\s*\S+"""), "irq=${overrides.irq}")
                .replace(Regex("""(?im)^dma\s*=\s*\S+"""), "dma=${overrides.dma}")
                .replace(Regex("""(?im)^hdma\s*=\s*\S+"""), "hdma=${overrides.hdma}")

            val missingSections = buildString {
                if (!hasMixer) {
                    appendLine()
                    appendLine("[mixer]")
                    appendLine("nosound=false")
                    appendLine("rate=${overrides.rate}")
                    appendLine("blocksize=1024")
                    appendLine("prebuffer=20")
                }
                if (!hasSound) {
                    appendLine()
                    appendLine("[sblaster]")
                    appendLine("sbtype=${overrides.sbtype}")
                    appendLine("sbbase=${overrides.sbbase}")
                    appendLine("irq=${overrides.irq}")
                    appendLine("dma=${overrides.dma}")
                    appendLine("hdma=${overrides.hdma}")
                    appendLine("sbmixer=true")
                    appendLine("oplmode=${overrides.oplmode}")
                    appendLine("oplemu=${overrides.oplemu}")
                    appendLine("oplrate=${overrides.rate}")
                }
                if (!hasGus) {
                    appendLine()
                    appendLine("[gus]")
                    appendLine("gus=$gusEnabled")
                    appendLine("gusrate=${overrides.rate}")
                    appendLine("gusbase=240")
                    appendLine("gusirq=5")
                    appendLine("gusdma=3")
                    appendLine("ultradir=C:\\ULTRASND")
                }
                if (!hasSpeaker) {
                    appendLine()
                    appendLine("[speaker]")
                    appendLine("pcspeaker=true")
                    appendLine("pcrate=${overrides.rate}")
                    appendLine("tandy=auto")
                    appendLine("tandyrate=${overrides.rate}")
                    appendLine("disney=true")
                }
                if (!hasGlide) {
                    appendLine()
                    appendLine("[glide]")
                    appendLine("glide=true")
                    appendLine("grate=${overrides.rate}")
                    appendLine("lfb=full")
                }
            }

            if (missingSections.isNotBlank()) {
                val updatedAutoexecIndex = content.indexOf("[autoexec]", ignoreCase = true)
                content = if (updatedAutoexecIndex >= 0) {
                    val before = content.substring(0, updatedAutoexecIndex).trimEnd()
                    val after = content.substring(updatedAutoexecIndex)
                    "$before\n$missingSections\n$after"
                } else {
                    "$content\n$missingSections"
                }
            }

            confFile.writeText(content)
        }.onFailure { err ->
            Log.e(TAG, "Failed to patch dosbox.conf: ${confFile.absolutePath}", err)
        }
    }

    private fun applyConfigRuleToFile(
        file: File,
        rule: ConfigPatchRule,
        preferredSoundHardware: SoundHardware,
        hasUltraDir: Boolean
    ) {
        runCatching {
            var text = file.readText()
            when (rule.format) {
                ConfigFormat.WHITESPACE_KV -> {
                    for ((key, rawDefaultVal) in rule.entries) {
                        val value = if ((key == "snd_sfxdevice" || key == "snd_musicdevice") &&
                            preferredSoundHardware == SoundHardware.GRAVIS_ULTRASOUND && hasUltraDir
                        ) {
                            "5"
                        } else {
                            rawDefaultVal
                        }
                        val regex = Regex("""(?im)^(\Q$key\E)\s+\S+.*$""")
                        text = if (regex.containsMatchIn(text)) {
                            regex.replace(text) { "$key\t\t$value" }
                        } else {
                            text.trimEnd() + "\r\n$key\t\t$value\r\n"
                        }
                    }
                }
                ConfigFormat.INI_EQUALS -> {
                    for ((key, value) in rule.entries) {
                        val regex = Regex("""(?im)^(\Q$key\E)\s*=.*$""")
                        if (regex.containsMatchIn(text)) {
                            text = regex.replace(text) { "$key = $value" }
                        }
                    }
                }
                ConfigFormat.INI_SECTION_EQUALS -> {
                    text = applySectionScopedIniEntries(text, rule.entries)
                }
                ConfigFormat.QUAKE_CFG -> {
                    for ((key, value) in rule.entries) {
                        if (value.isEmpty()) {
                            // Standalone command like +mlook
                            val cmdRegex = Regex("""(?im)^\s*\Q$key\E\s*$""")
                            if (!cmdRegex.containsMatchIn(text)) {
                                text = text.trimEnd() + "\r\n$key\r\n"
                            }
                        } else {
                            val regex = Regex("""(?im)^\s*\Q$key\E\s+.*$""")
                            text = if (regex.containsMatchIn(text)) {
                                regex.replace(text) { "$key $value" }
                            } else {
                                text.trimEnd() + "\r\n$key $value\r\n"
                            }
                        }
                    }
                }
            }
            file.writeText(text)
        }.onFailure { err ->
            Log.e(TAG, "Failed to apply config rule to ${file.absolutePath}", err)
        }
    }

    private fun applySectionScopedIniEntries(text: String, entries: Map<String, String>): String {
        val lines = text.lines().toMutableList()
        var currentSection = ""
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim().uppercase(Locale.ROOT)
                continue
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0 && currentSection.isNotEmpty()) {
                val keyName = trimmed.substring(0, eqIdx).trim()
                val matchEntry = entries.entries.firstOrNull { (k, _) ->
                    k.equals("$currentSection.$keyName", ignoreCase = true)
                }
                if (matchEntry != null) {
                    val lhs = lines[i].substringBefore('=')
                    // Keep the file's own style: "KEY=value" stays without a space (Maxis' parser may not accept one)
                    val gap = if (lines[i].substringAfter('=').startsWith(" ")) " " else ""
                    lines[i] = "$lhs=$gap${matchEntry.value}"
                }
            }
        }
        return lines.joinToString("\r\n")
    }

    private fun loadProfiles(): List<GamePatchProfile> {
        val loaded = mutableListOf<GamePatchProfile>()
        runCatching {
            // Precedence is list order (first matching non-universal profile wins):
            // published default (CDN) > registry (curated, then harvested)
            loaded.addAll(readProfilesFile(File(context.filesDir, PUBLISHED_DEFAULTS_PATH)))

            // 1. The synced registry (curated first, then harvested). Empty until the first sync has completed: games
            //    then fall back to GOA's genre defaults and pick up their profile on the next launch.
            loaded.addAll(loadRegistryProfiles())

            // 2. Load optional local override file if present
            val overrideFile = File(context.filesDir, LOCAL_PATCH_FILENAME)
            if (overrideFile.exists()) {
                val overrideProfiles = parseProfilesJson(overrideFile.readText())
                val overrideIds = overrideProfiles.map { it.id }.toSet()
                loaded.removeAll { it.id in overrideIds }
                loaded.addAll(overrideProfiles)
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to load game patch profiles", err)
        }
        return loaded
    }

    /** A missing or unparseable profile file contributes nothing; one bad file must not hide the built-in catalog. */
    private fun readProfilesFile(file: File): List<GamePatchProfile> =
        if (!file.exists()) emptyList() else runCatching { parseProfilesJson(file.readText()) }
            .onFailure { Log.w(TAG, "Ignoring unreadable patch file ${file.name}", it) }
            .getOrDefault(emptyList())

    // The registry file is thousands of profiles long: parse it once per version of the file, not on every AI-fix change
    private fun loadRegistryProfiles(): List<GamePatchProfile> {
        val file = File(context.filesDir, REGISTRY_PATH)
        val stamp = if (file.exists()) "${file.lastModified()}:${file.length()}" else "-"
        cachedRegistry?.takeIf { it.first == stamp }?.let { return it.second }
        return readProfilesFile(file)
            // Harvested CPU speeds are unreviewed guesses (mostly 286-era "fixed 3000"); until they are checked
            // game by game the emulator keeps its own speed for those titles. Curated profiles and AI fixes apply.
            .map { if (it.evidence != null) it.copy(dosboxOverrides = it.dosboxOverrides.copy(cycles = "auto")) else it }
            .also { cachedRegistry = stamp to it }
    }

    private fun normalizeCueSheetPaths(installDir: File) {
        val cueFiles = installDir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("cue", ignoreCase = true) || it.extension.equals("dat", ignoreCase = true)) }
            .toList()

        for (cueFile in cueFiles) {
            val text = runCatching { cueFile.readText() }.getOrNull() ?: continue
            if (!text.contains(Regex("""(?i)\bFILE\s+"""))) continue

            var modified = false
            val fileRegex = Regex("""(?im)^(\s*FILE\s+)"([^"]+)"(\s+.*)$""")
            val newText = fileRegex.replace(text) { matchResult ->
                val prefix = matchResult.groupValues[1]
                val originalPath = matchResult.groupValues[2]
                val suffix = matchResult.groupValues[3]

                val normalizedRelative = originalPath.replace('\\', '/')
                val targetFile = File(installDir, normalizedRelative)
                if (targetFile.exists()) {
                    matchResult.value
                } else {
                    // Search case-insensitively for the file relative to installDir
                    val parts = normalizedRelative.split('/')
                    var current = installDir
                    var found = true
                    val resolvedParts = mutableListOf<String>()
                    for (part in parts) {
                        val matchingChild = current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) }
                        if (matchingChild != null) {
                            resolvedParts.add(matchingChild.name)
                            current = matchingChild
                        } else {
                            found = false
                            break
                        }
                    }
                    if (found) {
                        modified = true
                        val resolvedPath = resolvedParts.joinToString("\\")
                        "$prefix\"$resolvedPath\"$suffix"
                    } else {
                        matchResult.value
                    }
                }
            }
            if (modified) {
                runCatching { cueFile.writeText(newText) }
            }
        }
    }
}
