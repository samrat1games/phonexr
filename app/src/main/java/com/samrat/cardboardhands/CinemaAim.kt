package com.samrat.cardboardhands

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Where an aim ray from the eyes meets the cinema screen. It lives on its own because three things
 * aim at the same screen — the hands, a Bluetooth mouse and a second phone held as a pointer — and
 * the screen may be flat or bent around the viewer.
 */
object CinemaAim {
    /**
     * [direction] is the aim in world space (x, y, z, ...), [place] is CinemaRenderer.screenPlacement.
     * Returns the point as screen u, v, which may fall outside 0..1 when the aim misses the screen.
     */
    fun screenPoint(direction: FloatArray, place: FloatArray): FloatArray? {
        val centerY = place[0]
        val screenZ = place[1]
        val width = place[2]
        val eyeHeight = place[3]
        val radius = place[4]
        val height = place[5]
        if (radius > 0f) {
            // The viewer sits on the axis of the bent screen, so a ray always meets it: it is enough
            // to stretch the ray out to the radius.
            val sideways = hypot(direction[0], direction[2])
            if (sideways < 1e-4f) return null
            val reach = radius / sideways
            val angle = atan2(direction[0] * reach, -direction[2] * reach)
            val y = eyeHeight + direction[1] * reach
            return floatArrayOf(angle / (width / radius) + .5f, (centerY + height / 2 - y) / height)
        }
        if (direction[2] >= -1e-3f) return null
        val reach = screenZ / direction[2]
        val x = direction[0] * reach
        val y = eyeHeight + direction[1] * reach
        return floatArrayOf((x + width / 2) / width, (centerY + height / 2 - y) / height)
    }
}
