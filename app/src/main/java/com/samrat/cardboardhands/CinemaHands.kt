package com.samrat.cardboardhands

import android.opengl.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Two-hand play on the cinema screen: each hand aims a cursor at the big screen, a pinch touches
 * there. Both hands together make a two-finger multi-touch, so on-screen joysticks and buttons of
 * Roblox, Brawl Stars or Minecraft can be used at once.
 */
class CinemaHands(
    private val tracker: HeadTracker,
    /** Screen centre y, z, width and the eye height, from the renderer. */
    private val placement: () -> FloatArray,
    private val onCursors: (List<CinemaRenderer.Cursor>) -> Unit,
    /** Real hands in head space: triangles with camera texture coordinates (x, y, z, u, v). */
    private val onGhosts: (List<FloatArray>) -> Unit,
    private val inject: (MotionEvent) -> Unit,
) {
    private class Hand(val pointerId: Int) {
        val filterX = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
        val filterY = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
        val latch = HandGestures.PinchLatch()
        var down = false
        var u = .5f
        var v = .5f
        var seen = false
    }

    /**
     * Minecraft: the game fills the whole view, each hand is a cursor on it (a pinch taps, in menus
     * and in the game). With the PhoneXR VR mod linked, the hands and head drive the mod instead.
     */
    @Volatile var minecraft = false
    /** Full-view mode: camera picture → view scale (camera aspect / eye aspect), from the renderer. */
    @Volatile var cameraToView = 4f / 3f / .8f
    /** Size of the app's virtual screen in pixels, for touches. */
    @Volatile var screenWidth = CinemaRenderer.SCREEN_PIXELS_W
    @Volatile var screenHeight = CinemaRenderer.SCREEN_PIXELS_H

    /** For the PhoneXR VR mod: where the hand is around the head (metres) and what it does. */
    private fun bridgeData(hand: Hand, shape: HandGestures.Shape, points: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, pinching: Boolean) {
        fun d(a: Int, b: Int) = kotlin.math.hypot(points[a].x() - points[b].x(), points[a].y() - points[b].y())
        val indexOut = d(0, 8) > d(0, 6) * 1.12f
        val othersCurled = intArrayOf(12, 16, 20).zip(intArrayOf(10, 14, 18)).all { (tip, pip) -> d(0, tip) < d(0, pip) * 1.05f }
        val thumbOut = d(4, 5) > shape.palmWidth * .55f
        val gun = indexOut && othersCurled && thumbOut
        val palm = intArrayOf(0, 5, 9, 17)
        val tx = palm.map { (points[it].x() - .5f) * 2f * TAN_X }.average().toFloat()
        val ty = palm.map { (.5f - points[it].y()) * 2f * TAN_Y }.average().toFloat()
        val palmTan = kotlin.math.hypot((points[5].x() - points[17].x()) * 2f * TAN_X, (points[5].y() - points[17].y()) * 2f * TAN_Y)
        val distance = (PALM_WIDTH_M / palmTan.coerceAtLeast(.01f)).coerceIn(.2f, .8f)
        val bits = (if (shape.fist) 1 else 0) or (if (gun) 2 else 0) or (if (pinching) 4 else 0)
        bridgeHands[hand.pointerId] = floatArrayOf(tx * distance, ty * distance, distance, bits.toFloat())
    }

    /** See-through hands for the full view, in its own coordinates (-1..1), and their cursors. */
    @Volatile var onViewHands: (List<FloatArray>) -> Unit = {}

    /** Latest hands for the Minecraft mod: [x, y, z, bits] per hand (left, right), null when unseen. */
    val bridgeHands = arrayOfNulls<FloatArray>(2)

    /** Called when the user asks to link the mod (palm toward the face + pinch). */
    @Volatile var onConnectGesture: () -> Unit = {}

    /** Left hand is pointer 0, right hand pointer 1. */
    private val hands = mapOf(true to Hand(0), false to Hand(1))
    private var downTime = 0L

    fun onResult(result: HandLandmarkerResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        val frameNs = result.timestampMs() * 1_000_000L
        val head = FloatArray(16).also { tracker.copyHeadAt(frameNs, it) }
        val place = placement()
        hands.values.forEach { it.seen = false }
        val cursors = ArrayList<CinemaRenderer.Cursor>(2)
        val ghosts = ArrayList<FloatArray>(2)
        val viewHands = ArrayList<FloatArray>(2)

        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val physicalLeft = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true)
            val hand = hands.getValue(physicalLeft)
            if (hand.seen) return@forEachIndexed
            val shape = HandGestures.shape(points, physicalLeft)
            if (minecraft) {
                hand.seen = true
                val pinching = hand.latch.update(shape)
                bridgeData(hand, shape, points, pinching)
                // The hand drawn over the game where it really is.
                viewHands += GhostHand.triangles(
                    FloatArray(21) { (points[it].x() - .5f) * cameraToView * 2f },
                    FloatArray(21) { (.5f - points[it].y()) * 2f },
                    0f,
                )
                val x = (hand.filterX.filter(shape.aimX, now) - .5f) * cameraToView + .5f
                val y = hand.filterY.filter(shape.aimY, now)
                hand.u = x.coerceIn(0f, 1f)
                hand.v = y.coerceIn(0f, 1f)
                // Palm toward the face + pinch: PhoneXR types "/connect" into Minecraft for the mod.
                if (pinching && !hand.down && shape.palmToFace && !MinecraftBridge.connected) {
                    hand.down = true
                    onConnectGesture()
                    return@forEachIndexed
                }
                if (MinecraftBridge.connected) {
                    // The mod plays with the hands; no taps on the screen.
                    if (hand.down) release(hand)
                    cursors += CinemaRenderer.Cursor(hand.u, hand.v, pinching)
                    return@forEachIndexed
                }
                val onScreen = x in 0f..1f && y in 0f..1f
                val pressed = pinching && (hand.down || onScreen)
                cursors += CinemaRenderer.Cursor(hand.u, hand.v, pressed)
                if (pressed != hand.down) { if (pressed) press(hand) else release(hand) }
                return@forEachIndexed
            }
            val x = hand.filterX.filter(shape.aimX, now)
            val y = hand.filterY.filter(shape.aimY, now)
            val hit = screenPoint(x, y, head, place) ?: return@forEachIndexed
            hand.seen = true
            hand.u = hit[0].coerceIn(0f, 1f)
            hand.v = hit[1].coerceIn(0f, 1f)
            val pinching = hand.latch.update(shape)
            // A touch may only start on the screen; once down it follows the hand to the edges.
            val onScreen = hit[0] in 0f..1f && hit[1] in 0f..1f
            if (pinching && !hand.down && !onScreen) hand.latch.reset()
            val pressed = pinching && (hand.down || onScreen)
            if (hit[0] in -.08f..1.08f && hit[1] in -.08f..1.08f) cursors += CinemaRenderer.Cursor(hand.u, hand.v, pressed)
            if (pressed != hand.down) {
                if (pressed) press(hand) else release(hand)
            }
        }
        // A hand that left the camera lets go of the screen.
        for (hand in hands.values) {
            if (!hand.seen && minecraft) bridgeHands[hand.pointerId] = null
            if (!hand.seen) {
                hand.filterX.reset(); hand.filterY.reset(); hand.latch.reset()
                if (hand.down) release(hand)
            }
        }
        if (hands.values.any { it.down }) send(MotionEvent.ACTION_MOVE, null)
        onCursors(cursors)
        onGhosts(ghosts)
        onViewHands(viewHands)
    }

    /** Lets go of everything, e.g. when the cinema pauses. */
    fun releaseAll() {
        for (hand in hands.values) if (hand.down) release(hand)
        onCursors(emptyList())
        onGhosts(emptyList())
        onViewHands(emptyList())
    }

    /** Where the hand's aim ray from the eyes meets the screen, as screen u, v (may be outside 0..1). */
    private fun screenPoint(x: Float, y: Float, head: FloatArray, place: FloatArray): FloatArray? {
        val local = floatArrayOf((x - .5f) * 2f * TAN_X, (.5f - y) * 2f * TAN_Y, -1f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, local, 0)
        return CinemaAim.screenPoint(world, place)
    }

    private fun press(hand: Hand) {
        val first = hands.values.none { it.down }
        hand.down = true
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, hand)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN, hand)
        }
    }

    private fun release(hand: Hand) {
        val last = hands.values.count { it.down } == 1
        send(if (last) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, hand)
        hand.down = false
    }

    /** One multi-touch event with every hand that is down; [actor] is the pointer that changes. */
    private fun send(action: Int, actor: Hand?) {
        val active = hands.values.filter { it.down }.sortedBy { it.pointerId }
        if (active.isEmpty()) return
        val properties = Array(active.size) { i ->
            MotionEvent.PointerProperties().apply { id = active[i].pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(active.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = active[i].u * screenWidth
                y = active[i].v * screenHeight
                pressure = 1f
                size = 1f
            }
        }
        val masked = if (actor != null && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            action or (active.indexOf(actor) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), masked, active.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        inject(event)
        event.recycle()
    }

    private companion object {
        /**
         * Half-width and half-height of the camera view as tangents, with a little extra reach so
         * the whole screen is covered without stretching the arms (4:3 analysis frames).
         */
        const val TAN_X = .95f
        /** A grown-up palm is about this wide: its size in the picture gives the hand's distance. */
        const val PALM_WIDTH_M = .08f
        const val TAN_Y = .72f
    }
}

