package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * First start of the headset, like visionOS: "hello" in fifteen languages written in the air, then
 * the Persona (hands, then a guided face scan), the user's name, the room
 * boundary (6DoF), a pinch calibration, and "Welcome" before the home screen appears.
 */
class Onboarding(private val context: Context, private val host: Host) {
    interface Host {
        val sixDof: Boolean
        fun startBoundary()
        fun boundaryReady(): Boolean
        fun capturePersona()
        fun finish()
    }

    enum class Step { HELLO, HANDS, FACE_SCAN, WAIT_FACE, NAME, ROOM, PINCH, WELCOME }

    var step = Step.HELLO
        private set
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val script: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Borel-Regular.ttf") }.getOrDefault(Typeface.DEFAULT)
    private var stepStart = SystemClock.elapsedRealtime()
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private val keyboard = KeyboardPanel()
    private var name = Settings.userName(context)
    private var hover: String? = null

    // Hands scan and pinch calibration.
    private var bothHandsSince = 0L
    private var pinches = 0
    private var pinchClosed = false
    private var minGap = 9f
    private var maxGap = 0f

    private fun go(next: Step) {
        step = next
        stepStart = SystemClock.elapsedRealtime()
        bothHandsSince = 0L
    }

    private fun elapsed() = (SystemClock.elapsedRealtime() - stepStart) / 1000f

    /** Called when the guided face capture is complete. */
    @Synchronized
    fun personaDone() {
        if (step == Step.WAIT_FACE) go(Step.NAME)
    }

    /** Hands from the camera: both open hands for the scan, pinch gaps for the calibration. */
    @Synchronized
    fun onHands(hands: List<Pair<Boolean, HandGestures.Shape>>, points: List<FloatArray>) {
        val now = SystemClock.elapsedRealtime()
        when (step) {
            Step.HANDS -> {
                val ready = hands.size >= 2 && hands.none { it.second.fist }
                if (!ready) { bothHandsSince = 0L; return }
                if (bothHandsSince == 0L) bothHandsSince = now
                if (now - bothHandsSince > SCAN_MS) {
                    HandProfile.save(context, points)
                    go(if (BuildConfig.LITE) Step.NAME else Step.FACE_SCAN)
                }
            }
            Step.PINCH -> {
                val hand = hands.maxByOrNull { it.second.palmWidth }?.second ?: return
                val gap = hand.pinchGap
                maxGap = max(maxGap, gap)
                if (!pinchClosed && gap < .42f) { pinchClosed = true; minGap = min(minGap, gap) }
                if (pinchClosed) minGap = min(minGap, gap)
                if (pinchClosed && gap > .6f) {
                    pinchClosed = false
                    pinches++
                    if (pinches >= 3) {
                        HandProfile.savePinch(context, minGap, maxGap)
                        go(Step.WELCOME)
                    }
                }
            }
            else -> Unit
        }
    }

