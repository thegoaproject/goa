package com.goa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class VideoPresetInfo(
    val id: Int,
    val name: String,
    val description: String
)

val AVAILABLE_VIDEO_PRESETS = listOf(
    VideoPresetInfo(0, "Clean Modern", "Snapdragon GSR / FSR edge-adaptive upscaling with OLED pillarbox"),
    VideoPresetInfo(1, "Sharp Retro", "Pixel-perfect integer scaling preserving original pixel grid"),
    VideoPresetInfo(2, "Classic CRT", "Curvature, phosphor scanlines, and retro shadow mask"),
    VideoPresetInfo(3, "Fullscreen Stretch", "Stretches 4:3 DOS viewport to fill the entire modern screen")
)

@Composable
fun VideoSettingsSection(
    currentPreset: Int,
    sharpness: Float = 0.6f,
    onSelectPreset: (Int) -> Unit,
    onSharpnessChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "Video Scaling & Shaders",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "Select a hardware-accelerated upscaling preset. The factory default 'Clean Modern' preserves the authentic 4:3 aspect ratio with OLED black borders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AVAILABLE_VIDEO_PRESETS.take(2).forEach { preset ->
                    FilterChip(
                        selected = currentPreset == preset.id,
                        onClick = { onSelectPreset(preset.id) },
                        label = { com.goa.ui.menu.OneLineLabel(preset.name, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AVAILABLE_VIDEO_PRESETS.drop(2).forEach { preset ->
                    FilterChip(
                        selected = currentPreset == preset.id,
                        onClick = { onSelectPreset(preset.id) },
                        label = { com.goa.ui.menu.OneLineLabel(preset.name, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val activeDesc = AVAILABLE_VIDEO_PRESETS.find { it.id == currentPreset }?.description ?: ""
            Text(
                text = activeDesc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            if (currentPreset == 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edge Sharpness",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${(sharpness * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = sharpness,
                    onValueChange = onSharpnessChange,
                    valueRange = 0.0f..1.0f
                )
            }
        }
    }
}

