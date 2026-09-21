package com.samrat.cardboardhands

import android.opengl.Matrix
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.sqrt

/** Sends the live headset and hand poses to the PhoneXR SteamVR driver on the local network. */
class SteamVrLink : AutoCloseable {
    data class Hand(val left: Boolean, val shape: HandGestures.Shape)

    private val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
    private val target = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()

    fun send(headMatrix: FloatArray, headPosition: FloatArray, hands: List<Hand>) {
        val out = socket ?: return
        val address = target ?: return
        val head = quaternion(headMatrix)
        val left = hands.firstOrNull { it.left }
        val right = hands.firstOrNull { !it.left }
        fun block(hand: Hand?): List<Any> {
            val shape = hand?.shape
            if (shape == null) return listOf(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
            // Camera coordinates become SteamVR metres: x right, y up, -z forward.
            val x = (shape.aimX - .5f) * 1.15f
            val y = (.58f - shape.aimY) * .75f
            val z = -.32f - shape.palmWidth.coerceIn(.03f, .3f) * 1.4f
            return listOf(1, x, y, z, 0f, 0f, 0f, 1f, shape.pinchStrength, if (shape.fist) 1f else 0f)
        }
        val values = ArrayList<Any>(28).apply {
            addAll(listOf(headPosition[0], headPosition[1], headPosition[2], head[0], head[1], head[2], head[3]))
            addAll(block(left)); addAll(block(right))
        }
        val payload = buildString {
            append("PXRVR1")
            values.forEach { value ->
                append(' ')
                when (value) {
                    is Int -> append(value)
                    is Float -> append(String.format(Locale.US, "%.5f", value))
                    else -> append(value)
                }
            }
        }.toByteArray(Charsets.US_ASCII)
        runCatching { out.send(DatagramPacket(payload, payload.size, address, PORT)) }
    }

    override fun close() = socket?.close() ?: Unit

    /** Quaternion x,y,z,w from Android's column-major rotation matrix. */
    private fun quaternion(matrix: FloatArray): FloatArray {
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        System.arraycopy(matrix, 0, rotation, 0, minOf(matrix.size, 16))
        val m00 = rotation[0]; val m11 = rotation[5]; val m22 = rotation[10]
        val q = FloatArray(4)
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = .25f * s; q[0] = (rotation[6] - rotation[9]) / s
            q[1] = (rotation[8] - rotation[2]) / s; q[2] = (rotation[1] - rotation[4]) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (rotation[6] - rotation[9]) / s; q[0] = .25f * s
            q[1] = (rotation[4] + rotation[1]) / s; q[2] = (rotation[8] + rotation[2]) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (rotation[8] - rotation[2]) / s; q[0] = (rotation[4] + rotation[1]) / s
            q[1] = .25f * s; q[2] = (rotation[9] + rotation[6]) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (rotation[1] - rotation[4]) / s; q[0] = (rotation[8] + rotation[2]) / s
            q[1] = (rotation[9] + rotation[6]) / s; q[2] = .25f * s
        }
        return q
    }

    companion object { const val PORT = 24821 }
}
