package com.goa.ui.emulation

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.goa.engine.emulator.DosBoxEngineBridge
import com.goa.engine.input.PointAndClickInputManager

@Composable
fun EmulationSurfaceScreen(
    pointerInputManager: PointAndClickInputManager? = null,
    onSurfaceReady: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color.Black) // Pure black OLED pillarbox background
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    pointerInputManager?.attachToView(this)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            DosBoxEngineBridge.setSurface(holder.surface)
                            onSurfaceReady()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // Surface resolution change handled in native EGL
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            DosBoxEngineBridge.setSurface(null)
                        }
                    })
                }
            }
        )
    }
}

