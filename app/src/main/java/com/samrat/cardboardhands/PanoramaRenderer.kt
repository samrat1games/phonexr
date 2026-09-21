package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A panorama around the viewer: the picture is wrapped on a sphere centred on the eyes, all 360°
 * of it or the 180° in front. A photo comes as a bitmap, a video through a surface the player draws
 * into. When the file holds two eyes, each eye takes its own half of it and the panorama has depth.
 */
class PanoramaRenderer(
    private val layout: Spatial.Layout,
    private val shape: Spatial.Shape,
    private val photo: Bitmap?,
    /** Called on the GL thread with the surface a video player should draw into. */
    private val onSurface: ((Surface) -> Unit)? = null,
) : GLSurfaceView.Renderer {
    /** Head rotation (head to world), written by the sensor thread. */
    var head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** The lenses of the headset; a panorama sits at infinity, so only their offset matters. */
    @Volatile var eyes: Eyes = Eyes.DEFAULT

    private val video = photo == null
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameReady = AtomicBoolean(false)
    private var mesh: FloatBuffer? = null
    private var vertices = 0
    private var width = 1
    private var height = 1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val eye = FloatArray(16)
    private val headCopy = FloatArray(16)
    private val worldToHead = FloatArray(16)
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        program = CinemaRenderer.program(VERTEX, if (video) EXTERNAL_FRAGMENT else FLAT_FRAGMENT)
        val target = if (video) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(target, texture)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (video) {
            val stream = SurfaceTexture(texture).apply { setOnFrameAvailableListener { frameReady.set(true) } }
            surfaceTexture = stream
            onSurface?.invoke(Surface(stream))
        } else {
            photo?.let { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0) }
        }
        mesh = buildSphere()
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(unused: GL10?) {
        if (frameReady.getAndSet(false)) surfaceTexture?.updateTexImage()
        val buffer = mesh ?: return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        synchronized(head) { System.arraycopy(head, 0, headCopy, 0, 16) }
        Matrix.transposeM(worldToHead, 0, headCopy, 0)
        val eyeWidth = width / 2
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val target = if (video) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        for (index in 0..1) {
            Matrix.perspectiveM(projection, 0, FOV_Y, eyeWidth.toFloat() / height, .05f, 100f)
            eyes.shift(projection, index, eyeWidth)
            GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
            // The sphere sits on the eyes, so only the turn of the head matters.
            Matrix.setIdentityM(eye, 0)
            Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            // This eye's half of the picture, or all of it when the file holds one eye only.
            val part = Spatial.uv(layout, index)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvOffset"), part[0], part[1])
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvScale"), part[2] - part[0], part[3] - part[1])
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices)
        }
    }

    /** Half a sphere for a 180° picture, a whole one for 360°; the viewer is inside it. */
    private fun buildSphere(): FloatBuffer {
        val span = if (shape == Spatial.Shape.PANORAMA_180) 1f else 2f
        val buffer = ByteBuffer.allocateDirect(STACKS * SLICES * 6 * 20).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun put(u: Float, v: Float) {
            // u = 0.5 is straight ahead (-z), v = 0 straight up; a 180° picture only spans the front.
            val lon = (u - .5f) * span * PI.toFloat()
            val lat = (.5f - v) * PI.toFloat()
            buffer.put(sin(lon) * cos(lat) * RADIUS)
            buffer.put(sin(lat) * RADIUS)
            buffer.put(-cos(lon) * cos(lat) * RADIUS)
            buffer.put(u)
            buffer.put(v)
        }
        for (stack in 0 until STACKS) {
            val v0 = stack.toFloat() / STACKS
            val v1 = (stack + 1f) / STACKS
            for (slice in 0 until SLICES) {
                val u0 = slice.toFloat() / SLICES
                val u1 = (slice + 1f) / SLICES
                put(u0, v0); put(u0, v1); put(u1, v0)
                put(u1, v0); put(u0, v1); put(u1, v1)
            }
        }
        vertices = STACKS * SLICES * 6
        buffer.position(0)
        return buffer
    }

    private companion object {
        const val STACKS = 48
        const val SLICES = 96
        const val RADIUS = 12f
        const val FOV_Y = 90f

        const val VERTEX = """
            uniform mat4 uMvp;
            uniform vec2 uUvOffset;
            uniform vec2 uUvScale;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = uUvOffset + aUv * uUvScale;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        const val FLAT_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
    }
}
