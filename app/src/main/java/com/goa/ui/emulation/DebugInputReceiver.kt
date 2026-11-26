package com.goa.ui.emulation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.engine.input.GamepadInputManager

/**
 * Debuggable builds only: drive the running game from adb so input paths can be checked without a controller.
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ei tap 28            # tap a DOS scancode (Enter)
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ef my 2.0 --ei frames 30   # mouse Y per frame
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ez gamepad true      # mock gamepad connection
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ef ry -1.0 --el holdMs 500   # right stick Y, then rest
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ef ly -1.0 --el holdMs 800   # left stick Y (walk)
 *   adb shell am broadcast -a com.goa.debug.INPUT -p com.goateam.goa --ei pad 3              # libretro pad button (3 = Start)
 */
class DebugInputReceiver(
    private val gamepadInputManager: GamepadInputManager? = null
) : BroadcastReceiver() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("gamepad")) {
            val connected = intent.getBooleanExtra("gamepad", false)
            gamepadInputManager?.setGamepadConnectedForTesting(connected)
        }
        if (intent.hasExtra("tap")) {
            val sc = intent.getIntExtra("tap", 0)
            DosBoxEngineBridge.sendKeyEvent(sc, true)
            handler.postDelayed({ DosBoxEngineBridge.sendKeyEvent(sc, false) }, intent.getLongExtra("holdMs", 120L))
        }
        if (intent.hasExtra("pad")) {
            val id = intent.getIntExtra("pad", 0)
            DosBoxEngineBridge.sendJoypadButton(id, true)
            handler.postDelayed({ DosBoxEngineBridge.sendJoypadButton(id, false) }, intent.getLongExtra("holdMs", 120L))
        }
        if (intent.hasExtra("ry") || intent.hasExtra("ly")) {
            DosBoxEngineBridge.sendAnalogSticks(0f, intent.getFloatExtra("ly", 0f), 0f, intent.getFloatExtra("ry", 0f))
            handler.postDelayed({ DosBoxEngineBridge.sendAnalogSticks(0f, 0f, 0f, 0f) }, intent.getLongExtra("holdMs", 500L))
        }
        if (intent.hasExtra("my") || intent.hasExtra("mx")) {
            val mx = intent.getFloatExtra("mx", 0f)
            val my = intent.getFloatExtra("my", 0f)
            val frames = intent.getIntExtra("frames", 1)
            for (i in 0 until frames) handler.postDelayed({ DosBoxEngineBridge.sendRelativeMouse(mx, my) }, i * 16L)
        }
    }

    companion object {
        const val ACTION = "com.goa.debug.INPUT"

        fun registerIfDebuggable(
            context: Context,
            gamepadInputManager: GamepadInputManager? = null
        ): DebugInputReceiver? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return DebugInputReceiver(gamepadInputManager).also {
                ContextCompat.registerReceiver(context, it, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
            }
        }
    }
}
