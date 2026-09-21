package com.samrat.cardboardhands

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the Joy-Con themselves in the camera image by their colour, for phones whose kernel does not
 * pass the Joy-Con gyroscope to Android. Buttons still come from the Joy-Con over Bluetooth.
 *
 * Each Joy-Con is a coloured bar. Its blob gives the position in the image, its apparent thickness the
 * distance, and how stretched it looks tells where it points: seen end-on (pointing away from the
 * camera, as when aiming forward) it is almost square, tilted it gets longer along the tilt.
 * The twist around the Joy-Con's own long axis cannot be seen on a plain bar and stays zero.
 */
class JoyConVision {
    /** Colour of one Joy-Con in HSV, plus how long it looks side-on (length / thickness). */
    data class Target(
        val hue: Float,
        val saturation: Float,
        val value: Float,
        val sideRatio: Float = DEFAULT_SIDE_RATIO
    ) {
        fun encode() = "$hue;$saturation;$value;$sideRatio"

        companion object {
            /** Official "neon blue" and "neon red" Joy-Con. */
            val NEON_BLUE = Target(193f, .92f, .88f)
            val NEON_RED = Target(5f, .84f, .98f)
            val NEON_YELLOW = Target(66f, .98f, .98f)
            val NEON_GREEN = Target(112f, .98f, .86f)
            val NEON_PINK = Target(340f, .80f, .98f)
            val NEON_PURPLE = Target(287f, .96f, .88f)
            val NEON_ORANGE = Target(38f, .96f, .96f)
            val BLUE = Target(235f, .72f, .94f)
            val PASTEL_PINK = Target(350f, .38f, .98f)
            val PASTEL_GREEN = Target(150f, .35f, .92f)

            /**
             * The colours Joy-Con come in, for picking instead of teaching the camera. Grey and
             * white ones are not here: the camera finds a Joy-Con by its colour, and those have
             * none to find — for them use "запомнить цвет" in front of a plain background.
             */
            val PRESETS: List<Pair<String, Target>> = listOf(
                "Неоновый синий" to NEON_BLUE,
                "Неоновый красный" to NEON_RED,
                "Неоновый жёлтый" to NEON_YELLOW,
                "Неоновый зелёный" to NEON_GREEN,
                "Неоновый розовый" to NEON_PINK,
                "Неоновый фиолетовый" to NEON_PURPLE,
                "Неоновый оранжевый" to NEON_ORANGE,
                "Синий" to BLUE,
                "Пастельно‑розовый" to PASTEL_PINK,
                "Пастельно‑зелёный" to PASTEL_GREEN,
            )

            /** The preset closest to [target], so the settings screen can show what is chosen. */
            fun nameOf(target: Target): String? = PRESETS.firstOrNull { (_, preset) ->
                kotlin.math.abs(preset.hue - target.hue) < 6f &&
                    kotlin.math.abs(preset.saturation - target.saturation) < .06f &&
                    kotlin.math.abs(preset.value - target.value) < .06f
            }?.first

            fun decode(text: String?): Target? {
                val parts = text?.split(';')?.mapNotNull { it.toFloatOrNull() } ?: return null
                return if (parts.size == 4) Target(parts[0], parts[1], parts[2], parts[3]) else null
            }
        }
    }

    data class Detection(
        val found: Boolean = false,
        /** Centre in the upright image, 0..1. */
        val x: Float = .5f,
        val y: Float = .5f,
        /** Apparent thickness as a share of the image width; bigger means closer. */
        val thickness: Float = 0f,
        /** Pointing direction in camera space: x right, y up, z towards the viewer. */
        val dirX: Float = 0f,
        val dirY: Float = 0f,
        val dirZ: Float = -1f,
        /** How much of the image the blob covers, 0..1, for the preview. */
        val coverage: Float = 0f
    ) {
        /** Rotation that turns "forward" (0, 0, -1) into the pointing direction, without twist. */
        fun quaternion(): FloatArray {
            // cross((0,0,-1), d) = (dy, -dx, 0), 1 + dot = 1 - dz.
            val w = 1f - dirZ
            if (w < 1e-4f) return floatArrayOf(0f, 1f, 0f, 0f)
            val length = sqrt(dirY * dirY + dirX * dirX + w * w)
            return floatArrayOf(dirY / length, -dirX / length, 0f, w / length)
        }
    }

