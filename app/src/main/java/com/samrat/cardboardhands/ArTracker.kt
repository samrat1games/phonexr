package com.samrat.cardboardhands

import android.app.Activity
import android.graphics.Bitmap
import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 6DoF for the VR home with ARCore: where the headset is in the room. The head's rotation still
 * comes from the fast [HeadTracker]; ARCore adds the position, turned into the same world (its yaw
 * is aligned to the head tracker's), so windows stay put while the user walks around them.
 * ARCore also owns the camera: it gives the passthrough texture and the frames for hand tracking.
 */
class ArTracker private constructor(private val session: Session) {
    enum class Availability { READY, CHECKING, MISSING }

    /** Camera frame for hands: upright bitmap, its time, and where it lies on the eye's view (0..1). */
    class CameraFrame(val bitmap: Bitmap, val timestampNs: Long, val viewLeft: Float, val viewTop: Float, val viewWidth: Float, val viewHeight: Float)

    @Volatile var tracking = false
        private set
    @Volatile var horizontalPlanes = 0
        private set
    @Volatile var verticalPlanes = 0
        private set
    /** Head position in the head tracker's world, metres, relative to where tracking started. */
    val position = FloatArray(3)
    private var origin: FloatArray? = null
    // Steady position: calm when standing, quick when walking; no millimetre shimmer.
    private val smooth = Array(3) { HandGestures.OneEuro(minCutoff = 1.0f, beta = 2.5f, deadZone = .003f) }
    private val lastRaw = FloatArray(3)
    private var hasLast = false
    private var alignYaw = Float.NaN
    private val quadNdc: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    /** Texture coordinates of the passthrough quad (per eye), updated when the display changes. */
    val passthroughUv: FloatBuffer = floatBuffer(FloatArray(8))
    @Volatile var hasUv = false
        private set
    /** The camera's projection for one eye's viewport: virtual things line up with the passthrough. */
    val projection = FloatArray(16).also { Matrix.perspectiveM(it, 0, 90f, 1f, .05f, 100f) }
    private var imageRotation = 0
    private var imageToView = floatArrayOf(0f, 0f, 1f, 1f)

    /** Must be called on the GL thread with the external texture ARCore draws the camera into. */
    fun attachTexture(texture: Int) = session.setCameraTextureName(texture)

    fun setDisplay(rotation: Int, eyeWidth: Int, height: Int) = session.setDisplayGeometry(rotation, eyeWidth, height)

    fun resume() = runCatching { session.resume() }.onFailure { Log.w(TAG, "ARCore resume failed", it) }.isSuccess

    fun pause() = session.pause()

    fun close() = session.close()

    /** Makes the current spot the centre of the room again (with the head tracker's recenter). */
    fun recenter() {
        origin = null
        alignYaw = Float.NaN
        hasLast = false
        smooth.forEach { it.reset() }
        horizontalPlanes = 0
        verticalPlanes = 0
    }

    /**
     * One ARCore frame on the GL thread. [sensorHead] is the head tracker's rotation, used to align
     * ARCore's world. Returns a CPU camera frame for the hands when [wantImage] and one is ready.
     */
    fun update(sensorHead: FloatArray, wantImage: Boolean): (() -> CameraFrame?)? {
        val frame: Frame = runCatching { session.update() }.getOrElse { return null }
        if (frame.hasDisplayGeometryChanged() || !hasUv) {
            passthroughUv.position(0)
            quadNdc.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc, Coordinates2d.TEXTURE_NORMALIZED, passthroughUv)
            passthroughUv.position(0)
            hasUv = true
            updateImageMapping(frame)
        }
        val camera = frame.camera
        tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            val planes = session.getAllTrackables(Plane::class.java).filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
            }
            horizontalPlanes = planes.count { it.type != Plane.Type.VERTICAL }
            verticalPlanes = planes.count { it.type == Plane.Type.VERTICAL }
        }
        synchronized(projection) { camera.getProjectionMatrix(projection, 0, .05f, 100f) }
        if (tracking) {
            val pose = camera.displayOrientedPose
            val ar = FloatArray(16).also { pose.toMatrix(it, 0) }
            // Both worlds have gravity along y; only the heading differs. Follow it slowly.
            val yawAr = atan2(ar[8], ar[10])
            val yawSensor = atan2(sensorHead[8], sensorHead[10])
            var delta = yawSensor - yawAr
            while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            alignYaw = if (alignYaw.isNaN()) delta else alignYaw + wrap(delta - alignYaw) * .05f
            val start = origin ?: floatArrayOf(pose.tx(), pose.ty(), pose.tz()).also { origin = it }
            // ARCore sometimes snaps to a corrected map: a jump of half a metre in one frame is not
            // the user walking. Move the origin with it so the room does not lurch.
            if (hasLast) {
                val jx = pose.tx() - lastRaw[0]; val jy = pose.ty() - lastRaw[1]; val jz = pose.tz() - lastRaw[2]
                if (jx * jx + jy * jy + jz * jz > .25f) { start[0] += jx; start[1] += jy; start[2] += jz }
            }
            lastRaw[0] = pose.tx(); lastRaw[1] = pose.ty(); lastRaw[2] = pose.tz(); hasLast = true
            val dx = pose.tx() - start[0]; val dy = pose.ty() - start[1]; val dz = pose.tz() - start[2]
            val c = kotlin.math.cos(alignYaw); val s = kotlin.math.sin(alignYaw)
            val time = frame.timestamp
            val px = smooth[0].filter(c * dx + s * dz, time)
            val py = smooth[1].filter(dy, time)
            val pz = smooth[2].filter(-s * dx + c * dz, time)
            synchronized(position) {
                position[0] = px
                position[1] = py
                position[2] = pz
            }
        }
        if (!wantImage) return null
        val image: Image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return null
        // Copy the planes now (the image must go back to ARCore), convert later off the GL thread.
        val timestamp = image.timestamp
        val width = image.width
        val height = image.height
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yBytes = copy(y.buffer); val uBytes = copy(u.buffer); val vBytes = copy(v.buffer)
        val yStride = y.rowStride; val uvStride = u.rowStride; val uvPixel = u.pixelStride
        image.close()
        val rotation = imageRotation
        val map = imageToView.copyOf()
        return {
            val bitmap = yuvToBitmap(width, height, yBytes, uBytes, vBytes, yStride, uvStride, uvPixel).rotate(rotation)
            CameraFrame(bitmap, timestamp, map[0], map[1], map[2], map[3])
        }
    }

    /** How the CPU image sits on the eye's view: its rotation and the box it covers (may exceed 0..1). */
    private fun updateImageMapping(frame: Frame) {
        val size = frame.camera.imageIntrinsics.imageDimensions
        val w = size[0].toFloat(); val h = size[1].toFloat()
        val corners = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
        val out = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW_NORMALIZED, out)
        // Where the image's x axis points on the view (y down): that is how far to turn it upright.
        val ex = out[2] - out[0]; val ey = out[3] - out[1]
        imageRotation = ((Math.toDegrees(atan2(ey, ex).toDouble()) / 90.0).roundToInt() * 90 + 360) % 360
        val xs = listOf(out[0], out[2], out[4], out[6]); val ys = listOf(out[1], out[3], out[5], out[7])
        imageToView = floatArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    private fun wrap(angle: Float): Float {
        var a = angle
        while (a > Math.PI) a -= (2 * Math.PI).toFloat()
        while (a < -Math.PI) a += (2 * Math.PI).toFloat()
        return a
    }

    companion object {
        private const val TAG = "PhoneXR-AR"

        /** ARCore's answer can take a moment on the first call: CHECKING means ask again shortly. */
        fun availability(activity: Activity): Availability {
            val answer = runCatching { ArCoreApk.getInstance().checkAvailability(activity) }.getOrNull() ?: return Availability.MISSING
            return when {
                answer == ArCoreApk.Availability.SUPPORTED_INSTALLED -> Availability.READY
                answer.isTransient -> Availability.CHECKING
                answer == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED || answer == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                    runCatching { ArCoreApk.getInstance().requestInstall(activity, true) }
                    Availability.MISSING
                }
                else -> Availability.MISSING
            }
        }

        /** An ARCore session, or null when it cannot start (then the home stays 3DoF). */
        fun create(activity: Activity): ArTracker? = runCatching {
            val session = Session(activity)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                // Room geometry exists only in 6DoF. Horizontal and vertical planes are the basis
                // for walls, tables and collision-aware PhoneXR experiences.
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            session.configure(config)
            ArTracker(session)
        }.onFailure { Log.w(TAG, "ARCore session failed", it) }.getOrNull()

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        private fun copy(buffer: ByteBuffer): ByteArray {
            buffer.rewind()
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }

        private fun yuvToBitmap(
            width: Int, height: Int, yData: ByteArray, uData: ByteArray, vData: ByteArray,
            yStride: Int, uvStride: Int, uvPixel: Int,
        ): Bitmap {
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val yRow = row * yStride
                val uvRow = (row shr 1) * uvStride
                for (col in 0 until width) {
                    val yy = (yData[yRow + col].toInt() and 0xff) - 16
                    val uvIndex = uvRow + (col shr 1) * uvPixel
                    val uu = (uData.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                    val vv = (vData.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                    val c = 1192 * yy.coerceAtLeast(0)
                    val r = ((c + 1634 * vv) shr 10).coerceIn(0, 255)
                    val g = ((c - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                    val b = ((c + 2066 * uu) shr 10).coerceIn(0, 255)
                    pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
