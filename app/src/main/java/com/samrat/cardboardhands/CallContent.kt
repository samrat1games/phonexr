package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.concurrent.thread

/**
 * The "Звонки" app: who is online, calling, and the call itself — the other person's Persona
 * talking with their voice, their hands in front of them, and ours in a small corner.
 */
class CallContent(private val context: Context) : VrWindow.Content {
    override val pixelWidth = 1400
    override val pixelHeight = 1000
    override val external = false
    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handsLayer = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val handsCanvas = Canvas(handsLayer)
    @Volatile private var fresh = true
    @Volatile private var running = true
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private val remoteBlink = Blinker()
    private val selfBlink = Blinker()
    private var remoteRenderer: PersonaRenderer? = null
    private var remoteFaceShown: Persona.Face? = null
    private val selfRenderer = if (BuildConfig.LITE) null else Persona.load(context)?.let { PersonaRenderer(it) }
    private var voice: Voice? = null
    private val listener: () -> Unit = { fresh = true }
    /** People added in the Friends tab; shown first, online or not. */
    @Volatile private var friends: List<Friends.Person> = emptyList()

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        Calls.listen(listener)
        thread { Calls.start(context) }
        thread { friends = runCatching { Friends.mine(context) }.getOrDefault(emptyList()); fresh = true }
        voice = VoiceHub.acquire(context)
        thread(name = "PhoneXR calls") {
            while (running) {
                draw()
                Thread.sleep(if (Calls.state == Calls.State.IN_CALL) 50 else 200)
            }
        }
        onReady()
    }

    override fun takeBitmap(): Bitmap? = if (fresh) synchronized(this) { fresh = false; bitmap } else null

    override fun toolbarTitle() = when (Calls.state) {
        Calls.State.IN_CALL -> Calls.peer?.name ?: "Звонок"
        else -> tr("Звонки")
    }

    override val toolbarVersion get() = Calls.state.ordinal

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val x = u * pixelWidth; val y = v * pixelHeight
        val hit = synchronized(this) { buttons.firstOrNull { it.first.contains(x, y) }?.second } ?: return
        thread { hit(); draw() }
    }

    override fun release() {
        running = false
        Calls.unlisten(listener)
        if (voice != null) VoiceHub.release()
        voice = null
    }

    @Synchronized
    private fun draw() {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.argb(215, 32, 32, 38)
        canvas.drawRoundRect(RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()), 60f, 60f, paint)
        when (Calls.state) {
            Calls.State.OFFLINE -> offline()
            Calls.State.IDLE -> contacts()
            Calls.State.CALLING -> {
                center("Звоним ${Calls.peer?.name ?: ""}…", 440f, 56f, bold = true)
                button(RectF(500f, 700f, 900f, 790f), tr("Отменить"), RED) { Calls.hangUp() }
            }
            Calls.State.RINGING -> {
                center("${Calls.peer?.name ?: "Кто-то"} звонит", 420f, 60f, bold = true)
                if (!BuildConfig.LITE) center("Звонок с персоной", 490f, 36f, color = GREY)
                button(RectF(300f, 680f, 660f, 780f), tr("Отклонить"), RED) { Calls.decline() }
                button(RectF(740f, 680f, 1100f, 780f), tr("Принять"), GREEN) { Calls.accept() }
            }
            Calls.State.IN_CALL -> inCall()
        }
        fresh = true
    }

    private fun offline() {
        center(tr("Звонки"), 200f, 64f, bold = true)
        val signedIn = Account.current(context) != null
        center(if (signedIn) "Подключение…" else "Войдите в аккаунт PhoneXR", 440f, 44f)
        if (!signedIn) center("На телефоне: PhoneXR → Настройки → Аккаунт", 510f, 34f, color = GREY)
        else button(RectF(500f, 620f, 900f, 710f), "Повторить", BLUE) { Calls.stop(); Calls.start(context) }
    }

    private fun contacts() {
        text(tr("Звонки"), 60f, 110f, 64f, bold = true)
        text("Вы: ${Account.current(context)?.name ?: ""}", 60f, 170f, 34f, GREY)
        Calls.message?.let { text(it, 60f, 230f, 32f, Color.rgb(255, 180, 90)) }
        // Friends first (online ones can be called), then anyone else who is online.
        val online = Calls.online
        val friendIds = friends.map { it.id }.toSet()
        val rows = friends.map { friend ->
            Triple(Calls.Contact(friend.id, friend.name.ifBlank { friend.username }), online.any { it.id == friend.id }, "@${friend.username}")
        }.sortedByDescending { it.second } + online.filter { it.id !in friendIds }.map { Triple(it, true, null) }
        if (rows.isEmpty()) {
            center(tr("Сейчас никого нет в сети"), 520f, 42f, color = GREY)
            center("Добавьте друзей во вкладке «Друзья» на телефоне", 580f, 32f, color = GREY)
            return
        }
        rows.take(6).forEachIndexed { i, (contact, isOnline, username) ->
            val top = 270f + i * 120f
            paint.color = Color.argb(60, 255, 255, 255)
            canvas.drawRoundRect(RectF(60f, top, pixelWidth - 60f, top + 100f), 30f, 30f, paint)
            paint.color = if (isOnline) GREEN else Color.argb(120, 255, 255, 255)
            canvas.drawCircle(110f, top + 50f, 14f, paint)
            text(contact.name, 150f, top + 58f, 42f, if (isOnline) Color.WHITE else GREY)
            username?.let { text(it, 150f, top + 90f, 26f, GREY) }
            if (isOnline) button(RectF(pixelWidth - 360f, top + 12f, pixelWidth - 80f, top + 88f), tr("Позвонить"), GREEN) { Calls.call(contact) }
            else text(tr("Не в сети"), pixelWidth - 300f, top + 62f, 32f, GREY)
        }
    }

    private fun inCall() {
        if (BuildConfig.LITE) {
            // Lite never renders or transmits a face or hands: only the peer's name and call controls.
            center(Calls.peer?.name ?: tr("Звонок"), 410f, 68f, bold = true)
            if (Calls.remoteTalking) center("говорит", 475f, 30f, color = GREEN)
            button(RectF(360f, 880f, 680f, 970f), if (Calls.muted) "Микрофон выкл." else tr("Микрофон"), if (Calls.muted) RED else Color.argb(120, 255, 255, 255)) {
                Calls.muted = !Calls.muted
            }
            button(RectF(720f, 880f, 1040f, 970f), tr("Завершить"), RED) { Calls.hangUp() }
            return
        }
        // The other person's Persona, big in the middle.
        val face = Calls.remoteFace
        if (face != null && face !== remoteFaceShown) {
            remoteRenderer = PersonaRenderer(face)
            remoteFaceShown = face
        }
        val persona = remoteRenderer?.render(remoteBlink.value(), Calls.remoteMouth, Calls.remoteRound)
        val area = RectF(250f, 40f, 1150f, 940f)
        if (persona != null) canvas.drawBitmap(persona, null, area, paint)
        else center("Получаем персону…", 460f, 40f, color = GREY)
        // Their hands, see-through, where they hold them in front of their camera.
        val hands = Calls.remoteHands
        if (hands.isNotEmpty()) {
            handsLayer.eraseColor(Color.TRANSPARENT)
            val handImage = Calls.remoteHandImage
            val silhouette = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 164, 132) }
            for (points in hands) {
                val xs = FloatArray(21) { points[it * 2] * pixelWidth }
                val ys = FloatArray(21) { points[it * 2 + 1] * pixelHeight }
                val triangles = GhostHand.triangles(xs, ys, 0f)
                val path = Path()
                var i = 0
                while (i + 8 < triangles.size) {
                    path.moveTo(triangles[i], triangles[i + 1])
                    path.lineTo(triangles[i + 3], triangles[i + 4])
                    path.lineTo(triangles[i + 6], triangles[i + 7])
                    path.close()
                    i += 9
                }
                if (handImage != null) {
                    handsCanvas.save()
                    handsCanvas.clipPath(path)
                    handsCanvas.drawBitmap(handImage, null, RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()), paint)
                    handsCanvas.restore()
                } else {
                    handsCanvas.drawPath(path, silhouette)
                }
            }
            paint.alpha = 255
            canvas.drawBitmap(handsLayer, 0f, 0f, paint)
            paint.alpha = 255
        }
        text(Calls.peer?.name ?: "", 60f, 100f, 48f, bold = true)
        if (Calls.remoteTalking) text("говорит", 60f, 150f, 30f, GREEN)
        // Ourselves in the corner.
        selfRenderer?.let { renderer ->
            val v = voice
            val self = renderer.render(selfBlink.value(), if (Calls.muted) 0f else v?.mouthOpen ?: 0f, v?.mouthRound ?: .5f)
            paint.color = Color.argb(90, 255, 255, 255)
            val box = RectF(pixelWidth - 300f, 40f, pixelWidth - 40f, 300f)
            canvas.drawRoundRect(box, 30f, 30f, paint)
            canvas.drawBitmap(self, null, box, paint)
        }
        button(RectF(360f, 880f, 680f, 970f), if (Calls.muted) "Микрофон выкл." else tr("Микрофон"), if (Calls.muted) RED else Color.argb(120, 255, 255, 255)) {
            Calls.muted = !Calls.muted
        }
        button(RectF(720f, 880f, 1040f, 970f), tr("Завершить"), RED) { Calls.hangUp() }
    }

    private fun button(rect: RectF, label: String, color: Int, action: () -> Unit) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.color = Color.WHITE
        paint.textSize = 38f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), rect.centerY() + 13f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        buttons += rect to action
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
        paint.typeface = Typeface.DEFAULT
    }

    private fun center(value: String, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = false) {
        paint.textAlign = Paint.Align.CENTER
        text(value, pixelWidth / 2f, y, size, color, bold)
        paint.textAlign = Paint.Align.LEFT
    }

    private companion object {
        val GREEN = Color.rgb(48, 209, 88)
        val RED = Color.rgb(255, 69, 58)
        val BLUE = Color.rgb(10, 132, 255)
        val GREY = Color.rgb(170, 170, 178)
    }
}
