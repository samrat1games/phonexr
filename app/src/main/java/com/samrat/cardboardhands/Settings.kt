package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** User settings. The tracking service runs in its own process, so changes travel as a broadcast. */
object Settings {
    const val ACTION_APPLY = "com.samrat.cardboardhands.APPLY_SETTINGS"

    /** What the camera reports to OpenXR. */
    enum class HandMode { CONTROLLERS, HANDS }
    enum class HomeStyle(val title: String) { LARGE("Большое"), COMPACT("Компактная панель") }

    /** A VR controller input a Joy-Con button can be bound to. */
    enum class Action(val title: String, val hint: String, val bit: Int) {
        TRIGGER("Курок", "удар, выстрел, выбор в меню", JoyConButtons.TRIGGER),
        SQUEEZE("Захват", "взять предмет, держать саблю", JoyConButtons.SQUEEZE),
        PRIMARY("A / X", "нижняя кнопка контроллера", JoyConButtons.PRIMARY),
        SECONDARY("B / Y", "верхняя кнопка контроллера", JoyConButtons.SECONDARY),
        MENU("Меню", "пауза, выход в меню игры", JoyConButtons.MENU),
        STICK_CLICK("Нажатие стика", "бег, приседание — зависит от игры", JoyConButtons.STICK_CLICK),
        SYSTEM("Системная", "редко используется играми", JoyConButtons.SYSTEM),
        NONE("Не назначено", "кнопка ничего не делает", 0),
    }

    /**
     * Joy-Con key codes as Android reports them. Several physical buttons share a code
     * (SL with L, SR with ZL), so buttons are bound by pressing them, never by a guessed name.
     */
    private val DEFAULT_BINDINGS = mapOf(
        KeyEvent.KEYCODE_BUTTON_R2 to Action.TRIGGER,   // ZR
        KeyEvent.KEYCODE_BUTTON_L2 to Action.TRIGGER,   // ZL
        KeyEvent.KEYCODE_BUTTON_R1 to Action.SQUEEZE,   // R и SR
        KeyEvent.KEYCODE_BUTTON_L1 to Action.SQUEEZE,   // L и SL
        KeyEvent.KEYCODE_BUTTON_A to Action.PRIMARY,
        KeyEvent.KEYCODE_BUTTON_B to Action.SECONDARY,
        KeyEvent.KEYCODE_BUTTON_X to Action.PRIMARY,
        KeyEvent.KEYCODE_BUTTON_Y to Action.SECONDARY,
        KeyEvent.KEYCODE_DPAD_DOWN to Action.PRIMARY,
        KeyEvent.KEYCODE_DPAD_LEFT to Action.PRIMARY,
        KeyEvent.KEYCODE_DPAD_UP to Action.SECONDARY,
        KeyEvent.KEYCODE_DPAD_RIGHT to Action.SECONDARY,
        KeyEvent.KEYCODE_BUTTON_START to Action.MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to Action.MENU,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Action.STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_THUMBR to Action.STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_MODE to Action.SYSTEM,
    )

    /** Every Joy-Con key PhoneXR knows about, in the order used by the live diagram. */
    val KNOWN_KEYS: List<Int> = listOf(
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_MODE
    )

    private const val PREFS = "phonexr"
    private const val KEY_SIX_DOF = "six_dof"
    private const val KEY_HAND_MODE = "hand_mode"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_CAMERA_JOYCONS = "camera_joycons"
    private const val KEY_MARKER_JOYCONS = "marker_joycons"
    private const val KEY_LEFT_COLOR = "left_joycon_color"
    private const val KEY_RIGHT_COLOR = "right_joycon_color"
    private const val KEY_TRACKING_SMOOTHNESS = "tracking_smoothness"

    data class State(
        val sixDof: Boolean = true,
        val handMode: HandMode = HandMode.CONTROLLERS,
        /** Android key code of a Joy-Con button to the VR input it presses. */
        val bindings: Map<Int, Action> = DEFAULT_BINDINGS,
        /** Position and rotation of the Joy-Con come from the camera, which finds them by colour. */
        val cameraJoyCons: Boolean = false,
        /** Position and full rotation from printed ArUco markers on the Joy-Con (markers/joycon_markers_A4.pdf). */
        val markerJoyCons: Boolean = false,
        /** 0 is fastest, 100 is steadiest. */
        val trackingSmoothness: Int = 50,
        val leftColor: JoyConVision.Target = JoyConVision.Target.NEON_BLUE,
        val rightColor: JoyConVision.Target = JoyConVision.Target.NEON_RED
    ) {
        fun keysFor(action: Action): List<Int> =
            bindings.filterValues { it == action }.keys.sorted()
    }

