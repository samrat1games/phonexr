package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Persona calls between PhoneXR accounts. Everyone signed in is "online" in a lobby (Supabase
 * Realtime presence). A call is a private channel where both sides send, 20 times a second, their
 * voice (IMA ADPCM), how their mouth moves and where their hands are; the Personas themselves are
 * exchanged once at the start. No video leaves the headset — only the Persona is shown.
 */
object Calls {
    enum class State { OFFLINE, IDLE, CALLING, RINGING, IN_CALL }

    data class Contact(val id: String, val name: String)

    @Volatile var state = State.OFFLINE
        private set
    @Volatile var online: List<Contact> = emptyList()
        private set
    @Volatile var peer: Contact? = null
        private set
    @Volatile var message: String? = null
    @Volatile var muted = false

    // What the other side sends.
    @Volatile var remoteFace: Persona.Face? = null
        private set
    @Volatile var remoteMouth = 0f
        private set
    @Volatile var remoteRound = .5f
        private set
    @Volatile var remoteTalking = false
        private set
    /** Remote hands: 21 (x, y) points each, camera image coordinates 0..1. */
    @Volatile var remoteHands: List<FloatArray> = emptyList()
        private set
    /** Low-rate camera texture for the hand silhouettes; full edition only. */
    @Volatile var remoteHandImage: Bitmap? = null
        private set

