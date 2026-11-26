package com.goa.engine.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.goa.engine.emulator.DosBoxEngineBridge

class GyroscopeMotionManager(
    private val context: Context,
    val filter: MotionLookFilter = MotionLookFilter()
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Build-engine mouse aiming (see BuildMouseAim): vertical look follows the gyro's pitch rotation as mouse Y, so the
     * view moves only while the phone is turning and holds wherever it ends up. Tilt-angle pitch is not used then.
     */
    @Volatile
    var verticalMouseLook: Boolean = false

    /**
     * Gyro look is a dual-stick FPS feature. Arrow-key, gameport and mouse-pointer games read the mouse as player input
     * (Tyrian steers its ship and menus with it), so there even sensor noise would play the game by itself.
     */
    @Volatile
    var lookAllowed: Boolean = true
        set(value) {
            field = value
            if (!value) DosBoxEngineBridge.sendMotionPitch(0.0f)
        }

    var isEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                DosBoxEngineBridge.sendMotionPitch(0.0f)
                neutralPitchAngle = null
            }
        }

    private var isRunning: Boolean = false
    private var neutralPitchAngle: Float? = null

    fun start() {
        if (!isRunning) {
            gyroSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gravitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            neutralPitchAngle = null
            isRunning = true
        }
    }

    fun stop() {
        if (isRunning) {
            sensorManager.unregisterListener(this)
            DosBoxEngineBridge.sendMotionPitch(0.0f)
            neutralPitchAngle = null
            isRunning = false
        }
    }

    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display.rotation
            } catch (_: Throwable) {
                Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || !lookAllowed || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val radX = event.values[0]
                val radY = event.values[1]
                val radZ = event.values[2]

                val rotation = getDisplayRotation()
                val delta = filter.filter(radX, radY, radZ, rotation)
                // Left/Right horizontal turn is sent as relative mouse delta (mouse yaw); mouse-aiming games also get pitch
                // Mouse-aim games run with the game's MouseInvert on (stick up = look up), so the gyro is flipped to match
                val deltaY = if (verticalMouseLook) -delta.deltaY else 0.0f
                if (delta.deltaX != 0.0f || deltaY != 0.0f) {
                    DosBoxEngineBridge.sendRelativeMouse(delta.deltaX, deltaY)
                }
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                if (verticalMouseLook) return   // pitch comes from the gyro rate as mouse Y instead
                val gX = event.values[0]
                val gY = event.values[1]
                val gZ = event.values[2]

                val rotation = getDisplayRotation()
                val (tiltAngle, pitch) = filter.computeTiltPitch(gX, gY, gZ, neutralPitchAngle, rotation)

                val currentNeutral = neutralPitchAngle
                if (currentNeutral == null) {
                    neutralPitchAngle = tiltAngle
                    DosBoxEngineBridge.sendMotionPitch(0.0f)
                } else {
                    DosBoxEngineBridge.sendMotionPitch(pitch)
                    // If pitch is within deadzone (0.0f), slowly adapt neutral angle so user posture changes don't drift
                    if (pitch == 0.0f) {
                        neutralPitchAngle = currentNeutral * 0.996f + tiltAngle * 0.004f
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

