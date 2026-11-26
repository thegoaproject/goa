package com.goa.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.goa.data.prefs.DisplayAudioPreferences
import com.goa.ui.settings.VideoSettingsSection

/** ☰ → Video: the scaling preset and sharpness every game starts with, unless it has its own on its page. */
@Composable
fun VideoPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { DisplayAudioPreferences.globalVideo(context) }
    var preset by remember { mutableIntStateOf(saved.first) }
    var sharpness by remember { mutableFloatStateOf(saved.second) }

    MenuPageScaffold(title = MenuPage.VIDEO.title, onBack = onBack) {
        VideoSettingsSection(
            currentPreset = preset,
            sharpness = sharpness,
            onSelectPreset = {
                preset = it
                DisplayAudioPreferences.setGlobalPreset(context, it)
            },
            onSharpnessChange = {
                sharpness = it
                DisplayAudioPreferences.setGlobalSharpness(context, it)
            }
        )
        Text(
            text = "A game's own video settings, on its page, take priority.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
