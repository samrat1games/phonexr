package com.samrat.cardboardhands

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The user's voice for the Persona's mouth.
 *
 * Noise is removed twice: the platform's voice-call pipeline (noise suppression) cleans the
 * microphone, and a neural network (YAMNet, 521 sound classes) decides whether the sound is
 * speech at all — a door, music or a keyboard do not move the mouth. While speech is on, the mouth
 * follows the loudness of each syllable; bright sounds (и, е) spread it, dark ones (о, у) round it.
 */
class Voice(private val context: Context) {
    /** 0 closed .. 1 wide open. */
    @Volatile var mouthOpen = 0f
        private set
    /** 0 spread (и) .. 1 round (о, у). */
    @Volatile var mouthRound = .5f
        private set
    @Volatile var talking = false
        private set
    /** Every 20 ms of cleaned microphone sound, e.g. for a call. */
    @Volatile var onPcm: ((ShortArray, Int) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        // Lite has no sound classifier: the model is not in it and a budget phone cannot spare it.
        if (BuildConfig.LITE) return
        if (running) return
        running = true
        thread = kotlin.concurrent.thread(name = "PhoneXR voice") {
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 8)
                )
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "No microphone (permission?)")
                running = false
                return@thread
            }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(record.audioSessionId)?.enabled = true }
            val classifier = runCatching {
                AudioClassifier.createFromOptions(
                    context,
                    AudioClassifier.AudioClassifierOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                        .setRunningMode(RunningMode.AUDIO_CLIPS)
                        .setMaxResults(8)
                        .build()
                )
            }.onFailure { Log.w(TAG, "YAMNet failed; loudness only", it) }.getOrNull()
            record.startRecording()
            loop(record, classifier)
            record.stop()
            record.release()
            classifier?.close()
        }
    }

    fun stop() {
        running = false
        thread = null
        mouthOpen = 0f
        talking = false
    }

    private fun loop(record: AudioRecord, classifier: AudioClassifier?) {
        val frame = ShortArray(FRAME)
        val window = FloatArray(WINDOW)
        var filled = 0
        var sinceClassify = 0
        var noiseFloor = 40f
        var speech = 0f
        var level = 0f
        while (running) {
            val read = record.read(frame, 0, FRAME)
            if (read <= 0) continue
            onPcm?.invoke(frame, read)
            // Slide the one-second window YAMNet looks at.
            System.arraycopy(window, read, window, 0, WINDOW - read)
            for (i in 0 until read) window[WINDOW - read + i] = frame[i] / 32768f
            filled = minOf(WINDOW, filled + read)

            var energy = 0.0
            var crossings = 0
            for (i in 0 until read) {
                energy += frame[i].toDouble() * frame[i]
                if (i > 0 && (frame[i] >= 0) != (frame[i - 1] >= 0)) crossings++
            }
            val db = 20f * log10((sqrt(energy / read) + 1.0).toFloat())
            // The floor follows quiet moments quickly and loud ones very slowly.
            noiseFloor = if (db < noiseFloor) noiseFloor + (db - noiseFloor) * .2f else noiseFloor + (db - noiseFloor) * .002f
            val loud = ((db - noiseFloor - 6f) / 22f).coerceIn(0f, 1f)

            sinceClassify += read
            if (classifier != null && filled == WINDOW && sinceClassify >= RATE / 4) {
                sinceClassify = 0
                speech = speechScore(classifier, window, speech)
            } else if (classifier == null) {
                speech = if (loud > .15f) 1f else speech * .9f
            }
            talking = if (talking) speech > .25f else speech > .45f
            val target = if (talking) loud else 0f
            // Syllables: open fast, close a little slower.
            level += (target - level) * if (target > level) .6f else .35f
            mouthOpen = level
            val brightness = (crossings.toFloat() / read * 8f).coerceIn(0f, 1f)
            mouthRound += ((1f - brightness) - mouthRound) * .2f
        }
    }

    private fun speechScore(classifier: AudioClassifier, window: FloatArray, previous: Float): Float {
        val result = runCatching {
            val data = AudioData.create(AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(RATE.toFloat()).build(), WINDOW)
            data.load(window)
            classifier.classify(data)
        }.getOrNull() ?: return previous
        val categories = result.classificationResults().lastOrNull()?.classifications()?.firstOrNull()?.categories().orEmpty()
        val score = categories.filter { it.categoryName() in SPEECH }.maxOfOrNull { it.score() } ?: 0f
        return previous + (score - previous) * .6f
    }

    private companion object {
        const val TAG = "PhoneXR-Voice"
        const val RATE = 16_000
        /** 20 ms of sound per loudness step. */
        const val FRAME = 320
        /** YAMNet listens to 0.975 s at a time. */
        const val WINDOW = 15_600
        val SPEECH = setOf("Speech", "Conversation", "Narration, monologue", "Male speech, man speaking",
            "Female speech, woman speaking", "Child speech, kid speaking", "Singing")
    }
}

/** One microphone for everyone who needs the voice (the Persona window and calls). */
object VoiceHub {
    private var voice: Voice? = null
    private var users = 0

    @Synchronized
    fun acquire(context: Context): Voice {
        users++
        return voice ?: Voice(context.applicationContext).also { voice = it; it.start() }
    }

    @Synchronized
    fun release() {
        users--
        if (users <= 0) {
            users = 0
            voice?.stop()
            voice = null
        }
    }
}
