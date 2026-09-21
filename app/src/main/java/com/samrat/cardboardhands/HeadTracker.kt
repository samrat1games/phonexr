package com.samrat.cardboardhands

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Display
import android.view.Surface
import kotlin.math.atan2

/**
 * Head rotation from the phone's rotation vector, for a phone lying landscape in a VR headset.
 * [head] is head-to-world, column-major, with world y up and "straight ahead" along -z.
 */
class HeadTracker(private val sensors: SensorManager, private val display: () -> Display?) : SensorEventListener {
    val head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val rotation = FloatArray(9)
    @Volatile private var yawOffset = Float.NaN
    /** In a car or a train the world turns under the user; that slow turn is followed and removed. */
    @Volatile var travelMode = false
    /** Recent head poses with sensor timestamps (elapsedRealtimeNanos), newest last. */
    private val history = Array(HISTORY) { FloatArray(16) }
    private val historyTimes = LongArray(HISTORY)
    private var historyNext = 0

    fun start() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() = sensors.unregisterListener(this)

    /** Makes the current head direction "straight ahead". */
    fun recenter() {
        yawOffset = Float.NaN
    }

    /** Copies the head rotation into [out] under the tracker's lock. */
    fun copyHead(out: FloatArray) = synchronized(head) { System.arraycopy(head, 0, out, 0, 16) }

    /**
     * The head as it was at [timeNanos] (elapsedRealtimeNanos, like camera frame timestamps). Hands
     * seen in an older camera frame must be turned into the world with the head of that moment,
     * otherwise every head turn drags the cursor along until the camera catches up.
     */
    fun copyHeadAt(timeNanos: Long, out: FloatArray) = synchronized(head) {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until HISTORY) {
            val time = historyTimes[i]
            if (time == 0L) continue
            val gap = kotlin.math.abs(time - timeNanos)
            if (gap < bestGap) { bestGap = gap; best = i }
        }
        // Without a pose close in time (clock mismatch), the current head is the safer choice.
        if (best < 0 || bestGap > 250_000_000L) System.arraycopy(head, 0, out, 0, 16)
        else System.arraycopy(history[best], 0, out, 0, 16)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val r = rotation
        // Device to world (east, north, up) -> head to GL world (x right, y up, z back).
        // Landscape in the headset: screen right is device -Y, or +Y when turned the other way.
        val turned = display()?.rotation == Surface.ROTATION_270
        val d = if (turned) arrayOf(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        else arrayOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        val rd = Array(3) { row -> FloatArray(3) { col -> r[row * 3] * d[0][col] + r[row * 3 + 1] * d[1][col] + r[row * 3 + 2] * d[2][col] } }
        // East, north, up -> x, -z, y.
        val m = arrayOf(rd[0], rd[2], FloatArray(3) { -rd[1][it] })
        val yaw = atan2(m[0][2], m[2][2])
        if (yawOffset.isNaN()) yawOffset = yaw
        // Travel mode: the centre creeps after the yaw, so a turning car takes the view nowhere
        // while a turn of the head still does everything it should.
        if (travelMode) {
            var drift = yaw - yawOffset
            while (drift > Math.PI) drift -= 2 * Math.PI.toFloat()
            while (drift < -Math.PI) drift += 2 * Math.PI.toFloat()
            yawOffset += drift * TRAVEL_CATCH_UP
        }
        val gl = FloatArray(16)
        for (row in 0..2) for (col in 0..2) gl[col * 4 + row] = m[row][col]
        gl[15] = 1f
        val recenter = FloatArray(16)
        Matrix.setRotateM(recenter, 0, Math.toDegrees(-yawOffset.toDouble()).toFloat(), 0f, 1f, 0f)
        synchronized(head) {
            Matrix.multiplyMM(head, 0, recenter, 0, gl, 0)
            System.arraycopy(head, 0, history[historyNext], 0, 16)
            historyTimes[historyNext] = event.timestamp
            historyNext = (historyNext + 1) % HISTORY
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** About half a second of poses at the sensor's fastest rate. */
        const val HISTORY = 128
        /** How fast the centre follows the vehicle: slow enough that head turns are untouched. */
        const val TRAVEL_CATCH_UP = .004f
    }
}
