package com.goa.ui.detail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.data.db.ExtraType
import com.goa.data.db.GameExtraEntity

@Composable
fun ExtrasTab(
    extras: List<GameExtraEntity>,
    downloadingExtraIds: Set<String> = emptySet(),
    onOpenExtra: (GameExtraEntity) -> Unit,
    onDownloadExtra: (GameExtraEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (extras.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No bonus extras available for this title.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(extras, key = { it.id }) { extra ->
                ExtraItemCard(
                    extra = extra,
                    isDownloading = downloadingExtraIds.contains(extra.id),
                    onOpen = { onOpenExtra(extra) },
                    onDownload = { onDownloadExtra(extra) }
                )
            }
        }
    }
}

@Composable
private fun ExtraItemCard(
    extra: GameExtraEntity,
    isDownloading: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit
) {
    val icon = when (extra.type) {
        ExtraType.MANUAL_PDF -> Icons.Default.Description
        ExtraType.SOUNDTRACK -> Icons.Default.MusicNote
        ExtraType.WALLPAPER -> Icons.Default.Image
        else -> Icons.Default.Description
    }

    val actionLabel = when (extra.type) {
        ExtraType.SOUNDTRACK -> "Play"
        ExtraType.WALLPAPER -> "View"
        ExtraType.MANUAL_PDF, ExtraType.REFERENCE_CARD -> "Read"
        else -> "Open"
    }

    val actionIcon = when (extra.type) {
        ExtraType.SOUNDTRACK -> Icons.Default.PlayArrow
        ExtraType.WALLPAPER -> Icons.Default.Image
        ExtraType.MANUAL_PDF, ExtraType.REFERENCE_CARD -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.OpenInNew
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extra.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatFileSize(extra.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (extra.isDownloaded && extra.localFilePath != null) {
                Button(
                    onClick = onOpen,
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    com.goa.ui.menu.OneLineLabel(actionLabel)
                }
            } else if (isDownloading) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = MaterialTheme.shapes.small
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    com.goa.ui.menu.OneLineLabel("Downloading...")
                }
            } else {
                OutlinedButton(
                    onClick = onDownload,
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    com.goa.ui.menu.OneLineLabel("Download")
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024)
    } else {
        String.format("%.1f MB", mb)
    }
}

