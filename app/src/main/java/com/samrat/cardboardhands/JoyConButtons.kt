package com.samrat.cardboardhands

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicIntegerArray

/** Joy-Con state shared between the accessibility filter and the hand-tracking service. */
object JoyConButtons {
    const val PRIMARY = 1
    const val SECONDARY = 1 shl 1
    const val TRIGGER = 1 shl 2
    const val SQUEEZE = 1 shl 3
    const val MENU = 1 shl 4
    const val STICK_CLICK = 1 shl 5
    const val SYSTEM = 1 shl 6

    /** Live state of one Joy-Con, used by the settings screen to show what is arriving. */
    data class Live(
        val connected: Boolean,
        /** Bits of the VR inputs currently pressed. */
        val buttons: Int,
        /** Bits over Settings.KNOWN_KEYS: which physical buttons are held. */
        val rawKeys: Int,
        val stickX: Float,
        val stickY: Float
    )

    private val masks = AtomicIntegerArray(2)
    private val rawMasks = AtomicIntegerArray(2)
    private val sticks = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
    @Volatile private var bindings: Map<Int, Settings.Action> = Settings.defaults().bindings
    /** While set, key presses are reported here instead of being turned into VR buttons. */
    @Volatile private var learner: ((keyCode: Int, left: Boolean) -> Unit)? = null

    fun mask(left: Boolean): Int = masks.get(if (left) 0 else 1)

    fun stick(left: Boolean): FloatArray = sticks[if (left) 0 else 1]

    fun live(left: Boolean): Live {
        val slot = if (left) 0 else 1
        return Live(connected(left), masks.get(slot), rawMasks.get(slot), sticks[slot][0], sticks[slot][1])
    }

    fun apply(state: Settings.State) {
        bindings = state.bindings
        clear()
    }

    /** Puts the filter into "press a button" mode for the settings screen. */
    fun learn(callback: ((keyCode: Int, left: Boolean) -> Unit)?) {
        learner = callback
        clear()
    }

    /**
     * Any controller PhoneXR can play VR with: a Joy-Con, a DualShock, an Xbox pad or a no-name one
     * from a marketplace. They all speak the same gamepad language to Android, so they are all let
     * in; what differs is which hand a button belongs to.
     */
    fun isController(device: InputDevice?): Boolean {
        device ?: return false
        if (isJoyCon(device)) return true
        if (device.isVirtual) return false
        val sources = device.sources
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return gamepad || joystick
    }

    /**
     * Which hand a button belongs to. A Joy-Con is already one hand's worth; a single gamepad plays
     * both, so its left side (X, Y, L, ZL, left stick) is the left hand and its right side the right.
     */
    fun handOf(device: InputDevice, keyCode: Int): Boolean {
        if (isJoyCon(device)) return isLeft(device)
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    fun isJoyCon(device: InputDevice?): Boolean {
        device ?: return false
        val name = device.name.lowercase()
        return name.contains("joy-con") || name.contains("joycon") ||
            (device.vendorId == 0x057e && (device.productId == 0x2006 || device.productId == 0x2007))
    }

    fun isLeft(device: InputDevice): Boolean {
        val name = device.name.lowercase()
        return device.productId == 0x2006 || name.contains("left") || name.contains("(l)")
    }

    /** A Joy-Con of that side, or any other controller — one pad stands for both hands. */
    fun connected(left: Boolean): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        if (isJoyCon(device)) isLeft(device) == left else isController(device)
    }

    /** Names of the controllers plugged in or paired, for the settings screen. */
    fun names(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        InputDevice.getDevice(id)?.takeIf { isController(it) }?.name
    }.distinct()

    /** Returns true when the event came from a controller, so the game never sees it. */
    fun onKey(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        if (!isController(device)) return false
        val left = handOf(device!!, event.keyCode)
        val slot = if (left) 0 else 1
        val rawBit = Settings.KNOWN_KEYS.indexOf(event.keyCode).takeIf { it >= 0 }?.let { 1 shl it } ?: 0
        if (rawBit != 0) {
            val pressedNow = event.action != KeyEvent.ACTION_UP
            while (true) {
                val current = rawMasks.get(slot)
                val next = if (pressedNow) current or rawBit else current and rawBit.inv()
                if (rawMasks.compareAndSet(slot, current, next)) break
            }
        }
        val teacher = learner
        if (teacher != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) teacher(event.keyCode, left)
            return true
        }
        val bit = bindings[event.keyCode]?.bit ?: return true
        if (bit == 0) return true
        val pressed = event.action != KeyEvent.ACTION_UP
        while (true) {
            val current = masks.get(slot)
            val next = if (pressed) current or bit else current and bit.inv()
            if (masks.compareAndSet(slot, current, next)) break
        }
        return true
    }

    /**
     * Sticks arrive as joystick axes. A gamepad holds both of them at once; a single Joy-Con is held
     * sideways, so its axes are swapped and turned.
     */
    fun onMotion(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        if (!isController(device)) return false
        if (!isJoyCon(device)) return onGamepadMotion(event)
        val left = isLeft(device!!)
        val rawX: Float
        val rawY: Float
        if (left) {
            rawX = event.getAxisValue(MotionEvent.AXIS_X)
            rawY = event.getAxisValue(MotionEvent.AXIS_Y)
        } else {
            rawX = event.getAxisValue(MotionEvent.AXIS_Z)
            rawY = event.getAxisValue(MotionEvent.AXIS_RZ)
        }
        // Sideways: the stick's own up is the player's left on the left Joy-Con, right on the right one.
        val turn = if (left) 1f else -1f
        val slot = sticks[if (left) 0 else 1]
        slot[0] = deadzone(-rawY * turn)
        slot[1] = deadzone(rawX * turn)
        return true
    }

    /**
     * One gamepad for both hands: the left stick is the left hand, the right stick the right one.
     * Many pads (DualShock above all) only report their triggers as axes, so those become presses.
     */
    private fun onGamepadMotion(event: MotionEvent): Boolean {
        sticks[0][0] = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
        sticks[0][1] = deadzone(-event.getAxisValue(MotionEvent.AXIS_Y))
        sticks[1][0] = deadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        sticks[1][1] = deadzone(-event.getAxisValue(MotionEvent.AXIS_RZ))
        trigger(0, event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        trigger(1, event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        return true
    }

    /** An analogue trigger counts as pressed once it is past half way. */
    private fun trigger(slot: Int, primary: Float, secondary: Float) {
        val pulled = maxOf(primary, secondary) > .5f
        while (true) {
            val current = masks.get(slot)
            val next = if (pulled) current or TRIGGER else current and TRIGGER.inv()
            if (masks.compareAndSet(slot, current, next)) break
        }
    }

    private fun deadzone(value: Float): Float {
        val limited = value.coerceIn(-1f, 1f)
        return if (kotlin.math.abs(limited) < .12f) 0f else limited
    }

    fun clear() {
        masks.set(0, 0)
        masks.set(1, 0)
        rawMasks.set(0, 0)
        rawMasks.set(1, 0)
        sticks.forEach { it[0] = 0f; it[1] = 0f }
    }
}
