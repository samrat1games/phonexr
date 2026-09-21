package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Guided Persona scan. The face is sampled from several useful angles (the back of the head is not
 * required); the on-device face/segmentation models turn the best frontal sample into the Persona.
 */
class PersonaCaptureActivity : ComponentActivity() {
    private val previews = ArrayList<ImageView>(2)
    private val statuses = ArrayList<TextView>(2)
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private val done = AtomicBoolean(false)
    private var goodFrames = 0
    private var lastTimestamp = 0L
    private var poseIndex = 0
    private val captured = LinkedHashMap<String, Bitmap>()

    private enum class Pose(val prompt: String) {
        FRONT("Смотрите прямо"),
        LEFT("Медленно поверните голову влево"),
        RIGHT("Теперь поверните голову вправо"),
        UP("Слегка поднимите подбородок"),
        DOWN("Слегка опустите подбородок"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.init(this)
        // The phone stays landscape in Cardboard: duplicate the scanner for the left and right lens.
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(2) { addView(eyeView(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
        })
        executor.execute {
            landmarker = runCatching {
                FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build())
            }.getOrNull()
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 960))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (!done.get()) analyze(image.toBitmap().rotate(image.imageInfo.rotationDegrees), image.imageInfo.timestamp / 1_000_000L)
                } finally {
                    image.close()
                }
            }
            // The outward-facing camera is the headset camera; the phone stays in Cardboard.
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
                .onFailure { setStatus(tr("Камера шлема недоступна")) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun eyeView(): FrameLayout {
        val preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            text = tr("Не вынимайте телефон · покажите лицо камере шлема")
        }
        previews += preview
        statuses += status
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 0, 24, 28)
            addView(status)
            addView(Button(this@PersonaCaptureActivity).apply {
                text = tr("Пропустить")
                setOnClickListener { finishWith(false) }
            })
        }
        return FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(FaceGuide(this@PersonaCaptureActivity))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun setStatus(value: String) = runOnUiThread { statuses.forEach { it.text = value } }

    private fun analyze(frame: Bitmap, timestamp: Long) {
        runOnUiThread { previews.forEach { it.setImageBitmap(frame) } }
        val face = landmarker ?: return
        val stamp = maxOf(timestamp, lastTimestamp + 1).also { lastTimestamp = it }
        val points = face.detectForVideo(BitmapImageBuilder(frame).build(), stamp).faceLandmarks().firstOrNull()
        val message = when {
            points == null -> { goodFrames = 0; tr("Лицо не видно") }
            else -> {
                val nose = points[1]; val left = points[234]; val right = points[454]
                val width = right.x() - left.x()
                val centred = abs((left.x() + right.x()) / 2 - .5f) < .12f && abs(nose.y() - .45f) < .15f
                val turn = (nose.x() - left.x()) / width
                val near = abs(width) > .28f
                val pose = Pose.entries[poseIndex]
                val matchesPose = when (pose) {
                    Pose.FRONT -> abs(turn - .5f) < .10f
                    Pose.LEFT -> turn < .42f
                    Pose.RIGHT -> turn > .58f
                    // Pitch estimates vary by face; movement plus a short steady hold is more robust
                    // than rejecting valid users with a fixed anatomical threshold.
                    Pose.UP, Pose.DOWN -> centred
                }
                when {
                    !near -> { goodFrames = 0; tr("Приблизьте лицо к камере шлема") }
                    !centred -> { goodFrames = 0; tr("Лицо в центр рамки") }
                    !matchesPose -> { goodFrames = 0; tr(pose.prompt) }
                    else -> { goodFrames++; "${tr(pose.prompt)} · ${poseIndex + 1}/${Pose.entries.size}" }
                }
            }
        }
        setStatus(message)
        if (goodFrames >= 8) {
            val pose = Pose.entries[poseIndex]
            captured.remove(pose.name.lowercase())?.recycle()
            captured[pose.name.lowercase()] = frame.copy(Bitmap.Config.ARGB_8888, false)
            goodFrames = 0
            poseIndex++
            if (poseIndex < Pose.entries.size) {
                setStatus(tr(Pose.entries[poseIndex].prompt))
                return
            }
        }
        if (poseIndex >= Pose.entries.size && done.compareAndSet(false, true)) {
            setStatus(tr("Создаю персону…"))
            thread {
                val error = Persona.buildScan(this, captured)
                runOnUiThread {
                    if (error == null) finishWith(true)
                    else { setStatus(error); done.set(false); goodFrames = 0 }
                }
            }
        }
    }

    private fun finishWith(ok: Boolean) {
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        executor.execute { landmarker?.close() }
        executor.shutdown()
        super.onDestroy()
    }

    /** An oval showing where to put the face. */
    private class FaceGuide(context: android.content.Context) : android.view.View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.WHITE }
        override fun onDraw(canvas: Canvas) {
            val w = width * .62f; val h = w * 1.3f
            canvas.drawOval(width / 2f - w / 2, height * .42f - h / 2, width / 2f + w / 2, height * .42f + h / 2, paint)
        }
    }
}
