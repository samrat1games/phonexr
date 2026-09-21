package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CinemaAimTest {
    /** centre y, z, width, eye height, bend radius, height — as the renderer reports them. */
    private val flat = floatArrayOf(1.45f, -3.9f, 3.4f, 1.15f, 0f, 1.9125f)
    private val curved = floatArrayOf(1.45f, -3.9f, 6.8f, 1.15f, 3.9f, 1.9125f)

    /** Looking straight ahead points at the middle of the screen, bent or not. */
    @Test
    fun straightAheadIsTheMiddle() {
        listOf(flat, curved).forEach { place ->
            val hit = CinemaAim.screenPoint(floatArrayOf(0f, 0f, -1f, 0f), place)!!
            assertEquals(.5f, hit[0], 1e-3f)
            assertTrue(hit.toList().toString(), hit[1] in 0f..1f)
        }
    }

    @Test
    fun theEdgeOfTheBentScreenIsWhereItsArcEnds() {
        // Half the arc to the right is the right edge: the screen is as long as it is wide.
        val halfArc = curved[2] / curved[4] / 2
        val hit = CinemaAim.screenPoint(floatArrayOf(sin(halfArc), 0f, -cos(halfArc), 0f), curved)!!
        assertEquals(1f, hit[0], 1e-3f)
    }

    @Test
    fun aBentScreenKeepsItsEdgesAtTheSameDistance() {
        // Half way to the edge is half way across the picture — no stretching towards the sides,
        // which is the whole point of bending it.
        val quarterArc = curved[2] / curved[4] / 4
        val hit = CinemaAim.screenPoint(floatArrayOf(sin(quarterArc), 0f, -cos(quarterArc), 0f), curved)!!
        assertEquals(.75f, hit[0], 1e-3f)
    }

    @Test
    fun aFlatScreenStretchesTowardsItsEdges() {
        // The same angle on a flat screen lands further out: tan grows faster than the angle.
        val angle = curved[2] / curved[4] / 4
        val bent = CinemaAim.screenPoint(floatArrayOf(sin(angle), 0f, -cos(angle), 0f), curved)!!
        val straight = CinemaAim.screenPoint(floatArrayOf(sin(angle), 0f, -cos(angle), 0f), flat)!!
        assertTrue("$straight vs $bent", straight[0] > bent[0])
    }

    @Test
    fun lookingUpAndDownMovesAlongTheScreen() {
        val up = CinemaAim.screenPoint(floatArrayOf(0f, .3f, -1f, 0f), curved)!!
        val down = CinemaAim.screenPoint(floatArrayOf(0f, -.3f, -1f, 0f), curved)!!
        assertTrue("$up $down", up[1] < down[1])
    }

    @Test
    fun lookingAwayFromAFlatScreenHitsNothing() {
        assertNull(CinemaAim.screenPoint(floatArrayOf(0f, 0f, 1f, 0f), flat))
    }

    @Test
    fun lookingBehindLeavesTheBentScreen() {
        val hit = CinemaAim.screenPoint(floatArrayOf(0f, 0f, 1f, 0f), curved)!!
        assertTrue(hit[0].toString(), hit[0] < 0f || hit[0] > 1f)
    }
}
