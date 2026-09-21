package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Persona: the user's face from one photo, shown like visionOS does — a soft, slightly frosted
 * portrait whose eyes blink and whose mouth moves with the user's speech.
 *
 * Building: MediaPipe finds 478 face points on the photo; the portrait is cropped around the face
 * (with shoulders), its background frosted, and the points are kept to warp the face later.
 */
object Persona {
    const val SIZE = 768
    private const val DIR = "persona"

    class Face(val image: Bitmap, val points: FloatArray) {
        /** Triangles over the whole portrait (face points plus a frame of fixed border points). */
        val triangles: IntArray by lazy { Delaunay.triangulate(allPoints) }
        val allPoints: FloatArray by lazy { points + border() }

        private fun border(): FloatArray {
            val out = ArrayList<Float>()
            val steps = 8
            for (i in 0..steps) {
                val t = i * SIZE.toFloat() / steps
                out += listOf(t, 0f, t, SIZE.toFloat())
                if (i in 1 until steps) out += listOf(0f, t, SIZE.toFloat(), t)
            }
            return out.toFloatArray()
        }
    }

    fun exists(context: Context) = File(context.filesDir, "$DIR/face.png").exists()

    fun delete(context: Context) = File(context.filesDir, DIR).deleteRecursively()

    fun load(context: Context): Face? = runCatching {
        val folder = File(context.filesDir, DIR)
        val image = BitmapFactory.decodeFile(File(folder, "face.png").path) ?: return null
        val array = JSONArray(File(folder, "points.json").readText())
        Face(image, FloatArray(array.length()) { array.getDouble(it).toFloat() })
    }.getOrNull()

    /** Studies the photo and saves the portrait; null with a reason when no face is found. */
    fun build(context: Context, photo: Uri): String? {
        val source = decode(context, photo) ?: return "Фото не открывается"
        return build(context, source)
    }

