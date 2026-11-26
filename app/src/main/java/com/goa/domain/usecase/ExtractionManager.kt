package com.goa.domain.usecase

import android.util.Log
import com.goa.data.db.GameDao
import com.goa.data.db.InstallStatus
import com.goa.data.repository.GamePatchRepository
import com.goa.data.storage.AppStorageManager
import com.goa.domain.scummvm.ScummVmGameDetector
import com.goa.engine.extractor.InnoExtractBridge
import com.goa.engine.extractor.InnoExtractListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ExtractionManager(
    private val gameDao: GameDao,
    private val storageManager: AppStorageManager,
    private val patchRepository: GamePatchRepository? = null
) {
    companion object {
        private const val TAG = "ExtractionManager"

        /** Ranked pick of the DOS program to launch from an install directory (see [MainExecutablePicker]). */
        fun findMainExecutable(dir: File, slug: String?, title: String?): File? {
            val executables = dir.walkTopDown().filter { it.isFile && isDosExecutable(it) }.toList()
            val candidates = executables.map {
                val batch = if (it.extension.equals("bat", ignoreCase = true) && it.length() < 64 * 1024) {
                    runCatching { MainExecutablePicker.batchCommands(it.readText(Charsets.ISO_8859_1)) }.getOrNull()
                } else null
                MainExecutablePicker.Candidate(it.relativeTo(dir).path, it.length(), batch)
            }
            val picked = MainExecutablePicker.pick(candidates, slug, title) ?: return null
            // Installer-first archives hold only helpers beside INSTALL.EXE: run the installer, not a utility
            if (MainExecutablePicker.isUtility(picked.fileName) && findInstaller(dir) != null) return null
            return executables[candidates.indexOf(picked)]
        }

        /** Top-level INSTALL.EXE/.BAT for archives that need their DOS installer run before the game exists. */
        fun findInstaller(dir: File): File? =
            dir.listFiles()
                ?.filter { it.isFile && it.nameWithoutExtension.equals("install", ignoreCase = true) && isDosExecutable(it) }
                ?.minByOrNull { if (it.extension.equals("exe", ignoreCase = true)) 0 else 1 }

        fun isDosExecutable(file: File): Boolean {
            if (!file.isFile || file.length() == 0L) return false
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext == "com" || ext == "bat") return true
            if (file.length() < 64) return false
            if (ext != "exe") return false

            return try {
                file.inputStream().use { stream ->
                    val header = ByteArray(64)
                    val read = stream.read(header)
                    if (read < 64) return false
                    if (header[0] != 'M'.code.toByte() || header[1] != 'Z'.code.toByte()) return false

                    val lfanew = (header[0x3C].toInt() and 0xFF) or
                            ((header[0x3D].toInt() and 0xFF) shl 8) or
                            ((header[0x3E].toInt() and 0xFF) shl 16) or
                            ((header[0x3F].toInt() and 0xFF) shl 24)

                    if (lfanew <= 0 || lfanew + 4 > file.length()) {
                        return true
                    }

                    val toSkip = lfanew - 64
                    if (toSkip > 0) {
                        stream.skip(toSkip.toLong())
                    }
                    val sig = ByteArray(4)
                    stream.read(sig)
                    val isPE = sig[0] == 'P'.code.toByte() && sig[1] == 'E'.code.toByte() && sig[2] == 0.toByte() && sig[3] == 0.toByte()
                    val isNE = sig[0] == 'N'.code.toByte() && sig[1] == 'E'.code.toByte()

                    !isPE && !isNE
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun extractAndSanitize(
        gameId: Long,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val downloadDir = storageManager.getDownloadDir(gameId)
            val installDir = storageManager.getGameInstallDir(gameId)

            // Find setup_*.exe, zip, or first executable/installer part in downloadDir
            val downloadedFiles = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val shFile = downloadedFiles.firstOrNull { it.extension.equals("sh", ignoreCase = true) }
            val zipFile = downloadedFiles.firstOrNull { isZipArchive(it) }
            val sevenZipFile = downloadedFiles.firstOrNull { SevenZipExtractor.isSevenZip(it) }
            var installerExe = zipFile ?: sevenZipFile ?: downloadedFiles.firstOrNull {
                it.extension.equals("exe", ignoreCase = true)
            } ?: downloadedFiles.firstOrNull { file ->
                // Check for PE/DOS header ("MZ")
                if (file.length() > 2) {
                    file.inputStream().use { stream ->
                        stream.read() == 'M'.code && stream.read() == 'Z'.code
                    }
                } else false
            } ?: shFile
            ?: downloadedFiles.maxByOrNull { it.length() }
            ?: throw IllegalStateException("No installer or archive found in ${downloadDir.absolutePath}")

            gameDao.updateStatus(gameId, InstallStatus.EXTRACTING)

            val isMojo = installerExe.extension.equals("sh", ignoreCase = true)
            val isZip = isZipArchive(installerExe)
            val isSevenZip = !isZip && SevenZipExtractor.isSevenZip(installerExe)
            if (isSevenZip) {
                try {
                    SevenZipExtractor.extract(installerExe, installDir, onProgress)
                } catch (e: Exception) {
                    Log.e(TAG, "7z extraction error", e)
                    gameDao.updateStatus(gameId, InstallStatus.ERROR)
                    throw IllegalStateException("Failed to extract 7z archive ${installerExe.name}: ${e.message}")
                }
            } else if (isMojo) {
                val extracted = extractMojoSetupArchive(installerExe, installDir, onProgress)
                if (!extracted) {
                    gameDao.updateStatus(gameId, InstallStatus.ERROR)
                    throw IllegalStateException("Failed to extract MojoSetup shell archive from ${installerExe.name}")
                }
            } else if (isZip) {
                val extracted = extractZipArchive(installerExe, installDir, onProgress)
                if (!extracted) {
                    gameDao.updateStatus(gameId, InstallStatus.ERROR)
                    throw IllegalStateException("Failed to extract zip archive from ${installerExe.name}")
                }
            } else {
                // If the installer file does not end with .exe, rename it to .exe so innoextract recognizes it
                if (!installerExe.name.endsWith(".exe", ignoreCase = true)) {
                    val cleanBase = installerExe.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val renamed = File(installerExe.parentFile, "$cleanBase.exe")
                    if (installerExe.renameTo(renamed)) {
                        installerExe = renamed
                    }
                }

                var extractionError: String? = null

                val returnCode = InnoExtractBridge.extractArchive(
                    installerPath = installerExe.absolutePath,
                    destinationDir = installDir.absolutePath,
                    gogExtractOnly = true,
                    lowercase = true,
                    listener = object : InnoExtractListener {
                        override fun onProgress(currentBytes: Long, totalBytes: Long, currentFile: String) {
                            if (totalBytes > 0) {
                                val percent = ((currentBytes * 100) / totalBytes).toInt()
                                onProgress(percent)
                            }
                        }

                        override fun onMessage(logLine: String) {
                            Log.d(TAG, logLine)
                        }

                        override fun onError(errorCode: Int, errorMessage: String) {
                            extractionError = errorMessage
                        }

                        override fun onComplete(extractedFileCount: Int) {
                            Log.i(TAG, "Extracted $extractedFileCount files")
                        }
                    }
                )

                if (returnCode != 0 || extractionError != null) {
                    if (isDosExecutable(installerExe)) {
                        Log.i(TAG, "Archive is not InnoSetup, but is a raw DOS executable. Copying directly: ${installerExe.name}")
                        val targetExe = File(installDir, installerExe.name)
                        installerExe.copyTo(targetExe, overwrite = true)
                    } else {
                        gameDao.updateStatus(gameId, InstallStatus.ERROR)
                        throw IllegalStateException(extractionError ?: "Extraction failed with code $returnCode")
                    }
                }
            }

            finalizeIn(gameId, installDir)
        }
    }

    /**
     * Finishes an install once the game's files are in its install directory (extracted from an installer, or copied in
     * from a DOS game folder): cleans up, finds the program to run, writes dosbox.conf, applies the patch profile and marks
     * the game ready. On failure the game is set to ERROR.
     */
    suspend fun finalizeInstall(gameId: Long): Result<File> = withContext(Dispatchers.IO) {
        runCatching { finalizeIn(gameId, storageManager.getGameInstallDir(gameId)) }
    }

    private suspend fun finalizeIn(gameId: Long, installDir: File): File {
        // Post-Extraction Sanitization
        sanitizeExtractedFiles(installDir)

        // Shareware ships its game inside self-extracting zips (DOOM's DOOMS_19.1/.2, OMF's OMF21.EXE): unpack them
        val expanded = EmbeddedArchiveExpander.expandAll(installDir)
        if (expanded > 0) Log.i(TAG, "Unpacked $expanded embedded archive(s) in ${installDir.name}")

        // ScummVM titles (GOG builds bundling Windows ScummVM, or bare freeware data) have no DOS executable or
        // dosbox.conf; they are launched on the ScummVM core, so a recognised game is a complete install
        ScummVmGameDetector.detect(installDir)?.let { scummVmGame ->
            storageManager.cleanDownloads(gameId)
            gameDao.updateInstallDetails(
                id = gameId,
                status = InstallStatus.READY_TO_PLAY,
                localInstallPath = installDir.absolutePath,
                mainExecutable = "${scummVmGame.gameId}.scummvm",
                dosboxConfPath = null
            )
            return installDir
        }

        val confFiles = installDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("conf", ignoreCase = true) }
            .toList()

        // GOG's own conf (a software-rendering one first: GOA's DOSBox has no Glide)
        val singleConf = GogConfAdapter.pickGogConf(installDir)
            ?: confFiles.firstOrNull { !it.name.equals("dosbox.conf", ignoreCase = true) }

        // Resolve Main Executable & dosbox.conf
        val gameEntity = gameDao.getGameByIdSync(gameId)
        // Apogee .SHR shareware (Keen, Wolf3D, Duke 3D) can only be unpacked by its own INSTALL.EXE: launch that
        // first, and LaunchGameUseCase switches to the installed game once it exists
        val mainExe = findExecutableFromConf(singleConf, installDir)
            ?: findMainExecutable(installDir, gameEntity?.slug, gameEntity?.title)
            ?: findInstaller(installDir)
        if (mainExe == null || mainExe.length() == 0L) {
            gameDao.updateStatus(gameId, InstallStatus.ERROR)
            val windowsOnly = installDir.walkTopDown().any { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            throw IllegalStateException(
                if (windowsOnly) "This is a Windows game (no DOS program inside), which GOA can't run."
                else "No valid DOS executable found in extracted directory: ${installDir.absolutePath}"
            )
        }

        val rootConf = File(installDir, "dosbox.conf")
        if (singleConf != null) {
            // GOG's Windows-relative mounts resolved against this install (see GogConfAdapter)
            rootConf.writeText(GogConfAdapter.adapt(singleConf.readText(), installDir))
        } else {
            val relDir = if (mainExe.parentFile != null && mainExe.parentFile != installDir) {
                val rel = mainExe.parentFile!!.relativeTo(installDir).path.replace('/', '\\')
                if (rel.isNotBlank()) "cd $rel\n" else ""
            } else ""
            rootConf.writeText("[dosbox]\nmemsize=16\n[cpu]\ncycles=auto\n[autoexec]\nmount c .\nc:\n$relDir${mainExe.name}\n")
        }
        val confFile = rootConf

        // Apply automatic compatibility & audio patches from GamePatchRepository
        patchRepository?.applyPatches(
            gameId = gameId,
            slug = gameEntity?.slug,
            installDir = installDir,
            confFile = confFile
        )

        // Purge temporary installer downloads (.exe and .bin)
        storageManager.cleanDownloads(gameId)

        // Update Game Entity to READY_TO_PLAY
        gameDao.updateInstallDetails(
            id = gameId,
            status = InstallStatus.READY_TO_PLAY,
            localInstallPath = installDir.absolutePath,
            mainExecutable = mainExe.name,
            dosboxConfPath = confFile.absolutePath
        )

        return installDir
    }

    /** GOG's Windows launcher support lives in these folders; DLLs there are not the DOS game's. */
    private fun isInWrapperDir(root: File, file: File): Boolean {
        val wrapperDirs = setOf("dosbox", "__redist", "__support", "commonappdata")
        return file.relativeTo(root).parentFile?.path?.split(File.separatorChar)
            ?.any { it.lowercase(Locale.ROOT) in wrapperDirs } == true
    }

    private fun sanitizeExtractedFiles(dir: File) {
        // Flatten InnoSetup 'app/' subdirectory into the root installDir if present
        val appSubdir = File(dir, "app")
        if (appSubdir.isDirectory) {
            appSubdir.listFiles()?.forEach { child ->
                val target = File(dir, child.name)
                if (!target.exists()) {
                    child.renameTo(target)
                }
            }
            if (appSubdir.listFiles().isNullOrEmpty()) {
                appSubdir.delete()
            }
        }

        // If directory has only 1 subdirectory and no files at root, flatten it
        val rootEntries = dir.listFiles() ?: emptyArray()
        val nonDirFiles = rootEntries.filter { it.isFile }
        val rootDirs = rootEntries.filter { it.isDirectory }
        if (nonDirFiles.isEmpty() && rootDirs.size == 1) {
            val singleDir = rootDirs.first()
            singleDir.listFiles()?.forEach { child ->
                val target = File(dir, child.name)
                if (!target.exists()) {
                    child.renameTo(target)
                }
            }
            if (singleDir.listFiles().isNullOrEmpty()) {
                singleDir.delete()
            }
        }

        val files = dir.walkTopDown().toList()
        for (file in files) {
            val name = file.name.lowercase(Locale.ROOT)
            val ext = file.extension.lowercase(Locale.ROOT)

            // Delete Windows DOSBox binaries and the wrapper's DLLs. A .dll anywhere else is the game's own: DOS games
            // such as SimCity 2000 load their Miles sound drivers (A32SBPDG.DLL...) from .DLL files, so keep those.
            if (file.isFile && (name == "dosbox.exe" ||
                name.startsWith("unins") ||
                name.endsWith(".ico") ||
                (name.endsWith(".dll") && isInWrapperDir(dir, file)))
            ) {
                file.delete()
            }
        }
    }

    private fun findExecutableFromConf(confFile: File?, installDir: File): File? {
        if (confFile == null || !confFile.exists()) return null
        val lines = confFile.readLines()
        var inAutoexec = false
        val candidateNames = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[autoexec]", ignoreCase = true)) {
                inAutoexec = true
                continue
            }
            if (inAutoexec) {
                if (trimmed.startsWith("[")) break
                val tokens = trimmed.split(Regex("""\s+"""))
                for (token in tokens) {
                    val cleanToken = token.trim('"', '\'').lowercase(Locale.ROOT)
                    if (cleanToken.endsWith(".exe") || cleanToken.endsWith(".com") || cleanToken.endsWith(".bat")) {
                        // "DOS4GW OMF.EXE"-style lines: skip the extender, keep the game
                        if (!cleanToken.startsWith("setup") &&
                            !cleanToken.contains("unins") &&
                            !cleanToken.contains("dosbox") &&
                            !cleanToken.startsWith("gogdos") &&
                            !MainExecutablePicker.isRuntime(cleanToken)
                        ) {
                            candidateNames.add(cleanToken)
                        }
                    }
                }
            }
        }

        for (cand in candidateNames) {
            // shallowest copy: Shadow Warrior keeps a backup SW.EXE in dragon/DRAGONBU
            val found = GogConfAdapter.shallowestFirst(installDir.walkTopDown().filter {
                it.isFile && it.name.equals(cand, ignoreCase = true) && isDosExecutable(it)
            }.toList(), installDir).firstOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun findDosboxConf(dir: File): File? {
        return dir.walkTopDown()
            .filter { it.isFile && (it.name.contains("dosbox", ignoreCase = true) && it.extension.equals("conf", ignoreCase = true)) }
            .firstOrNull()
    }

    private fun extractMojoSetupArchive(
        shFile: File,
        destinationDir: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            destinationDir.mkdirs()
            // Scan for embedded zip signature (PK\x03\x04 = 0x50, 0x4B, 0x03, 0x04)
            var zipOffset = -1L
            java.io.RandomAccessFile(shFile, "r").use { raf ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                var currentPos = 0L
                while (raf.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead - 3) {
                        if (buffer[i] == 0x50.toByte() &&
                            buffer[i + 1] == 0x4B.toByte() &&
                            buffer[i + 2] == 0x03.toByte() &&
                            buffer[i + 3] == 0x04.toByte()
                        ) {
                            zipOffset = currentPos + i
                            break
                        }
                    }
                    if (zipOffset != -1L) break
                    currentPos += (bytesRead - 3)
                    raf.seek(currentPos)
                }
            }

            if (zipOffset == -1L) return false

            shFile.inputStream().use { fileStream ->
                fileStream.skip(zipOffset)
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(fileStream)).use { zipStream ->
                    var entry = zipStream.nextEntry
                    var count = 0
                    while (entry != null) {
                        val cleanName = entry.name.replace("\\", "/")
                        // Filter out linux mojo wrapper binaries
                        val isData = !cleanName.startsWith("data/noarch/") && !cleanName.startsWith("scripts/")
                        val outPath = cleanName.removePrefix("data/noarch/").removePrefix("data/")
                        val targetFile = File(destinationDir, outPath)

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            java.io.FileOutputStream(targetFile).use { out ->
                                zipStream.copyTo(out)
                            }
                            count++
                            onProgress(count % 100)
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "MojoSetup extraction error", e)
            false
        }
    }

    private fun isZipArchive(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        if (file.extension.equals("zip", ignoreCase = true)) return true
        return try {
            file.inputStream().use { stream ->
                stream.read() == 0x50 && stream.read() == 0x4B
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractZipArchive(
        zipFile: File,
        destinationDir: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            destinationDir.mkdirs()
            val destCanonical = destinationDir.canonicalPath + File.separator

            java.util.zip.ZipFile(zipFile).use { zip ->
                val totalEntries = zip.size()
                var processed = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    processed++

                    val targetFile = File(destinationDir, entry.name.replace('\\', '/'))
                    if (!targetFile.canonicalPath.startsWith(destCanonical) && targetFile.canonicalPath != destinationDir.canonicalPath) {
                        Log.w(TAG, "Skipping zip-slip entry: ${entry.name}")
                        continue
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (totalEntries > 0) {
                        onProgress((processed * 100) / totalEntries)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Zip extraction error", e)
            false
        }
    }
}

