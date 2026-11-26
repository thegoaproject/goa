package com.goa.ui.menu

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Call first inside a `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))`: makes the dialog's
 * window cover the whole display, so the content can lay itself out in the safe area (`WindowInsets.safeDrawing`).
 *
 * Why: with usePlatformDefaultWidth = false, Compose sizes the dialog window to the full display (1080x2424), but the
 * system still fits a dialog window between the status and navigation bars (1080x2280). The content then runs past the
 * bottom of the window by the bars' height, and anything pinned to the bottom (the pause menu's Resume / Exit) is cut
 * off: seen on a Pixel 10, whose bars are tall. Here the window is told not to fit the bars, so window and content are
 * the same size and the content's own safe-area padding keeps it clear of the bars and cutout, shown or hidden.
 */
@Composable
fun FullScreenDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val attrs = window.attributes
        if (attrs.fitInsetsTypes != 0 || attrs.fitInsetsSides != 0 ||
            attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        ) {
            attrs.fitInsetsTypes = 0
            attrs.fitInsetsSides = 0
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = attrs
        }
    }
}
