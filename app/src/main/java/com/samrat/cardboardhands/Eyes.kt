package com.samrat.cardboardhands

import android.content.Context
import android.util.DisplayMetrics

/**
 * Where the two eyes are and where their pictures sit on the screen.
 *
 * Two different settings decide whether a headset shows one picture or two. The distance between
 * the eyes ([ipdMetres]) is how far apart the two cameras stand in the scene — it sets how deep
 * everything looks. The lens offset ([offsetPixels]) moves each half of the screen sideways under
 * the lenses of the headset: when it is wrong, the eyes cannot merge the halves and the picture
 * doubles, however good the tracking is.
 */
class Eyes private constructor(val ipdMetres: Float, val offsetPixels: Float) {
    /** Half the distance between the eyes: how far each virtual camera stands from the middle. */
    val halfIpd get() = ipdMetres / 2

    /**
     * Moves the picture of one eye sideways by shifting the frustum, which keeps the view straight
     * — unlike moving the viewport, which would cut the picture off at the edge.
     */
    fun shift(projection: FloatArray, eye: Int, eyeWidthPixels: Int) {
        if (offsetPixels == 0f || eyeWidthPixels <= 0) return
        val ndc = 2f * offsetPixels / eyeWidthPixels
        projection[8] += if (eye == 0) ndc else -ndc
    }

    companion object {
        fun load(context: Context): Eyes {
            val metrics: DisplayMetrics = context.resources.displayMetrics
            // Millimetres become pixels through the screen's own density.
            val perMm = (if (metrics.xdpi > 1f) metrics.xdpi else 400f) / 25.4f
            return Eyes(
                ipdMetres = Settings.ipdMm(context) / 1000f,
                offsetPixels = Settings.lensOffsetMm(context) * perMm,
            )
        }

        /** What PhoneXR used before any of this could be set. */
        val DEFAULT = Eyes(.064f, 0f)
    }
}
