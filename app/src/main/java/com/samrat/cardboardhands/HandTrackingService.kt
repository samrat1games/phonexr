package com.samrat.cardboardhands

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Camera tracking that remains active while an OpenXR game is in front. */
class HandTrackingService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val transportExecutor = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)
    private var tracker: HandTracker? = null
    private var lastFrameMs = 0L
    private val socket = DatagramSocket()
    private val stableLeft = StableHand(.34f)
    private val stableRight = StableHand(.66f)
    private val pinchLatches = arrayOf(HandGestures.PinchLatch(), HandGestures.PinchLatch())
    private var joyCons: JoyConTracker? = null
    private val vision = JoyConVision()
    private val markers by lazy { JoyConMarkers() }
    @Volatile private var markerPoses = arrayOf(MarkerPose(), MarkerPose())
    private val markerSeenAtMs = LongArray(2)
    /** Latest Joy-Con seen by the camera and when, per side. */
    @Volatile private var seen = arrayOf(JoyConVision.Detection(), JoyConVision.Detection())
    private val seenAtMs = LongArray(2)
    @Volatile private var settings = Settings.State()
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applySettings()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PhoneXR Hand Tracking")
            .setContentText("Жесты рук передаются в OpenXR")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        )
        applySettings()
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            IntentFilter(Settings.ACTION_APPLY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        joyCons = JoyConTracker(this)
        transportExecutor.scheduleAtFixedRate({ sendLatest() }, 0L, 16L, TimeUnit.MILLISECONDS)
        // GPU delegates must be created and invoked on the same thread.
        trackingExecutor.execute {
            tracker = try {
                HandTracker(this, useGpu = true, onResult = ::onHands)
            } catch (_: Throwable) {
                HandTracker(this, useGpu = false, onResult = ::onHands)
            }
            ContextCompat.getMainExecutor(this).execute { bindCamera() }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // Markers are small in the image; they need a sharper frame than hands.
            // A sharper frame gives steadier landmarks; markers need it anyway.
            // Lite looks at a smaller frame: fewer pixels is the cheapest speed there is.
            val size = android.util.Size(640, 480)
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    // Colour search is cheap, so the camera Joy-Con mode uses every frame it can.
                    val interval = if (settings.cameraJoyCons || settings.markerJoyCons) 25 else 30
                    if (timestamp - lastFrameMs >= interval && busy.compareAndSet(false, true)) {
                        lastFrameMs = timestamp
                        val frame = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        trackingExecutor.execute {
                            try {
                                if (settings.markerJoyCons) findMarkers(frame.rotate(rotation))
                                else if (settings.cameraJoyCons) findJoyCons(frame.rotate(rotation))
                                else tracker?.detect(frame, timestamp, rotation)
                            }
                            finally { if (!frame.isRecycled) frame.recycle(); busy.set(false) }
                        }
                    }
                } catch (_: Throwable) {
                    busy.set(false)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun findMarkers(frame: Bitmap) {
        val (left, right) = markers.process(frame)
        if (!frame.isRecycled) frame.recycle()
        val now = android.os.SystemClock.elapsedRealtime()
        if (left.found) markerSeenAtMs[0] = now
        if (right.found) markerSeenAtMs[1] = now
        markerPoses = arrayOf(if (left.found) left else markerPoses[0], if (right.found) right else markerPoses[1])
    }

    private fun markerHand(slot: Int, connected: Boolean): HandState {
        val pose = markerPoses[slot]
        val visible = pose.found && android.os.SystemClock.elapsedRealtime() - markerSeenAtMs[slot] < LOST_MS
        // Monado places the hand at 0.35 + z * 0.45 m in front of the eyes.
        val depth = ((pose.distance - 0.35f) / 0.45f).coerceIn(0f, 1f)
        val x = if (pose.found) pose.x else if (slot == 0) .34f else .66f
        return HandState(visible || connected, x = x, y = pose.y, z = depth)
    }

    private fun findJoyCons(frame: Bitmap) {
        val current = settings
        val (left, right) = vision.process(frame, current.leftColor, current.rightColor)
        if (!frame.isRecycled) frame.recycle()
        val now = android.os.SystemClock.elapsedRealtime()
        listOf(left, right).forEachIndexed { slot, detection ->
            if (detection.found) {
                seenAtMs[slot] = now
            } else if (now - seenAtMs[slot] > LOST_MS) {
                vision.reset(slot)
            }
        }
        seen = arrayOf(
            if (left.found) left else seen[0],
            if (right.found) right else seen[1]
        )
    }

    /** Camera Joy-Con state in the hand message: position from the blob, no finger gestures. */
    private fun cameraHand(slot: Int, connected: Boolean): HandState {
        val detection = seen[slot]
        val visible = detection.found && android.os.SystemClock.elapsedRealtime() - seenAtMs[slot] < LOST_MS
        // Joy-Con is about 36 mm thick: 0.08 of the image width is close to the camera, 0.02 is an arm away.
        val depth = ((0.08f - detection.thickness) / 0.06f).coerceIn(0f, 1f)
        // A connected Joy-Con out of view stays where it was last seen instead of vanishing.
        // Never seen yet: rest to the side the Joy-Con belongs to.
        val x = if (detection.found) detection.x else if (slot == 0) .34f else .66f
        return HandState(visible || connected, x = x, y = detection.y, z = depth)
    }

    private fun onHands(result: HandLandmarkerResult) {
        var left = HandState()
        var right = HandState()
        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val reported = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().orEmpty()
            // MediaPipe handedness assumes a mirrored selfie image; the back camera is not mirrored.
            val physicalLeft = reported.equals("Right", true)
            val state = classify(points, physicalLeft).let { hand ->
                val gesture = HandGestures.shape(points, physicalLeft)
                val pinch = pinchLatches[if (physicalLeft) 0 else 1].update(gesture)
                hand.copy(pinch = pinch, palmToFace = gesture.palmToFace)
            }
            if (physicalLeft) left = state else right = state
        }
        stableLeft.update(left)
        stableRight.update(right)
    }

    private fun classify(p: List<NormalizedLandmark>, physicalLeft: Boolean): HandState {
        fun d(a: Int, b: Int): Float {
            val dx = p[a].x() - p[b].x()
            val dy = p[a].y() - p[b].y()
            return sqrt(dx * dx + dy * dy)
        }
        fun extended(mcp: Int, pip: Int, tip: Int): Boolean {
            val ax = p[mcp].x() - p[pip].x()
            val ay = p[mcp].y() - p[pip].y()
            val bx = p[tip].x() - p[pip].x()
            val by = p[tip].y() - p[pip].y()
            val length = sqrt((ax * ax + ay * ay) * (bx * bx + by * by)).coerceAtLeast(.0001f)
            val jointCosine = (ax * bx + ay * by) / length
            return jointCosine < -.48f && d(0, tip) > d(0, pip) * 1.01f
        }
        val index = extended(5, 6, 8)
        val middle = extended(9, 10, 12)
        val ring = extended(13, 14, 16)
        val pinky = extended(17, 18, 20)
        val palmWidth = d(5, 17).coerceAtLeast(0.035f)
        val thumb = extended(2, 3, 4) && d(4, 5) > palmWidth * .62f
        val folded = listOf(index, middle, ring, pinky).count { !it }
        val indexOnly = index && !middle && !ring && !pinky
        val thumbOnly = thumb && !index && folded >= 3
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        val tightlyFolded = tips.indices.count { d(0, tips[it]) < d(0, pips[it]) * 1.04f }
        // A pointing finger or a visible thumb is a button gesture, never a fist.
        val fist = tightlyFolded >= 3 && !indexOnly && !thumbOnly
        val palmX = (p[0].x() + p[5].x() + p[9].x() + p[13].x() + p[17].x()) / 5f
        val palmY = (p[0].y() + p[5].y() + p[9].y() + p[13].y() + p[17].y()) / 5f
        val depth = ((0.17f - palmWidth) / 0.13f).coerceIn(0f, 1f)
        fun jointCurl(a: Int, joint: Int, b: Int): Float {
            val ax = p[a].x() - p[joint].x(); val ay = p[a].y() - p[joint].y(); val az = p[a].z() - p[joint].z()
            val bx = p[b].x() - p[joint].x(); val by = p[b].y() - p[joint].y(); val bz = p[b].z() - p[joint].z()
            val length = sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz)).coerceAtLeast(.0001f)
            val cosine = (ax * bx + ay * by + az * bz) / length
            return ((cosine + .82f) / 1.64f).coerceIn(0f, 1f)
        }
        fun fingerCurl(mcp: Int, pip: Int, dip: Int, tip: Int) =
            (jointCurl(mcp, pip, dip) * .55f + jointCurl(pip, dip, tip) * .45f).coerceIn(0f, 1f)
        fun direction(from: Int, to: Int): Vec3 {
            val x = p[to].x() - p[from].x(); val y = -(p[to].y() - p[from].y()); val z = -(p[to].z() - p[from].z())
            val n = sqrt(x * x + y * y + z * z).coerceAtLeast(.0001f)
            return Vec3(x / n, y / n, z / n)
        }
        fun cross(a: Vec3, b: Vec3): Vec3 {
            val x = a.y * b.z - a.z * b.y; val y = a.z * b.x - a.x * b.z; val z = a.x * b.y - a.y * b.x
            val n = sqrt(x * x + y * y + z * z).coerceAtLeast(.0001f)
            return Vec3(x / n, y / n, z / n)
        }
        val side = if (physicalLeft) direction(17, 5) else direction(5, 17)
        val palmUp = direction(0, 9)
        val forward = cross(side, palmUp)
        val up = cross(forward, side)
        val q = matrixQuaternion(side, up, forward)
        return HandState(
            true, fist, indexOnly, thumbOnly, palmX, palmY, depth,
            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
            thumbCurl = fingerCurl(1, 2, 3, 4),
            indexCurl = fingerCurl(5, 6, 7, 8),
            middleCurl = fingerCurl(9, 10, 11, 12),
            ringCurl = fingerCurl(13, 14, 15, 16),
            pinkyCurl = fingerCurl(17, 18, 19, 20),
        )
    }

    private fun applySettings() {
        settings = Settings.load(this)
        stableLeft.configure(settings.trackingSmoothness)
        stableRight.configure(settings.trackingSmoothness)
        JoyConButtons.apply(settings)
    }

    private fun sendLatest() {
        val current = settings
        var leftJoy = joyCons?.pose(true) ?: JoyConTracker.Pose()
        var rightJoy = joyCons?.pose(false) ?: JoyConTracker.Pose()
        var left = stableLeft.snapshot(leftJoy.connected)
        var right = stableRight.snapshot(rightJoy.connected)
        if (current.markerJoyCons) {
            left = markerHand(0, leftJoy.connected)
            right = markerHand(1, rightJoy.connected)
            markerPoses[0].let { leftJoy = JoyConTracker.Pose(leftJoy.connected, it.qx, it.qy, it.qz, it.qw) }
            markerPoses[1].let { rightJoy = JoyConTracker.Pose(rightJoy.connected, it.qx, it.qy, it.qz, it.qw) }
        } else if (current.cameraJoyCons) {
            left = cameraHand(0, leftJoy.connected)
            right = cameraHand(1, rightJoy.connected)
            // A working Joy-Con gyroscope stays the better source of rotation; otherwise the camera gives it.
            leftJoy = cameraRotation(0, leftJoy, gyroWorks(true))
            rightJoy = cameraRotation(1, rightJoy, gyroWorks(false))
        } else if (current.handMode == Settings.HandMode.HANDS) {
            // Plain hand tracking: fingers move the hand, they never press anything.
            left = left.copy(fist = false, index = false, thumb = false)
            right = right.copy(fist = false, index = false, thumb = false)
        }
        // Quest-style gestures for games: a pinch clicks (the runtime's trigger comes from the fist field),
        // a real fist grabs (squeeze). With a Joy-Con in hand its buttons do this instead.
        var leftMask = JoyConButtons.mask(true)
        var rightMask = JoyConButtons.mask(false)
        if (current.handMode == Settings.HandMode.CONTROLLERS && !current.markerJoyCons && !current.cameraJoyCons) {
            if (!leftJoy.connected) {
                if (left.fist) leftMask = leftMask or JoyConButtons.SQUEEZE
                left = left.copy(fist = left.pinch)
            }
            if (!rightJoy.connected) {
                if (right.fist) rightMask = rightMask or JoyConButtons.SQUEEZE
                right = right.copy(fist = right.pinch)
            }
        }
        val flags = (if (current.sixDof) 1 else 0) or (if (current.handMode == Settings.HandMode.HANDS) 2 else 0)
        val leftStick = JoyConButtons.stick(left = true)
        val rightStick = JoyConButtons.stick(left = false)
        val leftRotation = if (leftJoy.connected) floatArrayOf(leftJoy.x, leftJoy.y, leftJoy.z, leftJoy.w)
            else floatArrayOf(left.qx, left.qy, left.qz, left.qw)
        val rightRotation = if (rightJoy.connected) floatArrayOf(rightJoy.x, rightJoy.y, rightJoy.z, rightJoy.w)
            else floatArrayOf(right.qx, right.qy, right.qz, right.qw)
        val message = String.format(
            Locale.US,
            "PH6 %d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f %.3f %.3f %.3f %.3f %.3f " +
                "%d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f %.3f %.3f %.3f %.3f %.3f %d " +
                "%d %d %d %d",
            left.present.i, left.fist.i, left.index.i, left.thumb.i, left.x, left.y, left.z,
            leftRotation[0], leftRotation[1], leftRotation[2], leftRotation[3], leftMask,
            leftStick[0], leftStick[1], left.thumbCurl, left.indexCurl, left.middleCurl, left.ringCurl, left.pinkyCurl,
            right.present.i, right.fist.i, right.index.i, right.thumb.i, right.x, right.y, right.z,
            rightRotation[0], rightRotation[1], rightRotation[2], rightRotation[3], rightMask,
            rightStick[0], rightStick[1], right.thumbCurl, right.indexCurl, right.middleCurl, right.ringCurl, right.pinkyCurl, flags,
            left.pinch.i, left.palmToFace.i, right.pinch.i, right.palmToFace.i
        )
        // The bundled runtime still understands PH5; SDK clients receive PH6 with finger curls.
        val runtimeMessage = String.format(
            Locale.US,
            "PH5 %d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f " +
                "%d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d %.3f %.3f %d %d %d %d %d",
            left.present.i, left.fist.i, left.index.i, left.thumb.i, left.x, left.y, left.z,
            leftRotation[0], leftRotation[1], leftRotation[2], leftRotation[3], leftMask, leftStick[0], leftStick[1],
            right.present.i, right.fist.i, right.index.i, right.thumb.i, right.x, right.y, right.z,
            rightRotation[0], rightRotation[1], rightRotation[2], rightRotation[3], rightMask, rightStick[0], rightStick[1], flags,
            left.pinch.i, left.palmToFace.i, right.pinch.i, right.palmToFace.i
        )
        // Monado listens on IPv4. Android may resolve getLoopbackAddress() to ::1.
        val loopback = InetAddress.getByName("127.0.0.1")
        // RUNTIME_PORT feeds Monado, SDK_PORT feeds a game that wants the raw hand and Joy-Con data.
        for ((port, payload) in arrayOf(RUNTIME_PORT to runtimeMessage, SDK_PORT to message)) {
            val bytes = payload.toByteArray(Charsets.US_ASCII)
            try { socket.send(DatagramPacket(bytes, bytes.size, loopback, port)) }
            catch (_: Throwable) { }
        }
    }

    private fun gyroWorks(left: Boolean) = (joyCons?.motion(left)?.rateHz ?: 0f) >= 1f

    private fun cameraRotation(slot: Int, gyro: JoyConTracker.Pose, useGyro: Boolean): JoyConTracker.Pose {
        if (useGyro) return gyro
        val q = seen[slot].quaternion()
        return JoyConTracker.Pose(gyro.connected, q[0], q[1], q[2], q[3])
    }

    override fun onDestroy() {
        unregisterReceiver(settingsReceiver)
        tracker?.close()
        joyCons?.close()
        cameraExecutor.shutdownNow()
        trackingExecutor.shutdownNow()
        transportExecutor.shutdownNow()
        socket.close()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "PhoneXR Hands", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private data class HandState(
        val present: Boolean = false,
        val fist: Boolean = false,
        val index: Boolean = false,
        val thumb: Boolean = false,
        val x: Float = .5f,
        val y: Float = .5f,
        val z: Float = .5f,
        val pinch: Boolean = false,
        val palmToFace: Boolean = false,
        val thumbCurl: Float = 0f,
        val indexCurl: Float = 0f,
        val middleCurl: Float = 0f,
        val ringCurl: Float = 0f,
        val pinkyCurl: Float = 0f,
        val qx: Float = 0f,
        val qy: Float = 0f,
        val qz: Float = 0f,
        val qw: Float = 1f,
    )

    /** Removes landmark jitter and keeps a detected click alive long enough for games to read it. */
    private class StableHand(private val restingX: Float = .5f) {
        private var x = restingX
        private var y = .5f
        private var z = .5f
        // One Euro filters: calm while the hand holds still, responsive when it moves.
        private var fx = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, deadZone = .002f)
        private var fy = HandGestures.OneEuro(minCutoff = .6f, beta = 1.4f, deadZone = .002f)
        private var fz = HandGestures.OneEuro(minCutoff = .3f, beta = .6f, deadZone = .004f)
        private var configured = -1
        private var pinchUntilMs = 0L
        private var palmToFace = false
        private var lastSeenMs = 0L
        private var fistUntilMs = 0L
        private var indexUntilMs = 0L
        private var thumbUntilMs = 0L
        private val curls = FloatArray(5)
        private val rotation = floatArrayOf(0f, 0f, 0f, 1f)

        @Synchronized
        fun configure(amount: Int) {
            val value = amount.coerceIn(0, 100)
            if (value == configured) return
            configured = value
            val t = value / 100f
            val cutoff = 1.3f - 1.05f * t
            val beta = 2.2f - 1.4f * t
            val dead = .0005f + .004f * t
            fx = HandGestures.OneEuro(minCutoff = cutoff, beta = beta, deadZone = dead)
            fy = HandGestures.OneEuro(minCutoff = cutoff, beta = beta, deadZone = dead)
            fz = HandGestures.OneEuro(minCutoff = cutoff * .55f, beta = beta * .5f, deadZone = dead * 1.7f)
        }

        @Synchronized
        fun update(raw: HandState) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (raw.present) {
                val first = lastSeenMs == 0L || now - lastSeenMs > 300L
                if (first) { fx.reset(); fy.reset(); fz.reset() }
                val ns = now * 1_000_000L
                x = fx.filter(raw.x, ns)
                y = fy.filter(raw.y, ns)
                z = fz.filter(raw.z, ns)
                val incoming = floatArrayOf(raw.thumbCurl, raw.indexCurl, raw.middleCurl, raw.ringCurl, raw.pinkyCurl)
                for (i in curls.indices) curls[i] += .52f * (incoming[i] - curls[i])
                var dot = rotation[0] * raw.qx + rotation[1] * raw.qy + rotation[2] * raw.qz + rotation[3] * raw.qw
                val sign = if (dot < 0f) -1f else 1f
                dot = kotlin.math.abs(dot)
                val blend = if (first || dot < .35f) 1f else .42f
                rotation[0] += blend * (raw.qx * sign - rotation[0])
                rotation[1] += blend * (raw.qy * sign - rotation[1])
                rotation[2] += blend * (raw.qz * sign - rotation[2])
                rotation[3] += blend * (raw.qw * sign - rotation[3])
                val qn = sqrt(rotation.sumOf { (it * it).toDouble() }.toFloat()).coerceAtLeast(.0001f)
                for (i in rotation.indices) rotation[i] /= qn
                lastSeenMs = now
                if (raw.pinch) pinchUntilMs = now + 90L
                palmToFace = raw.palmToFace
                if (raw.fist) {
                    fistUntilMs = now + 110L
                    indexUntilMs = 0L
                    thumbUntilMs = 0L
                } else {
                    // An observed open/pointing hand releases a stale false fist immediately.
                    fistUntilMs = 0L
                    if (raw.index) indexUntilMs = now + 150L
                    if (raw.thumb) thumbUntilMs = now + 150L
                }
            }
        }

        @Synchronized
        fun snapshot(controllerConnected: Boolean): HandState {
            val now = android.os.SystemClock.elapsedRealtime()
            val handPresent = now - lastSeenMs < 420L
            return HandState(
                present = handPresent || controllerConnected,
                fist = handPresent && now < fistUntilMs,
                index = handPresent && now < indexUntilMs,
                thumb = handPresent && now < thumbUntilMs,
                x = x,
                y = y,
                z = z,
                pinch = handPresent && now < pinchUntilMs,
                palmToFace = handPresent && palmToFace,
                thumbCurl = curls[0], indexCurl = curls[1], middleCurl = curls[2],
                ringCurl = curls[3], pinkyCurl = curls[4],
                qx = rotation[0], qy = rotation[1], qz = rotation[2], qw = rotation[3]
            )
        }
    }

    /** Quaternion from a column-major orthonormal palm basis. */
    private fun matrixQuaternion(right: Vec3, up: Vec3, forward: Vec3): FloatArray {
        val m00 = right.x; val m01 = up.x; val m02 = forward.x
        val m10 = right.y; val m11 = up.y; val m12 = forward.y
        val m20 = right.z; val m21 = up.z; val m22 = forward.z
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f; q[3] = .25f * s
            q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f; q[3] = (m21 - m12) / s
            q[0] = .25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f; q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s; q[1] = .25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f; q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = .25f * s
        }
        return q
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)

    private val Boolean.i get() = if (this) 1 else 0

    companion object {
        private const val CHANNEL = "phonexr_hands"
        private const val NOTIFICATION_ID = 42
        private const val RUNTIME_PORT = 42424
        private const val SDK_PORT = 42425
        /** A Joy-Con not seen for this long no longer counts as tracked. */
        private const val LOST_MS = 400L
    }
}
