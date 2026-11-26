package com.goa.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goa.domain.storage.InstalledGameStorage
import com.goa.domain.storage.StorageSummary
import com.goa.domain.storage.StorageUsageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Storage page: the three figures, clearing temporary files, and the installed games with Uninstall. */
class StorageViewModel(
    private val repository: StorageUsageRepository,
    /** InstallGameUseCase.uninstallGame: true when every file was removed. */
    private val uninstallGame: suspend (Long) -> Boolean,
    lockedGames: Flow<Set<Long>>,
    /** Save states on the phone and in Drive (US5); null in previews. */
    private val saveStates: com.goa.data.drive.DriveSaveStates? = null
) : ViewModel() {

    /** The Save States section's rows, from the same scan as a Drive export or import. */
    val saveStateList: StateFlow<com.goa.data.drive.SaveStateList> =
        saveStates?.list ?: MutableStateFlow(com.goa.data.drive.SaveStateList())

    fun refreshSaveStates() {
        val service = saveStates ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { service.refreshList() } }
    }

    private val _summary = MutableStateFlow<StorageSummary?>(null)
    val summary: StateFlow<StorageSummary?> = _summary.asStateFlow()

    private val rows = MutableStateFlow<List<InstalledGameStorage>>(emptyList())

    /** Rows with their lock shown; locked games are installed beyond the free slots while GOA Pro isn't unlocked. */
    val games: StateFlow<List<InstalledGameStorage>> = combine(rows, lockedGames) { list, locked ->
        list.map { it.copy(locked = it.gameId in locked) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _measuring = MutableStateFlow(true)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var measureJob: Job? = null

    /** The page opened (or needs fresh figures): forget the last message and measure again. */
    fun open() {
        _message.value = null
        refresh()
        refreshSaveStates()
    }

    fun refresh() {
        viewModelScope.launch { _summary.value = repository.summary() }
        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            _measuring.value = true
            repository.installedGames().collect { fresh ->
                // Keep the state of rows that are uninstalling or failed while the list is re-measured
                val busy = rows.value.filter { it.state == InstalledGameStorage.State.UNINSTALLING || it.state == InstalledGameStorage.State.UNINSTALL_FAILED }
                    .associateBy { it.gameId }
                rows.value = fresh.map { row -> busy[row.gameId]?.let { row.copy(state = it.state) } ?: row }
            }
            _measuring.value = false
        }
    }

    fun clearTemporaryFiles() {
        viewModelScope.launch {
            val result = repository.clearTemporaryFiles()
            _message.value = buildString {
                append("Freed ${formatBytes(result.freedBytes)}.")
                if (result.keptAny) append(" Some files belong to installs in progress and were kept.")
            }
            _summary.value = repository.summary()
        }
    }

    fun uninstall(gameId: Long) {
        setState(gameId, InstalledGameStorage.State.UNINSTALLING)
        _message.value = null
        viewModelScope.launch {
            if (uninstallGame(gameId)) {
                rows.update { list -> list.filterNot { it.gameId == gameId } }
                refreshSaveStates() // its save state went with it
            } else {
                setState(gameId, InstalledGameStorage.State.UNINSTALL_FAILED)
                refresh() // what's left of it
            }
            _summary.value = repository.summary()
        }
    }

    private fun setState(gameId: Long, state: InstalledGameStorage.State) =
        rows.update { list -> list.map { if (it.gameId == gameId) it.copy(state = state) else it } }
}

/** Sizes as players read them: "512 KB", "37 MB", "1.4 GB". */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(java.util.Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(java.util.Locale.US, "%.0f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
