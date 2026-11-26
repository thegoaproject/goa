package com.goa.data.folders

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.goa.data.db.GameDao
import com.goa.data.db.GoaDatabase
import com.goa.data.folders.FolderStore.Companion.AddDecision
import com.goa.domain.scan.CandidateWriter
import com.goa.domain.scan.FolderMerge
import com.goa.domain.scan.FolderScanner
import com.goa.domain.scan.GameDaoCandidateStore
import com.goa.domain.scan.SafTreeLister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class PrefsTextStore(private val prefs: SharedPreferences, private val key: String) : TextStore {
    override fun read(): String? = prefs.getString(key, null)
    override fun write(value: String) { prefs.edit().putString(key, value).apply() }
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val folderId: String, val dirs: Int, val found: Int) : ScanState
    data class Failed(val folderId: String, val message: String) : ScanState
}

sealed interface AddFolderResult {
    /** [replaced]: watched folders inside the new one, merged into it (their games moved, not scanned again). */
    data class Added(val folder: AddedFolder, val replaced: List<AddedFolder> = emptyList()) : AddFolderResult
    data class AlreadyAdded(val folder: AddedFolder) : AddFolderResult
    data class InsideExisting(val parent: AddedFolder) : AddFolderResult
    data class Failed(val message: String) : AddFolderResult
}

/**
 * The folders the player added and the scans of them. Scans run on this class's own scope (not a screen's), one at a
 * time, so leaving the library or rotating the phone does not cancel one; removing a folder cancels its scan.
 */
