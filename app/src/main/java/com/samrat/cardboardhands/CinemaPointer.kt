package com.samrat.cardboardhands

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A pointer on the cinema screen that is not a hand: a Bluetooth mouse, or a second phone held like
 * a remote. Both end up in the same place — a point on the screen that can press, drag and scroll —
 * so both are served here, and the app on the virtual display sees plain touches.
 */
class CinemaPointer(
    /** Screen placement from the renderer, for aiming a second phone at it. */
    private val placement: () -> FloatArray,
    private val onCursor: (CinemaRenderer.Cursor?) -> Unit,
    private val inject: (MotionEvent) -> Unit,
    private val injectKey: (KeyEvent) -> Unit,
) {
    /** Where the pointer is on the screen, 0..1. */
    private var u = .5f
    private var v = .5f
    private var down = false
    private var downTime = 0L
    /** Hidden until something moves it, so a lone cursor never sits on the screen. */
    private var visible = false

    /** How far across the screen a mouse travels; the screen is wider than a phone's own. */
    var mouseSpeed = 1f / 1600f

    // ---------------------------------------------------------------- mouse

    fun mouseMoved(dx: Float, dy: Float) {
        u = (u + dx * mouseSpeed).coerceIn(0f, 1f)
        v = (v + dy * mouseSpeed).coerceIn(0f, 1f)
        visible = true
        if (down) send(MotionEvent.ACTION_MOVE)
        show()
    }

    /** [buttons] is MotionEvent.getButtonState: the left button presses, the right one goes back. */
    fun mouseButtons(buttons: Int) {
        val primary = buttons and MotionEvent.BUTTON_PRIMARY != 0
        if (primary != down) if (primary) press() else release()
        val secondary = buttons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_BACK) != 0
        if (secondary && !secondaryWasDown) back()
        secondaryWasDown = secondary
    }

    private var secondaryWasDown = false

    /** The wheel scrolls the app under the pointer, the way a mouse does on a phone. */
    fun scroll(amount: Float) {
        if (amount == 0f) return
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = u * screenWidth
            y = v * screenHeight
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
            setAxisValue(MotionEvent.AXIS_SCROLL, amount)
        })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        inject(event)
        event.recycle()
        show()
    }

    // ---------------------------------------------------------------- a second phone

    /**
     * A phone held like a remote: [direction] is where it points, in the same world the head is in.
     * Misses of the screen keep the pointer at its edge, so it never disappears mid-swipe.
     */
    fun aim(direction: FloatArray) {
        val hit = CinemaAim.screenPoint(direction, placement()) ?: return
        u = hit[0].coerceIn(0f, 1f)
        v = hit[1].coerceIn(0f, 1f)
        visible = true
        if (down) send(MotionEvent.ACTION_MOVE)
        show()
    }

    /** The trigger of the second phone: it presses the screen where the phone points. */
    fun trigger(pressed: Boolean) {
        if (pressed == down) return
        if (pressed) press() else release()
    }

    fun back() = injectKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        .also { injectKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)) }

    // ---------------------------------------------------------------- shared

    /** Size of the virtual display, so a point on the screen becomes a touch in its pixels. */
    var screenWidth = CinemaRenderer.SCREEN_PIXELS_W
    var screenHeight = CinemaRenderer.SCREEN_PIXELS_H

    /** Nothing is aiming any more: the cursor goes away and lets go of whatever it held. */
    fun release() {
        if (down) {
            send(MotionEvent.ACTION_UP)
            down = false
        }
        show()
    }

    fun hide() {
        release()
        visible = false
        onCursor(null)
    }

    private fun press() {
        down = true
        downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN)
        show()
    }

    private fun show() = onCursor(if (visible) CinemaRenderer.Cursor(u, v, down) else null)

    private fun send(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = u * screenWidth
            y = v * screenHeight
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(
            downTime, now, action, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        inject(event)
        event.recycle()
    }

    private companion object {
        /** The hands take 0 and 1, so the pointer never collides with them. */
        const val POINTER_ID = 2
    }
}