    /** Our own hands (x, y, z per landmark), set by the VR home. */
    @Volatile var localHands: () -> List<FloatArray> = { emptyList() }
    @Volatile var localHandImage: () -> Bitmap? = { null }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private var context: Context? = null
    private var me: Account.User? = null
    private var realtime: Realtime? = null
    private var room: String? = null
    private var voice: Voice? = null
    private var sender: ScheduledExecutorService? = null
    private var track: AudioTrack? = null
    private val encoder = Adpcm()
    private var decoder = Adpcm()
    private val pending = ByteArrayOutputStream()
    private val chunks = HashMap<Int, String>()
    private var chunkPoints: String? = null
    private var greeted = false
    private var lastHandImageAt = 0L

    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)
    private fun changed() = listeners.forEach { it() }

    /** Goes online if signed in. */
    @Synchronized
    fun start(context: Context) {
        if (realtime != null) return
        this.context = context.applicationContext
        val token = Account.token(context) ?: run { state = State.OFFLINE; changed(); return }
        val user = Account.current(context) ?: return
        me = user
        realtime = Realtime(token, object : Realtime.Listener {
            override fun onConnected() {
                realtime?.join(LOBBY, user.id)
                realtime?.track(LOBBY, JSONObject().put("id", user.id).put("name", user.name))
                state = State.IDLE
                changed()
            }

            override fun onBroadcast(topic: String, event: String, payload: JSONObject) = received(topic, event, payload)

            override fun onPresence(topic: String, members: Map<String, JSONObject>) {
                if (topic != LOBBY) return
                online = members.values.map { Contact(it.optString("id"), it.optString("name")) }
                    .filter { it.id.isNotEmpty() && it.id != user.id }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                changed()
            }

            override fun onClosed() {
                endCall()
                state = State.OFFLINE
                realtime = null
                changed()
            }
        }).also { it.connect() }
    }

    @Synchronized
    fun stop() {
        hangUp()
        realtime?.close()
        realtime = null
        state = State.OFFLINE
        online = emptyList()
        changed()
    }

    fun call(contact: Contact) {
        val user = me ?: return
        if (state != State.IDLE) return
        val id = "call-" + UUID.randomUUID().toString().take(12)
        room = id
        peer = contact
        state = State.CALLING
        message = null
        realtime?.broadcast(LOBBY, "invite", JSONObject().put("to", contact.id).put("from", user.id).put("name", user.name).put("room", id))
        changed()
    }

    fun accept() {
        val user = me ?: return
        val caller = peer ?: return
        if (state != State.RINGING) return
        realtime?.broadcast(LOBBY, "accept", JSONObject().put("to", caller.id).put("from", user.id).put("room", room))
        enterCall()
    }

    fun decline() {
        val user = me ?: return
        val caller = peer ?: return
        realtime?.broadcast(LOBBY, "decline", JSONObject().put("to", caller.id).put("from", user.id).put("room", room))
        reset("Звонок отклонён")
    }

    fun hangUp() {
        when (state) {
            State.IN_CALL -> room?.let { realtime?.broadcast(it, "bye", JSONObject()) }
            State.CALLING -> peer?.let { realtime?.broadcast(LOBBY, "cancel", JSONObject().put("to", it.id).put("room", room)) }
            State.RINGING -> { decline(); return }
            else -> return
        }
        reset("Звонок завершён")
    }

    private fun reset(text: String?) {
        endCall()
        room = null
        peer = null
        message = text
        if (realtime != null) state = State.IDLE
        changed()
    }

    private fun received(topic: String, event: String, payload: JSONObject) {
        val user = me ?: return
        if (topic == LOBBY) {
            if (payload.optString("to") != user.id) return
            when (event) {
                "invite" -> if (state == State.IDLE) {
                    peer = Contact(payload.optString("from"), payload.optString("name"))
                    room = payload.optString("room")
                    state = State.RINGING
                    changed()
                } else {
                    realtime?.broadcast(LOBBY, "busy", JSONObject().put("to", payload.optString("from")).put("room", payload.optString("room")))
                }
                "accept" -> if (state == State.CALLING && payload.optString("room") == room) enterCall()
                "decline" -> if (payload.optString("room") == room) reset("${peer?.name ?: "Собеседник"} отклонил звонок")
                "busy" -> if (payload.optString("room") == room) reset("${peer?.name ?: "Собеседник"} занят")
                "cancel" -> if (payload.optString("room") == room && state == State.RINGING) reset("Пропущенный звонок")
            }
            return
        }
        if (topic != room) return
        when (event) {
            "hi" -> {
                sendPersona()
                if (!greeted) { greeted = true; realtime?.broadcast(topic, "hi", JSONObject()) }
            }
            "persona" -> receivePersona(payload)
            "s" -> {
                remoteMouth = payload.optDouble("m", 0.0).toFloat()
                remoteRound = payload.optDouble("r", .5).toFloat()
                remoteTalking = payload.optBoolean("t")
                remoteHands = decodeHands(payload.optString("h"))
                payload.optString("hv").takeIf { it.isNotEmpty() }?.let { encoded ->
                    runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { remoteHandImage = it }
                    }
                }
                payload.optString("a").takeIf { it.isNotEmpty() }?.let { play(Base64.decode(it, Base64.NO_WRAP)) }
            }
            "bye" -> reset("${peer?.name ?: "Собеседник"} завершил звонок")
        }
    }

    private fun enterCall() {
        val topic = room ?: return
        val ctx = context ?: return
        state = State.IN_CALL
        remoteFace = null
        greeted = false
        chunks.clear()
        decoder = Adpcm()
        realtime?.join(topic)
        realtime?.broadcast(topic, "hi", JSONObject())
        // Our voice: the same cleaned microphone the Persona's mouth uses.
        val v = VoiceHub.acquire(ctx)
        voice = v
        v.onPcm = { frame, count -> synchronized(pending) { pending.write(encoder.encode(frame, count)) } }
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(16_000 * 2 / 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        sender = Executors.newSingleThreadScheduledExecutor().also { it.scheduleAtFixedRate(::sendState, 50, 50, TimeUnit.MILLISECONDS) }
        changed()
    }

    private fun endCall() {
        sender?.shutdownNow()
        sender = null
        voice?.onPcm = null
        if (voice != null) VoiceHub.release()
        voice = null
        track?.runCatching { stop(); release() }
        track = null
        context?.let { (it.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL }
        room?.let { realtime?.leave(it) }
        remoteFace = null
        remoteHands = emptyList()
        remoteHandImage = null
        remoteMouth = 0f
        synchronized(pending) { pending.reset() }
    }

    /** 20 times a second: mouth, hands and the voice since the last message. */
    private fun sendState() {
        val topic = room ?: return
        val v = voice
        val audio = synchronized(pending) { pending.toByteArray().also { pending.reset() } }
        val hands = if (BuildConfig.LITE) emptyList() else localHands()
        val state = JSONObject()
            .put("m", if (muted) 0.0 else (v?.mouthOpen ?: 0f).toDouble())
            .put("r", (v?.mouthRound ?: .5f).toDouble())
            .put("t", !muted && v?.talking == true)
            .put("h", encodeHands(hands))
        val now = android.os.SystemClock.elapsedRealtime()
        if (!BuildConfig.LITE && hands.isNotEmpty() && now - lastHandImageAt >= 200L) {
            lastHandImageAt = now
            localHandImage()?.let { image ->
                val bytes = ByteArrayOutputStream().also {
                    @Suppress("DEPRECATION")
                    image.compress(Bitmap.CompressFormat.WEBP, 38, it)
                }.toByteArray()
                image.recycle()
                state.put("hv", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        if (!muted && audio.isNotEmpty()) state.put("a", Base64.encodeToString(audio, Base64.NO_WRAP))
        realtime?.broadcast(topic, "s", state)
    }

    private fun play(data: ByteArray) {
        val samples = decoder.decode(data)
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    // ---------------------------------------------------------------- Personas

    /** Our Persona, compressed, in small pieces (realtime messages must stay small). */
    private fun sendPersona() {
        val ctx = context ?: return
        val topic = room ?: return
        val face = Persona.load(ctx) ?: return
        val bytes = ByteArrayOutputStream().also {
            @Suppress("DEPRECATION")
            face.image.compress(Bitmap.CompressFormat.WEBP, 80, it)
        }.toByteArray()
        val text = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val pieces = text.chunked(CHUNK)
        val points = JSONArray().apply { face.points.forEach { put(Math.round(it * 10) / 10.0) } }.toString()
        pieces.forEachIndexed { i, piece ->
            val message = JSONObject().put("i", i).put("n", pieces.size).put("d", piece)
            if (i == 0) message.put("p", points)
            realtime?.broadcast(topic, "persona", message)
        }
    }

    private fun receivePersona(payload: JSONObject) {
        val i = payload.optInt("i"); val n = payload.optInt("n")
        chunks[i] = payload.optString("d")
        if (i == 0) chunkPoints = payload.optString("p")
        if (chunks.size < n || chunkPoints == null) return
        val bytes = Base64.decode((0 until n).joinToString("") { chunks[it] ?: "" }, Base64.NO_WRAP)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val array = JSONArray(chunkPoints)
        remoteFace = Persona.Face(image, FloatArray(array.length()) { array.getDouble(it).toFloat() })
        chunks.clear()
        changed()
    }

    // ---------------------------------------------------------------- Hands

    private fun encodeHands(hands: List<FloatArray>): String {
        val out = ByteArray(1 + hands.size.coerceAtMost(2) * 42)
        out[0] = hands.size.coerceAtMost(2).toByte()
        hands.take(2).forEachIndexed { h, points ->
            for (k in 0 until 21) {
                out[1 + h * 42 + k * 2] = (points[k * 3].coerceIn(0f, 1f) * 255).toInt().toByte()
                out[1 + h * 42 + k * 2 + 1] = (points[k * 3 + 1].coerceIn(0f, 1f) * 255).toInt().toByte()
            }
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decodeHands(text: String): List<FloatArray> {
        if (text.isEmpty()) return emptyList()
        val data = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull() ?: return emptyList()
        val count = data.getOrNull(0)?.toInt() ?: return emptyList()
        return (0 until count.coerceAtMost(2)).mapNotNull { h ->
            if (data.size < 1 + (h + 1) * 42) return@mapNotNull null
            FloatArray(42) { (data[1 + h * 42 + it].toInt() and 0xff) / 255f }
        }
    }

    private const val LOBBY = "phonexr-lobby"
    private const val CHUNK = 24_000

    @Suppress("unused")
    private fun cacheFile(context: Context) = File(context.cacheDir, "remote_persona.webp")
}
