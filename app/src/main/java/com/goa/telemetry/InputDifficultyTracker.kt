package com.goa.telemetry

/**
 * Detects "controls don't seem to work": a burst of presses (gamepad, touch overlay or pointer) during which the
 * picture didn't change at all (research R4). Conservative by design: any on-screen change means the game reacted.
 */
class InputDifficultyTracker(
    private val clock: () -> Long,
    private val frameChangeCount: () -> Long,
    private val onBurst: (at: Long, presses: Int, windowMs: Long, source: String) -> Unit
) {
    private data class Press(val at: Long, val frames: Long)

    private val presses = ArrayDeque<Press>()
    private var lastBurstAt = Long.MIN_VALUE

    @Synchronized
    fun onPress(source: String) {
        val now = clock()
        presses.addLast(Press(now, frameChangeCount()))
        while (presses.isNotEmpty() && now - presses.first().at > WINDOW_MS) presses.removeFirst()

        if (presses.size >= MIN_PRESSES && now - lastBurstAt > WINDOW_MS && frameChangeCount() == presses.first().frames) {
            onBurst(now, presses.size, now - presses.first().at, source)
            lastBurstAt = now
            presses.clear()
        }
    }

    companion object {
        const val MIN_PRESSES = 6
        const val WINDOW_MS = 3_000L
    }
}