    /** A pinch on the panel at (u, v). */
    @Synchronized
    fun press(u: Float, v: Float) {
        val x = u * WIDTH; val y = v * HEIGHT
        if (step == Step.NAME && keyboardRect.contains(x, y)) {
            when (val key = keyboard.press((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height())) {
                null -> Unit
                KeyboardPanel.BACKSPACE -> name = name.dropLast(1)
                KeyboardPanel.ENTER, KeyboardPanel.HIDE -> confirmName()
                else -> if (name.length < 24) name += key
            }
            return
        }
        buttons.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
    }

    @Synchronized
    fun hover(u: Float, v: Float) {
        hover = if (step == Step.NAME) {
            val x = u * WIDTH; val y = v * HEIGHT
            if (keyboardRect.contains(x, y)) keyboard.hovered((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height()) else null
        } else null
    }

    private fun confirmName() {
        if (name.isBlank()) return
        Settings.setUserName(context, name.trim())
        if (host.sixDof) { host.startBoundary(); go(Step.ROOM) } else go(Step.PINCH)
    }

    /** Redraws the panel for this moment; returns false once setup is over. */
    @Synchronized
    fun draw(): Boolean {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        val t = elapsed()
        when (step) {
            Step.HELLO -> {
                hello(t)
                if (t > 2f) button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), tr("Продолжить")) { go(Step.HANDS) }
            }
            Step.HANDS -> {
                card()
                title(tr("Покажите руки"))
                body(tr("Держите обе руки перед собой, пальцы раскрыты. Не двигайтесь пару секунд."))
                val progress = if (bothHandsSince == 0L) 0f else ((SystemClock.elapsedRealtime() - bothHandsSince) / SCAN_MS.toFloat()).coerceIn(0f, 1f)
                bar(progress)
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), "Пропустить", Color.argb(90, 255, 255, 255)) { go(Step.NAME) }
            }
            Step.FACE_SCAN -> {
                card()
                title(tr("Сканирование лица"))
                body(tr("Не вынимайте телефон из шлема. Покажите лицо внешней камере и медленно поворачивайте голову. Затылок сканировать не нужно."))
                button(RectF(WIDTH / 2f - 460f, 720f, WIDTH / 2f - 20f, 820f), tr("Пропустить"), Color.argb(90, 255, 255, 255)) { go(Step.NAME) }
                button(RectF(WIDTH / 2f + 20f, 720f, WIDTH / 2f + 460f, 820f), tr("Начать сканирование")) {
                    go(Step.WAIT_FACE); host.capturePersona()
                }
            }
            Step.WAIT_FACE -> {
                card()
                title(tr("Сканирование лица"))
                body(tr("Следуйте подсказкам камеры: прямо, влево, вправо, вверх и вниз."))
            }
            Step.NAME -> {
                card()
                title("Как вас зовут?")
                paint.color = Color.argb(70, 255, 255, 255)
                canvas.drawRoundRect(RectF(300f, 220f, WIDTH - 300f, 330f), 55f, 55f, paint)
                text(if (name.isEmpty()) "Имя пользователя" else name + if ((t * 2).toInt() % 2 == 0) "|" else "",
                    WIDTH / 2f, 295f, 60f, if (name.isEmpty()) Color.argb(140, 255, 255, 255) else Color.WHITE)
                button(RectF(WIDTH / 2f - 200f, 360f, WIDTH / 2f + 200f, 440f), "Готово") { confirmName() }
                keyboard.draw(hover)
                canvas.drawBitmap(keyboard.bitmap, null, keyboardRect, paint)
            }
            Step.ROOM -> {
                card()
                title("Настройка комнаты")
                body("Обойдите свободное место по краю — PhoneXR запомнит границу. Круг замкнётся сам, щипок — готово.")
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), "Пропустить", Color.argb(90, 255, 255, 255)) { go(Step.PINCH) }
                if (host.boundaryReady() && t > 1f) go(Step.PINCH)
            }
            Step.PINCH -> {
                card()
                title("Калибровка рук")
                body("Сведите большой и указательный пальцы и разведите их. Три раза.")
                for (i in 0 until 3) {
                    paint.color = if (i < pinches) Color.rgb(48, 209, 88) else Color.argb(80, 255, 255, 255)
                    canvas.drawCircle(WIDTH / 2f + (i - 1) * 90f, 600f, 30f, paint)
                }
            }
            Step.WELCOME -> {
                val hi = Settings.userName(context).takeIf { it.isNotBlank() }
                written("Добро пожаловать", t, 150f, HEIGHT / 2f + 20f)
                if (hi != null && t > 1f) text(hi, WIDTH / 2f, HEIGHT / 2f + 150f, 64f, Color.argb(((t - 1f).coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255))
                if (t > 3f) {
                    Settings.setSetupDone(context)
                    host.finish()
                    return false
                }
            }
        }
        return true
    }

    /** "hello" in fifteen languages, written in the air one after another, for ever. */
    private fun hello(t: Float) {
        val index = (t / HELLO_SECONDS).toInt() % HELLOS.size
        val local = t % HELLO_SECONDS
        written(HELLOS[index], local, 230f, HEIGHT / 2f + 60f, fadeAt = HELLO_SECONDS - .45f)
    }

    /**
     * Script text drawn as if written: revealed from left to right, then held; white, round and a
     * little lit from above like the visionOS lettering.
     */
    private fun written(value: String, t: Float, size: Float, baseline: Float, fadeAt: Float = Float.MAX_VALUE) {
        paint.typeface = script
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val width = paint.measureText(value)
        val reveal = (t / 1.3f).coerceIn(0f, 1f)
        val eased = 1f - (1f - reveal) * (1f - reveal)
        val alpha = if (t > fadeAt) (1f - (t - fadeAt) / .45f).coerceIn(0f, 1f) else 1f
        val left = WIDTH / 2f - width / 2 - 20f
        canvas.save()
        canvas.clipRect(left, 0f, left + (width + 40f) * eased, HEIGHT.toFloat())
        paint.shader = null
        paint.color = Color.argb((alpha * 90).toInt(), 0, 0, 0)
        canvas.drawText(value, WIDTH / 2f + 6f, baseline + 10f, paint)
        paint.shader = LinearGradient(0f, baseline - size, 0f, baseline, Color.WHITE, Color.rgb(214, 216, 222), Shader.TileMode.CLAMP)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawText(value, WIDTH / 2f, baseline, paint)
        paint.shader = null
        canvas.restore()
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        paint.alpha = 255
    }

    private fun card() {
        paint.color = Color.argb(200, 36, 36, 42)
        canvas.drawRoundRect(RectF(60f, 40f, WIDTH - 60f, HEIGHT - 40f), 70f, 70f, paint)
    }

    private fun title(value: String) = text(value, WIDTH / 2f, 170f, 76f, Color.WHITE, bold = true)

    private fun body(value: String) {
        paint.textSize = 44f
        var line = ""
        var row = 0
        for (word in value.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > WIDTH - 360f) {
                text(line, WIDTH / 2f, 300f + row * 62f, 44f, Color.argb(220, 255, 255, 255)); row++; line = word
            } else line = next
        }
        text(line, WIDTH / 2f, 300f + row * 62f, 44f, Color.argb(220, 255, 255, 255))
    }

    private fun bar(progress: Float) {
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(RectF(400f, 560f, WIDTH - 400f, 590f), 15f, 15f, paint)
        paint.color = Color.rgb(10, 132, 255)
        canvas.drawRoundRect(RectF(400f, 560f, 400f + (WIDTH - 800f) * progress, 590f), 15f, 15f, paint)
    }

    private fun button(rect: RectF, label: String, color: Int = Color.rgb(10, 132, 255), action: () -> Unit) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        text(label, rect.centerX(), rect.centerY() + 16f, 44f, Color.WHITE, bold = true)
        buttons += rect to action
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private val keyboardRect = RectF(40f, 470f, WIDTH - 40f, 470f + (WIDTH - 80f) * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH)

    companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1000
        private const val SCAN_MS = 2000L
        private const val HELLO_SECONDS = 2.6f
        private val HELLOS = listOf(
            "hello", "привет", "hola", "bonjour", "hallo", "ciao", "olá", "こんにちは",
            "你好", "안녕하세요", "merhaba", "cześć", "hej", "नमस्ते", "مرحبا",
        )
    }
}

