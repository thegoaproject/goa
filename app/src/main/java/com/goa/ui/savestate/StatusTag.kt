package com.goa.ui.savestate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goa.data.drive.Outcome
import com.goa.domain.savestate.SaveStateComparison.Status

/**
 * A status label for save states: square corners (not a rounded pill chip), a tonal fill, one line. It is a label,
 * not a button, so it takes no focus (contracts/ui-drive-backup.md, house rules).
 */
@Composable
fun StatusTag(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (fill, ink) = when (tone) {
        Tone.GOOD -> colors.primaryContainer to colors.onPrimaryContainer
        Tone.ATTENTION -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tone.BAD -> colors.errorContainer to colors.onErrorContainer
        Tone.NEUTRAL -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    StatusTag(text, fill, ink, modifier)
}

@Composable
private fun StatusTag(text: String, fill: Color, ink: Color, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(fill, RectangleShape).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

enum class Tone { GOOD, ATTENTION, BAD, NEUTRAL }

/** The Storage list's backup status (contracts/ui-drive-backup.md "Status tags map the comparison"). */
fun Status.label(): String = when (this) {
    Status.SAME -> "Backed up"
    Status.PHONE_NEWER -> "Phone is newer"
    Status.DRIVE_NEWER -> "Drive is newer"
    Status.ONLY_PHONE -> "Not backed up"
    Status.ONLY_DRIVE -> "Only in Drive"
    Status.NOT_INSTALLED -> "Only in Drive: game not on this phone"
    Status.AMBIGUOUS -> "Only in Drive"
}

fun Status.tone(): Tone = when (this) {
    Status.SAME -> Tone.GOOD
    Status.PHONE_NEWER, Status.DRIVE_NEWER, Status.ONLY_PHONE -> Tone.ATTENTION
    Status.ONLY_DRIVE, Status.NOT_INSTALLED, Status.AMBIGUOUS -> Tone.NEUTRAL
}

fun Outcome.tone(): Tone = when (this) {
    Outcome.UPLOADED, Outcome.ADDED, Outcome.REPLACED, Outcome.SAME -> Tone.GOOD
    Outcome.FAILED, Outcome.DAMAGED -> Tone.BAD
    Outcome.KEPT_PHONE, Outcome.KEPT_DRIVE, Outcome.SKIPPED, Outcome.NOT_INSTALLED -> Tone.NEUTRAL
}
