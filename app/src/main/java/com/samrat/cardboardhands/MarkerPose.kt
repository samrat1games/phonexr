package com.samrat.cardboardhands

/**
 * Where a Joy-Con is, as the printed markers on it show. It lives on its own because the tracker
 * that fills it differs between the editions: the full PhoneXR finds markers with OpenCV, and Lite
 * carries no OpenCV at all — 24 MB of library and a heavy first load for a budget phone.
 */
data class MarkerPose(
    val found: Boolean = false,
    /** Marker centre in the image, 0..1. */
    val x: Float = .5f,
    val y: Float = .5f,
    /** Distance from the camera in metres. */
    val distance: Float = .5f,
    /** Controller rotation, OpenXR axes (x right, y up, z back). */
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val markerId: Int = -1
)
