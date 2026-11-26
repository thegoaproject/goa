package com.goa.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.goa.data.api.toSecureGogImageUrl
import com.goa.domain.scan.GameSource
import com.goa.domain.storage.InstalledGameStorage
import com.goa.domain.storage.InstalledGameStorage.State
import com.goa.domain.storage.StorageSummary
import com.goa.ui.theme.GOATheme

/** ☰ → Storage: where the space goes, clearing temporary files, and uninstalling games largest first. */
@Composable
fun StoragePage(
    viewModel: StorageViewModel,
    onBack: () -> Unit,
    /** Builds the Save States footer: who is looking decides what it offers (GOA OSS, not Pro, no account, linked). */
    saveStatesFooter: @Composable () -> SaveStatesFooter = { SaveStatesFooter.None },
    /** The Watched folders section (needs the folder picker, which belongs to the screen that hosts this page). */
    watchedFolders: @Composable () -> Unit = {}
) {
    LaunchedEffect(viewModel) { viewModel.open() }
    val summary by viewModel.summary.collectAsState()
    val games by viewModel.games.collectAsState()
    val measuring by viewModel.measuring.collectAsState()
    val message by viewModel.message.collectAsState()
    val saveStates by viewModel.saveStateList.collectAsState()
    val footer = saveStatesFooter()
    MenuPageScaffold(title = MenuPage.STORAGE.title, onBack = onBack) {
        StoragePageContent(
            summary = summary,
            games = games,
            measuring = measuring,
            message = message,
            onClear = viewModel::clearTemporaryFiles,
            onUninstall = viewModel::uninstall
        )
        watchedFolders()
        SaveStatesSection(list = saveStates, footer = footer, onRefresh = viewModel::refreshSaveStates)
    }
}

@Composable
fun StoragePageContent(
    summary: StorageSummary?,
    games: List<InstalledGameStorage>,
    measuring: Boolean,
    message: String?,
    onClear: () -> Unit,
    onUninstall: (Long) -> Unit
) {
    var confirming by remember { mutableStateOf<InstalledGameStorage?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryCard(summary = summary, message = message, onClear = onClear)

        Text(
            text = "Installed games",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (games.isEmpty() && !measuring) {
            Text(
                text = "No games installed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    games.forEachIndexed { index, game ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        GameRow(game = game, onUninstall = { confirming = game })
                    }
                }
            }
        }
    }

    confirming?.let { game ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Uninstall ${game.title}?", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    (game.sizeBytes?.let { "Frees ${formatBytes(it)}. " } ?: "") +
                        "Saves the game keeps in its own folder are deleted with it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onUninstall(game.gameId)
                }) { com.goa.ui.menu.OneLineLabel("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { com.goa.ui.menu.OneLineLabel("Cancel") } }
        )
    }
}

@Composable
private fun SummaryCard(summary: StorageSummary?, message: String?, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Space",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            FigureRow("Free on device", summary?.freeBytes)
            FigureRow("Used by GOA", summary?.goaUsedBytes)
            FigureRow("Temporary files", summary?.tempBytes)

            val temp = summary?.tempBytes ?: 0L
            Button(
                onClick = onClear,
                enabled = temp > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                OneLineLabel(if (temp > 0L) "Clear temporary files (${formatBytes(temp)})" else "Nothing to clear")
            }
            message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FigureRow(label: String, bytes: Long?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = bytes?.let { formatBytes(it) } ?: "…",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun GameRow(game: InstalledGameStorage, onUninstall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            if (game.boxArtUrl.isNotBlank()) {
                AsyncImage(
                    model = game.boxArtUrl.toSecureGogImageUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // No cover: the title's initials, as the library shows them
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = game.title.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
                            .joinToString("") { it.first().uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.locked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = game.sizeBytes?.let { formatBytes(it) } ?: "Measuring…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (game.state == State.UNINSTALL_FAILED) {
                Text(
                    text = "Couldn't remove every file. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        when (game.state) {
            State.READY, State.UNINSTALL_FAILED -> OutlinedButton(
                onClick = onUninstall,
                modifier = Modifier
                    .width(120.dp)
                    .height(40.dp)
            ) { OneLineLabel("Uninstall") }
            State.INSTALLING -> StateLabel("Installing…")
            State.UNINSTALLING -> StateLabel("Uninstalling…")
        }
    }
}

@Composable
private fun StateLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.width(120.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

private val previewGames = listOf(
    InstalledGameStorage(1, "Tomb Raider 1", "", GameSource.GOG, 1_480_000_000L, State.READY),
    InstalledGameStorage(2, "Quake", "", GameSource.GOG, 210_000_000L, State.UNINSTALL_FAILED, locked = true),
    InstalledGameStorage(3, "One Must Fall: 2097", "", GameSource.PREPACKAGED, null, State.INSTALLING)
)

@Preview(name = "Phone", widthDp = 411, heightDp = 900)
@Preview(name = "Tablet", widthDp = 840, heightDp = 900)
@Composable
private fun StoragePagePreview() {
    GOATheme {
        StoragePageContent(StorageSummary(12_000_000_000L, 2_100_000_000L, 340_000_000L), previewGames, false, null, {}, {})
    }
}

@Preview(name = "Empty, dark", widthDp = 411, heightDp = 500)
@Composable
private fun StoragePageEmptyPreview() {
    GOATheme(darkTheme = true) { StoragePageContent(StorageSummary(12_000_000_000L, 40_000_000L, 0L), emptyList(), false, null, {}, {}) }
}
