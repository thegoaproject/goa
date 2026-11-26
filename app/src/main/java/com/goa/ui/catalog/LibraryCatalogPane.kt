package com.goa.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.goa.ui.brand.GoaWordmark
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import com.goa.data.repository.SyncState
import com.goa.ui.theme.RetroAmber

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryCatalogPane(
    uiState: LibraryUiState,
    selectedGameId: Long? = null,
    isDarkTheme: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    /** Phone in landscape: controls on the left, games as a grid of thumbnails on the right (see [isPhoneLandscape]). */
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    onToggleTheme: () -> Unit = {},
    onGameClick: (Long) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (CatalogFilter) -> Unit,
    onSortChange: (CatalogSort) -> Unit = {},
    onOpenMenu: () -> Unit = {},
    /** Where the ☰ icon is (window coordinates), so the menu opens under it in any layout. */
    onMenuIconPlaced: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onConnectGog: () -> Unit = {},
    onAddFolder: (initialTreeUri: String?) -> Unit = {},
    onRescanFolders: () -> Unit = {},
    onRemoveFolder: (String) -> Unit = {},
    /** Library hints the player closed ([LibraryViewModel.HINT_GOG], [LibraryViewModel.HINT_FOLDERS]). */
    hiddenHints: Set<String> = emptySet(),
    onHideHint: (String) -> Unit = {},
    onToggleSource: (com.goa.domain.scan.GameSource) -> Unit = {},
    onToggleSection: (String) -> Unit = {},
    /** Free tier: the GOG and Local Folders sections end with "n/3 installs. Upgrade for more." */
    registered: Boolean = true,
    ownInstalls: Int = 0,
    onUpgrade: () -> Unit = {},
    /** Own games installed beyond the free slots while unregistered: shown with a lock. */
    lockedGameIds: Set<Long> = emptySet(),
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val phoneLandscape = isPhoneLandscape()
    val topBar: @Composable () -> Unit = {
            TopAppBar(
                title = {
                    GoaWordmark(modifier = Modifier.height(24.dp))
                },
                actions = {
                    // Theme toggle icon (Light / Dark mode)
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Sync icon
                    IconButton(onClick = onSyncClick) {
                        if (uiState.syncState is SyncState.Syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Library"
                            )
                        }
                    }

                    // ☰ menu: account & privacy, video, audio, storage, help, licenses
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .onGloballyPositioned { onMenuIconPlaced(it.boundsInWindow()) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
    }

    // Search and filters: pinned above the list in portrait, the left panel (with the hint cards) in phone landscape
    val controls: @Composable () -> Unit = {
            // Sync progress bar
            AnimatedVisibility(visible = uiState.syncState is SyncState.Syncing) {
                val syncing = uiState.syncState as? SyncState.Syncing
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    if (syncing != null && syncing.total > 0) {
                        LinearProgressIndicator(
                            progress = { syncing.current.toFloat() / syncing.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncing?.currentTitle ?: "Syncing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        text = "Search your MS-DOS games...",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                singleLine = true,
                maxLines = 1
            )

            // Filter & Sort Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                // Wrapped chips (phone landscape): the source filter button stays level with the first row
                verticalAlignment = if (phoneLandscape) Alignment.Top else Alignment.CenterVertically
            ) {
              val chips: @Composable () -> Unit = {
                CatalogFilter.values().forEach { filter ->
                    val label = when (filter) {
                        CatalogFilter.ALL -> "All"
                        CatalogFilter.INSTALLED -> "Installed"
                        CatalogFilter.NOT_INSTALLED -> "Not Installed"
                    }
                    FilterChip(
                        selected = uiState.selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { com.goa.ui.menu.OneLineLabel(label, style = MaterialTheme.typography.labelMedium) },
                        shape = MaterialTheme.shapes.small
                    )
                }

                Box {
                    FilterChip(
                        selected = sortMenuExpanded,
                        onClick = { sortMenuExpanded = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { com.goa.ui.menu.OneLineLabel(uiState.selectedSort.label, style = MaterialTheme.typography.labelMedium) },
                        shape = MaterialTheme.shapes.small
                    )

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        CatalogSort.values().forEach { sortOption ->
                            DropdownMenuItem(
                                text = { com.goa.ui.menu.OneLineLabel(sortOption.label) },
                                onClick = {
                                    onSortChange(sortOption)
                                    sortMenuExpanded = false
                                },
                                leadingIcon = if (uiState.selectedSort == sortOption) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }

              }
              // The chips scroll sideways on a narrow screen (the source filter button stays pinned at the end); in the
              // phone-landscape side panel they wrap instead, so none is cut off at the panel's edge
              if (phoneLandscape) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) { chips() }
              } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { chips() }
              }
                // Source filter: Prepackaged / GOG (when connected) / My Folders (when added). Only worth showing with a choice.
                if (uiState.availableSources.size > 1) {
                    var sourceMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sourceMenuExpanded = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter by source",
                                tint = if (uiState.hiddenSources.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                            uiState.availableSources.forEach { source ->
                                DropdownMenuItem(
                                    text = {
                                        com.goa.ui.menu.OneLineLabel(sectionTitle(source))
                                    },
                                    onClick = { onToggleSource(source) },
                                    leadingIcon = {
                                        androidx.compose.material3.Checkbox(
                                            checked = source !in uiState.hiddenSources,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
    }

    // The GOG suggestion and Add Folder: dismissible cards. In portrait they are the game list's first item and scroll
    // away with it: pinned above the list, with large fonts they filled the whole screen and left the games no room.
    val showHints = (uiState.userSession == null && LibraryViewModel.HINT_GOG !in hiddenHints) ||
        LibraryViewModel.HINT_FOLDERS !in hiddenHints
    val hints: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Offline / Local Mode Banner (when unauthenticated), until the player closes it
            if (uiState.userSession == null && LibraryViewModel.HINT_GOG !in hiddenHints) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connect GOG sits beside the text, or under it with large fonts (a Row squeezed the text
                        // to a few letters per line); the close button stays at the end either way
                        com.goa.ui.menu.SideOrBelow(
                            modifier = Modifier.weight(1f),
                            trailingMaxFraction = 0.4f,
                            leading = {
                                Column {
                                    Text(
                                        text = "Playing in Local & Freeware Mode",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Free classics, released by their makers for anyone to play. Connect GOG to sync your full collection.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        ) {
                            Button(
                                onClick = onConnectGog,
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                com.goa.ui.menu.OneLineLabel("Connect GOG", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HintCloseButton(LibraryViewModel.HINT_GOG, onHideHint)
                    }
                }
            }

            // Add a folder of games to scan, under the GOG banner, until the player closes it (☰ → Storage has it too)
            if (LibraryViewModel.HINT_FOLDERS !in hiddenHints) {
                AddFolderRow(
                    folders = uiState.folders,
                    ownGames = uiState.ownGameCount,
                    scanState = uiState.scanState,
                    onAddFolder = onAddFolder,
                    onRescan = onRescanFolders,
                    onRemoveFolder = { onRemoveFolder(it.id) },
                    onClose = { onHideHint(LibraryViewModel.HINT_FOLDERS) }
                )
            }
        }
    }

    val showEmpty = uiState.filteredGames.isEmpty() && uiState.syncState !is SyncState.Syncing
    val rows = remember(uiState.sections, uiState.recentGames, uiState.recentCollapsed, uiState.searchQuery, registered, ownInstalls) {
        libraryRows(uiState, registered)
    }
    val emptyState: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No MS-DOS games found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) {
                                "Try searching with a different term."
                            } else {
                                "Tap the sync icon above, or add a folder of DOS games below."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onAddFolder(null) }) {
                                com.goa.ui.menu.OneLineLabel("Add Folder")
                            }
                            if (uiState.userSession == null) {
                                Button(onClick = onConnectGog) {
                                    com.goa.ui.menu.OneLineLabel("Connect GOG")
                                }
                            }
                        }
                    }
                }
    }

    val gameList: @Composable () -> Unit = {
                // Keep the gamepad/keyboard selection visible (its row in its section, not the Recently Played copy).
                // Only when the selection moves: the list's rows are rebuilt on every library update, and re-running on
                // those would snap the list back to the selected game whenever the player scrolls away from it (on a
                // phone the last opened game stays selected after going back, so the top of the list was unreachable).
                val currentRows by androidx.compose.runtime.rememberUpdatedState(rows)
                var scrolledFor by rememberSaveable { mutableStateOf<Long?>(null) }
                LaunchedEffect(selectedGameId) {
                    if (selectedGameId == null || selectedGameId == scrolledFor) return@LaunchedEffect
                    val row = currentRows.indexOfFirst { it is LibraryRow.Game && !it.recent && it.game.id == selectedGameId }
                    if (row < 0) return@LaunchedEffect
                    val target = row + if (showHints) 1 else 0   // the hint cards are item 0
                    scrolledFor = selectedGameId
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val first = visible.firstOrNull()?.index
                    val last = visible.lastOrNull()?.index
                    if (first == null || last == null || target < first || target > last) {
                        listState.animateScrollToItem((target - 2).coerceAtLeast(0))
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showHints) item(key = "hints") { hints() }   // the cards carry their own side margins
                    items(rows, key = { it.key }) { row ->
                      Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        when (row) {
                            is LibraryRow.Header -> SectionHeader(
                                title = row.title,
                                count = row.count,
                                collapsed = row.collapsed,
                                accent = row.id == LibrarySections.RECENT_ID,
                                onToggle = { onToggleSection(row.id) }
                            )
                            is LibraryRow.Game -> GameListItem(
                                game = row.game,
                                isSelected = (row.game.id == selectedGameId),
                                isLocked = row.game.id in lockedGameIds,
                                onClick = { onGameClick(row.game.id) }
                            )
                            is LibraryRow.InstallsFootnote -> InstallsFootnote(used = ownInstalls, onUpgrade = onUpgrade)
                        }
                      }
                    }
                }
    }

    if (phoneLandscape) {
        // Phone on its side: too short for a list under the controls, too small for two panes. Controls on the left,
        // the games as thumbnails on the right; a game opens full screen (AdaptiveAppRoot uses one pane here).
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        ) {
            Column(
                modifier = Modifier
                    .width(LandscapeControlsWidth)
                    .fillMaxHeight()
            ) {
                topBar()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    controls()
                    hints()
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (showEmpty) emptyState() else LibraryGameGrid(
                    rows = rows,
                    gridState = gridState,
                    selectedGameId = selectedGameId,
                    lockedGameIds = lockedGameIds,
                    ownInstalls = ownInstalls,
                    onToggleSection = onToggleSection,
                    onGameClick = onGameClick,
                    onUpgrade = onUpgrade
                )
            }
        }
    } else {
        Scaffold(modifier = modifier.fillMaxSize(), topBar = topBar) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                controls()
                if (showEmpty) {
                    hints()
                    emptyState()
                } else gameList()
            }
        }
    }
}

/** The left panel's width in phone landscape: enough for the search box and the GOG suggestion. */
private val LandscapeControlsWidth = 340.dp

/**
 * A phone on its side: short (under 480 dp tall) and wider than tall. Foldables and tablets are taller in landscape and
 * keep their two-pane layout.
 */
@Composable
fun isPhoneLandscape(): Boolean {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return configuration.screenHeightDp < 480 && configuration.screenWidthDp > configuration.screenHeightDp
}


fun sectionTitle(source: com.goa.domain.scan.GameSource): String = when (source) {
    com.goa.domain.scan.GameSource.PREPACKAGED -> "Free Classics"
    com.goa.domain.scan.GameSource.GOG -> "GOG"
    com.goa.domain.scan.GameSource.MY_FOLDERS -> "Local Folders"
}

/** One row of the library list. */
internal sealed interface LibraryRow {
    val key: String

    data class Header(val id: String, val title: String, val count: Int, val collapsed: Boolean) : LibraryRow {
        override val key get() = "header_$id"
    }

    data class Game(val game: com.goa.data.db.GameEntity, val recent: Boolean) : LibraryRow {
        override val key get() = if (recent) "recent_${game.id}" else "game_${game.id}"
    }

    data class InstallsFootnote(val sectionId: String) : LibraryRow {
        override val key get() = "installs_$sectionId"
    }
}

/**
 * Recently Played (when not searching), then each source section. A collapsed section keeps only its header. On the
 * free tier the player's own sections (GOG, Local Folders) end with the install count; bundled games never use a slot.
 */
internal fun libraryRows(state: LibraryUiState, registered: Boolean): List<LibraryRow> = buildList {
    if (state.recentGames.isNotEmpty() && state.searchQuery.isBlank()) {
        add(LibraryRow.Header(LibrarySections.RECENT_ID, "Recently Played", state.recentGames.size, state.recentCollapsed))
        if (!state.recentCollapsed) state.recentGames.forEach { add(LibraryRow.Game(it, recent = true)) }
    }
    state.sections.forEach { section ->
        val id = LibrarySections.id(section.source)
        add(LibraryRow.Header(id, sectionTitle(section.source), section.games.size, section.collapsed))
        if (!section.collapsed) {
            section.games.forEach { add(LibraryRow.Game(it, recent = false)) }
            if (!registered && section.source != com.goa.domain.scan.GameSource.PREPACKAGED) add(LibraryRow.InstallsFootnote(id))
        }
    }
}

/** A section title with its game count and a ^ that turns down while the section is collapsed; tap to toggle. */
@Composable
internal fun SectionHeader(title: String, count: Int, collapsed: Boolean, accent: Boolean, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 180f else 0f, label = "sectionChevron")
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = if (collapsed) "Expand" else "Collapse", onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp).rotate(rotation)
        )
    }
}