    /** Saves every measured angle from the guided scan, then builds the animated front Persona. */
    fun buildScan(context: Context, views: Map<String, Bitmap>): String? {
        val front = views["front"] ?: return "Фронтальный ракурс не отсканирован"
        val folder = File(context.filesDir, DIR).apply { mkdirs() }
        views.forEach { (name, bitmap) ->
            File(folder, "scan_$name.webp").outputStream().use {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 88, it)
            }
        }
        val result = build(context, front.copy(Bitmap.Config.ARGB_8888, false))
        views.values.forEach { if (!it.isRecycled) it.recycle() }
        return result
    }

    /**
     * Makes the Persona from a picture (gallery or the front camera): the person is cut out of the
     * background by a segmentation network, so it floats on its own like in visionOS.
     */
    fun build(context: Context, source: Bitmap): String? {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .build()
        val landmarks = FaceLandmarker.createFromOptions(context, options).use { landmarker ->
            landmarker.detect(BitmapImageBuilder(source).build()).faceLandmarks().firstOrNull()
        } ?: return "Лицо не найдено: нужно фото, где лицо видно спереди"

        val xs = FloatArray(landmarks.size) { landmarks[it].x() * source.width }
        val ys = FloatArray(landmarks.size) { landmarks[it].y() * source.height }
        val faceHeight = ys.max() - ys.min()
        // Head and shoulders: the face takes a bit under half of the square, a little above centre.
        val side = faceHeight * 2.3f
        val cx = (xs.min() + xs.max()) / 2
        val cy = ys.min() + faceHeight * .62f
        val crop = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        val scale = SIZE / side

        val portrait = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(portrait)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cropSrc = android.graphics.Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())
        val full = android.graphics.Rect(0, 0, SIZE, SIZE)
        canvas.drawBitmap(source, cropSrc, full, paint)
        // Only the person: the segmentation mask becomes the alpha, softened at the edge.
        val mask = personMask(context, portrait)
        if (mask != null) {
            val pixels = IntArray(SIZE * SIZE)
            portrait.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (i in pixels.indices) {
                val alpha = ((mask[i] - .35f) / .4f).coerceIn(0f, 1f)
                pixels[i] = (pixels[i] and 0x00ffffff) or ((alpha * 255).toInt() shl 24)
            }
            portrait.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        }
        // Shoulders fade out at the bottom, like a bust.
        val fade = Paint().apply {
            shader = android.graphics.LinearGradient(0f, SIZE * .78f, 0f, SIZE.toFloat(), Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), fade)
        source.recycle()

        val points = FloatArray(xs.size * 2) { i -> if (i % 2 == 0) (xs[i / 2] - crop.left) * scale else (ys[i / 2] - crop.top) * scale }
        val folder = File(context.filesDir, DIR).apply { mkdirs() }
        File(folder, "face.png").outputStream().use { portrait.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(folder, "points.json").writeText(JSONArray().apply { points.forEach { put(it.toDouble()) } }.toString())
        portrait.recycle()
        return null
    }

    /** Person probability per pixel (SIZE × SIZE), from MediaPipe's selfie segmenter. */
    private fun personMask(context: Context, image: Bitmap): FloatArray? = runCatching {
        val options = com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.createFromOptions(context, options).use { segmenter ->
            val result = segmenter.segment(BitmapImageBuilder(image).build())
            val masks = result.confidenceMasks().orElse(null) ?: return null
            val mask = masks.last()
            val buffer = com.google.mediapipe.framework.image.ByteBufferExtractor.extract(mask).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val w = mask.width; val h = mask.height
            FloatArray(SIZE * SIZE) { i ->
                val x = (i % SIZE) * w / SIZE; val y = (i / SIZE) * h / SIZE
                buffer.get(y * w + x)
            }
        }
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    // MediaPipe face mesh points used for the animation.
    val UPPER_LID_LEFT = intArrayOf(246, 161, 160, 159, 158, 157, 173)
    val LOWER_LID_LEFT = intArrayOf(7, 163, 144, 145, 153, 154, 155)
    val UPPER_LID_RIGHT = intArrayOf(466, 388, 387, 386, 385, 384, 398)
    val LOWER_LID_RIGHT = intArrayOf(249, 390, 373, 374, 380, 381, 382)
    /** Inner lip ring, clockwise from the left corner: the mouth opening. */
    val INNER_LIPS = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
    val LOWER_INNER = setOf(324, 318, 402, 317, 14, 87, 178, 88, 95)
    const val CHIN = 152
    const val UPPER_LIP = 13
    const val LOWER_LIP = 14
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291
    val LEFT_IRIS = 468..472
    val RIGHT_IRIS = 473..477
}

/** Bowyer–Watson Delaunay triangulation for a few hundred points. */
object Delaunay {
    fun triangulate(points: FloatArray): IntArray {
        val n = points.size / 2
        val minX = (0 until n).minOf { points[it * 2] }; val maxX = (0 until n).maxOf { points[it * 2] }
        val minY = (0 until n).minOf { points[it * 2 + 1] }; val maxY = (0 until n).maxOf { points[it * 2 + 1] }
        val d = max(maxX - minX, maxY - minY) * 20
        val mx = (minX + maxX) / 2; val my = (minY + maxY) / 2
        val px = DoubleArray(n + 3); val py = DoubleArray(n + 3)
        for (i in 0 until n) { px[i] = points[i * 2].toDouble(); py[i] = points[i * 2 + 1].toDouble() }
        px[n] = (mx - d).toDouble(); py[n] = (my - d).toDouble()
        px[n + 1] = mx.toDouble(); py[n + 1] = (my + d).toDouble()
        px[n + 2] = (mx + d).toDouble(); py[n + 2] = (my - d).toDouble()
        val triangles = ArrayList<IntArray>()
        triangles += intArrayOf(n, n + 1, n + 2)
        for (i in 0 until n) {
            val bad = triangles.filter { inCircle(it, px[i], py[i], px, py) }
            val edges = HashMap<Long, IntArray>()
            for (t in bad) for (e in 0..2) {
                val a = t[e]; val b = t[(e + 1) % 3]
                val key = min(a, b).toLong() * 100_000 + max(a, b)
                if (edges.remove(key) == null) edges[key] = intArrayOf(a, b)
            }
            triangles.removeAll(bad.toSet())
            for (edge in edges.values) triangles += intArrayOf(edge[0], edge[1], i)
        }
        return triangles.filter { t -> t.all { it < n } }.flatMap { it.toList() }.toIntArray()
    }

