package com.samrat.cardboardhands

import android.graphics.Bitmap

/**
 * Lite carries no marker tracking: finding printed markers needs OpenCV, which is 24 MB of native
 * library and a slow first load — exactly what a budget phone has none of. The full PhoneXR has the
 * real tracker; here the Joy-Con are followed by colour and by their own gyroscope instead.
 */
class JoyConMarkers {
    val available = false

    fun process(frame: Bitmap): Pair<MarkerPose, MarkerPose> = MarkerPose() to MarkerPose()
}