/** Free tier: "1/3 installs. Upgrade for more." The count is shared by GOG and Local Folders games. */
@Composable
internal fun InstallsFootnote(used: Int, onUpgrade: () -> Unit) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append("$used/${com.goa.domain.license.GameSlots.FREE_SLOTS} installs. ")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
        append("Unlock Pro for more.")
        pop()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Unlock Pro", onClick = onUpgrade)
            .padding(start = 4.dp, top = 2.dp, bottom = 8.dp)
    )
}

/** Where a closed library hint can be found again. */
internal fun hintWhereToFind(id: String): String = when (id) {
    LibraryViewModel.HINT_GOG -> "You can always connect your GOG account later in ☰ → Account & Privacy."
    else -> "You can always add folders, and choose which ones GOA watches for games, in ☰ → Storage."
}

/** The X on a library hint: asks first, saying where to find it later, then hides it for good. */
@Composable
internal fun HintCloseButton(id: String, onHide: (String) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    IconButton(onClick = { asking = true }, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Default.Close, contentDescription = "Hide this", modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Hide this?") },
            text = { Text(hintWhereToFind(id)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; onHide(id) }) { com.goa.ui.menu.OneLineLabel("Hide") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { com.goa.ui.menu.OneLineLabel("Keep") } }
        )
    }
}
