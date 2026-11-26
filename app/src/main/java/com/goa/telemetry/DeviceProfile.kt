package com.goa.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

/** Phone make/model/chipset for per-model performance breakdowns (FR-010b). No hardware identifiers. */
data class DeviceInfo(val make: String, val model: String, val soc: String, val androidApi: Int, val abi: String, val ramGb: Int)

object DeviceProfile {
    fun read(context: Context): DeviceInfo {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
        return DeviceInfo(
            make = clean(Build.MANUFACTURER),
            model = clean(Build.MODEL),
            soc = clean(soc.ifBlank { Build.HARDWARE }),
            androidApi = Build.VERSION.SDK_INT,
            abi = clean(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            ramGb = (memory.totalMem / (1024.0 * 1024 * 1024)).roundToInt().coerceIn(1, 64)
        )
    }

    private fun clean(value: String) = value.filterNot { it.isISOControl() }.trim().take(64)
}