    private var pixels = IntArray(0)
    private var labels = IntArray(0)
    private var queue = IntArray(0)
    private val previous = arrayOf(Detection(), Detection())

    /** Returns the left and right Joy-Con. [left] and [right] may be the same colour: then image side decides. */
    fun process(frame: Bitmap, left: Target, right: Target): Pair<Detection, Detection> {
        val step = max(1, frame.width / GRID_WIDTH)
        val width = frame.width / step
        val height = frame.height / step
        read(frame, step, width, height)

        val sameColour = hueDistance(left.hue, right.hue) < HUE_TOLERANCE * 2
        val result = if (sameColour) {
            val blobs = blobs(width, height, left, limit = 2).sortedBy { it.x }
            when (blobs.size) {
                0 -> Detection() to Detection()
                1 -> if (blobs[0].x < .5f) blobs[0] to Detection() else Detection() to blobs[0]
                else -> blobs[0] to blobs[1]
            }
        } else {
            (blobs(width, height, left, 1).firstOrNull() ?: Detection()) to
                (blobs(width, height, right, 1).firstOrNull() ?: Detection())
        }
        return smooth(0, result.first) to smooth(1, result.second)
    }

    /**
     * Learns the colour in the centre square of [frame], where the user holds a Joy-Con.
     * Returns null when the centre is too grey or dark to track by colour.
     */
    fun sample(frame: Bitmap): Target? {
        val side = min(frame.width, frame.height) / 6
        val left = frame.width / 2 - side / 2
        val top = frame.height / 2 - side / 2
        val area = IntArray(side * side)
        frame.getPixels(area, 0, side, left, top, side, side)
        var sumSin = 0.0
        var sumCos = 0.0
        var saturation = 0f
        var value = 0f
        val hsv = FloatArray(3)
        for (color in area) {
            hsv(color, hsv)
            val weight = hsv[1] * hsv[2]
            sumSin += sin(hsv[0] * PI / 180) * weight
            sumCos += cos(hsv[0] * PI / 180) * weight
            saturation += hsv[1]
            value += hsv[2]
        }
        saturation /= area.size
        value /= area.size
        if (saturation < MIN_SATURATION || value < MIN_VALUE) return null
        val hue = ((atan2(sumSin, sumCos) * 180 / PI + 360) % 360).toFloat()
        return Target(hue, saturation, value)
    }

    /** How long the largest blob of [target] looks now; the user holds the Joy-Con side-on. */
    fun measureSideRatio(frame: Bitmap, target: Target): Float? {
        val step = max(1, frame.width / GRID_WIDTH)
        val width = frame.width / step
        val height = frame.height / step
        read(frame, step, width, height)
        return shapes(width, height, target, 1).firstOrNull()?.ratio?.takeIf { it > END_RATIO + .5f }
    }