/**
 * The user's hands as scanned in setup: bone lengths relative to the palm, used to keep the
 * see-through hands steady; and the pinch distances measured in calibration.
 */
object HandProfile {
    private const val PREFS = "hand_profile"

    fun save(context: Context, hands: List<FloatArray>) {
        val hand = hands.firstOrNull() ?: return
        fun d(a: Int, b: Int) = kotlin.math.hypot(hand[a * 3] - hand[b * 3], hand[a * 3 + 1] - hand[b * 3 + 1])
        val palm = d(5, 17).coerceAtLeast(1e-4f)
        val ratios = GhostHand.BONES.joinToString(",") { (a, b) -> "%.4f".format(java.util.Locale.US, d(a, b) / palm) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bones", ratios).apply()
    }

    /** Bone length / palm width per bone of [GhostHand.BONES], or null before the scan. */
    fun bones(context: Context): FloatArray? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("bones", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
        ?.takeIf { it.size == GhostHand.BONES.size }

    fun savePinch(context: Context, closed: Float, open: Float) {
        val close = (closed + (open - closed) * .3f).coerceIn(.2f, .45f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pinch_close", close).putFloat("pinch_open", (close + .16f).coerceAtMost(.7f)).apply()
    }

    /**
     * Where hands cut through the VR content: how much wider than the fingers the cut is and how
     * far it is shifted (head-space tangent units), set in Settings → Калибровка рук.
     */
    data class Mask(val grow: Float, val dx: Float, val dy: Float)

    fun mask(context: Context): Mask {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Mask(prefs.getFloat("mask_grow", 1.1f), prefs.getFloat("mask_dx", 0f), prefs.getFloat("mask_dy", 0f))
    }

    fun saveMask(context: Context, mask: Mask) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("mask_grow", mask.grow.coerceIn(.7f, 2f))
            .putFloat("mask_dx", mask.dx.coerceIn(-.3f, .3f))
            .putFloat("mask_dy", mask.dy.coerceIn(-.3f, .3f))
            .apply()
    }

    /** A pinch latch tuned to this user's fingers. */
    fun latch(context: Context): HandGestures.PinchLatch {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HandGestures.PinchLatch(prefs.getFloat("pinch_close", .30f), prefs.getFloat("pinch_open", .48f))
    }
}
