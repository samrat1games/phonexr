package com.samrat.cardboardhands

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface

/**
 * A video on a window in VR. Ordinary videos play flat; a 3D one (the two eyes packed side by side
 * or one over the other) plays with depth, each eye taking its own half of the picture.
 *
 * The bottom strip of the window is the timeline: tapping it jumps, tapping anywhere else pauses.
 */
class VideoContent(
    private val context: Context,
    private val uri: Uri,
    private val name: String,
    width: Int,
    height: Int,
    private val onError: (String) -> Unit = {},
) : VrWindow.Content {
    val layout = Spatial.layout(name, width, height)
    val shape = Spatial.shape(name, width, height, layout)

    /** The window keeps the shape of what one eye sees, so nothing is squeezed. */
    override val pixelWidth = 1920
    override val pixelHeight = (1920 / Spatial.eyeAspect(width, height, layout)).toInt().coerceIn(240, 2400)
    override val external = true

    private var player: MediaPlayer? = null
    private var texture: SurfaceTexture? = null
    @Volatile private var version = 0

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        this.texture = texture
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        val surface = Surface(texture)
        Handler(Looper.getMainLooper()).post {
            val media = MediaPlayer()
            runCatching {
                media.setDataSource(context, uri)
                media.setSurface(surface)
                media.isLooping = true
                media.setOnPreparedListener {
                    // The picture the file really has decides how the window is filled.
                    texture?.setDefaultBufferSize(it.videoWidth.coerceAtLeast(16), it.videoHeight.coerceAtLeast(16))
                    it.start()
                    version++
                    onReady()
                }
                media.setOnErrorListener { _, what, _ ->
                    onError("Видео не открылось (код $what)")
                    true
                }
                media.prepareAsync()
                player = media
            }.onFailure {
                media.release()
                onError("Видео не открылось: ${it.message}")
                onReady()
            }
        }
    }

    override fun uv(eye: Int): FloatArray = Spatial.uv(layout, eye)

    override fun toolbarTitle(): String = buildString {
        append(name.ifEmpty { "Видео" })
        append(" · ")
        append(Spatial.describe(layout, shape))
        player?.let { append(" · ").append(time(position())).append(" / ").append(time(it.duration)) }
    }

    override val toolbarVersion get() = version + position() / 1000

    /** Back and forward jump ten seconds; reload starts the video again. */
    override fun toolbarAction(action: String) {
        val media = player ?: return
        runCatching {
            when (action) {
                "back" -> media.seekTo((media.currentPosition - 10_000).coerceAtLeast(0))
                "forward" -> media.seekTo((media.currentPosition + 10_000).coerceAtMost(media.duration))
                "reload" -> media.seekTo(0)
                "home" -> if (media.isPlaying) media.pause() else media.start()
            }
            version++
        }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val media = player ?: return
        runCatching {
            if (v > .88f) media.seekTo((u * media.duration).toInt().coerceIn(0, media.duration))
            else if (media.isPlaying) media.pause() else media.start()
            version++
        }
    }

    fun playing() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun position() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    private fun time(ms: Int): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun release() {
        runCatching { player?.release() }
        player = null
    }
}
