package com.goa.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.goa.ui.theme.GOATheme

private val PanelWidth = 248.dp

val MenuPage.icon: ImageVector
    get() = when (this) {
        MenuPage.ACCOUNT_PRIVACY -> Icons.Default.AccountCircle
        MenuPage.VIDEO -> Icons.Default.Tv
        MenuPage.AUDIO -> Icons.Default.Headphones
        MenuPage.STORAGE -> Icons.Default.Storage
        MenuPage.DRIVE_BACKUP -> Icons.Default.CloudUpload
        MenuPage.HELP -> Icons.AutoMirrored.Filled.HelpOutline
        MenuPage.LICENSES -> Icons.Default.Gavel
    }

/**
 * The ☰ menu: a drop-down panel opening under the ☰ icon ([anchor], its bounds in the window), over a light scrim.
 * On a phone that is the screen's top-right corner; on a tablet or unfolded foldable it is the library pane's.
 * It is drawn in the app's own window (not a popup), so a controller drives it through the same key path as the rest
 * of the app.
 */
@Composable
fun LibraryMenuPanel(
    visible: Boolean,
    anchor: Rect?,
    onSelect: (MenuPage) -> Unit,
    onDismiss: () -> Unit
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState) return

    var origin by remember { mutableStateOf(Offset.Zero) }
    var containerWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val panelWidthPx = with(density) { PanelWidth.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned {
        origin = it.positionInWindow()
        containerWidth = it.size.width
    }) {
        AnimatedVisibility(visibleState = state, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    // Tapping outside closes the menu; never a focus stop for the D-pad
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        val placement = if (anchor != null) {
            // Right edge under the icon's right edge, kept on screen
            Modifier.offset {
                val maxX = (containerWidth - panelWidthPx - marginPx).coerceAtLeast(marginPx)
                IntOffset(
                    x = (anchor.right - origin.x - panelWidthPx).roundToInt().coerceIn(marginPx, maxX),
                    y = (anchor.bottom - origin.y).roundToInt()
                )
            }
        } else {
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 56.dp, end = 8.dp)
        }
        AnimatedVisibility(
            visibleState = state,
            modifier = placement,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                expandIn(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.TopEnd),
            exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) + shrinkOut(shrinkTowards = Alignment.TopEnd)
        ) {
            MenuPanel(onSelect = onSelect, requestFocus = visible)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MenuPanel(onSelect: (MenuPage) -> Unit, requestFocus: Boolean) {
    val firstItem = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) { if (requestFocus) focusWhenPlaced(firstItem) { panelHasFocus } }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.width(PanelWidth)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .onFocusChanged { panelHasFocus = it.hasFocus }
                // While open, the D-pad stays in the menu: never into the dimmed library behind it
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
        ) {
            MenuPage.shown.forEachIndexed { index, page ->
                DropdownMenuItem(
                    text = { OneLineLabel(page.label) },
                    leadingIcon = { Icon(imageVector = page.icon, contentDescription = null) },
                    onClick = { onSelect(page) },
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier
                )
            }
        }
    }
}

@Preview(name = "Menu, light", widthDp = 300)
@Preview(name = "Menu, dark", widthDp = 300, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MenuPanelPreview() {
    GOATheme { MenuPanel(onSelect = {}, requestFocus = false) }
}
