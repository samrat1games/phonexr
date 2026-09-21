package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialTest {
    @Test
    fun readsTheLayoutFromTheNamePeopleGiveTheFile() {
        assertEquals(Spatial.Layout.SIDE_BY_SIDE, Spatial.layout("holiday_SBS.jpg", 3840, 1080))
        assertEquals(Spatial.Layout.OVER_UNDER, Spatial.layout("concert_over-under.mp4", 1920, 2160))
        assertEquals(Spatial.Layout.MONO, Spatial.layout("IMG_0042.jpg", 4032, 3024))
    }

    @Test
    fun readsTheLayoutFromTheShapeWhenTheNameSaysNothing() {
        // Two 16:9 pictures next to each other are twice as wide as one.
        assertEquals(Spatial.Layout.SIDE_BY_SIDE, Spatial.layout("clip.mp4", 3840, 1080))
        // Two of them stacked are twice as tall.
        assertEquals(Spatial.Layout.OVER_UNDER, Spatial.layout("clip.mp4", 1080, 1920))
        assertEquals(Spatial.Layout.MONO, Spatial.layout("clip.mp4", 1920, 1080))
    }

    @Test
    fun knowsAPanoramaByItsProportions() {
        // A 360° picture is exactly twice as wide as tall, once the eyes are unpacked.
        assertEquals(Spatial.Shape.PANORAMA_360, Spatial.shape("trip.jpg", 5760, 2880, Spatial.Layout.MONO))
        assertEquals(Spatial.Shape.PANORAMA_360, Spatial.shape("trip.jpg", 11520, 2880, Spatial.Layout.SIDE_BY_SIDE))
        assertEquals(Spatial.Shape.FLAT, Spatial.shape("trip.jpg", 4032, 3024, Spatial.Layout.MONO))
        assertEquals(Spatial.Shape.PANORAMA_180, Spatial.shape("walk_vr180.mp4", 3840, 1920, Spatial.Layout.SIDE_BY_SIDE))
    }

    @Test
    fun eachEyeTakesItsOwnHalf() {
        val (leftU0, _, leftU1) = Spatial.uv(Spatial.Layout.SIDE_BY_SIDE, 0).let { Triple(it[0], it[1], it[2]) }
        assertEquals(0f, leftU0, 0f)
        assertEquals(.5f, leftU1, 0f)
        assertEquals(.5f, Spatial.uv(Spatial.Layout.SIDE_BY_SIDE, 1)[0], 0f)
        // The left eye is the top half of an over-under file.
        assertEquals(.5f, Spatial.uv(Spatial.Layout.OVER_UNDER, 0)[3], 0f)
        assertEquals(.5f, Spatial.uv(Spatial.Layout.OVER_UNDER, 1)[1], 0f)
        assertTrue(Spatial.uv(Spatial.Layout.MONO, 1).contentEquals(floatArrayOf(0f, 0f, 1f, 1f)))
    }

    // ---------------------------------------------------------------- 3D out of a flat photo

    /** A stripe of near pixels in the middle of a far background. */
    private fun scene(width: Int, height: Int): Pair<IntArray, FloatArray> {
        val pixels = IntArray(width * height) { if ((it % width) in width / 2 - 10 until width / 2 + 10) NEAR else FAR }
        val depth = FloatArray(DepthModel.SIZE * DepthModel.SIZE) { index ->
            val x = index % DepthModel.SIZE
            if (x in DepthModel.SIZE / 2 - 10 until DepthModel.SIZE / 2 + 10) 1f else 0f
        }
        return pixels to depth
    }

    @Test
    fun theNearThingMovesOppositeWaysForTheTwoEyes() {
        val width = 512
        val height = 8
        val (pixels, depth) = scene(width, height)
        val (left, right) = SpatialPhoto.eyes(pixels, width, height, depth)
        fun centre(row: IntArray): Float {
            val xs = (0 until width).filter { row[it] == NEAR }
            return xs.average().toFloat()
        }
        val leftRow = left.copyOfRange(0, width)
        val rightRow = right.copyOfRange(0, width)
        val was = (0 until width).filter { pixels[it] == NEAR }.average().toFloat()
        // Near things sit to the right for the left eye and to the left for the right eye: that is
        // what makes them stand in front of the picture.
        assertTrue("$was -> ${centre(leftRow)}", centre(leftRow) > was)
        assertTrue("$was -> ${centre(rightRow)}", centre(rightRow) < was)
    }

    @Test
    fun noHoleIsLeftBehindTheNearThing() {
        val width = 512
        val height = 8
        val (pixels, depth) = scene(width, height)
        val (left, right) = SpatialPhoto.eyes(pixels, width, height, depth)
        assertTrue("дырки в левом глазу", left.none { it == 0 })
        assertTrue("дырки в правом глазу", right.none { it == 0 })
    }

    @Test
    fun aPhotoWithNoDepthComesOutUnchanged() {
        val width = 64
        val height = 4
        val pixels = IntArray(width * height) { NEAR }
        // Everything at the depth that stays put: both eyes see the original.
        val depth = FloatArray(DepthModel.SIZE * DepthModel.SIZE) { .45f }
        val (left, right) = SpatialPhoto.eyes(pixels, width, height, depth)
        assertTrue(left.contentEquals(pixels))
        assertTrue(right.contentEquals(pixels))
    }

    private companion object {
        const val NEAR = 0xFFFFFFFF.toInt()
        const val FAR = 0xFF203040.toInt()
    }
}
