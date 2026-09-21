package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The depth network behind 3D memories: MiDaS v2.1 small, which looks at one ordinary photo and
 * says how far every point of it is. It is 63 MB, far too much to carry inside PhoneXR, so it is
 * downloaded once on request and kept in the app's own files.
 */
object DepthModel {
    /** Everything the network sees and answers is this many pixels across. */
    const val SIZE = 256

    private const val FILE = "midas_v21_small.tflite"
    private const val SOURCE = "https://tfhub.dev/intel/lite-model/midas/v2_1_small/1/lite/1?lite-format=tflite"
    /** A model smaller than this is a broken download, not a model. */
    private const val LEAST_BYTES = 50_000_000L
    const val MEGABYTES = 63

    private var interpreter: Interpreter? = null

    fun file(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, FILE)

    fun installed(context: Context) = file(context).length() >= LEAST_BYTES

    /**
     * Downloads the network, reporting 0..1 as it goes. It is tried from the PhoneXR store first,
     * so a copy can live next to the games, and from where it is published otherwise.
     */
    fun download(context: Context, onProgress: (Float) -> Unit): File {
        val target = file(context)
        val partial = File(target.parentFile, "$FILE.part")
        partial.delete()
        val sources = listOfNotNull(storeUrl(), SOURCE)
        var failure: Throwable? = null
        for (source in sources) {
            val result = runCatching { fetch(source, partial, onProgress) }
            if (result.isSuccess && partial.length() >= LEAST_BYTES) {
                partial.renameTo(target)
                return target
            }
            failure = result.exceptionOrNull()
            partial.delete()
        }
        throw failure ?: IllegalStateException("Нейросеть глубины не скачалась")
    }

    /** A copy in the PhoneXR store, when the store has one. */
    private fun storeUrl(): String? = runCatching {
        GameStore.readText("models/$FILE.json")
            .let { org.json.JSONObject(it).optString("url").takeIf { url -> url.startsWith("http") } }
    }.getOrNull()

    private fun fetch(source: String, target: File, onProgress: (Float) -> Unit) {
        var url = URL(source)
        repeat(6) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: throw IllegalStateException("Пустая переадресация")
                connection.disconnect()
                url = URL(url, next)
                return@repeat
            }
            if (code != 200) {
                connection.disconnect()
                throw IllegalStateException("Сервер ответил $code")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            connection.disconnect()
            onProgress(1f)
            return
        }
        throw IllegalStateException("Слишком много переадресаций")
    }

    /**
     * How far everything in [bitmap] is: [SIZE] × [SIZE] values from 0 (farthest) to 1 (nearest),
     * row by row. Null when the network is not downloaded yet or will not load.
     */
    @Synchronized
    fun depth(context: Context, bitmap: Bitmap): FloatArray? {
        val model = interpreter ?: load(context) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            // The network takes plain colours from 0 to 1.
            input.putFloat(((pixel shr 16) and 0xff) / 255f)
            input.putFloat(((pixel shr 8) and 0xff) / 255f)
            input.putFloat((pixel and 0xff) / 255f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        runCatching { model.run(input, output) }.onFailure { return null }
        output.rewind()
        val raw = FloatArray(SIZE * SIZE) { output.getFloat() }
        // The answer is relative: only the order of the values means anything, so it is stretched
        // to 0..1 with the nearest point at 1.
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (value in raw) {
            if (value < min) min = value
            if (value > max) max = value
        }
        val span = (max - min).takeIf { it > 1e-3f } ?: return null
        return FloatArray(raw.size) { (raw[it] - min) / span }
    }

    private fun load(context: Context): Interpreter? {
        val model = file(context).takeIf { it.length() >= LEAST_BYTES } ?: return null
        return runCatching {
            Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
        }.getOrNull()?.also { interpreter = it }
    }

    /** Lets the network go; it holds tens of megabytes of weights. */
    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
