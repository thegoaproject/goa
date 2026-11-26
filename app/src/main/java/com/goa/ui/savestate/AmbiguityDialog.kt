package com.goa.ui.savestate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goa.data.db.GameEntity
import com.goa.data.drive.AmbiguityPrompt
import com.goa.ui.menu.OneLineLabel
import java.io.File

/** The same game installed twice on this phone: which install a Drive save state is for. */
@Composable
fun AmbiguityDialog(prompt: AmbiguityPrompt, onChoose: (GameEntity?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${prompt.title} is installed twice on this phone. Which one is this save state for?")
                prompt.installs.forEach { game ->
                    OutlinedButton(onClick = { onChoose(game) }, modifier = Modifier.fillMaxWidth()) {
                        OneLineLabel(source(game))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { OneLineLabel("Skip") } }
    )
}

/** "GOG" for a GOG install, else the folder the game was found in. */
private fun source(game: GameEntity): String =
    if (game.sourceKind == null && game.id > 0) "GOG"
    else game.sourceUri?.let { android.net.Uri.decode(it).substringAfterLast(':').substringAfterLast('/') }?.takeIf { it.isNotBlank() }
        ?: game.localInstallPath?.let { File(it).name } ?: game.title
