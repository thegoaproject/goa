package com.goa.ui.catalog

import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goa.data.api.UserSession
import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.data.folders.AddFolderResult
import com.goa.data.folders.AddedFolder
import com.goa.data.folders.FolderRepository
import com.goa.data.folders.ScanState
import com.goa.data.repository.AuthRepository
import com.goa.data.repository.GameCatalogRepository
import com.goa.data.repository.SyncState
import com.goa.domain.scan.GameSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

enum class CatalogFilter {
    ALL,
    INSTALLED,
    NOT_INSTALLED
}

enum class CatalogSort(val label: String) {
    TITLE_ASC("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    RELEASE_YEAR_DESC("Year (Newest)"),
    RELEASE_YEAR_ASC("Year (Oldest)"),
    RECENTLY_PLAYED("Recently Played")
}

/** One group of the library list (Free Classics / GOG / Local Folders), sorted on its own. */
data class LibrarySection(val source: GameSource, val games: List<GameEntity>, val collapsed: Boolean)

/**
 * The library list is split by where games come from, in a fixed order. Each section keeps the chosen sort on its own,
 * so an A-Z list starts again at A in every section. Collapsed sections are remembered by id.
 */
object LibrarySections {
    val ORDER = listOf(GameSource.PREPACKAGED, GameSource.GOG, GameSource.MY_FOLDERS)
    const val RECENT_ID = "recent"

    fun id(source: GameSource) = source.name

    /** [sorted] is already filtered and sorted; sections with no games are left out. */
    fun group(sorted: List<GameEntity>, collapsed: Set<String>): List<LibrarySection> {
        val bySource = sorted.groupBy { GameSource.of(it.id) }
        return ORDER.mapNotNull { source ->
            bySource[source]?.let { LibrarySection(source, it, id(source) in collapsed) }
        }
    }

    fun toggle(collapsed: Set<String>, id: String): Set<String> = if (id in collapsed) collapsed - id else collapsed + id
}

data class LibraryUiState(
    /** Every shown game, in list order (section by section). */
    val filteredGames: List<GameEntity> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val recentCollapsed: Boolean = false,
    val recentGames: List<GameEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: CatalogFilter = CatalogFilter.ALL,
    val selectedSort: CatalogSort = CatalogSort.TITLE_ASC,
    val syncState: SyncState = SyncState.Idle,
    val userSession: UserSession? = null,
    /** Folders the player added, and what the current scan is doing. */
    val folders: List<AddedFolder> = emptyList(),
    val scanState: ScanState = ScanState.Idle,
    /** Sources offered in the filter menu, and the ones the player has unticked (only offered ones count). */
    val availableSources: List<GameSource> = listOf(GameSource.PREPACKAGED),
    val hiddenSources: Set<GameSource> = emptySet(),
    /** How many of the player's own games (found in folders, or imported) are in the library, shown or not. */
    val ownGameCount: Int = 0
) {
    /** The games a gamepad steps through: those in expanded sections. */
    val navigableGames: List<GameEntity> get() = sections.filterNot { it.collapsed }.flatMap { it.games }
}

class LibraryViewModel(
    private val catalogRepository: GameCatalogRepository,
    private val authRepository: AuthRepository,
    private val folderRepository: FolderRepository,
    private val uiPrefs: SharedPreferences
) : ViewModel() {

    private val _hiddenSources = MutableStateFlow(SourceFilter.parse(uiPrefs.getStringSet(HIDDEN_SOURCES_KEY, null)))
    private val _collapsedSections = MutableStateFlow(uiPrefs.getStringSet(COLLAPSED_SECTIONS_KEY, null).orEmpty().toSet())

    /** Library hints the player closed with their X ([HINT_GOG], [HINT_FOLDERS]): not shown again. */
    private val _hiddenHints = MutableStateFlow(uiPrefs.getStringSet(HIDDEN_HINTS_KEY, null).orEmpty().toSet())
    val hiddenHints: StateFlow<Set<String>> = _hiddenHints.asStateFlow()

    fun hideHint(id: String) {
        val next = _hiddenHints.value + id
        _hiddenHints.value = next
        uiPrefs.edit().putStringSet(HIDDEN_HINTS_KEY, next).apply()
    }

    /** One-line messages for the screen to show (a folder that was already added, access problems...). */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(CatalogFilter.ALL)
    private val _selectedSort = MutableStateFlow(CatalogSort.TITLE_ASC)

    private val filterCriteria = combine(_searchQuery, _selectedFilter, _selectedSort) { query, filter, sort ->
        Triple(query, filter, sort)
    }

    private class Raw(
        val games: List<GameEntity>,
        val recents: List<GameEntity>,
        val criteria: Triple<String, CatalogFilter, CatalogSort>,
        val syncState: SyncState,
        val session: UserSession?
    )

    private val raw = combine(
        catalogRepository.getDosGames(),
        catalogRepository.getRecentlyPlayedGames(),
        filterCriteria,
        catalogRepository.syncState,
        authRepository.userSession
    ) { games, recents, criteria, syncState, session -> Raw(games, recents, criteria, syncState, session) }

    val uiState: StateFlow<LibraryUiState> = combine(
        raw, folderRepository.folders, folderRepository.scanState, _hiddenSources, _collapsedSections
    ) { r, folders, scanState, hidden, collapsed ->
        val (query, filter, sort) = r.criteria
        val ownGames = r.games.count { GameSource.of(it.id) == GameSource.MY_FOLDERS }
        val available = SourceFilter.visibleSources(loggedIn = r.session != null, hasFolders = folders.isNotEmpty(), hasOwnGames = ownGames > 0)
        val activeHidden = SourceFilter.activeHidden(hidden, available)

        val filtered = SourceFilter.filter(r.games, activeHidden).filter { game ->
            val matchesQuery = query.isBlank() ||
                    game.title.contains(query, ignoreCase = true) ||
                    game.slug.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                CatalogFilter.ALL -> true
                CatalogFilter.INSTALLED -> game.installStatus == InstallStatus.READY_TO_PLAY
                CatalogFilter.NOT_INSTALLED -> game.installStatus == InstallStatus.NOT_INSTALLED
            }

            matchesQuery && matchesFilter
        }

        val sorted = when (sort) {
            CatalogSort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            CatalogSort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase(Locale.ROOT) }
            CatalogSort.RELEASE_YEAR_DESC -> filtered.sortedWith(compareByDescending<GameEntity> { it.releaseYear ?: 0 }.thenBy { it.title })
            CatalogSort.RELEASE_YEAR_ASC -> filtered.sortedWith(compareBy<GameEntity> { it.releaseYear ?: 9999 }.thenBy { it.title })
            CatalogSort.RECENTLY_PLAYED -> filtered.sortedWith(compareByDescending<GameEntity> { it.lastPlayedTimestamp ?: 0L }.thenBy { it.title })
        }

