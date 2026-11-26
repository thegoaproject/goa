package com.goa.engine.video

import android.os.Build
import java.util.Locale

object GpuCapabilityDetector {

    /**
     * Detects if the current device is powered by a Qualcomm Snapdragon chipset with Adreno GPU.
     */
    fun isSnapdragonDevice(): Boolean {
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.ROOT)
        val board = (Build.BOARD ?: "").lowercase(Locale.ROOT)
        val soc = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (Build.SOC_MODEL ?: "").lowercase(Locale.ROOT)
            } else {
                ""
            }
        } catch (e: Throwable) {
            ""
        }

        val snapdragonKeywords = listOf(
            "qcom", "qualcomm", "snapdragon", "adreno", "sm8", "sm7", "sm6", "sdm", "msm"
        )

        return snapdragonKeywords.any {
            hardware.contains(it) || board.contains(it) || soc.contains(it)
        }
    }
}