    fun load(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BINDINGS, null)
        val bindings = stored?.split(',')?.mapNotNull { pair ->
            val (key, action) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val code = key.toIntOrNull() ?: return@mapNotNull null
            code to (Action.entries.firstOrNull { it.name == action } ?: return@mapNotNull null)
        }?.toMap() ?: DEFAULT_BINDINGS
        return State(
            sixDof = prefs.getBoolean(KEY_SIX_DOF, true),
            handMode = HandMode.valueOf(prefs.getString(KEY_HAND_MODE, HandMode.CONTROLLERS.name)!!),
            bindings = bindings,
            cameraJoyCons = prefs.getBoolean(KEY_CAMERA_JOYCONS, false),
            markerJoyCons = prefs.getBoolean(KEY_MARKER_JOYCONS, false),
            trackingSmoothness = prefs.getInt(KEY_TRACKING_SMOOTHNESS, 50).coerceIn(0, 100),
            leftColor = JoyConVision.Target.decode(prefs.getString(KEY_LEFT_COLOR, null)) ?: JoyConVision.Target.NEON_BLUE,
            rightColor = JoyConVision.Target.decode(prefs.getString(KEY_RIGHT_COLOR, null)) ?: JoyConVision.Target.NEON_RED
        )
    }

    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SIX_DOF, state.sixDof)
            .putString(KEY_HAND_MODE, state.handMode.name)
            .putString(KEY_BINDINGS, state.bindings.entries.joinToString(",") { "${it.key}:${it.value.name}" })
            .putBoolean(KEY_CAMERA_JOYCONS, state.cameraJoyCons)
            .putBoolean(KEY_MARKER_JOYCONS, state.markerJoyCons)
            .putInt(KEY_TRACKING_SMOOTHNESS, state.trackingSmoothness.coerceIn(0, 100))
            .putString(KEY_LEFT_COLOR, state.leftColor.encode())
            .putString(KEY_RIGHT_COLOR, state.rightColor.encode())
            .apply()
        // The tracking service keeps its own copy in another process.
        context.sendBroadcast(Intent(ACTION_APPLY).setPackage(context.packageName))
    }

    fun defaults() = State()

    /** The name chosen in the first setup, shown in VR. */
    fun userName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_NAME, "") ?: ""

    fun setUserName(context: Context, name: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_USER_NAME, name).apply()

    /** The first-start setup in the headset has been completed (or skipped to the end). */
    fun setupDone(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, done: Boolean = true) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DONE, done).apply()

    /** The shape of the cinema screen: how wide the picture is and whether it wraps around. */
    enum class ScreenShape(val title: String, val detail: String, val width: Int, val height: Int) {
        NORMAL("Обычный 16:9", "Как телевизор: 1920×1080", 1920, 1080),
        WIDE("Широкий 21:9", "Как в кино: 2560×1080", 2560, 1080),
        ULTRA("Панорамный 32:9", "Во весь обзор: 3840×1080", 3840, 1080);

        val aspect get() = width.toFloat() / height
    }

    /** How often the phone's own screen is redrawn; higher is smoother and eats more battery. */
    enum class Refresh(val title: String, val hz: Int) {
        AUTO("Автоматически", 0), HZ60("60 Гц", 60), HZ90("90 Гц", 90), HZ120("120 Гц", 120)
    }

    fun screenShape(context: Context): ScreenShape = runCatching {
        ScreenShape.valueOf(prefs(context).getString(KEY_SCREEN_SHAPE, ScreenShape.NORMAL.name)!!)
    }.getOrDefault(ScreenShape.NORMAL)

    fun setScreenShape(context: Context, shape: ScreenShape) =
        prefs(context).edit().putString(KEY_SCREEN_SHAPE, shape.name).apply()

    /** A wide screen bent around the viewer, so its edges stay the same distance away. */
    fun curvedScreen(context: Context) = prefs(context).getBoolean(KEY_CURVED, true)

    fun setCurvedScreen(context: Context, curved: Boolean) =
        prefs(context).edit().putBoolean(KEY_CURVED, curved).apply()

    /**
     * Distance between the eyes in millimetres. Everyone's is different, and when it does not match
     * the picture the two halves refuse to become one and the image doubles.
     */
    fun ipdMm(context: Context) = prefs(context).getInt(KEY_IPD, DEFAULT_IPD_MM).coerceIn(MIN_IPD_MM, MAX_IPD_MM)

    fun setIpdMm(context: Context, mm: Int) =
        prefs(context).edit().putInt(KEY_IPD, mm.coerceIn(MIN_IPD_MM, MAX_IPD_MM)).apply()

    /**
     * How far each eye's picture is moved sideways on the screen, in millimetres. Cardboard lenses
     * sit a fixed distance apart, and the two halves of the screen have to sit under them: this is
     * the setting that stops the doubling when the headset's lenses are wider or narrower than usual.
     */
    fun lensOffsetMm(context: Context) = prefs(context).getInt(KEY_LENS_OFFSET, 0).coerceIn(-MAX_LENS_MM, MAX_LENS_MM)

    fun setLensOffsetMm(context: Context, mm: Int) =
        prefs(context).edit().putInt(KEY_LENS_OFFSET, mm.coerceIn(-MAX_LENS_MM, MAX_LENS_MM)).apply()

    /** The home panel turns to stay in front of the user instead of waiting where it was left. */
    fun panelFollows(context: Context) = prefs(context).getBoolean(KEY_PANEL_FOLLOWS, true)

    fun setPanelFollows(context: Context, follows: Boolean) =
        prefs(context).edit().putBoolean(KEY_PANEL_FOLLOWS, follows).apply()

    fun homeStyle(context: Context): HomeStyle = runCatching {
        HomeStyle.valueOf(prefs(context).getString(KEY_HOME_STYLE, HomeStyle.LARGE.name)!!)
    }.getOrDefault(HomeStyle.LARGE)

    fun setHomeStyle(context: Context, style: HomeStyle) =
        prefs(context).edit().putString(KEY_HOME_STYLE, style.name).apply()

    const val DEFAULT_IPD_MM = 64
    const val MIN_IPD_MM = 40
    const val MAX_IPD_MM = 76
    const val MAX_LENS_MM = 12

    /** A window onto the desk under the screen, so a real keyboard can be seen while typing. */
    fun keyboardWindow(context: Context) = prefs(context).getBoolean(KEY_KEYBOARD_WINDOW, true)

    fun setKeyboardWindow(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEYBOARD_WINDOW, enabled).apply()

    fun refresh(context: Context): Refresh = runCatching {
        Refresh.valueOf(prefs(context).getString(KEY_REFRESH, Refresh.AUTO.name)!!)
    }.getOrDefault(Refresh.AUTO)

    fun setRefresh(context: Context, refresh: Refresh) =
        prefs(context).edit().putString(KEY_REFRESH, refresh.name).apply()

    /**
     * Guest mode: everything a guest sets — calibration, bindings, look — is kept in its own file,
     * so handing the headset to someone changes nothing for the owner. The flag itself lives with
     * the owner, which is how the way back is found.
     */
    fun guestMode(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUEST, false)

    fun setGuestMode(context: Context, guest: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GUEST, guest).apply()

    /**
     * Travel mode: in a car or a train the whole world turns, and the view would be dragged with it.
     * With this on, the head tracker lets slow turning go and keeps only what the head itself does.
     */
    fun travelMode(context: Context) = prefs(context).getBoolean(KEY_TRAVEL, false)

    fun setTravelMode(context: Context, travel: Boolean) =
        prefs(context).edit().putBoolean(KEY_TRAVEL, travel).apply()

    /** Text copied on one phone appears on the other. */
    fun sharedClipboard(context: Context) = prefs(context).getBoolean(KEY_CLIPBOARD, false)

    fun setSharedClipboard(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLIPBOARD, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(
        if (guestMode(context)) "$PREFS-guest" else PREFS, Context.MODE_PRIVATE
    )

    /** The look of the interface: Cupertino UI or Material You. */
    fun uiStyle(context: Context): UiStyle = runCatching {
        UiStyle.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UI_STYLE, UiStyle.CUPERTINO.name)!!)
    }.getOrDefault(UiStyle.CUPERTINO)

    fun setUiStyle(context: Context, style: UiStyle) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_UI_STYLE, style.name).apply()

    private const val KEY_USER_NAME = "user_name"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_UI_STYLE = "ui_style"
    private const val KEY_SCREEN_SHAPE = "screen_shape"
    private const val KEY_CURVED = "curved_screen"
    private const val KEY_REFRESH = "refresh"
    private const val KEY_KEYBOARD_WINDOW = "keyboard_window"
    private const val KEY_IPD = "ipd_mm"
    private const val KEY_LENS_OFFSET = "lens_offset_mm"
    private const val KEY_PANEL_FOLLOWS = "panel_follows"
    private const val KEY_HOME_STYLE = "home_style"
    private const val KEY_GUEST = "guest_mode"
    private const val KEY_TRAVEL = "travel_mode"
    private const val KEY_CLIPBOARD = "shared_clipboard"

    /** Human-readable name for a key code, used when showing what is bound. */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L / SL"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R / SR"
        KeyEvent.KEYCODE_BUTTON_L2 -> "ZL"
        KeyEvent.KEYCODE_BUTTON_R2 -> "ZR"
        KeyEvent.KEYCODE_BUTTON_START -> "+"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "−"
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> "стик"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Home"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        else -> "код $code"
    }
}
