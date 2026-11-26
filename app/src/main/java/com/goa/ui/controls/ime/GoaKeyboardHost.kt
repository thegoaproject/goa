package com.goa.ui.controls.ime

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Invisible host view that interfaces with Android's InputMethodManager to summon
 * the native soft keyboard (IME) and translate committed characters to DOS scancodes.
 * Inspired by DroidDeck's KeyboardHost.
 */
class GoaKeyboardHost(
    context: Context,
    private val sendScancode: (scancode: Int, isDown: Boolean) -> Unit
) : View(context) {

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var shown = false
        private set

    /** Called when the player dismisses the keyboard themselves (system hide button, swipe down), not via [hide]. */
    var onImeDismissed: (() -> Unit)? = null
    private var imeWasVisible = false

    init {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !visible && shown) {
                hide()
                onImeDismissed?.invoke()
            }
            imeWasVisible = visible
            insets
        }
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return KeyConnection()
    }

    fun isImeVisible(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            shown
        }

    fun show() {
        shown = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        shown = false
        imm.hideSoftInputFromWindow(windowToken, 0)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    fun toggle() {
        if (shown) hide() else show()
    }

    private fun tapKey(scancode: Int) {
        sendScancode(scancode, true)
        sendScancode(scancode, false)
    }

    private inner class KeyConnection : BaseInputConnection(this@GoaKeyboardHost, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            for (ch in text) {
                val scancode = charToDosScancode(ch)
                if (scancode != 0) {
                    val needsShift = ch.isUpperCase() || isShiftedPunctuation(ch)
                    if (needsShift) sendScancode(0x2A, true) // L-Shift down
                    tapKey(scancode)
                    if (needsShift) sendScancode(0x2A, false) // L-Shift up
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tapKey(0x0E) } // Backspace
            repeat(afterLength) { tapKey(0x53) }  // Delete
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val sc = when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> 0x0E
                    KeyEvent.KEYCODE_FORWARD_DEL -> 0x53
                    KeyEvent.KEYCODE_ENTER -> 0x1C
                    KeyEvent.KEYCODE_SPACE -> 0x39
                    KeyEvent.KEYCODE_ESCAPE -> 0x01
                    KeyEvent.KEYCODE_TAB -> 0x0F
                    else -> charToDosScancode(event.unicodeChar.toChar())
                }
                if (sc != 0) {
                    tapKey(sc)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            tapKey(0x1C) // Enter
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    companion object {
        fun charToDosScancode(ch: Char): Int {
            return when (ch.lowercaseChar()) {
                '1' -> 0x02; '2' -> 0x03; '3' -> 0x04; '4' -> 0x05; '5' -> 0x06
                '6' -> 0x07; '7' -> 0x08; '8' -> 0x09; '9' -> 0x0A; '0' -> 0x0B
                '-', '_' -> 0x0C
                '=', '+' -> 0x0D
                'q' -> 0x10; 'w' -> 0x11; 'e' -> 0x12; 'r' -> 0x13; 't' -> 0x14
                'y' -> 0x15; 'u' -> 0x16; 'i' -> 0x17; 'o' -> 0x18; 'p' -> 0x19
                '[', '{' -> 0x1A
                ']', '}' -> 0x1B
                '\n', '\r' -> 0x1C
                'a' -> 0x1E; 's' -> 0x1F; 'd' -> 0x20; 'f' -> 0x21; 'g' -> 0x22
                'h' -> 0x23; 'j' -> 0x24; 'k' -> 0x25; 'l' -> 0x26
                ';', ':' -> 0x27
                '\'', '"' -> 0x28
                '`', '~' -> 0x29
                '\\', '|' -> 0x2B
                'z' -> 0x2C; 'x' -> 0x2D; 'c' -> 0x2E; 'v' -> 0x2F; 'b' -> 0x30
                'n' -> 0x31; 'm' -> 0x32
                ',', '<' -> 0x33
                '.', '>' -> 0x34
                '/', '?' -> 0x35
                ' ' -> 0x39
                else -> 0
            }
        }

        fun isShiftedPunctuation(ch: Char): Boolean =
            ch in "~!@#$%^&*()_+{}|:\"<>?"
    }
}