        // A search shows every match: a collapsed section would hide the very game being looked for
        val sections = LibrarySections.group(sorted, if (query.isBlank()) collapsed else emptySet())
        LibraryUiState(
            filteredGames = sections.flatMap { it.games },
            sections = sections,
            recentCollapsed = LibrarySections.RECENT_ID in collapsed,
            recentGames = SourceFilter.filter(r.recents, activeHidden),
            searchQuery = query,
            selectedFilter = filter,
            selectedSort = sort,
            syncState = r.syncState,
            userSession = r.session,
            folders = folders,
            scanState = scanState,
            availableSources = available,
            hiddenSources = activeHidden,
            ownGameCount = ownGames
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    init {
        // Auto-sync on startup if logged in
        if (authRepository.isLoggedIn()) {
            syncLibrary()
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun onFilterChange(filter: CatalogFilter) {
        _selectedFilter.value = filter
    }

    fun onSortChange(sort: CatalogSort) {
        _selectedSort.value = sort
    }

    fun syncLibrary(force: Boolean = false) {
        viewModelScope.launch {
            catalogRepository.syncLibrary(force = force)
        }
    }

    /**
     * Signs out of GOG and forgets that account here: its not-installed games and GOG's login cookies (otherwise the
     * sign-in page greets the next person with this account's name and email). Installed games stay.
     */
    fun logout() {
        authRepository.logout()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        viewModelScope.launch { catalogRepository.pruneGogLibrary(ownedIds = null) }
    }

    /** The player picked a folder: keep access to it and scan it. */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            when (val result = folderRepository.addFolder(treeUri)) {
                is AddFolderResult.Added -> if (result.replaced.isNotEmpty()) _events.tryEmit(
                    "${result.folder.name} now covers ${result.replaced.joinToString { it.name }}, so " +
                        (if (result.replaced.size == 1) "that folder was" else "those folders were") + " merged into it."
                )
                is AddFolderResult.AlreadyAdded -> _events.tryEmit(
                    if (result.folder.needsRegrant) "Access to ${result.folder.name} is back. Scanning it again."
                    else "${result.folder.name} is already watched. Scanning it again."
                )
                is AddFolderResult.InsideExisting -> _events.tryEmit(
                    "${result.parent.name} is already watched, including every folder inside it."
                )
                is AddFolderResult.Failed -> _events.tryEmit(result.message)
            }
        }
    }

    fun rescanFolders() = folderRepository.rescanAll()

    fun removeFolder(id: String) {
        viewModelScope.launch { folderRepository.removeFolder(id) }
    }

    fun toggleSource(source: GameSource) {
        val next = SourceFilter.toggle(_hiddenSources.value, source, uiState.value.availableSources)
        _hiddenSources.value = next
        uiPrefs.edit().putStringSet(HIDDEN_SOURCES_KEY, SourceFilter.serialize(next)).apply()
    }

    /** Collapse or expand a library section (a [LibrarySections.id] or [LibrarySections.RECENT_ID]). */
    fun toggleSection(id: String) {
        val next = LibrarySections.toggle(_collapsedSections.value, id)
        _collapsedSections.value = next
        uiPrefs.edit().putStringSet(COLLAPSED_SECTIONS_KEY, next).apply()
    }

    companion object {
        const val HINT_GOG = "gog"
        const val HINT_FOLDERS = "folders"
        private const val HIDDEN_SOURCES_KEY = "library_hidden_sources"
        private const val COLLAPSED_SECTIONS_KEY = "library_collapsed_sections"
        private const val HIDDEN_HINTS_KEY = "library_hidden_hints"
    }
}