class FolderRepository(
    private val context: Context,
    private val store: FolderStore,
    private val gameDao: GameDao,
    private val db: GoaDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _folders = MutableStateFlow(store.list())
    val folders: StateFlow<List<AddedFolder>> = _folders.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val scanMutex = Mutex()
    private val jobs = HashMap<String, Job>()
    private val resolver get() = context.contentResolver

    private fun reload() { _folders.value = store.list() }

    /** Takes the permission that keeps the folder readable after a restart, then adds it and scans it. */
    suspend fun addFolder(treeUri: Uri): AddFolderResult = try {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uriString = treeUri.toString()
        when (val decision = FolderStore.decide(store.list(), uriString)) {
            is AddDecision.Duplicate -> { rescan(decision.of.id); AddFolderResult.AlreadyAdded(decision.of) }
            is AddDecision.InsideExisting -> {
                // The new grant is redundant: the larger folder already covers it
                runCatching { resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                AddFolderResult.InsideExisting(decision.parent)
            }
            is AddDecision.ReplacesNested, AddDecision.Add -> {
                val folder = AddedFolder(FolderStore.idFor(uriString), uriString, displayNameOf(treeUri))
                val nested = (decision as? AddDecision.ReplacesNested)?.nested.orEmpty()
                if (nested.isNotEmpty()) mergeInto(folder, nested)
                store.save(store.list().filterNot { f -> nested.any { it.id == f.id } } + folder)
                reload()
                rescan(folder.id)
                AddFolderResult.Added(folder, nested)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not add folder $treeUri", e)
        AddFolderResult.Failed(e.message ?: "Couldn't open that folder")
    }

    /**
     * [folder] is being added around [nested] watched folders: their games become its games (same ids, nothing
     * installed is lost or shown twice), and their scans and grants stop. The bigger folder's scan, which looks inside
     * every subfolder anyway, then refreshes them in place.
     */
    private suspend fun mergeInto(folder: AddedFolder, nested: List<AddedFolder>) {
        nested.forEach { jobs.remove(it.id)?.cancel() }
        db.withTransaction {
            val rows = nested.associate { n ->
                n.id to gameDao.folderGameRows(n.id).map { FolderMerge.Row(it.id, it.sourceKey, it.notInstalled) }
            }
            val taken = gameDao.sourceKeysForFolder(folder.id).toSet()
            val deletes = mutableListOf<Long>()
            for (change in FolderMerge.plan(rows, folder.id, taken)) when (change) {
                is FolderMerge.Change.Rekey -> gameDao.rekeyFolderGame(change.id, change.sourceKey, folder.id)
                is FolderMerge.Change.MoveOnly -> gameDao.moveFolderGame(change.id, folder.id)
                is FolderMerge.Change.Delete -> deletes += change.id
            }
            deletes.chunked(500).forEach { gameDao.deleteCandidates(it) }
        }
        nested.forEach { n -> if ((_scanState.value as? ScanState.Scanning)?.folderId == n.id) _scanState.value = ScanState.Idle }
        // Their games' file links point inside the old folders until the bigger folder's scan refreshes them: keep
        // access to the old folders until then, so installing one of those games in the meantime still works
        synchronized(releaseAfterScan) { releaseAfterScan[folder.id] = nested.map { it.treeUri } }
    }

    private val releaseAfterScan = HashMap<String, List<String>>()

    /** Forgets a folder. Its uninstalled games disappear from the library; games that are installed are never touched. */
    suspend fun removeFolder(id: String) {
        jobs.remove(id)?.cancel()
        val folder = store.list().firstOrNull { it.id == id } ?: return
        gameDao.deleteNotInstalledByFolder(id)
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        store.save(store.list().filterNot { it.id == id })
        reload()
        if ((_scanState.value as? ScanState.Scanning)?.folderId == id) _scanState.value = ScanState.Idle
    }

    /** The player picks which version of a not-yet-installed game to install; later rescans keep the choice. */
    suspend fun useVersion(gameId: Long, variantId: Long) {
        val variant = gameDao.variantById(variantId)?.takeIf { it.gameId == gameId } ?: return
        db.withTransaction {
            if (gameDao.applyVariantToGame(gameId, variant.sourceUri, variant.sourceKind, variant.sizeBytes) > 0) {
                gameDao.setPrimaryVariant(gameId, variantId)
                gameDao.setPickedVariant(gameId, variant.sourceKey)
            }
        }
    }

    fun rescan(id: String) {
        val folder = store.list().firstOrNull { it.id == id } ?: return
        jobs[id]?.cancel()
        jobs[id] = scope.launch { scanMutex.withLock { runScan(folder) } }
    }

    fun rescanAll() = store.list().forEach { rescan(it.id) }

    /** A quiet refresh at app open: only folders not scanned for a day, and never ones whose access is lost. */
    fun rescanIfStale(now: Long = System.currentTimeMillis()) {
        store.list().filter { !it.needsRegrant && now - it.lastScanMs > STALE_MS }.forEach { rescan(it.id) }
    }

    /** Marks folders the system no longer lets us read, so the UI can ask the player to pick them again. */
    fun validateGrants() {
        val granted = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        store.save(store.list().map { it.copy(needsRegrant = it.treeUri !in granted) })
        reload()
    }

    private suspend fun runScan(folder: AddedFolder) {
        val treeUri = Uri.parse(folder.treeUri)
        val granted = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!granted) {
            // Without access every listing comes back empty: that must never be mistaken for "the games are gone"
            store.update(folder.id) { it.copy(needsRegrant = true) }
            reload()
            _scanState.value = ScanState.Failed(folder.id, "Access to ${folder.name} was lost. Add it again.")
            return
        }
        _scanState.value = ScanState.Scanning(folder.id, 0, 0)
        val started = System.currentTimeMillis()
        try {
            val result = FolderScanner(SafTreeLister(resolver, treeUri), folder.id).scan { p ->
                _scanState.value = ScanState.Scanning(folder.id, p.dirsVisited, p.candidatesFound)
            }
            if (result.dirsVisited <= 1 && result.games.isEmpty() && folder.lastCount > 0) {
                _scanState.value = ScanState.Failed(folder.id, "${folder.name} looks empty or unreadable, so nothing was changed.")
                return
            }
            CandidateWriter(GameDaoCandidateStore(gameDao), inTransaction = { block -> db.withTransaction { block() } })
                .apply(folder.id, folder.name, result.games)
            store.update(folder.id) { it.copy(lastScanMs = System.currentTimeMillis(), lastCount = result.games.size, needsRegrant = false) }
            synchronized(releaseAfterScan) { releaseAfterScan.remove(folder.id) }?.forEach { uri ->
                if (store.list().none { it.treeUri == uri }) {
                    runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
            reload()
            _scanState.value = ScanState.Idle
            Log.i(TAG, "Scanned ${folder.name}: ${result.games.size} games from ${result.candidateFiles} files in ${result.dirsVisited} folders, ${System.currentTimeMillis() - started} ms")
        } catch (e: CancellationException) {
            if (_scanState.value is ScanState.Scanning) _scanState.value = ScanState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scan of ${folder.name} failed", e)
            if (e is SecurityException) store.update(folder.id) { it.copy(needsRegrant = true) }
            reload()
            _scanState.value = ScanState.Failed(folder.id, e.message ?: "Scan failed")
        }
    }

    private fun displayNameOf(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val name = runCatching {
            resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
    }

    private companion object {
        const val TAG = "FolderRepository"
        val STALE_MS = TimeUnit.HOURS.toMillis(24)
    }
}