    private fun inCircle(t: IntArray, x: Double, y: Double, px: DoubleArray, py: DoubleArray): Boolean {
        val ax = px[t[0]] - x; val ay = py[t[0]] - y
        val bx = px[t[1]] - x; val by = py[t[1]] - y
        val cx = px[t[2]] - x; val cy = py[t[2]] - y
        val det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) + (cx * cx + cy * cy) * (ax * by - bx * ay)
        val orientation = (px[t[1]] - px[t[0]]) * (py[t[2]] - py[t[0]]) - (py[t[1]] - py[t[0]]) * (px[t[2]] - px[t[0]])
        return if (orientation > 0) det > 0 else det < 0
    }
}

/**
 * The live portrait window: blinks every few seconds and speaks with the user's voice. The face is
 * warped on the CPU (drawVertices with the portrait as texture) and handed to the VR home as a bitmap.
 */
/** Blinks like people do: every 2.5–6 s, sometimes twice quickly. */
class Blinker {
    private val random = java.util.Random()
    private var nextBlink = System.nanoTime() + 2_000_000_000L
    private var blinkStart = 0L

    /** How closed the eyes are right now, 0..1. */
    fun value(now: Long = System.nanoTime()): Float {
        if (now > nextBlink) {
            blinkStart = now
            nextBlink = now + (2_500_000_000L + random.nextInt(3_500) * 1_000_000L) +
                if (random.nextInt(6) == 0) -2_200_000_000L else 0L
        }
        val t = (now - blinkStart) / 1e9f
        return if (t < .16f) (if (t < .07f) t / .07f else 1f - (t - .07f) / .09f).coerceIn(0f, 1f) else 0f
    }
}

/**
 * Draws a Persona with closed-to-open eyes and mouth: the face is warped on the CPU (drawVertices
 * with the portrait as texture). Used by the Persona window and by calls.
 */