    private fun read(frame: Bitmap, step: Int, width: Int, height: Int) {
        val full = frame.width * frame.height
        if (pixels.size != full) pixels = IntArray(full)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        if (labels.size != width * height) {
            labels = IntArray(width * height)
            queue = IntArray(width * height)
        }
        // Downsample in place: grid cell i holds the colour of every step-th pixel.
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = pixels[(y * step) * frame.width + x * step]
        }
    }

    private class Shape(val x: Float, val y: Float, val area: Int, val axisX: Float, val axisY: Float,
                        val length: Float, val thickness: Float) {
        val ratio get() = length / max(thickness, .001f)
    }

    private fun blobs(width: Int, height: Int, target: Target, limit: Int): List<Detection> =
        shapes(width, height, target, limit).map { shape ->
            // 0 when seen end-on, 1 when fully side-on.
            val stretch = ((shape.ratio - END_RATIO) / (target.sideRatio - END_RATIO)).coerceIn(0f, 1f)
            val lateral = stretch
            val forward = sqrt(1f - lateral * lateral)
            Detection(
                found = true,
                x = shape.x / width,
                y = shape.y / height,
                thickness = shape.thickness / width,
                dirX = lateral * shape.axisX,
                dirY = -lateral * shape.axisY,
                dirZ = -forward,
                coverage = shape.area.toFloat() / (width * height)
            )
        }

    /** Largest connected blobs of the target colour, biggest first. */
    private fun shapes(width: Int, height: Int, target: Target, limit: Int): List<Shape> {
        val hsv = FloatArray(3)
        val minSaturation = max(MIN_SATURATION, target.saturation * .55f)
        val minValue = max(MIN_VALUE, target.value * .4f)
        for (i in 0 until width * height) {
            hsv(pixels[i], hsv)
            val match = hsv[1] >= minSaturation && hsv[2] >= minValue &&
                hueDistance(hsv[0], target.hue) <= HUE_TOLERANCE
            labels[i] = if (match) -1 else 0
        }
        val found = mutableListOf<Shape>()
        var next = 1
        for (start in 0 until width * height) {
            if (labels[start] != -1) continue
            val label = next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var n = 0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                n++
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
                if (x > 0 && labels[index - 1] == -1) { labels[index - 1] = label; queue[tail++] = index - 1 }
                if (x < width - 1 && labels[index + 1] == -1) { labels[index + 1] = label; queue[tail++] = index + 1 }
                if (y > 0 && labels[index - width] == -1) { labels[index - width] = label; queue[tail++] = index - width }
                if (y < height - 1 && labels[index + width] == -1) {
                    labels[index + width] = label; queue[tail++] = index + width
                }
            }
            if (n < MIN_AREA) continue
            val cx = sx / n
            val cy = sy / n
            val a = sxx / n - cx * cx
            val c = syy / n - cy * cy
            val b = sxy / n - cx * cy
            val half = (a + c) / 2
            val root = sqrt(max(0.0, half * half - (a * c - b * b)))
            val major = half + root
            val minor = max(half - root, 1e-3)
            val angle = .5 * atan2(2 * b, a - c)
            // A filled rectangle of length L has variance L² / 12 along it.
            found += Shape(
                cx.toFloat(), cy.toFloat(), n, cos(angle).toFloat(), sin(angle).toFloat(),
                sqrt(12 * major).toFloat(), sqrt(12 * minor).toFloat()
            )
        }
        return found.sortedByDescending { it.area }.take(limit)
    }

    /** Keeps the direction continuous (a bar looks the same both ways) and calms the jitter. */
    private fun smooth(slot: Int, raw: Detection): Detection {
        val last = previous[slot]
        if (!raw.found) return raw
        var dx = raw.dirX
        var dy = raw.dirY
        if (last.found) {
            if (dx * last.dirX + dy * last.dirY < 0) { dx = -dx; dy = -dy }
        } else if (dy < 0) {
            // First sight: assume the tip points up rather than down.
            dx = -dx; dy = -dy
        }
        val k = if (last.found) SMOOTHING else 1f
        var x = last.dirX + (dx - last.dirX) * k
        var y = last.dirY + (dy - last.dirY) * k
        var z = last.dirZ + (raw.dirZ - last.dirZ) * k
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
        x /= length; y /= length; z /= length
        val result = raw.copy(
            x = last.x + (raw.x - last.x) * (if (last.found) POSITION_SMOOTHING else 1f),
            y = last.y + (raw.y - last.y) * (if (last.found) POSITION_SMOOTHING else 1f),
            thickness = last.thickness + (raw.thickness - last.thickness) * (if (last.found) SMOOTHING else 1f),
            dirX = x, dirY = y, dirZ = z
        )
        previous[slot] = result
        return result
    }

    /** Forget the previous pose, e.g. when the Joy-Con left the image for long. */
    fun reset(slot: Int) {
        previous[slot] = Detection()
    }

    companion object {
        /** Joy-Con body is about 102 × 36 mm. */
        const val DEFAULT_SIDE_RATIO = 2.8f
        /** Seen end-on it is about 36 × 28 mm. */
        private const val END_RATIO = 1.25f
        private const val GRID_WIDTH = 160
        private const val MIN_AREA = 18
        private const val HUE_TOLERANCE = 14f
        private const val MIN_SATURATION = .35f
        private const val MIN_VALUE = .2f
        private const val SMOOTHING = .5f
        private const val POSITION_SMOOTHING = .7f

        private fun hueDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        private fun hsv(color: Int, out: FloatArray) {
            val r = (color shr 16 and 0xff) / 255f
            val g = (color shr 8 and 0xff) / 255f
            val b = (color and 0xff) / 255f
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val delta = high - low
            out[2] = high
            out[1] = if (high <= 0f) 0f else delta / high
            out[0] = when {
                delta <= 0f -> 0f
                high == r -> 60f * (((g - b) / delta) % 6f)
                high == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }.let { if (it < 0) it + 360f else it }
        }
    }
}
