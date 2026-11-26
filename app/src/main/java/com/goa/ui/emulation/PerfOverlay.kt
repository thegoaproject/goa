package com.goa.ui.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goa.engine.emulator.DosBoxEngineBridge
import kotlinx.coroutines.delay

/** Field order of [DosBoxEngineBridge.getPerfStats]; mirrors PerfField in jni_bridge.cpp. */
object PerfStat {
    const val TARGET_FPS = 0
    const val EMU_FPS = 1
    const val PRESENT_FPS = 2
    const val DROPPED_FPS = 3
    const val RUN_AVG_MS = 4
    const val RUN_MAX_MS = 5
    const val PRESENT_AVG_MS = 6
    const val PRESENT_MAX_MS = 7
    const val AUDIO_BUFFER_MS = 8
    const val AUDIO_MIN_MS = 9
    const val AUDIO_IN_HZ = 10
    const val AUDIO_OUT_HZ = 11
    const val UNDERRUNS = 12
    const val SILENCE_MS = 13
    const val OVERFLOW_MS = 14
    const val LATE_FRAMES = 15
    const val DEVICE_RATE = 16
    const val XRUNS = 17
    const val CORE_SPEED_PCT = 18
    const val GAME_FPS = 19
    const val CORE_CYCLES = 20
    const val AUDIO_LEVEL_DBFS = 21
    const val FRAME_CHANGES = 22
    const val COUNT = 23
}

private val Good = Color(0xFF9CCB7A)
private val Bad = Color(0xFFFF7A6B)

/**
 * Debug HUD: is the emulator keeping up (emu fps vs target, DOSBox speed vs real time, frontend loop time vs frame
 * budget), and is audio starving (queue depth, underruns, silence inserted). Values refresh with each 2 s native window.
 */
@Composable
fun PerfOverlay(modifier: Modifier = Modifier, lookMode: String? = null) {
    var stats by remember { mutableStateOf(FloatArray(PerfStat.COUNT)) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { DosBoxEngineBridge.getPerfStats() }.getOrNull()?.let { stats = it }
            delay(1000)
        }
    }
    if (stats.size < PerfStat.COUNT) return

    val target = stats[PerfStat.TARGET_FPS]
    val budgetMs = if (target > 0f) 1000f / target else 16.7f
    val keepingUp = stats[PerfStat.EMU_FPS] >= target * 0.97f
    val audioOk = stats[PerfStat.UNDERRUNS] == 0f && stats[PerfStat.SILENCE_MS] < 1f

    Column(
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        line("EMU  %.1f / %.1f fps  late %d".format(stats[PerfStat.EMU_FPS], target, stats[PerfStat.LATE_FRAMES].toInt()), keepingUp)
        // DOSBox Pure only: speed < 100% means the phone can't emulate the configured cycles in real time
        val coreSpeed = stats[PerfStat.CORE_SPEED_PCT]
        if (coreSpeed > 0f) {
            line(
                "DOS  speed %.0f%%  game %.0f fps  cyc %d".format(coreSpeed, stats[PerfStat.GAME_FPS], stats[PerfStat.CORE_CYCLES].toInt()),
                coreSpeed >= 97f
            )
        }
        line(
            "LOOP %.1f ms avg  %.1f max  (budget %.1f)".format(stats[PerfStat.RUN_AVG_MS], stats[PerfStat.RUN_MAX_MS], budgetMs),
            stats[PerfStat.RUN_AVG_MS] < budgetMs
        )
        line(
            "GPU  %.1f fps  drop %.1f/s  %.1f ms".format(stats[PerfStat.PRESENT_FPS], stats[PerfStat.DROPPED_FPS], stats[PerfStat.PRESENT_AVG_MS]),
            true
        )
        line(
            "AUD  buf %.0f ms (min %.0f)  under %d  gap %.0f ms".format(
                stats[PerfStat.AUDIO_BUFFER_MS], stats[PerfStat.AUDIO_MIN_MS], stats[PerfStat.UNDERRUNS].toInt(), stats[PerfStat.SILENCE_MS]
            ),
            audioOk
        )
        line(
            "     in %.0f / out %.0f Hz  dev %d Hz".format(stats[PerfStat.AUDIO_IN_HZ], stats[PerfStat.AUDIO_OUT_HZ], stats[PerfStat.DEVICE_RATE].toInt()),
            stats[PerfStat.DEVICE_RATE].toInt() == 48000 || stats[PerfStat.DEVICE_RATE] == 0f
        )
        // Build-engine mouse-aim games: which path vertical look is using (keys = binary PageUp/PageDown)
        if (lookMode != null) line("LOOK $lookMode", lookMode.startsWith("mouse"))
    }
}

@Composable
private fun line(text: String, ok: Boolean) {
    Text(text = text, color = if (ok) Good else Bad, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
}
