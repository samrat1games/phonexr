package com.samrat.cardboardhands

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** Quest-style hand gestures from MediaPipe landmarks, and a filter that keeps tracking calm. */
object HandGestures {
    data class Shape(
        /** Thumb and index tips together: click. */
        val pinch: Boolean,
        /** How close to a pinch, 0 (open) to 1 (touching). */
        val pinchStrength: Float,
        /** Fingers curled into a fist: grab and move. */
        val fist: Boolean,
        /** The palm faces the user (the camera sees the back of the hand): system menu when pinching. */
        val palmToFace: Boolean,
        /** Point between thumb and index tips, image coordinates 0..1. */
        val pinchX: Float,
        val pinchY: Float,
        /** Palm width in image widths; bigger is closer to the camera. */
        val palmWidth: Float,
        /**
         * Aim point between the thumb and index bases: follows the hand but not the fingertips, so
         * the cursor stays put while the fingers pinch (a pinch no longer "jumps" the click).
         */
        val aimX: Float = pinchX,
        val aimY: Float = pinchY,
        /** Thumb-index distance in palm widths; feed it to a [PinchLatch] for a steady click. */
        val pinchGap: Float = 1f,
        /** Fingertip cursor and pose used for tablet-like direct touch on app windows. */
        val indexX: Float = aimX,
        val indexY: Float = aimY,
        val indexExtended: Boolean = false,
    )

    /**
     * [physicalLeft] is the user's real hand. MediaPipe's handedness assumes a mirrored selfie
     * image; with the back camera it is swapped, which the caller has already undone.
     */
    fun shape(p: List<NormalizedLandmark>, physicalLeft: Boolean): Shape {
        fun d(a: Int, b: Int): Float {
            val dx = p[a].x() - p[b].x()
            val dy = p[a].y() - p[b].y()
            return sqrt(dx * dx + dy * dy)
        }
        val palm = d(5, 17).coerceAtLeast(.02f)
        // Pinch hysteresis is applied by the caller; here only the distance relative to the palm.
        val gap = d(4, 8) / palm
        val strength = ((.9f - gap) / .6f).coerceIn(0f, 1f)
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        val curled = tips.indices.count { d(0, tips[it]) < d(0, pips[it]) * 1.04f }
        // A fist hides the thumb-index gap too, so a fist is never a pinch.
        val fist = curled >= 3
        // Winding of wrist -> index base -> pinky base: for the back camera, a right hand shows its
        // back (palm toward the user) when this turns clockwise in image space.
        val ax = p[5].x() - p[0].x(); val ay = p[5].y() - p[0].y()
        val bx = p[17].x() - p[0].x(); val by = p[17].y() - p[0].y()
        val cross = ax * by - ay * bx
        val palmToFace = if (physicalLeft) cross < 0 else cross > 0
        return Shape(
            pinch = !fist && gap < .35f,
            pinchStrength = strength,
            fist = fist,
            palmToFace = palmToFace && abs(cross) > palm * palm * .15f,
            pinchX = (p[4].x() + p[8].x()) / 2,
            pinchY = (p[4].y() + p[8].y()) / 2,
            palmWidth = palm,
            aimX = p[2].x() * .3f + p[5].x() * .45f + (p[4].x() + p[8].x()) / 2 * .25f,
            aimY = p[2].y() * .3f + p[5].y() * .45f + (p[4].y() + p[8].y()) / 2 * .25f,
            pinchGap = gap,
            indexX = p[8].x(),
            indexY = p[8].y(),
            indexExtended = d(0, 8) > d(0, 6) * 1.12f,
        )
    }

    /**
     * A pinch that starts when the fingers really touch and ends only once they clearly open, so a
     * click does not flicker on and off when the fingers hover near each other.
     */
    class PinchLatch(private val close: Float = .30f, private val open: Float = .48f) {
        var pinching = false
            private set

        fun update(shape: Shape): Boolean {
            pinching = if (shape.fist) false else if (pinching) shape.pinchGap < open else shape.pinchGap < close
            return pinching
        }

        fun reset() {
            pinching = false
        }
    }

    /**
     * One Euro filter (Casiez et al.): strong smoothing when the hand is still, little lag when it
     * moves fast. This is what keeps the cursor and controllers from shaking.
     */
    class OneEuro(
        private val minCutoff: Float = 1.2f,
        private val beta: Float = .6f,
        private val derivativeCutoff: Float = 1f,
        /** Changes smaller than this are ignored while the hand rests: no tremor at all when still. */
        private val deadZone: Float = 0f,
    ) {
        private var value = Float.NaN
        private var shown = Float.NaN
        private var derivative = 0f
        private var lastNs = 0L

        fun filter(raw: Float, timeNs: Long): Float {
            if (value.isNaN() || lastNs == 0L) {
                value = raw
                shown = raw
                lastNs = timeNs
                return raw
            }
            val dt = ((timeNs - lastNs) / 1e9f).coerceIn(1e-3f, .2f)
            lastNs = timeNs
            val rawDerivative = (raw - value) / dt
            derivative += alpha(derivativeCutoff, dt) * (rawDerivative - derivative)
            val cutoff = minCutoff + beta * abs(derivative)
            value += alpha(cutoff, dt) * (raw - value)
            // Hysteresis: the output follows only once the filtered value leaves the dead zone.
            val gap = value - shown
            if (abs(gap) > deadZone) shown = value - deadZone * kotlin.math.sign(gap)
            return shown
        }

        fun reset() {
            value = Float.NaN
            shown = Float.NaN
            derivative = 0f
            lastNs = 0L
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }
}
