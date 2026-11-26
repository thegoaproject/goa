package com.goa.data.drive

import com.goa.data.db.GameEntity
import com.goa.domain.savestate.Conflict
import com.goa.domain.savestate.ConflictChoice
import com.goa.domain.savestate.ConflictQueue
import com.goa.domain.savestate.GameKey
import com.goa.domain.savestate.LocalSaveState
import com.goa.domain.savestate.SaveStateComparison
import com.goa.domain.savestate.SaveStateComparison.ExportAction
import com.goa.domain.savestate.SaveStateComparison.ImportAction
import com.goa.domain.savestate.SaveStateComparison.Side
import com.goa.domain.savestate.SaveStateComparison.Status
import com.goa.domain.savestate.SaveStateMeta
import com.goa.domain.savestate.SaveStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** A save state in Drive, as GOA reads it from the file's details (no download). */
data class DriveCopy(val key: String, val title: String, val fileId: String, val meta: SaveStateMeta)

/** One game's outcome in a run (contracts/ui-drive-backup.md, the result tags). */
enum class Outcome(val label: String) {
    UPLOADED("Uploaded"), ADDED("Added"), REPLACED("Replaced"), KEPT_PHONE("Kept this phone's"),
    KEPT_DRIVE("Kept Drive's"), SKIPPED("Skipped"), SAME("Same"), NOT_INSTALLED("Not on this phone"),
    /** The phone's save state is damaged, so it wasn't uploaded (retrying won't help). */
    DAMAGED("Damaged"),
    FAILED("Failed")
}

data class GameResult(val key: String, val title: String, val outcome: Outcome, val reason: String? = null)

/** What import will do, shown before anything changes (FR-011). */
data class ImportSummary(
    val toAdd: List<String>, val same: List<String>, val differ: List<String>, val notOnPhone: List<String>
) {
    val nothingToDo: Boolean get() = toAdd.isEmpty() && differ.isEmpty()
}

/** One side of the conflict screen. */
data class CopyInfo(val device: String, val savedAt: Long, val coreVersion: String)

data class ConflictPrompt(
    val title: String, val position: Int, val total: Int, val remainingAfter: Int,
    val phone: CopyInfo, val drive: CopyInfo, val preselected: ConflictChoice, val exporting: Boolean
)

/** "<Title> is installed twice on this phone. Which one is this save state for?" */
data class AmbiguityPrompt(val title: String, val installs: List<GameEntity>)

/** The one export, import or download in progress or just finished (data-model.md "TransferRun"). */
data class TransferRun(
    val kind: Kind,
    val phase: Phase,
    val total: Int = 0,
    val done: Int = 0,
    val current: String? = null,
    val results: List<GameResult> = emptyList(),
    val summary: ImportSummary? = null,
    val conflict: ConflictPrompt? = null,
    val ambiguity: AmbiguityPrompt? = null,
    val error: DriveError? = null
) {
    enum class Kind { EXPORT, IMPORT, DOWNLOAD }
    enum class Phase { AUTHORIZING, SCANNING, SUMMARY, RESOLVING, TRANSFERRING, DONE, FAILED }

    val running: Boolean get() = phase != Phase.DONE && phase != Phase.FAILED
    val failed: List<GameResult> get() = results.filter { it.outcome == Outcome.FAILED }
}

/** One row of the standing list in Storage & Cache (data-model.md "SaveStateListRow"). */
data class SaveStateListRow(
    val key: String,
    val title: String,
    val gameId: Long?,
    val phone: SaveStateMeta?,
    val phoneBytes: Long,
    val drive: SaveStateMeta?,
    val hasPrevious: Boolean,
    val previousSavedAt: Long?,
    /** Null when Drive was not checked (not Pro, no account, GOA OSS, offline). */
    val status: Status?,
    /** Why the phone's save state can't be loaded, or null when it's fine (or there is none). */
    val damaged: com.goa.domain.savestate.DosBoxStateFile.Problem? = null
)

data class SaveStateList(
    val rows: List<SaveStateListRow> = emptyList(),
    val loading: Boolean = false,
    /** Whether Drive was read for these rows. */
    val driveChecked: Boolean = false,
    val checkedAt: Long? = null,
    /** Why Drive could not be read, when it was asked. */
    val driveError: DriveError? = null
)

