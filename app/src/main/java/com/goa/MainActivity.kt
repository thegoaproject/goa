package com.goa

import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.goa.ui.auth.AuthWelcomeScreen
import com.goa.ui.catalog.LibraryCatalogPane
import com.goa.ui.catalog.LibraryViewModel
import com.goa.ui.navigation.LauncherGamepadController
import com.goa.ui.theme.GOATheme
import android.view.KeyEvent
import android.view.MotionEvent

class MainActivity : ComponentActivity() {

    private val gamepadController = LauncherGamepadController()

    // Lint flags ComponentActivity.dispatchKeyEvent as library-restricted; overriding the framework method is fine
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadController.handleKeyEvent(event)) {
            return true
        }
        // Menu and its pages: A presses the focused control, like the D-pad's centre button
        if (gamepadController.uiFocusMode && event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(withKeyCode(event, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        return super.dispatchKeyEvent(event)
    }

    // Menu and its pages: the left stick and hat move focus like the D-pad (one step per push, repeating while held)
    private var stickKey = KeyEvent.KEYCODE_UNKNOWN
    private var stickRepeatAt = 0L

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadController.handleGenericMotionEvent(event)) {
            return true
        }
        if (gamepadController.uiFocusMode && isGamepadMotion(event)) {
            val key = stickDirection(event)
            val now = android.os.SystemClock.uptimeMillis()
            if (key != stickKey || (key != KeyEvent.KEYCODE_UNKNOWN && now >= stickRepeatAt)) {
                stickKey = key
                if (key != KeyEvent.KEYCODE_UNKNOWN) {
                    stickRepeatAt = now + 220
                    super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                    super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadMotion(event: MotionEvent): Boolean =
        (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK ||
            (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD

    private fun stickDirection(event: MotionEvent): Int {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (kotlin.math.abs(hatX) > 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (kotlin.math.abs(hatY) > 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        return when {
            kotlin.math.abs(y) >= kotlin.math.abs(x) && y < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            kotlin.math.abs(y) >= kotlin.math.abs(x) && y > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            x < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun withKeyCode(event: KeyEvent, keyCode: Int) = KeyEvent(
        event.downTime, event.eventTime, event.action, keyCode, event.repeatCount,
        event.metaState, event.deviceId, event.scanCode, event.flags, event.source
    )

    override fun onStart() {
        super.onStart()
        // App opened: pick up an updated game patch registry (throttled, a 304 when nothing changed)
        com.goa.data.repository.RegistrySync.syncOnOpen(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // Returning from a game: turn its session journal into a health record right away
        lifecycleScope.launch(Dispatchers.IO) { com.goa.telemetry.TelemetryIngestion.run(this@MainActivity) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as GoaApplication
        val authRepo = app.authRepository
        val catalogRepo = app.catalogRepository
        val libraryViewModel = LibraryViewModel(
            catalogRepo, authRepo, app.folderRepository, getSharedPreferences("goa_ui_state", MODE_PRIVATE)
        )

        setContent {
            val systemIsDark = isSystemInDarkTheme()
            var isDarkThemeOverride by remember { mutableStateOf<Boolean?>(null) }
            val isDark = isDarkThemeOverride ?: systemIsDark
            // Status and navigation bar icons follow GOA's own light/dark choice, not only the system's
            androidx.compose.runtime.DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
                    ) { isDark },
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
                    ) { isDark }
                )
                onDispose { }
            }

            GOATheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.goa.ui.AdaptiveAppRoot(
                        libraryViewModel = libraryViewModel,
                        app = app,
                        gamepadController = gamepadController,
                        isDarkTheme = isDark,
                        onToggleTheme = {
                            isDarkThemeOverride = !isDark
                        }
                    )
                }
            }
        }
    }
}

