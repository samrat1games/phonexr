package com.samrat.cardboardhands

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * A second phone used as a pointer: it tells where it points and what is pressed, over the local
 * network. There is nothing to pair — the controller shouts into the network and the headset picks
 * it up, the way PhoneXR's own tracking stream works.
 *
 * The packet is ASCII, 60 times a second:
 *
 *   PXC1 <x> <y> <z> <buttons>
 *
 * x, y, z is where the top edge of the phone points, in its own world (x right, y up, z back), and
 * buttons is 1 for the trigger, 2 for back, 4 for "point at the middle of the screen again". Both
 * phones agree on which way is up (gravity), but not on which way is north, so the headset turns the
 * aim by an angle the user sets with that third button.
 */
object PhoneController {
    const val PORT = 42427
    const val TRIGGER = 1
    const val BACK = 2
    const val RECENTER = 4

    /** Where the controller points and what it presses, with the moment it was heard. */
    class Aim(val x: Float, val y: Float, val z: Float, val buttons: Int, val at: Long) {
        val trigger get() = buttons and TRIGGER != 0
        val back get() = buttons and BACK != 0
        val recenter get() = buttons and RECENTER != 0
        /** A controller that went quiet (phone locked, app closed) stops pointing at anything. */
        fun fresh(now: Long = SystemClock.elapsedRealtime()) = now - at < SILENCE_MS
    }

    /** The headset side: listens for a controller on the network. */
    class Listener : AutoCloseable {
        @Volatile var aim: Aim? = null
            private set
        @Volatile private var socket: DatagramSocket? = null
        @Volatile private var running = false
        /** Turn of the controller's world onto the headset's, set by the recenter button. */
        @Volatile private var yawOffset = 0f
        private var recenterWasDown = false

        fun start() {
            if (running) return
            running = true
            thread(name = "PhoneXR controller") {
                val open = runCatching { DatagramSocket(PORT).apply { reuseAddress = true; soTimeout = 500 } }.getOrNull()
                socket = open ?: return@thread
                val buffer = ByteArray(128)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val text = runCatching { open.receive(packet); String(buffer, 0, packet.length) }.getOrNull() ?: continue
                    parse(text)?.let { heard ->
                        // Pressing "again" while pointing at the screen lines the two phones up.
                        if (heard.recenter && !recenterWasDown) yawOffset = -kotlin.math.atan2(heard.x, -heard.z)
                        recenterWasDown = heard.recenter
                        aim = heard
                    }
                }
                runCatching { open.close() }
            }
        }

        /** Where the controller points, in the headset's world; null when nothing is pointing. */
        fun direction(): FloatArray? {
            val heard = aim?.takeIf { it.fresh() } ?: return null
            val cos = kotlin.math.cos(yawOffset)
            val sin = kotlin.math.sin(yawOffset)
            return floatArrayOf(heard.x * cos + heard.z * sin, heard.y, -heard.x * sin + heard.z * cos)
        }

        override fun close() {
            running = false
            runCatching { socket?.close() }
            socket = null
        }
    }

    /** The controller side: this phone tells the headset where it points. */
    class Sender(context: Context) : SensorEventListener {
        private val sensors = context.getSystemService(SensorManager::class.java)
        private val rotation = FloatArray(9)
        private val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
        private val address = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()
        /** What the user holds down right now. */
        @Volatile var buttons = 0
        /** Packets sent since the start, so the screen can show that something is happening. */
        @Volatile var sent = 0L
            private set

        fun start() {
            val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        fun stop() {
            sensors?.unregisterListener(this)
            runCatching { socket?.close() }
        }

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            // The top edge of the phone is its +Y axis; in the world that is column 1 of the matrix.
            val east = rotation[1]
            val north = rotation[4]
            val up = rotation[7]
            // East, north, up -> x right, y up, z back, the same world the headset draws in.
            send(east, up, -north)
        }

        private fun send(x: Float, y: Float, z: Float) {
            val target = address ?: return
            val line = "PXC1 %.4f %.4f %.4f %d".format(java.util.Locale.US, x, y, z, buttons)
            val data = line.toByteArray()
            runCatching {
                socket?.send(DatagramPacket(data, data.size, target, PORT))
                sent++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun parse(text: String): Aim? {
        val parts = text.trim().split(' ')
        if (parts.size < 5 || parts[0] != "PXC1") return null
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        val z = parts[3].toFloatOrNull() ?: return null
        val buttons = parts[4].toIntOrNull() ?: return null
        return Aim(x, y, z, buttons, SystemClock.elapsedRealtime())
    }

    /** A controller heard this long ago is gone. */
    private const val SILENCE_MS = 700L
}
