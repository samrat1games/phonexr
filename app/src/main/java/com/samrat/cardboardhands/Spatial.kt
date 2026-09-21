package com.samrat.cardboardhands

/**
 * How a photo or a video holds its two eyes and how much of the world it covers. Nothing in a file
 * says this outright, so it is read from the name people give such files (3D, SBS, VR180, 360) and
 * from the shape of the picture, the way every VR player does it.
 */
object Spatial {
    /** Where the second eye's picture sits inside the file. */
    enum class Layout(val title: String) {
        MONO("обычное"),
        SIDE_BY_SIDE("3D бок о бок"),
        OVER_UNDER("3D сверху вниз")
    }

    /** How much of the world the picture covers. */
    enum class Shape(val title: String) {
        FLAT("плоское"),
        PANORAMA_180("панорама 180°"),
        PANORAMA_360("панорама 360°")
    }

    private val sideBySideWords = listOf("sbs", "side-by-side", "side_by_side", "_lr", "-lr", "vr180", "180x180")
    private val overUnderWords = listOf("over-under", "over_under", "_ou", "-ou", "_tb", "-tb", "topbottom", "top-bottom")
    private val panorama360Words = listOf("360", "equirect", "pano", "panorama", "insta360", "theta")
    private val panorama180Words = listOf("vr180", "180")
    /** Words that only promise depth, without saying how the two eyes are packed. */
    private val stereoWords = listOf("3d", "stereo", "spatial")

    fun layout(name: String, width: Int, height: Int): Layout {
        val lower = name.lowercase()
        if (overUnderWords.any { lower.contains(it) }) return Layout.OVER_UNDER
        if (sideBySideWords.any { lower.contains(it) }) return Layout.SIDE_BY_SIDE
        if (width <= 0 || height <= 0) return Layout.MONO
        val aspect = width.toFloat() / height
        // Two pictures side by side make a file twice as wide as usual: 32:9 for 16:9 eyes, 4:1 for
        // a 360 panorama. Over-under makes it twice as tall.
        if (aspect >= 3.2f) return Layout.SIDE_BY_SIDE
        if (aspect <= .75f && height > width) return Layout.OVER_UNDER
        if (stereoWords.any { lower.contains(it) } && aspect >= 1.9f) return Layout.SIDE_BY_SIDE
        return Layout.MONO
    }

    fun shape(name: String, width: Int, height: Int, layout: Layout): Shape {
        val lower = name.lowercase()
        if (panorama360Words.any { lower.contains(it) } && !lower.contains("vr180")) return Shape.PANORAMA_360
        if (panorama180Words.any { lower.contains(it) }) return Shape.PANORAMA_180
        if (width <= 0 || height <= 0) return Shape.FLAT
        // An equirectangular 360 picture is exactly twice as wide as it is tall, once the eyes are
        // unpacked. The window is kept tight, so an ordinary wide crop is not wrapped around anyone.
        val eyeAspect = eyeAspect(width, height, layout)
        return if (eyeAspect in 1.95f..2.05f) Shape.PANORAMA_360 else Shape.FLAT
    }

    /** Width over height of what one eye sees, with the packing undone. */
    fun eyeAspect(width: Int, height: Int, layout: Layout): Float {
        if (width <= 0 || height <= 0) return 16f / 9f
        return when (layout) {
            Layout.SIDE_BY_SIDE -> width / 2f / height
            Layout.OVER_UNDER -> width.toFloat() / (height / 2f)
            Layout.MONO -> width.toFloat() / height
        }
    }

    /** The part of the picture one eye takes: left/right halves, top/bottom halves, or all of it. */
    fun uv(layout: Layout, eye: Int): FloatArray = when (layout) {
        Layout.SIDE_BY_SIDE -> if (eye == 0) floatArrayOf(0f, 0f, .5f, 1f) else floatArrayOf(.5f, 0f, 1f, 1f)
        // The left eye is on top: that is the order every VR player and camera writes.
        Layout.OVER_UNDER -> if (eye == 0) floatArrayOf(0f, 0f, 1f, .5f) else floatArrayOf(0f, .5f, 1f, 1f)
        Layout.MONO -> floatArrayOf(0f, 0f, 1f, 1f)
    }

    /** What to write under a file in the gallery. */
    fun describe(layout: Layout, shape: Shape): String =
        if (shape == Shape.FLAT) layout.title else "${shape.title} · ${layout.title}"
}
