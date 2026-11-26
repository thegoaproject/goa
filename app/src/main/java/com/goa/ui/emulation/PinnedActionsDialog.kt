package com.goa.ui.emulation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goa.ui.menu.FullScreenDialogWindow

/**
 * A dialog whose actions never scroll away: [content] scrolls in the space left, and [actions] sit in their own pane
 * pinned to the bottom, side by side on one row (unlike AlertDialog, which wraps its buttons onto a second line and
 * lets a tall body push them off screen).
 *
 * The dialog window covers the whole screen ([FullScreenDialogWindow]) and the dialog keeps clear of the system bars
 * and display cutout itself, so it fits on any screen, with the bars shown or hidden, and re-fits when they come or go.
 * A tap outside the dialog still dismisses it.
 *
 * [actions] get a modifier to apply to each action: side by side with equal widths, or, when [actionMinWidth] (what one
 * action needs to show its whole label, which grows with the font size) doesn't fit in half the row, stacked full width.
 */
@Composable
fun PinnedActionsDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    actionMinWidth: Dp = 0.dp,
    actions: @Composable (itemModifier: Modifier) -> Unit
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FullScreenDialogWindow()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // The window covers the screen, so "outside the dialog" is this box: a tap here dismisses
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val perAction = (min(maxWidth, 560.dp) - 16.dp * 2 - 12.dp) / 2   // the actions pane's padding and gap
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight)
                    .pointerInput(Unit) { detectTapGestures { } }   // a tap on the dialog itself isn't "outside"
            ) {
                Column {
                    Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) { title() }
                    // The options take whatever height is left and scroll inside it
                    Box(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 24.dp)) { content() }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val paneModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    if (actionMinWidth > perAction) {
                        Column(modifier = paneModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = paneModifier,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) { actions(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
