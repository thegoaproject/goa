package com.goa.ui.detail

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.goa.data.prefs.DisplayAudioPreferences
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.engine.video.GpuCapabilityDetector
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroCyan
import com.goa.ui.theme.RetroGreen

/** The game's own video choice. It wins over the ☰ → Video default; "Use GOA default" forgets it. */
@Composable
fun VideoSettingsTab(
    gameId: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedPreset by remember(gameId) { mutableIntStateOf(DisplayAudioPreferences.effectiveVideo(context, gameId).first) }
    var sharpness by remember(gameId) { mutableFloatStateOf(DisplayAudioPreferences.effectiveVideo(context, gameId).second) }
    var hasOwnChoice by remember(gameId) {
        mutableStateOf(DisplayAudioPreferences.gameVideoOverride(context, gameId).let { it.first != null || it.second != null })
    }
    val isSnapdragon = remember { GpuCapabilityDetector.isSnapdragonDevice() }
    // "(Default)" marks what this game uses without a choice of its own: the ☰ → Video choice
    val globalPreset = remember { DisplayAudioPreferences.globalVideo(context).first }

    val presets = listOf(
        Triple(
            "Clean Modern",
            if (isSnapdragon) "Qualcomm Snapdragon Game Super Resolution (SGSR) edge-adaptive upscale with 4:3 OLED black pillarbox."
            else "AMD FSR edge-adaptive upscale with 4:3 OLED black pillarbox.",
            0
        ),
        Triple(
            "Sharp Retro",
            "True integer pixel-perfect scaling with zero interpolation or blur.",
            1
        ),
        Triple(
            "Classic CRT",
            "Subtle scanline shader and phosphor shadow mask simulating a 90s CRT monitor.",
            2
        ),
        Triple(
            "Fullscreen Stretch",
            "Stretches viewport edge-to-edge ignoring 4:3 aspect ratio.",
            3
        )
    ).map { (name, description, id) -> Triple(if (id == globalPreset) "$name (Default)" else name, description, id) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hardware GPU status banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (isSnapdragon) RetroCyan.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
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
                    color = if (isSnapdragon) RetroCyan else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = if (isSnapdragon) "Snapdragon Adreno Hardware Detected" else "Standard GPU Active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isSnapdragon) "Snapdragon Game Super Resolution (SGSR) hardware acceleration enabled." else "Integer Scaling & FSR active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Video Scaling Presets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (hasOwnChoice) "This game's own setting" else "Using GOA default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (hasOwnChoice) {
                TextButton(onClick = {
                    DisplayAudioPreferences.clearGameOverride(context, gameId)
                    val (preset, sharp) = DisplayAudioPreferences.effectiveVideo(context, gameId)
                    selectedPreset = preset
                    sharpness = sharp
                    hasOwnChoice = false
                }) { Text("Use GOA default", maxLines = 1, softWrap = false) }
            }
        }

        // Presets list
        presets.forEach { (title, desc, index) ->
            val isSelected = selectedPreset == index
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedPreset = index
                        DisplayAudioPreferences.setGamePreset(context, gameId, index)
                        hasOwnChoice = true
                    },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) RetroGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Sharpness adjustment for Clean Modern
        if (selectedPreset == 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Image Sharpness: ${(sharpness * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = sharpness,
                        onValueChange = { sharpness = it },
                        onValueChangeFinished = {
                            DisplayAudioPreferences.setGameSharpness(context, gameId, sharpness)
                            hasOwnChoice = true
                        },
                        valueRange = 0.0f..1.0f
                    )
                }
            }
        }
    }
}

