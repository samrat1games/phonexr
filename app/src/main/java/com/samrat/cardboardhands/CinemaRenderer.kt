package com.samrat.cardboardhands

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Side-by-side stereo scene for phone VR headsets: a cosy room (or an island in the sky) with a big
 * screen that shows the game running on the virtual display.
 */
class CinemaRenderer(
    /** The place (room model, panorama); loaded off the GL thread, uploaded on it. Null: a built-in scene. */
    private val model: CinemaScene?,
    private val onSurface: (Surface) -> Unit,
) : GLSurfaceView.Renderer {
    enum class Scene { ROOM, SKY }

    /** A hand's touch point on the screen: u, v in 0..1 and whether it presses. */
    class Cursor(val u: Float, val v: Float, val pressed: Boolean)

    /** Up to two cursors, one per hand; written by the hand thread. */
    @Volatile var cursors: List<Cursor> = emptyList()
    /** The cursor of a mouse or of a second phone held as a pointer, when one is aiming. */
    @Volatile var pointer: Cursor? = null
    /**
     * A window into the real world under the screen, for a keyboard on the desk: while it is on, the
     * camera picture of what is below is shown there, so the keys and the hands can be seen without
     * taking the phone out of the headset.
     */
    @Volatile var keyboardWindow = false
    private var keyboardFade = 0f
    private var windowProgram = 0
    /** The user's real hands (camera picture cut to the hand shape) in head space, over everything. */
    @Volatile var ghosts: List<FloatArray> = emptyList()
    private var handFrame: android.graphics.Bitmap? = null
    private val handFrameLock = Any()
    private var handTexture = 0
    private var hasHandTexture = false

    /** The camera picture the hands are cut from; the newest replaces the last. */
    fun handFrame(bitmap: android.graphics.Bitmap) = synchronized(handFrameLock) {
        handFrame?.recycle()
        handFrame = bitmap
    }
    /**
     * Full view (Minecraft): no room, the app's screen fills each eye exactly (the virtual screen is
     * made the eye's size), with the hands and their cursors on top.
     */
    @Volatile var fullscreen = false
    /** The app's virtual screen size; set before the GL surface is created. */
    var screenW = SCREEN_PIXELS_W
    var screenH = SCREEN_PIXELS_H
    /**
     * A wide screen bent around the viewer: every point of it stays the same distance from the eyes,
     * so the far edges of a 32:9 picture are as readable as its middle. Set before the surface is
     * created, together with the size above.
     */
    var curved = false
    /** Where the eyes are and how their pictures sit under the lenses; set before the surface. */
    @Volatile var eyes: Eyes = Eyes.DEFAULT
    /** Hands for the full view: triangles (x, y, z) in the eye's own coordinates (-1..1). */
    @Volatile var viewHands: List<FloatArray> = emptyList()
    private var flatProgram = 0
    /**
     * Where the screen is, for hit tests from the hand and pointer threads:
     * centre y, z, width, eye height, bend radius (0 when flat) and height — all in metres.
     * A wider picture keeps the height of a 16:9 screen and grows sideways.
     */
    val screenPlacement: FloatArray get() {
        val centerY = model?.screenCenterY ?: SCREEN_CENTER_Y
        val z = model?.screenZ ?: SCREEN_Z
        val base = model?.screenWidth ?: SCREEN_WIDTH
        val eye = model?.eyeHeight ?: EYE_HEIGHT
        val height = base * SCREEN_PIXELS_H / SCREEN_PIXELS_W
        val width = height * screenW / screenH
        return floatArrayOf(centerY, z, width, eye, if (curved) -z else 0f, height)
    }

    @Volatile var scene = Scene.ROOM
        set(value) { field = value; sceneDirty.set(true) }
    /** Head rotation (head to world), column-major 4x4, written by the sensor thread. */
    var head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val sceneDirty = AtomicBoolean(true)
    private val frameReady = AtomicBoolean(false)
    private var surfaceTexture: SurfaceTexture? = null
    private var screenTexture = 0
    private var colorProgram = 0
    private var screenProgram = 0
    private var cursorProgram = 0
    private var ghostProgram = 0
    private var sceneMesh: Mesh? = null
    private var width = 1
    private var height = 1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val eye = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val headCopy = FloatArray(16)
    private val worldToHead = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        colorProgram = program(COLOR_VERTEX, COLOR_FRAGMENT)
        screenProgram = program(SCREEN_VERTEX, SCREEN_FRAGMENT)
        cursorProgram = program(SCREEN_VERTEX, CURSOR_FRAGMENT)
        ghostProgram = program(SCREEN_VERTEX, HAND_FRAGMENT)
        flatProgram = program(GHOST_VERTEX, FLAT_FRAGMENT)
        windowProgram = program(SCREEN_VERTEX, WINDOW_FRAGMENT)
        handTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        screenTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val texture = SurfaceTexture(screenTexture).apply {
            setDefaultBufferSize(screenW, screenH)
            setOnFrameAvailableListener { frameReady.set(true) }
        }
        surfaceTexture = texture
        model?.upload()
        sceneDirty.set(true)
        onSurface(Surface(texture))
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(unused: GL10?) {
        if (frameReady.getAndSet(false)) surfaceTexture?.updateTexImage()
        if (sceneDirty.getAndSet(false)) {
            sceneMesh?.release()
            sceneMesh = when {
                model != null -> null
                scene == Scene.SKY -> buildSky()
                else -> buildRoom()
            }
        }
        if (fullscreen) { drawFullView(); return }
        val sky = scene == Scene.SKY
        val clear = model?.clearColor ?: if (sky) floatArrayOf(.55f, .75f, .98f) else floatArrayOf(.05f, .04f, .04f)
        GLES20.glClearColor(clear[0], clear[1], clear[2], 1f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        synchronized(head) { System.arraycopy(head, 0, headCopy, 0, 16) }
        val place = screenPlacement
        // World to head: inverse rotation, then the seated eye height.
        Matrix.transposeM(worldToHead, 0, headCopy, 0)
        Matrix.translateM(worldToHead, 0, 0f, -place[3], 0f)

        val eyeWidth = width / 2
        for (index in 0..1) {
            Matrix.perspectiveM(projection, 0, FOV_Y, eyeWidth.toFloat() / height, .05f, 200f)
            eyes.shift(projection, index, eyeWidth)
            GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
            Matrix.setIdentityM(eye, 0)
            Matrix.translateM(eye, 0, if (index == 0) eyes.halfIpd else -eyes.halfIpd, 0f, 0f)
            Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            if (model != null) model.draw(viewProjection) else sceneMesh?.draw(colorProgram, viewProjection)
            drawScreen(viewProjection, place)
            drawCursors(viewProjection, place)
            if (index == 0) uploadCameraFrame()
            drawKeyboardWindow()
        }
    }

    /** Width / height of one eye's view, for the hands' mapping. */
    @Volatile var eyeAspect = .8f
        private set

    private fun drawFullView() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val eyeWidth = width / 2
        eyeAspect = eyeWidth.toFloat() / height
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        for (index in 0..1) {
            GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            // The lens offset moves the whole picture, so the two halves meet under the lenses.
            val lens = if (eyes.offsetPixels == 0f) 0f else
                (if (index == 0) 1f else -1f) * 2f * eyes.offsetPixels / eyeWidth
            // The app's screen is exactly the eye's size: it fills the view, nothing cut off.
            GLES20.glUseProgram(screenProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProgram, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uMvp"), 1, false, identity, 0)
            val quad = floatBuffer(floatArrayOf(
                -1f + lens, -1f, 0f, 0f, 1f, 1f + lens, -1f, 0f, 1f, 1f,
                -1f + lens, 1f, 0f, 0f, 0f, 1f + lens, 1f, 0f, 1f, 0f
            ))
            val position = GLES20.glGetAttribLocation(screenProgram, "aPosition")
            val uv = GLES20.glGetAttribLocation(screenProgram, "aUv")
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, quad)
            GLES20.glEnableVertexAttribArray(position)
            quad.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, quad)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // See-through hands: one depth plane + GL_LESS blends each pixel once.
            val hands = viewHands
            if (hands.isNotEmpty()) {
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthFunc(GLES20.GL_LESS)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDisable(GLES20.GL_CULL_FACE)
                GLES20.glUseProgram(flatProgram)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatProgram, "uMvp"), 1, false, identity, 0)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(flatProgram, "uColor"), .93f, .95f, 1f, .35f)
                val flat = GLES20.glGetAttribLocation(flatProgram, "aPosition")
                for (mesh in hands) {
                    val buffer = floatBuffer(mesh)
                    GLES20.glVertexAttribPointer(flat, 3, GLES20.GL_FLOAT, false, 12, buffer)
                    GLES20.glEnableVertexAttribArray(flat)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.size / 3)
                }
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glEnable(GLES20.GL_CULL_FACE)
            }
            // Cursors: a ring on each hand's point, filled while tapping.
            val list = cursors + listOfNotNull(pointer)
            if (list.isNotEmpty()) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUseProgram(cursorProgram)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cursorProgram, "uMvp"), 1, false, identity, 0)
                val rx = .045f; val ry = rx * eyeAspect
                for (cursor in list) {
                    val x = cursor.u * 2f - 1f; val y = 1f - cursor.v * 2f
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(cursorProgram, "uPressed"), if (cursor.pressed) 1f else 0f)
                    val ring = floatBuffer(floatArrayOf(x - rx, y - ry, 0f, 0f, 1f, x + rx, y - ry, 0f, 1f, 1f, x - rx, y + ry, 0f, 0f, 0f, x + rx, y + ry, 0f, 1f, 0f))
                    val p = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
                    val t = GLES20.glGetAttribLocation(cursorProgram, "aUv")
                    ring.position(0)
                    GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 20, ring)
                    GLES20.glEnableVertexAttribArray(p)
                    ring.position(3)
                    GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 20, ring)
                    GLES20.glEnableVertexAttribArray(t)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            if (index == 0) uploadCameraFrame()
            Matrix.setIdentityM(eye, 0)
            Matrix.setIdentityM(projection, 0)
            drawKeyboardWindow()
        }
    }

    private fun drawScreen(mvp: FloatArray, place: FloatArray) {
        GLES20.glUseProgram(screenProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uMvp"), 1, false, mvp, 0)
        val mesh = screenMesh(place)
        val position = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(screenProgram, "aUv")
        mesh.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, mesh)
        GLES20.glEnableVertexAttribArray(position)
        mesh.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, mesh)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mesh.capacity() / 5)
    }

    private var screenStrip: FloatBuffer? = null
    private var screenStripFor = FloatArray(0)

    /**
     * The screen as a triangle strip: two triangles when it is flat, a ring of columns when it is
     * bent. The strip is kept until the screen changes, since it is the same every frame.
     */
    private fun screenMesh(place: FloatArray): FloatBuffer {
        screenStrip?.let { if (place.contentEquals(screenStripFor)) return it }
        val width = place[2]
        val halfH = place[5] / 2
        val cy = place[0]
        val radius = place[4]
        val vertices = if (radius <= 0f) {
            // x, y, z, u, v; a virtual display's picture has t = 0 at the top.
            floatArrayOf(
                -width / 2, cy - halfH, place[1], 0f, 1f,
                width / 2, cy - halfH, place[1], 1f, 1f,
                -width / 2, cy + halfH, place[1], 0f, 0f,
                width / 2, cy + halfH, place[1], 1f, 0f,
            )
        } else {
            // The picture is wrapped on a cylinder around the viewer: its arc is as long as the
            // screen is wide, so nothing is stretched and the edges come no closer.
            val arc = width / radius
            val columns = SCREEN_COLUMNS
            FloatArray((columns + 1) * 2 * 5).also { out ->
                for (column in 0..columns) {
                    val u = column.toFloat() / columns
                    val angle = (u - .5f) * arc
                    val x = radius * kotlin.math.sin(angle)
                    val z = -radius * kotlin.math.cos(angle)
                    val base = column * 10
                    out[base] = x; out[base + 1] = cy - halfH; out[base + 2] = z; out[base + 3] = u; out[base + 4] = 1f
                    out[base + 5] = x; out[base + 6] = cy + halfH; out[base + 7] = z; out[base + 8] = u; out[base + 9] = 0f
                }
            }
        }
        val buffer = floatBuffer(vertices)
        screenStrip = buffer
        screenStripFor = place.copyOf()
        return buffer
    }

    /** Where a point of the screen (u, v) sits in the world, flat or bent. */
    private fun screenPoint(place: FloatArray, u: Float, v: Float, towardsViewer: Float = 0f): FloatArray {
        val halfH = place[5] / 2
        val y = place[0] + halfH - v * place[5]
        val radius = place[4]
        if (radius <= 0f) return floatArrayOf((u - .5f) * place[2], y, place[1] + towardsViewer)
        val angle = (u - .5f) * (place[2] / radius)
        val r = radius - towardsViewer
        return floatArrayOf(r * kotlin.math.sin(angle), y, -r * kotlin.math.cos(angle))
    }

    /** Hand cursors: soft rings just in front of the screen, filled while touching. */
    private fun drawCursors(mvp: FloatArray, place: FloatArray) {
        val list = cursors + listOfNotNull(pointer)
        if (list.isEmpty()) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cursorProgram, "uMvp"), 1, false, mvp, 0)
        val r = place[5] * .032f
        for (cursor in list) {
            // A hair in front of the screen, bent along with it.
            val point = screenPoint(place, cursor.u, cursor.v, towardsViewer = .01f)
            val x = point[0]
            val y = point[1]
            val z = point[2]
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cursorProgram, "uPressed"), if (cursor.pressed) 1f else 0f)
            val quad = floatArrayOf(
                x - r, y - r, z, 0f, 1f,
                x + r, y - r, z, 1f, 1f,
                x - r, y + r, z, 0f, 0f,
                x + r, y + r, z, 1f, 0f,
            )
            val buffer = floatBuffer(quad)
            val position = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
            val uv = GLES20.glGetAttribLocation(cursorProgram, "aUv")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Puts the newest camera picture on the GPU; both the hands and the keyboard window use it. */
    private fun uploadCameraFrame() {
        synchronized(handFrameLock) {
            handFrame?.let { bitmap ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handTexture)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
                handFrame = null
                hasHandTexture = true
            }
        }
    }

    /**
     * The window onto the desk, in head space under the line of sight. Its texture coordinates come
     * from the same camera mapping the hands use, so what is drawn there really is what lies below.
     */
    private fun drawKeyboardWindow() {
        keyboardFade = (keyboardFade + if (keyboardWindow) FADE_STEP else -FADE_STEP).coerceIn(0f, 1f)
        if (keyboardFade <= 0f || !hasHandTexture) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(windowProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(windowProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(windowProgram, "uAlpha"), keyboardFade)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(windowProgram, "uMvp"), 1, false, mvp, 0)
        // Where the window hangs: a hand's length away, below the line of sight, where a desk is.
        val z = -WINDOW_DISTANCE
        val left = -WINDOW_HALF_W
        val right = WINDOW_HALF_W
        val bottom = WINDOW_BOTTOM
        val top = WINDOW_TOP
        fun u(x: Float) = (x / WINDOW_DISTANCE) / (2f * CAMERA_TAN_X) + .5f
        fun v(y: Float) = .5f - (y / WINDOW_DISTANCE) / (2f * CAMERA_TAN_Y)
        val quad = floatArrayOf(
            left, bottom, z, u(left), v(bottom),
            right, bottom, z, u(right), v(bottom),
            left, top, z, u(left), v(top),
            right, top, z, u(right), v(top),
        )
        val buffer = floatBuffer(quad)
        val position = GLES20.glGetAttribLocation(windowProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(windowProgram, "aUv")
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** Real hands in head space, cut out of the newest camera picture. */
    private fun drawGhosts() {
        uploadCameraFrame()
        val list = ghosts
        if (list.isEmpty() || !hasHandTexture) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ghostProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(ghostProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ghostProgram, "uMvp"), 1, false, mvp, 0)
        val position = GLES20.glGetAttribLocation(ghostProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(ghostProgram, "aUv")
        for (triangles in list) {
            val buffer = floatBuffer(triangles)
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangles.size / 5)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthFunc(GLES20.GL_LESS)
    }

    // ------------------------------------------------------------------ Scenes

    private fun buildRoom() = MeshBuilder().apply {
        // Wooden floor planks.
        for (i in -8..7) {
            val shade = if (i % 2 == 0) floatArrayOf(.45f, .30f, .18f) else floatArrayOf(.50f, .34f, .20f)
            box(i * .5f + .25f, -.05f, 0f, .5f, .1f, 10f, shade)
        }
        box(0f, .005f, -.6f, 3.2f, .01f, 2.2f, floatArrayOf(.55f, .14f, .12f))      // rug
        box(0f, 3.05f, 0f, 8f, .1f, 10f, floatArrayOf(.92f, .9f, .86f))             // ceiling
        box(0f, 1.5f, -4.05f, 8f, 3f, .1f, floatArrayOf(.80f, .72f, .60f))          // front wall
        box(0f, 1.5f, 4.05f, 8f, 3f, .1f, floatArrayOf(.80f, .72f, .60f))           // back wall
        box(-4.05f, 1.5f, 0f, .1f, 3f, 8.2f, floatArrayOf(.76f, .68f, .56f))        // left wall
        box(4.05f, 1.5f, 0f, .1f, 3f, 8.2f, floatArrayOf(.76f, .68f, .56f))         // right wall
        box(0f, .12f, -4f, 8f, .24f, .06f, floatArrayOf(.35f, .24f, .15f))          // skirting
        // Window with daylight on the left wall.
        box(-3.99f, 1.7f, -1f, .04f, 1.3f, 2f, floatArrayOf(.28f, .20f, .13f))
        box(-3.96f, 1.7f, -1f, .04f, 1.1f, 1.8f, floatArrayOf(.62f, .82f, 1f))
        box(-3.94f, 1.7f, -1f, .04f, 1.1f, .05f, floatArrayOf(.28f, .20f, .13f))
        // TV stand and the screen bezel.
        box(0f, .3f, SCREEN_Z - .25f, 2.6f, .6f, .5f, floatArrayOf(.30f, .20f, .13f))
        // A bent screen has no straight bezel to sit in, so the frame is only drawn around a flat one.
        if (!curved) {
            val place = screenPlacement
            box(0f, SCREEN_CENTER_Y, SCREEN_Z - .04f, place[2] + .12f, place[5] + .12f, .06f, floatArrayOf(.04f, .04f, .05f))
        }
        // Shelf with block-like decorations on the right wall.
        box(3.8f, 1.6f, -1.5f, .4f, .06f, 2f, floatArrayOf(.35f, .24f, .15f))
        box(3.8f, 1.8f, -2.2f, .3f, .3f, .3f, floatArrayOf(.36f, .62f, .25f))
        box(3.8f, 1.8f, -1.6f, .3f, .3f, .3f, floatArrayOf(.55f, .38f, .24f))
        box(3.8f, 1.8f, -1f, .3f, .3f, .3f, floatArrayOf(.62f, .62f, .62f))
        // Floor lamp with a warm shade.
        box(-2.8f, .75f, -3.2f, .06f, 1.5f, .06f, floatArrayOf(.15f, .15f, .15f))
        box(-2.8f, 1.6f, -3.2f, .5f, .4f, .5f, floatArrayOf(1f, .85f, .55f))
        // Couch behind the viewer and a side table.
        box(0f, .25f, 1.1f, 2.4f, .5f, .9f, floatArrayOf(.25f, .32f, .45f))
        box(0f, .75f, 1.5f, 2.4f, .6f, .2f, floatArrayOf(.22f, .29f, .42f))
        box(1.6f, .3f, .6f, .5f, .6f, .5f, floatArrayOf(.35f, .24f, .15f))
    }.build()

    private fun buildSky() = MeshBuilder().apply {
        // A floating grass island under the viewer, the world far below and blocky clouds around.
        for (x in -2..2) for (z in -2..2) {
            if (kotlin.math.abs(x) == 2 && kotlin.math.abs(z) == 2) continue
            box(x * 1f, -.5f, z * 1f, 1f, .2f, 1f, floatArrayOf(.36f, .62f, .25f))
            box(x * 1f, -1.1f, z * 1f, 1f, 1f, 1f, floatArrayOf(.47f, .33f, .22f))
        }
        box(0f, -2f, 0f, 3f, 1f, 3f, floatArrayOf(.47f, .33f, .22f))
        box(0f, -60f, 0f, 400f, 1f, 400f, floatArrayOf(.33f, .55f, .28f))
        box(-30f, -59f, -60f, 40f, 2f, 30f, floatArrayOf(.25f, .45f, .75f))
        val clouds = listOf(
            floatArrayOf(-12f, 6f, -25f, 10f), floatArrayOf(15f, 9f, -30f, 14f), floatArrayOf(-25f, 3f, 5f, 8f),
            floatArrayOf(22f, 4f, 12f, 12f), floatArrayOf(5f, 12f, 30f, 16f), floatArrayOf(-8f, -10f, -18f, 9f),
            floatArrayOf(10f, -14f, -8f, 11f), floatArrayOf(-18f, -12f, 20f, 13f),
        )
        for (c in clouds) box(c[0], c[1], c[2], c[3], 1.2f, c[3] * .6f, floatArrayOf(.97f, .97f, 1f))
        // A thin frame so the floating screen reads as a panel; a bent one needs none.
        if (!curved) {
            val place = screenPlacement
            box(0f, SCREEN_CENTER_Y, SCREEN_Z - .04f, place[2] + .08f, place[5] + .08f, .04f, floatArrayOf(.1f, .1f, .12f))
        }
    }.build()

    // ------------------------------------------------------------------ GL helpers

    private class Mesh(val buffer: FloatBuffer, val count: Int) {
        fun draw(program: Int, mvp: FloatArray) {
            if (count == 0) return
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val color = GLES20.glGetAttribLocation(program, "aColor")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, buffer)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        }

        fun release() = Unit
    }

    private class MeshBuilder {
        private val data = ArrayList<Float>(4096)

        /** Axis-aligned box; faces are shaded by direction so shapes read without lighting. */
        fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, color: FloatArray) =
            orientedBox(floatArrayOf(cx, cy, cz), null, 0f, 0f, 0f, sx, sy, sz, color)

        fun orientedBox(
            base: FloatArray, rotation: FloatArray?, ox: Float, oy: Float, oz: Float,
            sx: Float, sy: Float, sz: Float, color: FloatArray
        ) {
            val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
            fun corner(x: Float, y: Float, z: Float): FloatArray {
                val p = floatArrayOf(ox + x, oy + y, oz + z, 1f)
                if (rotation != null) {
                    val r = FloatArray(4)
                    Matrix.multiplyMV(r, 0, rotation, 0, p, 0)
                    return floatArrayOf(base[0] + r[0], base[1] + r[1], base[2] + r[2])
                }
                return floatArrayOf(base[0] + p[0], base[1] + p[1], base[2] + p[2])
            }
            val c = arrayOf(
                corner(-hx, -hy, -hz), corner(hx, -hy, -hz), corner(hx, hy, -hz), corner(-hx, hy, -hz),
                corner(-hx, -hy, hz), corner(hx, -hy, hz), corner(hx, hy, hz), corner(-hx, hy, hz)
            )
            // Counter-clockwise when seen from outside.
            face(c[4], c[5], c[6], c[7], color, .85f) // +z
            face(c[1], c[0], c[3], c[2], color, .85f) // -z
            face(c[5], c[1], c[2], c[6], color, .7f)  // +x
            face(c[0], c[4], c[7], c[3], color, .7f)  // -x
            face(c[7], c[6], c[2], c[3], color, 1f)   // +y
            face(c[0], c[1], c[5], c[4], color, .5f)  // -y
        }

        private fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, color: FloatArray, light: Float) {
            for (p in listOf(a, b, c, a, c, d)) {
                data += p[0]; data += p[1]; data += p[2]
                data += color[0] * light; data += color[1] * light; data += color[2] * light
            }
        }

        fun build() = Mesh(floatBuffer(data.toFloatArray()), data.size / 6)
    }

    companion object {
        /** Wide enough to fill the view (~92°): with Minecraft's FOV near 90 it feels like being inside. */
        private const val HEAD_SCREEN_WIDTH = 3.9f
        private const val HEAD_SCREEN_DISTANCE = 1.9f
        const val SCREEN_PIXELS_W = 1920
        const val SCREEN_PIXELS_H = 1080
        private const val SCREEN_WIDTH = 3.4f
        private const val SCREEN_CENTER_Y = 1.45f
        private const val SCREEN_Z = -3.9f
        private const val EYE_HEIGHT = 1.15f
        /** Columns of the bent screen: enough that its curve reads as smooth, few enough to be free. */
        private const val SCREEN_COLUMNS = 48
        /** The keyboard window: where it hangs in front of the eyes and how the camera sees it. */
        private const val WINDOW_DISTANCE = 1.2f
        private const val WINDOW_HALF_W = .5f
        private const val WINDOW_BOTTOM = -.78f
        private const val WINDOW_TOP = -.30f
        private const val CAMERA_TAN_X = .95f
        private const val CAMERA_TAN_Y = .72f
        /** How fast the window appears and goes, in parts of a frame. */
        private const val FADE_STEP = .06f
        private const val FOV_Y = 90f

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }

        fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }

        private const val COLOR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            varying vec3 vColor;
            void main() { gl_FragColor = vec4(vColor, 1.0); }"""
        private const val SCREEN_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        private const val GHOST_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val FLAT_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }"""
        private const val HAND_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, 1.0); }"""
        private const val CURSOR_FRAGMENT = """
            precision mediump float;
            uniform float uPressed;
            varying vec2 vUv;
            void main() {
                float d = length(vUv - vec2(0.5)) * 2.0;
                float ring = smoothstep(1.0, 0.85, d) * smoothstep(0.45, 0.6, d);
                float fill = uPressed * smoothstep(0.62, 0.5, d);
                float alpha = max(ring * 0.95, fill * 0.9);
                gl_FragColor = vec4(1.0, 1.0, 1.0, alpha);
            }"""
        private const val WINDOW_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vUv;
            void main() {
                vec2 d = abs(vUv - vec2(0.5)) * 2.0;
                float edge = smoothstep(1.0, 0.86, max(d.x, d.y));
                gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, uAlpha * edge);
            }"""
        private const val SCREEN_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
    }
}