class PersonaRenderer(private val face: Persona.Face) {
    val frame: Bitmap = Bitmap.createBitmap(Persona.SIZE, Persona.SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frame)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun render(blink: Float, open: Float, round: Float): Bitmap {
        val f = face
        if (shaderPaint.shader == null) shaderPaint.shader = BitmapShader(f.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val base = f.allPoints
        val moved = base.copyOf()
        val p = f.points
        fun x(i: Int) = p[i * 2]
        fun y(i: Int) = p[i * 2 + 1]
        // Blink: upper lids slide down to the lower ones.
        for ((upper, lower) in listOf(Persona.UPPER_LID_LEFT to Persona.LOWER_LID_LEFT, Persona.UPPER_LID_RIGHT to Persona.LOWER_LID_RIGHT)) {
            for (k in upper.indices) {
                val u = upper[k]; val l = lower[k]
                moved[u * 2] = x(u) + (x(l) - x(u)) * blink * .92f
                moved[u * 2 + 1] = y(u) + (y(l) - y(u)) * blink * .92f
            }
        }
        // Irises hide under the closing lids.
        for (i in Persona.LEFT_IRIS + Persona.RIGHT_IRIS) moved[i * 2 + 1] = y(i) + blink * 2f
        // Mouth: the jaw drops (lower lip, chin, lower face), corners pull in for round vowels.
        val mouthWidth = x(Persona.MOUTH_RIGHT) - x(Persona.MOUTH_LEFT)
        // A natural talking mouth opens little: at most about a third of its width.
        val drop = open * mouthWidth * .3f
        val mouthX = (x(Persona.MOUTH_LEFT) + x(Persona.MOUTH_RIGHT)) / 2
        val mouthY = (y(Persona.UPPER_LIP) + y(Persona.LOWER_LIP)) / 2
        val chinY = y(Persona.CHIN)
        val jaw = (chinY - mouthY).coerceAtLeast(1f)
        for (i in 0 until p.size / 2) {
            val dy = y(i) - mouthY
            val dx = kotlin.math.abs(x(i) - mouthX)
            if (dy <= 0f && i !in Persona.LOWER_INNER) continue
            val across = (1f - dx / (mouthWidth * 1.1f)).coerceIn(0f, 1f)
            val down = if (dy <= jaw) 1f else (1f - (dy - jaw) / (jaw * .6f)).coerceIn(0f, 1f)
            // The lower lip moves fully, the chin about half as much (the jaw hinges far back).
            val weight = if (i in Persona.LOWER_INNER) 1f else across * down * (if (dy <= jaw * .35f) .9f else .55f)
            moved[i * 2 + 1] = y(i) + drop * weight
        }
        val pinch = (round - .5f) * open * mouthWidth * .18f
        for (corner in intArrayOf(Persona.MOUTH_LEFT, 78)) moved[corner * 2] = x(corner) + pinch
        for (corner in intArrayOf(Persona.MOUTH_RIGHT, 308)) moved[corner * 2] = x(corner) - pinch

        frame.eraseColor(Color.TRANSPARENT)
        canvas.drawBitmap(f.image, 0f, 0f, null)
        // The open mouth: dark inside, a hint of upper teeth.
        if (open > .03f) {
            val path = Path()
            Persona.INNER_LIPS.forEachIndexed { k, i -> if (k == 0) path.moveTo(moved[i * 2], moved[i * 2 + 1]) else path.lineTo(moved[i * 2], moved[i * 2 + 1]) }
            path.close()
            // Warm, soft dark inside (not black), teeth only as a faint edge when wide open.
            val top = moved[Persona.UPPER_LIP * 2 + 1]
            mouthPaint.shader = android.graphics.LinearGradient(0f, top, 0f, top + drop + 4f,
                Color.rgb(96, 44, 44), Color.rgb(58, 24, 26), Shader.TileMode.CLAMP)
            canvas.drawPath(path, mouthPaint)
            mouthPaint.shader = null
            if (open > .45f) {
                canvas.save()
                canvas.clipPath(path)
                mouthPaint.color = Color.argb(150, 232, 226, 216)
                canvas.drawRect(mouthX - mouthWidth * .3f, top - 2f, mouthX + mouthWidth * .3f, top + drop * .16f, mouthPaint)
                canvas.restore()
            }
        }
        // Everything but the mouth opening, warped.
        val inner = Persona.INNER_LIPS.toSet()
        val tris = f.triangles.toList().chunked(3).filterNot { t -> open > .03f && t.all { it in inner } }.flatten().toIntArray()
        val indices = ShortArray(tris.size) { tris[it].toShort() }
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, moved.size, moved, 0, base, 0, null, 0,
            indices, 0, indices.size, shaderPaint
        )
        return frame
    }
}

/** The live portrait window: blinks by itself and speaks with the user's voice. */
class PersonaContent(private val context: Context) : VrWindow.Content {
    override val pixelWidth = Persona.SIZE
    override val pixelHeight = Persona.SIZE
    override val external = false
    private val renderer = Persona.load(context)?.let { PersonaRenderer(it) }
    @Volatile private var latest: Bitmap? = null
    @Volatile private var running = true
    private var voice: Voice? = null

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        voice = VoiceHub.acquire(context)
        kotlin.concurrent.thread(name = "PhoneXR persona") {
            val blinker = Blinker()
            while (running) {
                val v = voice
                latest = renderer?.render(blinker.value(), v?.mouthOpen ?: 0f, v?.mouthRound ?: .5f)
                    ?: Bitmap.createBitmap(Persona.SIZE, Persona.SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 60, 66)) }
                Thread.sleep(33)
            }
        }
        onReady()
    }

    override fun takeBitmap(): Bitmap? = latest.also { latest = null }

    override fun toolbarTitle(): String = when (voice?.talking) {
        true -> "Лицо · говорит"
        else -> if (renderer == null) "Лицо не создано" else "Лицо"
    }

    override val toolbarVersion get() = if (voice?.talking == true) 1 else 0

    override fun touch(action: Int, u: Float, v: Float) = Unit

    override fun release() {
        running = false
        if (voice != null) VoiceHub.release()
        voice = null
    }
}
