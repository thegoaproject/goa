package com.goa.domain.usecase

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.goa.data.db.InstallStatus
import com.goa.data.db.SourceKind
import com.goa.data.storage.AppStorageManager
import com.goa.domain.scan.DirRef
import com.goa.domain.scan.INSTALLER_URI_SEPARATOR
import com.goa.domain.scan.SafTreeLister
import com.goa.domain.scan.TreeLister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The file or folder a candidate was found in can't be read any more (access revoked, file moved or deleted). */
class SourceUnavailableException(message: String) : IllegalStateException(message)

class NotEnoughSpaceException(message: String) : IllegalStateException(message)

/** Reads a candidate's source. [ContentSourceAccess] is the real one (Storage Access Framework); tests use memory. */
interface SourceAccess {
    fun open(uri: String): InputStream?
    fun displayName(uri: String): String?
    /** A lister and the root directory for a folder candidate's document uri. */
    fun folder(uri: String): Pair<TreeLister, DirRef>?
}

class ContentSourceAccess(private val resolver: ContentResolver) : SourceAccess {
    override fun open(uri: String): InputStream? = runCatching { resolver.openInputStream(Uri.parse(uri)) }.getOrNull()

    override fun displayName(uri: String): String? = runCatching {
        resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun folder(uri: String): Pair<TreeLister, DirRef>? = runCatching {
        val docUri = Uri.parse(uri)
        val tree = DocumentsContract.buildTreeDocumentUri(docUri.authority, DocumentsContract.getTreeDocumentId(docUri))
        SafTreeLister(resolver, tree) to DirRef(DocumentsContract.getDocumentId(docUri))
    }.getOrNull()
}

/**
 * Installs a candidate found in a scanned folder: copies it into app storage (an archive or installer into the download
 * directory, a DOS folder straight into the install directory), then runs the same extraction and finishing steps as any
 * other install. Failure handling: a source that can't be read or too little space puts the game back to NOT_INSTALLED
 * (nothing went wrong with the game, so it stays a candidate); anything else is an ERROR the player can retry.
 */
class LocalSourceInstaller(
    private val storage: AppStorageManager,
    private val access: SourceAccess,
    private val setStatus: suspend (gameId: Long, status: InstallStatus) -> Unit,
    /** Extracts the staged installer and finishes the install (ExtractionManager.extractAndSanitize). */
    private val extract: suspend (gameId: Long, onProgress: (Int) -> Unit) -> Result<*>,
    /** Finishes an install whose files are already in the install directory (ExtractionManager.finalizeInstall). */
    private val finalize: suspend (gameId: Long) -> Result<*>,
    private val hasSpace: (requiredBytes: Long) -> Boolean = { storage.hasSufficientSpace(it) }
) {
    suspend fun install(gameId: Long, kind: String, sourceUri: String, sizeBytes: Long, onProgress: (Int) -> Unit = {}): Result<Unit> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (kind == SourceKind.FOLDER) installFolder(gameId, sourceUri, sizeBytes, onProgress)
                else installFile(gameId, kind, sourceUri, sizeBytes, onProgress)
                Result.success(Unit)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { abandon(gameId, kind, InstallStatus.NOT_INSTALLED) }
                throw e
            } catch (e: Exception) {
                val recoverable = e is SourceUnavailableException || e is NotEnoughSpaceException
                withContext(NonCancellable) { abandon(gameId, kind, if (recoverable) InstallStatus.NOT_INSTALLED else InstallStatus.ERROR) }
                Result.failure(e)
            }
        }

    private suspend fun installFile(gameId: Long, kind: String, sourceUri: String, size: Long, onProgress: (Int) -> Unit) {
        // An installer with its .bin parts is stored as several uris, installer first; an archive is just one
        val uris = sourceUri.split(INSTALLER_URI_SEPARATOR).filter { it.isNotBlank() }
        val streams = uris.map { access.open(it) ?: throw SourceUnavailableException("The file can't be read any more. Add its folder again.") }
        try {
            requireSpace((size * 2.2).toLong())
            setStatus(gameId, InstallStatus.DOWNLOADING)

            val dir = storage.getDownloadDir(gameId)
            dir.mkdirs()
            dir.listFiles()?.forEach { it.deleteRecursively() }        // leftovers of an earlier attempt would be mistaken for the installer
            var done = 0L
            uris.forEachIndexed { i, uri ->
                var name = safeName(access.displayName(uri)) ?: "game$i.bin"
                if (i == 0 && kind == SourceKind.INSTALLER && !name.endsWith(".exe", ignoreCase = true)) name += ".exe"
                val part = File(dir, "$name.part")
                val before = done
                copy(streams[i], part, 0L) { }
                done = before + part.length()
                if (size > 0) onProgress(((done * 100) / size).toInt().coerceIn(0, 100))
                val target = File(dir, name)
                if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
            }
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
        setStatus(gameId, InstallStatus.EXTRACTING)
        extract(gameId, onProgress).getOrThrow()
    }

    private suspend fun installFolder(gameId: Long, sourceUri: String, size: Long, onProgress: (Int) -> Unit) {
        val (lister, root) = access.folder(sourceUri) ?: throw SourceUnavailableException("The folder can't be read any more. Add its parent folder again.")
        requireSpace((size * 1.1).toLong())
        setStatus(gameId, InstallStatus.DOWNLOADING)

        val installDir = storage.getGameInstallDir(gameId)
        installDir.listFiles()?.forEach { it.deleteRecursively() }
        var copied = 0L
        val stack = ArrayDeque<Pair<DirRef, File>>()
        stack.addLast(root to installDir)
        while (stack.isNotEmpty()) {
            val (dir, target) = stack.removeLast()
            for (entry in lister.list(dir)) {
                currentCoroutineContext().ensureActive()
                val name = safeName(entry.name) ?: continue              // never let a provider write outside the install directory
                if (entry.isDir) {
                    val sub = File(target, name).also { it.mkdirs() }
                    stack.addLast(DirRef(entry.id) to sub)
                } else {
                    val input = access.open(entry.uri) ?: throw SourceUnavailableException("A file in the folder can't be read: $name")
                    input.use { copy(it, File(target, name), 0L) { } }
                    copied += entry.size
                    if (size > 0) onProgress(((copied * 100) / size).toInt().coerceIn(0, 100))
                }
            }
        }
        setStatus(gameId, InstallStatus.EXTRACTING)
        finalize(gameId).getOrThrow()
    }

    private fun requireSpace(required: Long) {
        if (!hasSpace(required)) throw NotEnoughSpaceException("Not enough free space: about ${required / (1024 * 1024)} MB is needed.")
    }

    private suspend fun copy(input: InputStream, out: File, size: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        out.outputStream().use { o ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                o.write(buffer, 0, n)
                done += n
                if (size > 0) onProgress(((done * 100) / size).toInt().coerceIn(0, 100))
            }
        }
    }

    /** Puts the game back in a clean state after a failure or cancel: partial files go, the status says what happened. */
    private suspend fun abandon(gameId: Long, kind: String, status: InstallStatus) {
        if (status == InstallStatus.ERROR || kind == SourceKind.FOLDER) storage.deleteGameFiles(gameId) else storage.cleanDownloads(gameId)
        setStatus(gameId, status)
    }

    private fun safeName(name: String?): String? =
        name?.let { File(it).name }?.takeIf { it.isNotBlank() && it != "." && it != ".." }
}