/**
 * Copies save states between the phone and the player's Google Drive (research R9): one app-wide service, one run at a
 * time, progress as [run]. Leaving the screen does not stop a run. Choices the player must make (the import summary,
 * which install, conflicts) pause the run until [confirmImport], [chooseInstall] or [answer] is called.
 *
 * Nothing here touches Android, so the whole flow is unit-tested against a fake Drive.
 */
class DriveSaveStates(
    private val client: DriveClient,
    private val installs: suspend () -> List<GameEntity>,
    /** GOA Pro, GOA (not GOA OSS), the feature switched on and an account linked. */
    private val available: () -> Boolean,
    private val defaults: (gameKey: String) -> SaveStateStore.Defaults,
    private val app: AppInfo,
    private val scope: CoroutineScope,
    private val onExported: (count: Int) -> Unit = {},
    private val onImported: (count: Int) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    data class AppInfo(val versionName: String, val versionCode: Int, val zone: () -> ZoneId = { ZoneId.systemDefault() })

    private val _run = MutableStateFlow<TransferRun?>(null)
    val run: StateFlow<TransferRun?> = _run.asStateFlow()

    private val _list = MutableStateFlow(SaveStateList())
    val list: StateFlow<SaveStateList> = _list.asStateFlow()

    private val runLock = Mutex()
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var summaryAnswer: CompletableDeferred<Boolean>? = null
    private var installAnswer: CompletableDeferred<GameEntity?>? = null
    private var queue: ConflictQueue<String>? = null
    private var conflictsSettled: CompletableDeferred<Unit>? = null
    private var conflictTitles: Map<String, String> = emptyMap()
    private var conflictExporting = false
    /** In-game Drive checks, per game key, for this session. */
    private val gameChecks = HashMap<String, DriveCopy?>()

    // ---- Starting runs ----

    /** Uploads every save state on the phone (FR-008..FR-010). [onlyKeys] limits it to those games (Retry). */
    fun export(onlyKeys: Set<String>? = null): Boolean = start(TransferRun.Kind.EXPORT) { runExport(onlyKeys) }

    /** Brings save states back from Drive (FR-011..FR-016). Pauses at [TransferRun.Phase.SUMMARY]. */
    fun import(onlyKeys: Set<String>? = null): Boolean = start(TransferRun.Kind.IMPORT) { runImport(onlyKeys) }

    /** Runs the last run's kind again for its failed games only. */
    fun retryFailed(): Boolean {
        val last = _run.value ?: return false
        if (last.running) return false
        val keys = last.failed.map { it.key }.toSet().ifEmpty { return false }
        return when (last.kind) {
            TransferRun.Kind.EXPORT -> export(keys)
            TransferRun.Kind.IMPORT -> import(keys)
            TransferRun.Kind.DOWNLOAD -> false
        }
    }

    /** Stops after the current game: an upload in flight finishes, nothing is left half-written. */
    fun cancel() {
        cancelled = true
        summaryAnswer?.complete(false)
        installAnswer?.complete(null)
        queue?.let { q -> while (!q.done) q.answer(ConflictChoice.SKIP) }
        conflictsSettled?.complete(Unit)
    }

    /** Clears a finished run's results. */
    fun dismiss() {
        if (_run.value?.running != true) _run.value = null
    }

    fun confirmImport(go: Boolean) {
        summaryAnswer?.complete(go)
    }

    fun chooseInstall(game: GameEntity?) {
        installAnswer?.complete(game)
    }

    /** The player's choice on the conflict screen; [applyToRemaining] is "Do this for the remaining N". */
    fun answer(choice: ConflictChoice, applyToRemaining: Boolean = false) {
        val q = queue ?: return
        q.answer(choice, applyToRemaining)
        afterAnswer(q)
    }

    fun keepNewerForAll() {
        val q = queue ?: return
        q.keepNewerForAll()
        afterAnswer(q)
    }

    private fun afterAnswer(q: ConflictQueue<String>) {
        if (q.done) conflictsSettled?.complete(Unit) else _run.update { it?.copy(conflict = prompt(q)) }
    }

    private fun start(kind: TransferRun.Kind, body: suspend () -> Unit): Boolean {
        if (!available()) return false
        if (_run.value?.running == true) return false
        cancelled = false
        _run.value = TransferRun(kind, TransferRun.Phase.SCANNING)
        job = scope.launch(io) {
            runLock.withLock {
                try {
                    body()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveException) {
                    _run.update { it?.copy(phase = TransferRun.Phase.FAILED, error = e.error, conflict = null, ambiguity = null) }
                } catch (e: Exception) {
                    _run.update {
                        it?.copy(phase = TransferRun.Phase.FAILED, error = DriveError.Other(e.message ?: "error"), conflict = null)
                    }
                } finally {
                    queue = null
                    conflictsSettled = null
                    summaryAnswer = null
                    installAnswer = null
                }
            }
            // Keep the standing list in step with what the run changed
            if (_list.value.rows.isNotEmpty() || _list.value.driveChecked) refreshList()
        }
        return true
    }

    // ---- Scanning ----

    /** A game's save state on the phone, in one install folder. */
    private data class PhoneCopy(val game: GameEntity, val key: String, val store: SaveStateStore, val state: LocalSaveState)

    private data class DriveGame(val folder: DriveFile?, val state: DriveFile?, val copy: DriveCopy?, val note: DriveFile?)

    private data class DriveScan(val roots: List<DriveFile>, val games: Map<String, DriveGame>)

    private suspend fun scanDrive(): DriveScan {
        val items = client.listGoaItems()
        fun kind(k: String) = items.filter { it.appProperties[DriveLayout.KIND] == k }
        val roots = kind(DriveLayout.KIND_ROOT).sortedBy { it.createdTime ?: it.modifiedTime ?: "" }
        val folders = kind(DriveLayout.KIND_GAME).groupBy { it.appProperties[DriveLayout.GAME_KEY].orEmpty() }
        val states = kind(DriveLayout.KIND_SAVE_STATE).groupBy { it.appProperties[DriveLayout.GAME_KEY].orEmpty() }
        val notes = kind(DriveLayout.KIND_NOTE).groupBy { it.appProperties[DriveLayout.GAME_KEY].orEmpty() }
        val keys = (folders.keys + states.keys).filter { it.isNotBlank() }
        val games = keys.associateWith { key ->
            // Two phones creating a folder at once leaves two: the oldest is the game's
            val folder = folders[key]?.minByOrNull { it.createdTime ?: it.modifiedTime ?: "" }
            val copies = states[key].orEmpty().mapNotNull { file -> driveCopy(file, folder)?.let { file to it } }
            val newest = copies.maxByOrNull { it.second.meta.savedAt }
            val note = notes[key]?.firstOrNull { folder == null || folder.id in it.parents } ?: notes[key]?.firstOrNull()
            DriveGame(folder, newest?.first, newest?.second, note)
        }
        return DriveScan(roots, games)
    }

    /** Reads a save state's details. Drive's own checksum wins over GOA's note of it (a file replaced by hand). */
    private fun driveCopy(file: DriveFile, folder: DriveFile?): DriveCopy? {
        val stored = DriveLayout.metaFrom(file.appProperties) ?: return null
        val checksum = file.sha256Checksum?.lowercase()
        val meta = if (checksum != null && checksum != stored.sha256.lowercase()) {
            stored.copy(sha256 = checksum, savedAt = parseTime(file.modifiedTime) ?: stored.savedAt)
        } else stored
        val title = file.appProperties[DriveLayout.TITLE]?.takeIf { it.isNotBlank() }
            ?: folder?.name?.takeIf { it.isNotBlank() } ?: file.name.removeSuffix(".state")
        return DriveCopy(meta.gameKey, title, file.id, meta)
    }

    private fun parseTime(rfc3339: String?): Long? = rfc3339?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private suspend fun scanPhone(games: List<GameEntity>): List<PhoneCopy> = withContext(io) {
        GameKey.installed(games).mapNotNull { game ->
            val key = GameKey.of(game)
            val store = SaveStateStore(File(game.localInstallPath!!))
            runCatching {
                store.recover()
                store.current(defaults(key))?.let { PhoneCopy(game, key, store, it) }
            }.getOrNull()
        }
    }

    private fun LocalSaveState.side() = Side(meta.sha256, meta.savedAt)
    private fun DriveCopy.side() = Side(meta.sha256, meta.savedAt)

    // ---- Export ----

    private suspend fun runExport(onlyKeys: Set<String>?) {
        val drive = scanDrive()
        // Several installs of one game: the newest save state is the one that goes up
        val phone = scanPhone(installs()).groupBy { it.key }
            .mapValues { (_, copies) -> copies.filter { it.state.problem == null }.maxByOrNull { it.state.meta.savedAt } ?: copies.maxBy { it.state.meta.savedAt } }
            .filterKeys { onlyKeys == null || it in onlyKeys }
        val results = mutableListOf<GameResult>()
        val uploads = mutableListOf<PhoneCopy>()
        val conflicts = mutableListOf<Conflict<String>>()
        for ((key, copy) in phone) {
            if (copy.state.problem != null) {
                results += GameResult(key, copy.game.title, Outcome.DAMAGED, DAMAGED_EXPORT_MESSAGE)
                continue
            }
            val remote = drive.games[key]?.copy
            val status = SaveStateComparison.status(copy.state.side(), remote?.side()) ?: continue
            when (SaveStateComparison.exportAction(status)) {
                ExportAction.UPLOAD -> uploads += copy
                ExportAction.SKIP -> results += GameResult(key, copy.game.title, Outcome.SAME)
                ExportAction.ASK -> conflicts += Conflict(key, copy.state.side(), remote!!.side())
                null -> Unit
            }
        }
        if (conflicts.isNotEmpty()) {
            val choices = resolve(conflicts, phone.mapValues { it.value.game.title }, exporting = true) { key ->
                val p = phone.getValue(key).state.meta
                val d = drive.games.getValue(key).copy!!.meta
                CopyInfo(p.device, p.savedAt, p.coreVersion) to CopyInfo(d.device, d.savedAt, d.coreVersion)
            }
            for ((key, choice) in choices) {
                val copy = phone.getValue(key)
                when (SaveStateComparison.exportAfterChoice(choice)) {
                    ExportAction.UPLOAD -> uploads += copy
                    else -> results += GameResult(
                        key, copy.game.title, if (choice == ConflictChoice.USE_DRIVE) Outcome.KEPT_DRIVE else Outcome.SKIPPED
                    )
                }
            }
        }

        transferring(uploads.size, results)
        var rootId: String? = null
        val folderIds = drive.games.mapValues { it.value.folder?.id }.toMutableMap()
        for (copy in uploads.sortedBy { it.game.title.lowercase() }) {
            if (cancelled) {
                results += GameResult(copy.key, copy.game.title, Outcome.SKIPPED)
                continue
            }
            _run.update { it?.copy(current = copy.game.title) }
            try {
                val root = rootId ?: ensureRoot(drive).also { rootId = it }
                val folder = folderIds[copy.key] ?: client.createFolder(
                    DriveLayout.folderName(copy.game.title), root, DriveLayout.gameProperties(copy.key)
                ).id.also { folderIds[copy.key] = it }
                upload(copy, folder, drive.games[copy.key])
                results += GameResult(copy.key, copy.game.title, Outcome.UPLOADED)
            } catch (e: DriveException) {
                results += GameResult(copy.key, copy.game.title, Outcome.FAILED, e.error.message)
                // Nothing more will fit, and access gone means every other game fails the same way
                if (e.error == DriveError.DriveFull || e.error == DriveError.AccessRemoved) {
                    finish(results, failRest = uploads.filter { u -> results.none { it.key == u.key } }, error = e.error)
                    return
                }
            }
            _run.update { it?.copy(done = it.done + 1, results = results.toList()) }
        }
        finish(results)
        onExported(results.count { it.outcome == Outcome.UPLOADED }) // "Same" wasn't exported: it was already there
    }

    /** The "GOA Save States" folder: the oldest when phones created several, the others' games moved into it. */
    private suspend fun ensureRoot(drive: DriveScan): String {
        val roots = drive.roots
        if (roots.isEmpty()) {
            return client.createFolder(DriveLayout.ROOT_NAME, null, DriveLayout.rootProperties()).id
        }
        val winner = roots.first()
        val others = roots.drop(1).map { it.id }.toSet()
        if (others.isNotEmpty()) {
            for (game in drive.games.values) {
                val folder = game.folder ?: continue
                folder.parents.firstOrNull { it in others }?.let { client.move(folder.id, winner.id, it) }
            }
        }
        return winner.id
    }

    private suspend fun upload(copy: PhoneCopy, folderId: String, existing: DriveGame?) {
        val meta = copy.state.meta
        val properties = DriveLayout.saveStateProperties(meta, copy.game.title)
        val source = UploadSource.FromFile(copy.state.file)
        val stateId = existing?.state?.id
        if (stateId != null) {
            client.uploadReplace(stateId, DriveLayout.STATE_MIME, properties, source)
        } else {
            client.uploadNew(DriveLayout.stateFileName(copy.game.title), DriveLayout.STATE_MIME, folderId, properties, source)
        }
        val note = UploadSource.FromBytes(
            DriveLayout.noteText(copy.game.title, meta, app.versionName, app.versionCode, app.zone()).toByteArray(Charsets.UTF_8)
        )
        val noteProps = DriveLayout.noteProperties(copy.key)
        val noteId = existing?.note?.id
        if (noteId != null) client.uploadReplace(noteId, DriveLayout.NOTE_MIME, noteProps, note)
        else client.uploadNew(DriveLayout.NOTE_NAME, DriveLayout.NOTE_MIME, folderId, noteProps, note)
    }

    // ---- Import ----

    /** A Drive save state matched to an install on this phone. */
    private data class Incoming(val drive: DriveCopy, val game: GameEntity, val phone: LocalSaveState?, val store: SaveStateStore) {
        // Judged once, before anything is downloaded over it
        val usablePhone: LocalSaveState? = phone?.takeIf { it.problem == null }
    }

    private suspend fun runImport(onlyKeys: Set<String>?) {
        val drive = scanDrive().games.mapNotNull { it.value.copy }.filter { onlyKeys == null || it.key in onlyKeys }
        val games = installs()
        val results = mutableListOf<GameResult>()
        val matched = mutableListOf<Incoming>()
        val ambiguous = mutableListOf<Pair<DriveCopy, List<GameEntity>>>()
        for (copy in drive) {
            when (val r = GameKey.resolve(copy.key, games)) {
                GameKey.Resolution.NotInstalled -> results += GameResult(copy.key, copy.title, Outcome.NOT_INSTALLED, NOT_INSTALLED_MESSAGE)
                is GameKey.Resolution.Ambiguous -> ambiguous += copy to r.games
                is GameKey.Resolution.Installed -> matched += incoming(copy, r.game)
            }
        }
        // A damaged phone copy is no copy: Drive's comes in without asking, and the damaged one isn't kept
        fun statusOf(i: Incoming) = SaveStateComparison.status(i.usablePhone?.side(), i.drive.side())!!
        val summary = ImportSummary(
            toAdd = matched.filter { statusOf(it) == Status.ONLY_DRIVE }.map { it.game.title },
            same = matched.filter { statusOf(it) == Status.SAME }.map { it.game.title },
            differ = matched.filter { SaveStateComparison.importAction(statusOf(it)) == ImportAction.ASK }.map { it.game.title } +
                ambiguous.map { it.first.title },
            notOnPhone = results.map { it.title }
        )
        val confirm = CompletableDeferred<Boolean>().also { summaryAnswer = it }
        _run.update { it?.copy(phase = TransferRun.Phase.SUMMARY, summary = summary, results = results.toList()) }
        if (!confirm.await() || cancelled) {
            _run.value = null
            return
        }
        _run.update { it?.copy(phase = TransferRun.Phase.SCANNING, summary = null) }

        // Which install each ambiguous save state is for
        for ((copy, candidates) in ambiguous) {
            val answer = CompletableDeferred<GameEntity?>().also { installAnswer = it }
            _run.update { it?.copy(phase = TransferRun.Phase.RESOLVING, ambiguity = AmbiguityPrompt(copy.title, candidates)) }
            val game = answer.await()
            _run.update { it?.copy(phase = TransferRun.Phase.SCANNING, ambiguity = null) }
            if (game == null || cancelled) results += GameResult(copy.key, copy.title, Outcome.SKIPPED)
            else matched += incoming(copy, game)
        }

        val downloads = mutableListOf<Incoming>()
        val conflicts = mutableListOf<Conflict<String>>()
        val byKey = matched.associateBy { it.drive.key }
        for (i in matched) {
            when (SaveStateComparison.importAction(statusOf(i))) {
                ImportAction.DOWNLOAD -> downloads += i
                ImportAction.ASK -> conflicts += Conflict(i.drive.key, i.usablePhone!!.side(), i.drive.side())
                else -> results += GameResult(i.drive.key, i.game.title, Outcome.SAME)
            }
        }
        if (conflicts.isNotEmpty()) {
            val choices = resolve(conflicts, byKey.mapValues { it.value.game.title }, exporting = false) { key ->
                val i = byKey.getValue(key)
                val p = i.usablePhone!!.meta
                val d = i.drive.meta
                CopyInfo(p.device, p.savedAt, p.coreVersion) to CopyInfo(d.device, d.savedAt, d.coreVersion)
            }
            for ((key, choice) in choices) {
                val i = byKey.getValue(key)
                when {
                    choice == ConflictChoice.USE_DRIVE -> downloads += i
                    choice == ConflictChoice.KEEP_PHONE -> results += GameResult(key, i.game.title, Outcome.KEPT_PHONE)
                    else -> results += GameResult(key, i.game.title, Outcome.SKIPPED)
                }
            }
        }

        transferring(downloads.size, results)
        for (i in downloads.sortedBy { it.game.title.lowercase() }) {
            if (cancelled) {
                results += GameResult(i.drive.key, i.game.title, Outcome.SKIPPED)
                continue
            }
            _run.update { it?.copy(current = i.game.title) }
            results += try {
                fetch(i.drive, i.store, keepCurrent = i.usablePhone != null)
                GameResult(i.drive.key, i.game.title, if (i.usablePhone == null) Outcome.ADDED else Outcome.REPLACED)
            } catch (e: DriveException) {
                if (e.error == DriveError.AccessRemoved) {
                    results += GameResult(i.drive.key, i.game.title, Outcome.FAILED, e.error.message)
                    finish(results, failRest = emptyList(), error = e.error)
                    return
                }
                GameResult(i.drive.key, i.game.title, Outcome.FAILED, e.error.message)
            }
            _run.update { it?.copy(done = it.done + 1, results = results.toList()) }
        }
        finish(results)
        onImported(results.count { it.outcome == Outcome.ADDED || it.outcome == Outcome.REPLACED })
    }

    private suspend fun incoming(copy: DriveCopy, game: GameEntity): Incoming = withContext(io) {
        val store = SaveStateStore(File(game.localInstallPath!!))
        store.recover()
        Incoming(copy, game, store.current(defaults(copy.key)), store)
    }

    /** Downloads into the temporary file, checks it, then swaps it in; the phone's copy becomes the previous one. */
    private suspend fun fetch(copy: DriveCopy, store: SaveStateStore, keepCurrent: Boolean = true) = withContext(io) {
        try {
            client.download(copy.fileId, store.tempFile)
            try {
                store.replaceFromTemp(copy.meta.copy(gameKey = copy.key), defaults(copy.key), keepCurrent)
            } catch (e: com.goa.domain.savestate.NotASaveStateException) {
                throw DriveException(DriveError.NotASaveState, e)
            } catch (e: java.io.IOException) {
                throw DriveException(DriveError.Damaged, e)
            }
        } finally {
            store.tempFile.delete()
        }
    }

    // ---- In-game download (US3) ----

    /** This game's save state in Drive, or null when there is none; checked once per session. */
    suspend fun checkGame(key: String, refresh: Boolean = false): DriveCopy? {
        if (!available()) return null
        if (!refresh) synchronized(gameChecks) { if (gameChecks.containsKey(key)) return gameChecks[key] }
        val copy = scanDrive().games[key]?.copy
        synchronized(gameChecks) { gameChecks[key] = copy }
        return copy
    }

    /** How this game's phone copy compares with [copy]: SAME means there is nothing to download. */
    fun compareWith(copy: DriveCopy, installPath: String): Status {
        val local = SaveStateStore(File(installPath)).current(defaults(copy.key))?.takeIf { it.problem == null }
        return SaveStateComparison.status(local?.side(), copy.side())!!
    }

    /**
     * Writes [copy] over this install's save state (the phone's becomes the previous one). The caller has already
     * asked when the copies differ.
     */
    suspend fun download(copy: DriveCopy, installPath: String): Outcome {
        val store = SaveStateStore(File(installPath))
        val had = withContext(io) { store.recover(); store.current(defaults(copy.key))?.takeIf { it.problem == null } != null }
        fetch(copy, store, keepCurrent = had)
        refreshListLater()
        return if (had) Outcome.REPLACED else Outcome.ADDED
    }

    // ---- Standing list (US5) ----

    /**
     * Rebuilds the Save States list in Storage & Cache from the same scans as a run, so it always agrees with what the
     * next export or import does (SC-007). Reads Drive's file details only; never uploads or downloads (FR-023).
     */
    suspend fun refreshList() {
        _list.update { it.copy(loading = true) }
        val games = installs()
        val phone = scanPhone(games)
        var driveError: DriveError? = null
        val drive: Map<String, DriveCopy>? = if (available()) {
            try {
                scanDrive().games.mapNotNull { it.value.copy }.associateBy { it.key }
            } catch (e: DriveException) {
                driveError = e.error
                null
            }
        } else null
        val keysOnPhone = GameKey.installed(games).groupBy { GameKey.of(it) }
        val rows = mutableListOf<SaveStateListRow>()
        for (p in phone) {
            val d = drive?.get(p.key)
            val previous = runCatching { p.store.previous(defaults(p.key))?.takeIf { it.problem == null } }.getOrNull()
            val problem = p.state.problem
            rows += SaveStateListRow(
                p.key, p.game.title, p.game.id, p.state.meta, p.state.bytes, d?.meta, previous != null,
                previous?.meta?.savedAt,
                drive?.let { SaveStateComparison.status(p.state.side().takeIf { problem == null }, d?.side()) },
                damaged = problem
            )
        }
        drive?.values?.forEach { d ->
            val installsOfKey = keysOnPhone[d.key].orEmpty()
            installsOfKey.filter { g -> phone.none { it.game.id == g.id } }.forEach { g ->
                rows += SaveStateListRow(d.key, g.title, g.id, null, 0, d.meta, false, null, Status.ONLY_DRIVE)
            }
            if (installsOfKey.isEmpty()) {
                rows += SaveStateListRow(d.key, d.title, null, null, 0, d.meta, false, null, Status.NOT_INSTALLED)
            }
        }
        rows.sortByDescending { maxOf(it.phone?.savedAt ?: 0L, it.drive?.savedAt ?: 0L) }
        _list.value = SaveStateList(
            rows, loading = false, driveChecked = drive != null, checkedAt = if (drive != null) now() else null,
            driveError = driveError
        )
        synchronized(gameChecks) { gameChecks.clear() }
    }

    /** Only where the list is on show (not in a game's own process, which never builds it). */
    private fun refreshListLater() {
        if (_list.value.rows.isEmpty() && !_list.value.driveChecked) return
        scope.launch(io) { runCatching { refreshList() } }
    }

    // ---- Shared steps ----

    private suspend fun resolve(
        conflicts: List<Conflict<String>>,
        titles: Map<String, String>,
        exporting: Boolean,
        infos: (String) -> Pair<CopyInfo, CopyInfo>
    ): Map<String, ConflictChoice> {
        val q = ConflictQueue(conflicts.sortedBy { titles[it.key]?.lowercase() })
        queue = q
        conflictTitles = titles
        conflictExporting = exporting
        conflictInfos = infos
        val settled = CompletableDeferred<Unit>().also { conflictsSettled = it }
        _run.update { it?.copy(phase = TransferRun.Phase.RESOLVING, conflict = prompt(q)) }
        settled.await()
        _run.update { it?.copy(conflict = null) }
        queue = null
        return q.choices.toMap()
    }

    private var conflictInfos: (String) -> Pair<CopyInfo, CopyInfo> = { error("no conflict") }

    private fun prompt(q: ConflictQueue<String>): ConflictPrompt? {
        val c = q.current ?: return null
        val (phone, drive) = conflictInfos(c.key)
        return ConflictPrompt(
            title = conflictTitles[c.key] ?: c.key, position = q.position, total = q.total,
            remainingAfter = q.remainingAfterCurrent, phone = phone, drive = drive,
            preselected = SaveStateComparison.newer(c.phone, c.drive), exporting = conflictExporting
        )
    }

    private fun transferring(total: Int, results: List<GameResult>) {
        _run.update {
            it?.copy(phase = TransferRun.Phase.TRANSFERRING, total = total, done = 0, results = results.toList(), conflict = null)
        }
    }

    private fun finish(results: MutableList<GameResult>, failRest: List<PhoneCopy> = emptyList(), error: DriveError? = null) {
        failRest.forEach { results += GameResult(it.key, it.game.title, Outcome.FAILED, error?.message) }
        _run.update {
            it?.copy(phase = TransferRun.Phase.DONE, current = null, results = results.toList(), error = error, conflict = null)
        }
    }

    companion object {
        const val DAMAGED_EXPORT_MESSAGE =
            "This phone's save state is damaged, so it wasn't uploaded. Make a new one with Save Slot."
        const val NOT_INSTALLED_MESSAGE = "Game not found on device. Add local folder or GOG account and install first."
    }
}
