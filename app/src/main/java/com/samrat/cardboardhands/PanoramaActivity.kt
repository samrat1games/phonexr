package com.samrat.cardboardhands

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A panorama all around: a 360° or 180° photo or video, wrapped on a sphere centred on the eyes.
 * 3D panoramas (two eyes packed into one file) keep their depth. A tap goes back.
 */
class PanoramaActivity : Activity() {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tracker: HeadTracker
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val layout = Spatial.layout(name, width, height)
        val shape = Spatial.shape(name, width, height, layout).takeIf { it != Spatial.Shape.FLAT }
            ?: Spatial.Shape.PANORAMA_360

        val photo = if (isVideo) null else load(uri)
        if (!isVideo && photo == null) {
            Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val renderer = PanoramaRenderer(layout, shape, photo, onSurface = { surface ->
            if (!isVideo) return@PanoramaRenderer
            runOnUiThread {
                runCatching {
                    player = MediaPlayer().apply {
                        setDataSource(this@PanoramaActivity, uri)
                        setSurface(surface)
                        isLooping = true
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                }.onFailure {
                    Log.w(TAG, "Panorama video failed", it)
                    Toast.makeText(this, "Видео не открылось", Toast.LENGTH_LONG).show()
                }
            }
        })
        renderer.eyes = Eyes.load(this)
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        renderer.head = tracker.head
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            // A tap leaves the panorama; a long press recentres it on where the head looks.
            setOnClickListener { finish() }
            setOnLongClickListener { tracker.recenter(); true }
        }
        setContentView(surfaceView)
    }

    override fun onResume() {
        super.onResume()
        DisplayRate.apply(this)
        surfaceView.onResume()
        tracker.travelMode = Settings.travelMode(this)
        tracker.start()
        player?.let { runCatching { it.start() } }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        tracker.stop()
        player?.let { runCatching { it.pause() } }
    }

    override fun onDestroy() {
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    /** A panorama can be huge; it is read down to a size the GPU takes without complaint. */
    private fun load(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_TEXTURE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    companion object {
        private const val TAG = "PhoneXR-Panorama"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        /** Every phone GPU takes 4096 across; many take no more. */
        private const val MAX_TEXTURE = 4096
    }
}
