package com.goa.ui.catalog

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.ui.theme.GOATheme
import com.goa.ui.theme.RetroGreen

/**
 * The library as thumbnails, for a phone on its side: the same sections and rows as the portrait list
 * ([libraryRows]), with each section's header and the free-tier footnote spanning the full width.
 */
@Composable
internal fun LibraryGameGrid(
    rows: List<LibraryRow>,
    gridState: LazyGridState,
    selectedGameId: Long?,
    lockedGameIds: Set<Long>,
    ownInstalls: Int,
    onToggleSection: (String) -> Unit,
    onGameClick: (Long) -> Unit,
    onUpgrade: () -> Unit
) {
    // Keep a controller's selection on screen, only when the selection moves (as the portrait list does)
    val currentRows by rememberUpdatedState(rows)
    var scrolledFor by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedGameId) {
        if (selectedGameId == null || selectedGameId == scrolledFor) return@LaunchedEffect
        val target = currentRows.indexOfFirst { it is LibraryRow.Game && !it.recent && it.game.id == selectedGameId }
        if (target < 0) return@LaunchedEffect
        scrolledFor = selectedGameId
        val visible = gridState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == target }) gridState.animateScrollToItem(target)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = rows,
            key = { it.key },
            span = { row -> if (row is LibraryRow.Game) GridItemSpan(1) else GridItemSpan(maxLineSpan) }
        ) { row ->
            when (row) {
                is LibraryRow.Header -> SectionHeader(
                    title = row.title,
                    count = row.count,
                    collapsed = row.collapsed,
                    accent = row.id == LibrarySections.RECENT_ID,
                    onToggle = { onToggleSection(row.id) }
                )
                is LibraryRow.Game -> GameTile(
                    game = row.game,
                    isSelected = row.game.id == selectedGameId,
                    isLocked = row.game.id in lockedGameIds,
                    onClick = { onGameClick(row.game.id) }
                )
                is LibraryRow.InstallsFootnote -> InstallsFootnote(used = ownInstalls, onUpgrade = onUpgrade)
            }
        }
    }
}

/** One game in the grid: its art (with install state, play-ready and lock marks) and its title under it. */
@Composable
internal fun GameTile(
    game: GameEntity,
    isSelected: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
        ) {
            GameArt(url = game.boxArtUrl, title = game.title, modifier = Modifier.fillMaxSize())

            // Installing, extracting, paused or failed: say so on the art (installed games get the play mark instead)
            if (game.installStatus != InstallStatus.NOT_INSTALLED && game.installStatus != InstallStatus.READY_TO_PLAY) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) { GameStatusBadge(status = game.installStatus) }
            }
            if (game.installStatus == InstallStatus.READY_TO_PLAY) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(30.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (isLocked) MaterialTheme.colorScheme.surfaceVariant else RetroGreen,
                    shadowElevation = 3.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.PlayArrow,
                            contentDescription = if (isLocked) "Locked" else "Installed",
                            tint = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Text(
            text = game.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}

@Preview(name = "Tiles", widthDp = 360)
@Composable
private fun GameTilePreview() {
    val game = GameEntity(
        id = 1, title = "One Must Fall: 2097", slug = "omf", boxArtUrl = "", bannerUrl = null, description = "",
        releaseYear = 1994, isDosNative = true, downloadSize = 0L, installStatus = InstallStatus.READY_TO_PLAY
    )
    GOATheme {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp)) {
            GameTile(game, isSelected = false, isLocked = false, onClick = {}, modifier = Modifier.size(width = 112.dp, height = 170.dp))
            GameTile(game.copy(installStatus = InstallStatus.DOWNLOADING), isSelected = true, isLocked = false, onClick = {}, modifier = Modifier.size(width = 112.dp, height = 170.dp))
            GameTile(game, isSelected = false, isLocked = true, onClick = {}, modifier = Modifier.size(width = 112.dp, height = 170.dp))
        }
    }
}
