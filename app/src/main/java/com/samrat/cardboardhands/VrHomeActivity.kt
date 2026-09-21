package com.samrat.cardboardhands

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PhoneXR Home, visionOS style, in mixed reality: the camera image fills the view, round app icons
 * float in front, apps open as windows (browser, Minecraft, Spatial Photos) with a move bar,
 * minimize to the dock and close. Pinch clicks; palm toward the face plus a pinch opens the menu.
 * With "controllers" chosen in settings and a Joy-Con connected, ZR or A clicks instead of a pinch.
 */
class VrHomeActivity : Activity(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tracker: HeadTracker
    private val panel = HomePanel()
    /** The app launcher can be hidden from the two-button palm menu without closing app windows. */
    @Volatile private var panelVisible = true
    /** 6DoF with ARCore; null means rotation only (no ARCore, or 6DoF off in settings). */
    @Volatile private var ar: ArTracker? = null
    private var renderer: Renderer? = null
    private val boundary by lazy { Boundary(this) }
    /** Head position in the world (metres), from ARCore; stays zero in 3DoF. */
    private val headPosition = FloatArray(3)
    /** How the last hand-tracking frame lies on the eye's view (ARCore frames): left, top, width, height. */
    @Volatile private var arFrameMap: FloatArray? = null
    /** The latest ARCore camera frame, kept for "take a photo". */
    @Volatile private var arPhoto: Bitmap? = null
    @Volatile private var boundaryWarning = false
    private val keyboard = KeyboardPanel()
    private val keyboardRedraw = AtomicBoolean(true)
    @Volatile private var hoveredKey: String? = null
    /** Window the keyboard was opened for by hand (apps that cannot ask for it themselves). */
    @Volatile private var manualKeyboard: VrWindow? = null
    private val redraw = AtomicBoolean(true)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handTracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var joyConReceiver: BroadcastReceiver? = null

    @Volatile private var frame: Bitmap? = null
    private val frameLock = Any()
    private val frameFresh = AtomicBoolean(false)

    private val windows = CopyOnWriteArrayList<VrWindow>()
    /** GL work that must run on the render thread: creating and deleting window textures. */
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val textures = ConcurrentHashMap<String, Int>()
    private val surfaceTextures = ConcurrentHashMap<String, SurfaceTexture>()
    private val framesReady = ConcurrentHashMap.newKeySet<String>()
    /**
     * How image coordinates map to view tangents on screen, set by the renderer from the eye and camera
     * shapes: a point at image x shows at tan = (x - 0.5) * 2 * viewScaleX. The cursor and hand use the
     * same mapping, so they sit exactly on the fingers seen in the passthrough.
     */
    @Volatile private var viewScaleX = 16f / 9f
    @Volatile private var viewScaleY = 1f
    /** Landmarks of visible hands (x, y pairs, 21 points each), for the white hand overlay. */
    @Volatile private var handPoints: List<FloatArray> = emptyList()
    @Volatile private var pinchPoint: FloatArray? = null

    /** Pointer ray in world space (from the eyes), or null without a hand. */
    @Volatile private var ray: FloatArray? = null
    @Volatile private var hit: Hit? = null
    @Volatile private var pressing = false
    private var focused: VrWindow? = null
        set(value) {
            field = value
            // Joy-Con play an app window (Minecraft) as a gamepad; elsewhere they point and click.
            CinemaActivity.setJoyConPassthrough(this, value?.content is ShizukuAppContent)
        }
    private var drag: Drag? = null
    private var pressedHit: Hit? = null
    /** Pinch thresholds from the setup's calibration. */
    private val pinchLatch by lazy { HandProfile.latch(this) }
    /** First-start setup; null once the home is set up. */
    @Volatile private var onboarding: Onboarding? = null
    private var onboardingTexture = 0
    /** When the home appeared after setup, for its entrance animation. */
    @Volatile private var appearStart = 0L
    /** Bone lengths from the setup's hand scan (null before it). */
    private val handProfile by lazy { HandProfile.bones(this) }
    private var wasPinching = false
    /** ACTION_DOWN generated by bringing an extended index finger close to an app window. */
    private var directTouching = false
    private var joyConDown = false
    private var stickHeldUntil = 0L
    private val filterX = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
    private val filterY = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
    private val steamVrLink = SteamVrLink()
    /** Which hand drives the cursor: the one that pinches first keeps it until it lets go. */
    private var activeLeft: Boolean? = null
    private var lastMoveSent = 0L

    private var games = emptyMap<String, GameLibrary.Game>()
    private var storeApps = emptyList<WebApps.App>()

    private sealed class Hit {
        data class Panel(val u: Float, val v: Float) : Hit()
        data class Setup(val u: Float, val v: Float) : Hit()
        data class Content(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class Bar(val window: VrWindow) : Hit()
        data class Minimize(val window: VrWindow) : Hit()
        data class Close(val window: VrWindow) : Hit()
        data class Toolbar(val window: VrWindow, val u: Float) : Hit()
        data class Resize(val window: VrWindow) : Hit()
        data class KeyboardButton(val window: VrWindow) : Hit()
        data class Keyboard(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class DesktopWidth(val window: VrWindow, val wider: Boolean) : Hit()
        data class DesktopCurve(val window: VrWindow) : Hit()
    }

    /** The window the VR keyboard types into, if it is shown. */
    private fun keyboardWindow(): VrWindow? {
        val manual = manualKeyboard?.takeIf { it in windows && !it.minimized }
        if (manual != null) return manual
        return windows.firstOrNull { !it.minimized && it.content.keyboardRequested }
    }

    private fun keyboardCenterY(window: VrWindow) = window.height - window.heightM / 2 - BAR_OFFSET - .09f - KEYBOARD_H / 2

    private data class Drag(val window: VrWindow, val startYaw: Float, val startHeight: Float, val pointerYaw: Float, val pointerHeight: Float, val resize: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        L10n.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(Renderer().also { renderer = it })
            setOnClickListener { recenter() }
        }
        setContentView(surfaceView)
        panel.compact = Settings.homeStyle(this) == Settings.HomeStyle.COMPACT
        if (!Settings.setupDone(this)) onboarding = Onboarding(this, onboardingHost)
        // Lite keeps to 3DoF: ARCore is the heaviest thing a budget phone would be asked to run.
        if (!BuildConfig.LITE && Settings.load(this).sixDof && ArTracker.availability(this) == ArTracker.Availability.READY) {
            ar = ArTracker.create(this)
        }
        trackingExecutor.execute {
            handTracker = runCatching { HandTracker(this, useGpu = true, onResult = ::onHands) }
                .getOrElse { HandTracker(this, useGpu = false, onResult = ::onHands) }
        }
    }

    override fun onResume() {
        super.onResume()
        DisplayRate.apply(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        stopService(Intent(this, HandTrackingService::class.java))
        // ARCore owns the camera in 6DoF; if it cannot start, CameraX gives 3DoF passthrough.
        if (ar?.resume() == false) { ar?.close(); ar = null; toast("6DoF недоступен: работает 3DoF") }
        surfaceView.onResume()
        tracker.travelMode = Settings.travelMode(this)
        tracker.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) toast("Разрешите PhoneXR доступ к камере")
        else if (ar == null) {
            bindCamera()
            if (Settings.load(this).sixDof) startArLater()
        }
        joyConReceiver = JoyConBridge.listen(this) { onJoyCon(it) }
        JoyConBridge.watch(this, watching = true, learning = false)
        CinemaActivity.setJoyConPassthrough(this, false)
        loadApps()
        Calls.localHands = { handPoints }
        Calls.localHandImage = if (BuildConfig.LITE) ({ null }) else ({ handFrameForCall() })
        Calls.unlisten(callListener)
        Calls.listen(callListener)
        thread(name = "PhoneXR calls start") { Calls.start(this) }
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        surfaceView.onPause()
        ar?.pause()
        tracker.stop()
        cameraProvider?.unbindAll()
        JoyConBridge.watch(this, watching = false, learning = false)
        joyConReceiver?.let { unregisterReceiver(it) }
        joyConReceiver = null
    }

    override fun onDestroy() {
        Calls.unlisten(callListener)
        Calls.stop()
        Calls.localHandImage = { null }
        steamVrLink.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        windows.forEach { it.content.release() }
        ar?.close()
        cameraExecutor.shutdownNow()
        trackingExecutor.execute { handTracker?.close() }
        trackingExecutor.shutdown()
        super.onDestroy()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * ARCore may still be checking when the home opens: ask again for a few seconds, then move the
     * camera from CameraX (3DoF) to ARCore (6DoF) on the fly.
     */
    private fun startArLater(attempt: Int = 0) {
        if (ar != null || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (BuildConfig.LITE) return
        when (ArTracker.availability(this)) {
            ArTracker.Availability.CHECKING -> if (attempt < 40) handler.postDelayed({ startArLater(attempt + 1) }, 250)
            ArTracker.Availability.MISSING -> Unit
            ArTracker.Availability.READY -> {
                cameraProvider?.unbindAll()
                val created = ArTracker.create(this)
                if (created == null || !created.resume()) {
                    created?.close()
                    bindCamera()
                    return
                }
                glTasks += { renderer?.attachAr(created) }
                ar = created
                toast("6DoF включён: можно ходить по комнате")
            }
        }
    }

    private val onboardingHost = object : Onboarding.Host {
        override val sixDof get() = ar != null
        override fun startBoundary() = boundary.startTracing()
        override fun boundaryReady() = boundary.tracing == null && boundary.defined
        override fun capturePersona() = runOnUiThread {
            launchPersonaScanner()
        }
        override fun finish() {
            onboarding = null
            appearStart = SystemClock.elapsedRealtime()
            redraw.set(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERSONA) {
            onboarding?.personaDone()
            windows.mapNotNull { it.content as? SettingsContent }.forEach { it.personaChanged() }
        }
    }

    private fun openCalls() = runOnUiThread { openWindow("calls", tr("Звонки"), ID_CALLS) { CallContent(this) } }

    /** An incoming call brings the Calls window up wherever the user is. */
    private val callListener: () -> Unit = {
        if (Calls.state == Calls.State.RINGING && windows.none { it.id == "calls" && !it.minimized }) openCalls()
    }

    private val storeHost = object : StoreContent.Host {
        override fun openCinema(packageName: String, scene: String) = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) return@runOnUiThread toast("Запустите Shizuku и разрешите доступ PhoneXR")
            startActivity(Intent(this@VrHomeActivity, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, packageName).putExtra(CinemaActivity.EXTRA_SCENE, scene))
        }

        override fun openWebApp(app: WebApps.App) = runOnUiThread {
            openWindow("web:${app.url}", app.name, "web:${app.url}") { BrowserContent(app.url, ::openWebXr) }
        }

        override fun openCalls() = this@VrHomeActivity.openCalls()

        override fun install(file: java.io.File) = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY)
                return@runOnUiThread toast("Для внутренней установки запустите Shizuku и разрешите PhoneXR")
            thread(name = "PhoneXR store prepare") {
                val apk = runCatching {
                    if (file.extension.equals("pxr", true)) PxrPackage.androidApk(this@VrHomeActivity, android.net.Uri.fromFile(file))
                    else ApkPatcher.patch(this@VrHomeActivity, android.net.Uri.fromFile(file)).apk
                }.getOrElse { return@thread toast(it.localizedMessage ?: "Не удалось подготовить игру") }
                runOnUiThread {
                    InternalInstaller.install(this@VrHomeActivity, apk) { problem ->
                        if (problem != null) toast(problem)
                        else {
                            toast("Игра добавлена в PhoneXR")
                            loadApps()
                        }
                    }
                }
            }
        }

        override fun homeChanged() = loadApps()

        override fun message(text: String) = toast(text)
    }

    /** "Straight ahead" and "here" become the current head direction and spot. */
    private fun recenter() {
        tracker.recenter()
        ar?.recenter()
    }

    private val settingsHost = object : SettingsContent.Host {
        override fun trackingText(): String {
            val tracker = ar
            return when {
                tracker == null && !Settings.load(this@VrHomeActivity).sixDof -> "3DoF · 6DoF выключен в настройках"
                tracker == null -> "3DoF · нет ARCore (Google Play Services for AR)"
                tracker.tracking -> "6DoF · ARCore, комната отслеживается"
                else -> "6DoF · ARCore ищет комнату…"
            }
        }

        override fun showPersona() = runOnUiThread {
            if (!Persona.exists(this@VrHomeActivity)) toast("Сначала соберите лицо в приложении PhoneXR")
            else openWindow("persona", tr("Лицо"), ID_PERSONA) { PersonaContent(this@VrHomeActivity) }
        }

        override fun scanPersona() = runOnUiThread {
            if (BuildConfig.LITE) return@runOnUiThread toast("Persona недоступна в Lite")
            launchPersonaScanner()
        }

        override fun startBoundary() = runOnUiThread { startBoundaryTracing() }

        override fun clearBoundary() {
            boundary.clear()
            toast("Граница удалена")
        }

        override fun boundaryText(): String = when {
            ar == null -> "Нужен 6DoF (ARCore): без него граница не работает"
            boundary.defined -> "Граница задана"
            else -> "Граница не задана"
        }

        override fun startRoomScan() = runOnUiThread {
            val tracker = ar ?: return@runOnUiThread toast("Сканирование комнаты работает только в 6DoF")
            tracker.recenter()
            windows.firstOrNull { it.id == "settings" }?.let { minimize(it) }
            toast("Медленно осмотрите пол, стены и столы — найденные поверхности появятся автоматически")
        }

        override fun roomText(): String {
            val tracker = ar ?: return "Комната недоступна"
            return if (!tracker.tracking) "Камера ищет окружение…"
            else "Найдено: пол/столы — ${tracker.horizontalPlanes}, стены — ${tracker.verticalPlanes}"
        }

        override fun openSystemSettings() = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) return@runOnUiThread toast("Сначала запустите Shizuku")
            openWindow("android-settings", "Wi‑Fi и Bluetooth", ID_SETTINGS) {
                ShizukuAppContent(this@VrHomeActivity, "com.android.settings") { toast(it) }
            }
        }

        override fun requestShizuku() = runOnUiThread {
            when (VirtualScreen.access()) {
                VirtualScreen.Access.READY -> toast("Shizuku уже подключён")
                VirtualScreen.Access.NEEDS_PERMISSION -> VirtualScreen.requestPermission()
                VirtualScreen.Access.NOT_RUNNING -> toast("Запустите Shizuku на телефоне, затем вернитесь в VR")
            }
        }

        override fun shizukuText(): String = when (VirtualScreen.access()) {
            VirtualScreen.Access.READY -> "Подключён"
            VirtualScreen.Access.NEEDS_PERMISSION -> "Нужно разрешение"
            VirtualScreen.Access.NOT_RUNNING -> "Не запущен"
        }

        override fun setHomeStyle(style: Settings.HomeStyle) = runOnUiThread {
            panel.compact = style == Settings.HomeStyle.COMPACT
            redraw.set(true)
        }
    }

    /** CameraX must release passthrough before the face scanner can bind the same camera. */
    private fun launchPersonaScanner() {
        cameraProvider?.unbindAll()
        ar?.pause()
        handler.postDelayed({
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, PersonaCaptureActivity::class.java), REQUEST_PERSONA)
        }, 250L)
    }

    private fun startBoundaryTracing() {
        if (ar == null) return toast("Граница работает только в 6DoF (нужен ARCore)")
        windows.firstOrNull { it.id == "settings" }?.let { minimize(it) }
        switchMode(HomePanel.Mode.HOME)
        boundary.startTracing()
        toast("Обойдите край свободного места. Круг замкнётся сам, щипок — готово")
    }

    // ------------------------------------------------------------------ Apps and windows

    private fun loadApps() {
        thread(name = "PhoneXR home apps") {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
                // Games still to be patched are left out: patching happens in the PhoneXR app, not in VR.
                .filterNot {
                    it.kind == GameLibrary.Kind.VRAPI_ORIGINAL || it.kind == GameLibrary.Kind.VRAPI_UNSUPPORTED ||
                        it.kind == GameLibrary.Kind.OPENXR_ORIGINAL
                }
            games = found.associateBy { it.packageName }
            // Minecraft stays in the PhoneXR app (PXR Bedrock), not on the MR home screen.
            // PhoneXR icons (light or dark, chosen with a long pinch on the home) where the pack has one.
            fun own(id: String, drawn: () -> Drawable) = IconPack.OWN[id]?.let { IconPack.icon(this, it) } ?: drawn()
            val own = listOf(
                HomePanel.Entry(ID_BROWSER, tr("Браузер"), own(ID_BROWSER) { drawBrowserIcon() }),
                HomePanel.Entry(ID_PHOTOS, tr("Фото"), own(ID_PHOTOS) { drawPhotosIcon() }),
                HomePanel.Entry(ID_SETTINGS, tr("Настройки"), own(ID_SETTINGS) { symbolIcon("⚙", Color.rgb(142, 142, 147)) }),
                HomePanel.Entry(ID_STORE, tr("Магазин"), own(ID_STORE) { drawStoreIcon() }),
                HomePanel.Entry(ID_CALLS, tr("Звонки"), own(ID_CALLS) { symbolIcon("✆", Color.rgb(48, 209, 88)) }),
                HomePanel.Entry(ID_DESKTOP, tr("Компьютер"), own(ID_DESKTOP) { symbolIcon("▣", Color.rgb(10, 132, 255)) }),
                HomePanel.Entry(ID_LEOS, "LeOS", own(ID_LEOS) { symbolIcon("L", Color.rgb(88, 86, 214)) }),
            ) + (if (BuildConfig.LITE) emptyList() else listOf(HomePanel.Entry(ID_ELIX, "Elix", drawElixIcon()))) +
                if (BuildConfig.LITE || AndroidAppsContent.enabled(this)) listOf(HomePanel.Entry(ID_ANDROID, "Android", own(ID_ANDROID) { symbolIcon("▦", Color.rgb(61, 220, 132)) })) else emptyList()
            val vr = found.map {
                HomePanel.Entry("app:${it.packageName}", it.label,
                    IconPack.icon(this, it.packageName) ?: runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull())
            }
            val web = WebApps.installed(this).map { app ->
                HomePanel.Entry("web:${app.url}", app.name, WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name))
            }
            synchronized(panel) { panel.setHome(own + vr + web) }
            updateDock()
            storeApps = WebApps.fromStore()
            refreshStore()
        }
    }

    private fun refreshStore() {
        val installed = WebApps.installed(this).map { it.url }.toSet()
        val entries = storeApps.map { app ->
            HomePanel.Entry(
                "store:${app.url}", app.name,
                WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name),
                badge = if (app.url in installed) "✓" else "+"
            )
        }
        synchronized(panel) { panel.setStore(entries) }
        redraw.set(true)
    }

    private fun openEntry(entry: HomePanel.Entry) {
        val id = entry.id
        when {
            id == ID_BROWSER -> openWindow("browser", tr("Браузер"), ID_BROWSER) { BrowserContent(BrowserContent.HOME, ::openWebXr) }
            id == ID_PHOTOS -> openWindow("photos", tr("Фото"), ID_PHOTOS) {
                PhotosContent(
                    this,
                    onVideo = { uri, name, width, height ->
                        // A video from the gallery gets its own window, in 3D when it holds two eyes.
                        runOnUiThread {
                            openWindow("video:$uri", name.ifEmpty { tr("Видео") }, ID_PHOTOS) {
                                VideoContent(this, uri, name, width, height) { message -> toast(message) }
                            }
                        }
                    },
                    onPanorama = { uri, name, width, height, video ->
                        runOnUiThread {
                            startActivity(
                                Intent(this, PanoramaActivity::class.java)
                                    .putExtra(PanoramaActivity.EXTRA_URI, uri.toString())
                                    .putExtra(PanoramaActivity.EXTRA_NAME, name)
                                    .putExtra(PanoramaActivity.EXTRA_VIDEO, video)
                                    .putExtra(PanoramaActivity.EXTRA_WIDTH, width)
                                    .putExtra(PanoramaActivity.EXTRA_HEIGHT, height)
                            )
                        }
                    },
                )
            }
            id == ID_SETTINGS -> openWindow("settings", tr("Настройки"), ID_SETTINGS) { SettingsContent(this, settingsHost) }
            id == ID_DESKTOP -> openWindow("desktop", tr("Компьютер"), ID_DESKTOP) { DesktopStreamContent { toast(it) } }
            id == ID_LEOS -> openWindow("leos", "LeOS", ID_LEOS) { BrowserContent("file:///android_asset/leos/index.html", ::openWebXr) }
            id == ID_ANDROID -> openWindow("android", tr("Android‑приложения"), ID_ANDROID) {
                AndroidAppsContent(this) { name, label ->
                    runOnUiThread {
                        if (VirtualScreen.access() != VirtualScreen.Access.READY) toast("Запустите Shizuku и разрешите доступ PhoneXR")
                        else openWindow("app:$name", label, ID_ANDROID) { ShizukuAppContent(this, name) { toast(it) } }
                    }
                }
            }
            id == MENU_BOUNDARY -> startBoundaryTracing()
            id == ID_MINECRAFT -> {
                if (runCatching { packageManager.getApplicationInfo(MINECRAFT, 0) }.isFailure) {
                    toast("Установите Minecraft из Google Play")
                } else {
                    openWindow("minecraft", "Minecraft", ID_MINECRAFT) { ShizukuAppContent(this, MINECRAFT) { toast(it) } }
                }
            }
            id == ID_STORE -> openWindow("store", tr("Магазин"), ID_STORE) { StoreContent(this, storeHost) }
            id.startsWith("env:") -> setEnvironment(id.removePrefix("env:"))
            id.startsWith("person:") -> openCalls()
            id == ID_CALLS -> openCalls()
            id == ID_ELIX && BuildConfig.LITE -> toast("Elix есть только в полной версии PhoneXR")
            id == ID_ELIX -> openWindow("elix", "Elix", ID_ELIX) {
                ElixContent(this) { action -> runOnUiThread { openEntry(HomePanel.Entry(action, "", null)) } }
            }
            id.startsWith("app:") -> launchGame(id.removePrefix("app:"))
            id.startsWith("web:") -> id.removePrefix("web:").let { url -> openWindow("web:$url", entry.label, id) { BrowserContent(url, ::openWebXr) } }
            id.startsWith("dock:") -> windows.firstOrNull { it.id == id.removePrefix("dock:") }?.let { restore(it) }
            id.startsWith("store:") -> {
                val app = storeApps.firstOrNull { it.url == id.removePrefix("store:") } ?: return
                if (WebApps.installed(this).any { it.url == app.url }) {
                    openWindow("web:${app.url}", app.name, "web:${app.url}") { BrowserContent(app.url, ::openWebXr) }
                } else {
                    WebApps.add(this, app)
                    toast("«${app.name}» добавлено на главный экран")
                    thread { refreshStore(); loadApps() }
                }
            }
            id == MENU_PHOTO -> takePhoto()
            id == MENU_TOGGLE_APPS -> {
                panelVisible = !panelVisible
                redraw.set(true)
            }
            id == MENU_RECENTER -> { recenter(); switchMode(HomePanel.Mode.HOME) }
            id == MENU_HOME -> switchMode(HomePanel.Mode.HOME)
            id == MENU_EXIT -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }

    /** Opens a window, or brings back the one already open with that id. */
    private fun openWindow(id: String, title: String, iconId: String, content: () -> VrWindow.Content) {
        windows.firstOrNull { it.id == id }?.let { return restore(it) }
        val window = VrWindow(id, title, iconId, content())
        val open = windows.count { !it.minimized }
        window.yaw = if (open == 0) 0f else (if (open % 2 == 1) -32f else 32f) * ((open + 1) / 2)
        window.height = .05f
        windows += window
        focused = window
        switchMode(HomePanel.Mode.HOME)
        glTasks += {
            val external = window.content.external
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textures[window.id] = ids[0]
            val surface = if (external) SurfaceTexture(ids[0]).also { texture ->
                texture.setOnFrameAvailableListener { framesReady += window.id }
                surfaceTextures[window.id] = texture
            } else null
            window.content.attach(this, surface) {}
        }
    }

    private fun restore(window: VrWindow) {
        window.minimized = false
        focused = window
        updateDock()
    }

    private fun minimize(window: VrWindow) {
        window.minimized = true
        if (focused == window) focused = null
        updateDock()
    }

    private fun close(window: VrWindow) {
        windows.remove(window)
        if (focused == window) focused = null
        window.content.release()
        glTasks += {
            textures.remove(window.id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            surfaceTextures.remove(window.id)?.release()
        }
        updateDock()
    }

    private fun updateDock() {
        val icons = synchronized(panel) { panel.homeIcons() }
        val dock = windows.filter { it.minimized }.map { window ->
            HomePanel.Entry("dock:${window.id}", window.title, icons[window.iconId] ?: letterIcon(window.title))
        }
        synchronized(panel) { panel.setDock(dock) }
        redraw.set(true)
    }

    /** A page asked for an immersive WebXR session: the WebView has none, so the PhoneXR browser (OpenXR) takes over. */
    private fun openWebXr(url: String) = runOnUiThread {
        if (WebApps.browserPackage(this) == null) return@runOnUiThread toast("Для WebXR установите «Браузер PhoneXR» с сайта или из магазина")
        cameraProvider?.unbindAll()
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        if (!WebApps.open(this, url)) toast("Не удалось открыть WebXR")
    }

    /** Real photo: the current passthrough frame goes to the gallery (Pictures/PhoneXR). */
    private fun takePhoto() {
        val bitmap = arPhoto?.takeIf { !it.isRecycled }?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
            ?: synchronized(frameLock) { frame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) }
            ?: return toast("Камера ещё не готова")
        switchMode(HomePanel.Mode.HOME)
        thread(name = "PhoneXR photo") {
            val saved = runCatching { Daydream.savePhoto(this, bitmap) }.isSuccess
            bitmap.recycle()
            toast(if (saved) "Фото сохранено в «Фото»" else "Не удалось сохранить фото")
            windows.forEach { (it.content as? PhotosContent)?.reload() }
        }
    }

    /** Small camera snapshot used to texture real-hand cut-outs in full-version calls. */
    private fun handFrameForCall(): Bitmap? {
        val source = arPhoto?.takeIf { !it.isRecycled }
            ?: synchronized(frameLock) { frame?.takeIf { !it.isRecycled } }
            ?: return null
        return runCatching {
            val width = 320
            Bitmap.createScaledBitmap(source, width, width * source.height / source.width, true)
        }.getOrNull()
    }

    private fun launchGame(packageName: String) {
        val game = games[packageName] ?: return
        val intent = GameLibrary.launchIntent(this, game) ?: return toast("У «${game.label}» нет экрана запуска")
        if (game.kind == GameLibrary.Kind.DAYDREAM && !Daydream.servicesInstalled(this)) {
            toast("Для Daydream нужны VR Services — ставлю Opendream Services")
            runOnUiThread { Daydream.installServices(this) }
            return
        }
        recent.remove(packageName)
        recent.add(0, packageName)
        cameraProvider?.unbindAll()
        // A game that tracks hands itself gets the camera; PhoneXR does not start its own tracking.
        if (GameLibrary.ownsHandTracking(this, packageName)) stopService(Intent(this, HandTrackingService::class.java))
        else ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Compact palm menu: only the two actions that must remain available everywhere. */
    @Volatile private var handMenu: HandMenu? = null

    /** Palm toward the face + pinch: the compact menu above that hand. */
    private fun openHandMenu(holderLeft: Boolean) {
        handMenu = HandMenu(holderLeft, listOf(
            HandMenu.Item(MENU_PHOTO, tr("Скриншот"), symbolIcon("◉", Color.rgb(255, 159, 10))),
            HandMenu.Item(
                MENU_TOGGLE_APPS,
                if (panelVisible) tr("Убрать меню") else tr("Показать меню"),
                symbolIcon(if (panelVisible) "▣" else "▦", Color.rgb(90, 200, 250)),
            ),
        ))
        pinchLatch.reset()
        filterX.reset(); filterY.reset()
    }

    private fun closeHandMenu() {
        handMenu = null
    }

    private fun openMenu() {
        val icons = synchronized(panel) { panel.homeIcons() }
        val entries = windows.map { HomePanel.Entry("dock:${it.id}", it.title, icons[it.iconId] ?: letterIcon(it.title)) } +
            recent.take(3).mapNotNull { name ->
                games[name]?.let { HomePanel.Entry("app:$name", it.label, runCatching { packageManager.getApplicationIcon(name) }.getOrNull()) }
            } + listOf(
            HomePanel.Entry(MENU_HOME, tr("Главная"), symbolIcon("⌂", Color.rgb(90, 90, 100))),
            HomePanel.Entry(ID_ELIX, "Elix", drawElixIcon()),
            HomePanel.Entry(MENU_PHOTO, tr("Снять фото"), symbolIcon("◉", Color.rgb(255, 159, 10))),
            HomePanel.Entry(MENU_RECENTER, tr("Выровнять"), symbolIcon("◎", Color.rgb(48, 176, 199))),
            HomePanel.Entry(MENU_BOUNDARY, tr("Граница"), symbolIcon("⬡", Color.rgb(90, 200, 250))),
            HomePanel.Entry(MENU_EXIT, tr("Выйти из VR"), symbolIcon("✕", Color.rgb(255, 69, 58))),
        )
        synchronized(panel) { panel.setMenu(entries) }
        switchMode(HomePanel.Mode.MENU)
    }

    private fun switchMode(mode: HomePanel.Mode) {
        synchronized(panel) {
            panel.mode = mode
            panel.showPage(0)
        }
        redraw.set(true)
    }

    // ------------------------------------------------------------------ Camera and hands

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(if (BuildConfig.LITE) 960 else 1280, if (BuildConfig.LITE) 540 else 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val upright = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                    synchronized(frameLock) {
                        val old = frame
                        frame = upright
                        if (frameFresh.getAndSet(true) && old != null && old !== upright) old.recycle()
                    }
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    if (busy.compareAndSet(false, true)) {
                        val small = Bitmap.createScaledBitmap(upright, 640, 640 * upright.height / upright.width, true)
                        trackingExecutor.execute {
                            try { handTracker?.detect(small, timestamp) } finally { small.recycle(); busy.set(false) }
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Camera frame failed", error)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
                .onFailure { toast("Камера занята другим приложением") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Joy-Con clicks only when "controllers" is chosen in settings and one is connected; otherwise hands click. */
    private fun controllersClick(): Boolean =
        Settings.load(this).handMode == Settings.HandMode.CONTROLLERS &&
            InputDevice.getDeviceIds().any { JoyConButtons.isJoyCon(InputDevice.getDevice(it)) }

    private fun onHands(result: HandLandmarkerResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        // The camera frame this result belongs to: the head is looked up for that moment.
        val frameNs = result.timestampMs() * 1_000_000L
        // ARCore frames: landmarks are on the upright camera image; place them on the eye's view.
        val map = arFrameMap
        val landmarks = result.landmarks().map { points ->
            if (map == null) points
            else points.map { NormalizedLandmark.create(map[0] + it.x() * map[2], map[1] + it.y() * map[3], it.z()) }
        }
        handPoints = landmarks.filter { it.size >= 21 }.map { points ->
            FloatArray(63) { i -> when (i % 3) { 0 -> points[i / 3].x(); 1 -> points[i / 3].y(); else -> points[i / 3].z() } }
        }
        val hands = landmarks.mapIndexedNotNull { index, points ->
            if (points.size < 21) return@mapIndexedNotNull null
            val physicalLeft = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true)
            physicalLeft to HandGestures.shape(points, physicalLeft)
        }
        val steamHead = FloatArray(16).also { tracker.copyHead(it) }
        val steamPosition = synchronized(headPosition) { headPosition.copyOf() }
        steamVrLink.send(steamHead, steamPosition, hands.map { SteamVrLink.Hand(it.first, it.second) })
        onboarding?.onHands(hands, handPoints)
        // The hand menu rides on the hand that called it; the other hand points at it.
        val menu = handMenu
        if (menu != null) {
            val holder = landmarks.indices.firstOrNull { index ->
                landmarks[index].size >= 21 &&
                    result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true) == menu.holderLeft
            }?.let { landmarks[it] }
            if (holder != null) {
                fun tx(i: Int) = (holder[i].x() - .5f) * 2f * viewScaleX
                fun ty(i: Int) = (.5f - holder[i].y()) * 2f * viewScaleY
                val palm = intArrayOf(0, 5, 9, 17)
                menu.follow(palm.map { tx(it) }.average().toFloat(), palm.map { ty(it) }.average().toFloat(),
                    kotlin.math.hypot(tx(9) - tx(0), ty(9) - ty(0)))
            } else if (System.currentTimeMillis() - menu.lastSeen > 1500) {
                closeHandMenu()
            }
        }
        // Keep the hand that holds the cursor; otherwise a pinching hand, otherwise the nearest one.
        val chosen = if (menu != null) hands.firstOrNull { it.first != menu.holderLeft }
        else hands.firstOrNull { it.first == activeLeft && (pinchLatch.pinching || wasPinching) }
            ?: hands.firstOrNull { it.second.pinchGap < .3f }
            ?: hands.maxByOrNull { it.second.palmWidth }
        if (chosen != null && chosen.first != activeLeft) {
            if (activeLeft != null) { filterX.reset(); filterY.reset() }
            activeLeft = chosen.first
        }
        val hand = chosen?.second
        if (hand == null) {
            activeLeft = null
            filterX.reset(); filterY.reset(); pinchLatch.reset()
            ray = null
            pinchPoint = null
            if (!controllersClick()) release()
            updateHit(null)
            return
        }
        val x = filterX.filter(hand.aimX, now)
        val y = filterY.filter(hand.aimY, now)
        var direction = pointerRay(x, y, frameNs)
        ray = direction
        pinchPoint = floatArrayOf(x, y)
        if (menu != null) {
            // While the menu is open the pointer only chooses in it.
            val lx = (x - .5f) * 2f * viewScaleX
            val ly = (.5f - y) * 2f * viewScaleY
            menu.hover(menu.itemAt(lx, ly))
            updateHit(null)
            val pinchingMenu = pinchLatch.update(hand)
            if (pinchingMenu && !wasPinching) {
                wasPinching = true
                val item = menu.item(menu.itemAt(lx, ly))
                closeHandMenu()
                if (item != null) runOnUiThread { openEntry(HomePanel.Entry(item.id, item.label, item.icon)) }
            } else if (!pinchingMenu) wasPinching = false
            return
        }
        val target = hitTest(direction)
        updateHit(target)
        if (controllersClick()) {
            dragOrMove(direction)
            return
        }
        val pinching = pinchLatch.update(hand)
        // A nearby extended index finger behaves like touching a tablet. This is deliberately
        // limited to app content: system bars and window furniture still require a pinch.
        val directReady = !pinching && hand.indexExtended && hand.palmWidth >= DIRECT_TOUCH_PALM
        if (directReady || directTouching) {
            direction = pointerRay(hand.indexX, hand.indexY, frameNs)
            ray = direction
            val directTarget = hitTest(direction)
            updateHit(directTarget)
            if (directReady && !directTouching && directTarget is Hit.Content) {
                directTouching = true
                press(directTarget, direction)
                return
            }
            if (directReady && directTouching) {
                dragOrMove(direction)
                return
            }
            if (!directReady && directTouching) {
                directTouching = false
                release()
                return
            }
        }
        if (pinching && !wasPinching) {
            wasPinching = true
            if (hand.palmToFace && onboarding == null) {
                openHandMenu(chosen.first)
            } else if (boundary.tracing != null) {
                if (boundary.finish()) toast("Граница сохранена") else toast("Граница слишком маленькая — обойдите комнату")
            } else {
                press(target, direction)
            }
        } else if (pinching) {
            checkLongPress()
            dragOrMove(direction)
        } else if (wasPinching) {
            wasPinching = false
            release()
        }
    }

    private fun onJoyCon(snapshot: JoyConBridge.Snapshot) {
        if (!controllersClick()) return
        val buttons = snapshot.left.buttons or snapshot.right.buttons
        val down = buttons and (JoyConButtons.TRIGGER or JoyConButtons.PRIMARY) != 0
        if (down && !joyConDown) press(hit, ray)
        if (down) checkLongPress()
        if (!down && joyConDown) release()
        joyConDown = down
        if (buttons and JoyConButtons.MENU != 0) openMenu()
        val stick = if (abs(snapshot.right.stickX) > abs(snapshot.left.stickX)) snapshot.right.stickX else snapshot.left.stickX
        if (abs(stick) > .7f && SystemClock.uptimeMillis() > stickHeldUntil) {
            stickHeldUntil = SystemClock.uptimeMillis() + 450
            synchronized(panel) { panel.turnPage(if (stick > 0) 1 else -1) }
            redraw.set(true)
        }
    }

    /** The fingers' pinch point seen from the eyes, as a world direction. */
    private fun pointerRay(x: Float, y: Float, frameNs: Long): FloatArray {
        val head = FloatArray(16)
        tracker.copyHeadAt(frameNs, head)
        val local = floatArrayOf((x - .5f) * 2f * viewScaleX, (.5f - y) * 2f * viewScaleY, -1f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, local, 0)
        return world
    }

    private fun yawOf(direction: FloatArray) = Math.toDegrees(atan2(-direction[0], -direction[2]).toDouble()).toFloat()
    private fun heightAt(direction: FloatArray, radius: Float) = radius * direction[1] / hypot(direction[0], direction[2])

    private fun hitTest(direction: FloatArray?): Hit? {
        direction ?: return null
        if (onboarding != null) {
            val local = toLocal(direction, panelYaw, PANEL_RADIUS) ?: return null
            val height = PANEL_WIDTH * Onboarding.HEIGHT / Onboarding.WIDTH
            val u = (local[0] + PANEL_WIDTH / 2) / PANEL_WIDTH
            val v = (height / 2 - local[1]) / height
            return if (u in 0f..1f && v in 0f..1f) Hit.Setup(u, v) else null
        }
        // The keyboard floats closest to the user.
        keyboardWindow()?.let { window ->
            val local = toLocal(direction, window.yaw, KEYBOARD_RADIUS)
            if (local != null) {
                val u = (local[0] + KEYBOARD_W / 2) / KEYBOARD_W
                val v = (keyboardCenterY(window) + KEYBOARD_H / 2 - local[1]) / KEYBOARD_H
                if (u in 0f..1f && v in 0f..1f) return Hit.Keyboard(window, u, v)
            }
        }
        // Windows are in front of the icons; the focused one first.
        val ordered = windows.filter { !it.minimized }.sortedByDescending { it == focused }
        for (window in ordered) {
            if (window.arcDegrees > 0f) curvedContentHit(direction, window)?.let { return it }
            val local = toLocal(direction, window.yaw, VrWindow.RADIUS) ?: continue
            val x = local[0]
            val y = local[1] - window.height
            val w = window.width / 2
            val h = window.heightM / 2
            // Corner handle: drag to resize.
            if (x > w - .05f && x < w + .09f && y < -h + .05f && y > -h - .09f && !(abs(x) <= w - .06f)) return Hit.Resize(window)
            if (abs(x) <= w && abs(y) <= h) return Hit.Content(window, (x + w) / window.width, (h - y) / window.heightM)
            if (window.content.toolbarTitle() != null && abs(y - (h + TOOLBAR_GAP + TOOLBAR_H / 2)) < TOOLBAR_H / 2 && abs(x) < TOOLBAR_W / 2) {
                return Hit.Toolbar(window, (x + TOOLBAR_W / 2) / TOOLBAR_W)
            }
            val barY = -h - BAR_OFFSET
            val desktopY = barY - .15f
            if (window.id == "desktop" && abs(y - desktopY) < .075f && abs(x) < .57f) {
                return when {
                    x < -.18f -> Hit.DesktopWidth(window, wider = false)
                    x < .18f -> Hit.DesktopWidth(window, wider = true)
                    else -> Hit.DesktopCurve(window)
                }
            }
            if (abs(y - barY) < .05f) {
                if (abs(x) < .22f) return Hit.Bar(window)
                if (abs(x + BUTTON_X) < .05f) return Hit.Minimize(window)
                if (abs(x - BUTTON_X) < .05f) return Hit.Close(window)
                if (abs(x + BUTTON_X + KEYBOARD_BUTTON_GAP) < .05f) return Hit.KeyboardButton(window)
            }
        }
        if (!panelVisible) return null
        val local = toLocal(direction, panelYaw, PANEL_RADIUS) ?: return null
        val u = (local[0] + PANEL_WIDTH / 2) / PANEL_WIDTH
        val v = (PANEL_HEIGHT / 2 - local[1]) / PANEL_HEIGHT
        return if (u in 0f..1f && v in 0f..1f) Hit.Panel(u, v) else null
    }

    /** Content hit on a cylindrical desktop screen wrapped around the viewer. */
    private fun curvedContentHit(direction: FloatArray, window: VrWindow): Hit.Content? {
        val rotate = FloatArray(16); Matrix.setRotateM(rotate, 0, -window.yaw, 0f, 1f, 0f)
        val d = FloatArray(4); Matrix.multiplyMV(d, 0, rotate, 0, direction, 0)
        val origin = synchronized(headPosition) { floatArrayOf(headPosition[0], headPosition[1], headPosition[2], 1f) }
        val o = FloatArray(4); Matrix.multiplyMV(o, 0, rotate, 0, origin, 0)
        val a = d[0] * d[0] + d[2] * d[2]
        val b = 2f * (o[0] * d[0] + o[2] * d[2])
        val c = o[0] * o[0] + o[2] * o[2] - VrWindow.RADIUS * VrWindow.RADIUS
        val disc = b * b - 4f * a * c
        if (a < 1e-5f || disc < 0f) return null
        val roots = floatArrayOf((-b - kotlin.math.sqrt(disc)) / (2f * a), (-b + kotlin.math.sqrt(disc)) / (2f * a))
        val t = roots.filter { it > 0f }.minOrNull() ?: return null
        val x = o[0] + d[0] * t; val z = o[2] + d[2] * t; val y = o[1] + d[1] * t - window.height
        val span = Math.toRadians((window.arcDegrees * window.widthScale).coerceAtMost(330f).toDouble()).toFloat()
        val angle = kotlin.math.atan2(x, -z)
        if (kotlin.math.abs(angle) > span / 2 || kotlin.math.abs(y) > window.heightM / 2) return null
        return Hit.Content(window, angle / span + .5f, (.5f - y / window.heightM).coerceIn(0f, 1f))
    }

    /** Where a ray from the eyes crosses the plane of a window at [yaw] and [radius], in that window's frame. */
    private fun toLocal(direction: FloatArray, yaw: Float, radius: Float): FloatArray? {
        val rotate = FloatArray(16)
        Matrix.setRotateM(rotate, 0, -yaw, 0f, 1f, 0f)
        val d = FloatArray(4)
        Matrix.multiplyMV(d, 0, rotate, 0, direction, 0)
        // In 6DoF the ray starts where the head is now, not at the centre of the room.
        val origin = synchronized(headPosition) { floatArrayOf(headPosition[0], headPosition[1], headPosition[2], 1f) }
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, rotate, 0, origin, 0)
        if (d[2] >= -1e-3f) return null
        val t = (-radius - o[2]) / d[2]
        if (t <= 0f) return null
        return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t)
    }

    private fun updateHit(target: Hit?) {
        if (target is Hit.Setup) onboarding?.hover(target.u, target.v)
        val key = (target as? Hit.Keyboard)?.let { keyboard.hovered(it.u, it.v) }
        if (key != hoveredKey) {
            hoveredKey = key
            keyboardRedraw.set(true)
        }
        val panelTarget = (target as? Hit.Panel)?.let { synchronized(panel) { panel.hit(it.u, it.v) } }
        val previous = hit
        hit = target
        if (panelTarget != hoveredPanel || (previous is Hit.Panel) != (target is Hit.Panel)) {
            hoveredPanel = panelTarget
            redraw.set(true)
        }
    }

    @Volatile private var hoveredPanel: HomePanel.Target? = null
    private var panelPressMoved = false

    private fun press(target: Hit?, direction: FloatArray?) {
        pressing = true
        pressedHit = target
        when (target) {
            is Hit.Setup -> onboarding?.press(target.u, target.v)
            is Hit.Panel -> {
                val item = synchronized(panel) { panel.hit(target.u, target.v) }
                // Home icons open when the pinch lets go; holding it opens "Customize" instead.
                if (panel.mode == HomePanel.Mode.HOME || panel.mode == HomePanel.Mode.LIBRARY) {
                    panelPress = item
                    panelPressAt = SystemClock.elapsedRealtime()
                    panelPressMoved = false
                    return
                }
                item ?: return
                runOnUiThread { panelAction(item); redraw.set(true) }
            }
            is Hit.Content -> {
                focused = target.window
                target.window.content.touch(MotionEvent.ACTION_DOWN, target.u, target.v)
            }
            is Hit.Bar -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.yaw, target.window.height, yawOf(direction), heightAt(direction, VrWindow.RADIUS))
            }
            is Hit.Toolbar -> {
                focused = target.window
                target.window.content.toolbarAction(
                    when {
                        target.u < TOOLBAR_BUTTON -> "back"
                        target.u < TOOLBAR_BUTTON * 2 -> "forward"
                        target.u > 1 - TOOLBAR_BUTTON -> "reload"
                        else -> "home"
                    }
                )
            }
            is Hit.Resize -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.widthScale, target.window.heightScale, 0f, 0f, resize = true)
            }
            is Hit.DesktopWidth -> {
                target.window.widthScale = (target.window.widthScale + if (target.wider) .2f else -.2f).coerceIn(MIN_SCALE, MAX_SCALE)
            }
            is Hit.DesktopCurve -> {
                target.window.arcDegrees = when (target.window.arcDegrees.toInt()) { 0 -> 90f; 90 -> 180f; 180 -> 300f; else -> 0f }
                toast(if (target.window.arcDegrees == 0f) "Экран плоский" else "Изгиб экрана: ${target.window.arcDegrees.toInt()}°")
            }
            is Hit.Keyboard -> {
                val key = keyboard.press(target.u, target.v)
                keyboardRedraw.set(true)
                when (key) {
                    null -> Unit
                    KeyboardPanel.HIDE -> {
                        manualKeyboard = null
                        target.window.content.hideKeyboard()
                    }
                    else -> target.window.content.type(key)
                }
            }
            is Hit.KeyboardButton -> {
                focused = target.window
                if (keyboardWindow() == target.window) {
                    manualKeyboard = null
                    target.window.content.hideKeyboard()
                } else {
                    manualKeyboard = target.window
                }
                keyboardRedraw.set(true)
            }
            is Hit.Minimize -> runOnUiThread { minimize(target.window) }
            is Hit.Close -> runOnUiThread { close(target.window) }
            null -> Unit
        }
    }

    private fun dragOrMove(direction: FloatArray) {
        if (!pressing) return
        val panelStart = pressedHit as? Hit.Panel
        val panelNow = hit as? Hit.Panel
        if (panelStart != null) {
            if (panelNow != null && kotlin.math.abs(panelNow.u - panelStart.u) > PANEL_SWIPE_SLOP) {
                panelPressMoved = true
                panelPressAt = 0L
                panelPress = null
            }
            return
        }
        drag?.let { d ->
            if (d.resize) {
                // The window keeps its centre; the corner follows the pointer.
                val local = toLocal(direction, d.window.yaw, VrWindow.RADIUS) ?: return
                val byWidth = 2 * kotlin.math.abs(local[0]) / VrWindow.WIDTH_M
                val aspect = d.window.content.pixelHeight.toFloat() / d.window.content.pixelWidth
                val byHeight = 2 * kotlin.math.abs(d.window.height - local[1]) / (VrWindow.WIDTH_M * aspect)
                d.window.widthScale = byWidth.coerceIn(MIN_SCALE, MAX_SCALE)
                d.window.heightScale = byHeight.coerceIn(MIN_SCALE, MAX_SCALE)
                return
            }
            d.window.yaw = d.startYaw + (yawOf(direction) - d.pointerYaw)
            d.window.height = d.startHeight + (heightAt(direction, VrWindow.RADIUS) - d.pointerHeight)
            return
        }
        val pressed = pressedHit as? Hit.Content ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveSent < 16) return
        lastMoveSent = now
        val local = toLocal(direction, pressed.window.yaw, VrWindow.RADIUS) ?: return
        val w = pressed.window.width / 2
        val h = pressed.window.heightM / 2
        val u = ((local[0] + w) / pressed.window.width).coerceIn(0f, 1f)
        val v = ((h - (local[1] - pressed.window.height)) / pressed.window.heightM).coerceIn(0f, 1f)
        pressed.window.content.touch(MotionEvent.ACTION_MOVE, u, v)
    }

    private fun panelAction(item: HomePanel.Target) {
        when (item) {
            is HomePanel.Target.App -> openEntry(item.entry)
            is HomePanel.Target.Page -> synchronized(panel) { panel.showPage(item.index) }
            HomePanel.Target.Library -> switchMode(HomePanel.Mode.LIBRARY)
            is HomePanel.Target.Theme -> {
                IconPack.setTheme(this, if (item.dark) IconPack.Theme.DARK else IconPack.Theme.LIGHT)
                openCustomize()
                loadApps()
            }
            HomePanel.Target.Close -> switchMode(HomePanel.Mode.HOME)
            is HomePanel.Target.Rail -> {
                synchronized(panel) { panel.setTab(item.tab) }
                redraw.set(true)
                when (item.tab) {
                    HomePanel.Tab.PEOPLE -> loadPeople()
                    HomePanel.Tab.ENVIRONMENTS -> loadEnvironments()
                    HomePanel.Tab.APPS -> Unit
                }
            }
        }
    }

    /** Friends on the "people" tab: tapping one opens the calls window. */
    private fun loadPeople() {
        thread(name = "PhoneXR people") {
            val friends = runCatching { Friends.mine(this) }.getOrDefault(emptyList())
            val entries = friends.map { person ->
                HomePanel.Entry(
                    "person:${person.username}",
                    person.name.ifEmpty { "@" + person.username },
                    letterIcon(person.name.ifEmpty { person.username }),
                    badge = if (Calls.online.any { it.id == person.id }) "•" else null,
                )
            }
            synchronized(panel) { panel.setPeople(entries) }
            redraw.set(true)
        }
    }

    /** Places to be in: only curated PhoneXR backgrounds; personal gallery photos stay private. */
    private fun loadEnvironments() {
        thread(name = "PhoneXR environments") {
            val places = Environments.BUILT_IN
            val entries = places.map { place ->
                val thumb = Environments.thumbnail(this, place.id)
                HomePanel.Entry(
                    "env:${place.id}",
                    place.title,
                    thumb?.let { BitmapDrawable(resources, it) },
                    badge = if (place.id == environmentId) "✓" else null,
                )
            }
            synchronized(panel) { panel.setEnvironments(entries) }
            redraw.set(true)
        }
    }

    /** Which place is around the user; [Environments.REAL_WORLD] is the room through the camera. */
    @Volatile private var environmentId = Environments.REAL_WORLD

    private fun setEnvironment(id: String) {
        environmentId = id
        thread(name = "PhoneXR environment") {
            val bitmap = runCatching { Environments.panorama(this, id) }.getOrNull()
            // The picture goes to the GPU on the drawing thread, where textures may be touched.
            glTasks += { renderer?.setEnvironment(bitmap) }
            loadEnvironments()
        }
    }

    /** A pinch held on the home: pick light or dark PhoneXR icons. */
    private fun openCustomize() {
        synchronized(panel) {
            panel.setCustomize(
                IconPack.icon(this, "com.miui.weather2", IconPack.Theme.LIGHT),
                IconPack.icon(this, "com.miui.weather2", IconPack.Theme.DARK),
                IconPack.theme(this) == IconPack.Theme.DARK,
            )
            panel.mode = HomePanel.Mode.CUSTOMIZE
        }
        redraw.set(true)
    }

    @Volatile private var panelPress: HomePanel.Target? = null
    @Volatile private var panelPressAt = 0L

    /** While pinching on the home: long enough opens "Customize". */
    private fun checkLongPress() {
        if (!pressing || panelPressAt == 0L) return
        if (panel.mode != HomePanel.Mode.HOME || panelPressMoved) return
        if (SystemClock.elapsedRealtime() - panelPressAt > LONG_PRESS_MS) {
            panelPressAt = 0L
            panelPress = null
            runOnUiThread { openCustomize() }
        }
    }

    private fun release() {
        if (!pressing) return
        pressing = false
        drag = null
        val panelStart = pressedHit as? Hit.Panel
        val panelEnd = hit as? Hit.Panel
        val swipe = if (panelPressMoved && panelStart != null && panelEnd != null) panelEnd.u - panelStart.u else 0f
        if (kotlin.math.abs(swipe) > PANEL_SWIPE_THRESHOLD) {
            synchronized(panel) { panel.turnPage(if (swipe < 0f) 1 else -1) }
            redraw.set(true)
        } else if (panelPressAt != 0L) {
            val item = panelPress
            panelPressAt = 0L
            panelPress = null
            if (item != null) runOnUiThread { panelAction(item); redraw.set(true) }
        }
        panelPressMoved = false
        val pressed = pressedHit as? Hit.Content
        if (pressed != null) {
            val current = hit as? Hit.Content
            val u = if (current?.window == pressed.window) current.u else pressed.u
            val v = if (current?.window == pressed.window) current.v else pressed.v
            pressed.window.content.touch(MotionEvent.ACTION_UP, u, v)
        }
        pressedHit = null
    }

    // ------------------------------------------------------------------ Gamepad goes to the focused window

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_DPAD)) {
            return super.dispatchKeyEvent(event)
        }
        val window = focused?.takeIf { !it.minimized }
        if (window != null) {
            window.content.key(event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> hit?.let { press(it, ray); release() }
                KeyEvent.KEYCODE_BUTTON_START -> openMenu()
                KeyEvent.KEYCODE_BUTTON_B -> switchMode(HomePanel.Mode.HOME)
                KeyEvent.KEYCODE_BUTTON_R1 -> { synchronized(panel) { panel.turnPage(1) }; redraw.set(true) }
                KeyEvent.KEYCODE_BUTTON_L1 -> { synchronized(panel) { panel.turnPage(-1) }; redraw.set(true) }
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val window = focused?.takeIf { !it.minimized }
        if (window != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            window.content.motion(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.mode != HomePanel.Mode.HOME) switchMode(HomePanel.Mode.HOME) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ Icons of the built-in apps

    private fun drawBrowserIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, Color.rgb(90, 200, 250), Color.rgb(0, 122, 255), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .035f
        canvas.drawCircle(size / 2, size / 2, size * .32f, paint)
        paint.style = Paint.Style.FILL
        val c = size / 2
        paint.color = Color.rgb(255, 59, 48)
        canvas.drawPath(Path().apply { moveTo(c + size * .2f, c - size * .2f); lineTo(c - size * .05f, c - size * .05f); lineTo(c + size * .05f, c + size * .05f); close() }, paint)
        paint.color = Color.WHITE
        canvas.drawPath(Path().apply { moveTo(c - size * .2f, c + size * .2f); lineTo(c - size * .05f, c - size * .05f); lineTo(c + size * .05f, c + size * .05f); close() }, paint)
    }

    /** Elix: a glowing orb in Siri-like colours. */
    private fun drawElixIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(10, 10, 16))
        val colors = intArrayOf(Color.rgb(255, 64, 160), Color.rgb(120, 90, 255), Color.rgb(40, 200, 255), Color.rgb(255, 150, 60))
        colors.forEachIndexed { i, color ->
            val angle = i * Math.PI / 2
            paint.shader = android.graphics.RadialGradient(
                size / 2 + (Math.cos(angle) * size * .12).toFloat(), size / 2 + (Math.sin(angle) * size * .12).toFloat(), size * .3f,
                color, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(size / 2, size / 2, size * .42f, paint)
        }
        paint.shader = android.graphics.RadialGradient(size / 2, size / 2, size * .16f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(size / 2, size / 2, size * .2f, paint)
    }

    private fun drawStoreIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, Color.rgb(255, 159, 10), Color.rgb(255, 94, 58), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(size * .28f, size * .38f, size * .72f, size * .74f), size * .05f, size * .05f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .04f
        canvas.drawArc(RectF(size * .38f, size * .24f, size * .62f, size * .48f), 180f, 180f, false, paint)
    }

    private fun drawPhotosIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.WHITE)
        val colors = intArrayOf(
            Color.rgb(255, 204, 0), Color.rgb(255, 149, 0), Color.rgb(255, 59, 48), Color.rgb(255, 45, 85),
            Color.rgb(175, 82, 222), Color.rgb(0, 122, 255), Color.rgb(52, 199, 89), Color.rgb(163, 212, 64),
        )
        colors.forEachIndexed { i, color ->
            paint.color = (color and 0x00ffffff) or (0xd0 shl 24)
            canvas.save()
            canvas.rotate(i * 45f, size / 2, size / 2)
            canvas.drawRoundRect(RectF(size * .42f, size * .14f, size * .58f, size * .5f), size * .08f, size * .08f, paint)
            canvas.restore()
        }
    }

    private fun drawMinecraftIcon(): Drawable = iconCanvas { canvas, size ->
        val paint = Paint()
        paint.color = Color.rgb(121, 85, 58)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.color = Color.rgb(95, 159, 53)
        canvas.drawRect(0f, 0f, size, size * .4f, paint)
        paint.color = Color.rgb(94, 64, 42)
        for (i in 0 until 8) canvas.drawRect(i * size / 8, size * .5f + (i % 3) * size * .12f, (i + 1) * size / 8, size * .6f + (i % 3) * size * .12f, paint)
    }

    private fun symbolIcon(symbol: String, color: Int): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.color = Color.WHITE
        paint.textSize = size * .42f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(symbol, size / 2, size / 2 + size * .15f, paint)
    }

    private fun letterIcon(name: String): Drawable = symbolIcon(name.take(1).uppercase(), Color.rgb(88, 86, 214))

    private fun iconCanvas(paint: (Canvas, Float) -> Unit): Drawable {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        paint(Canvas(bitmap), 256f)
        return BitmapDrawable(resources, bitmap)
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------ Rendering

    /**
     * Where the home panel hangs, in degrees around the user. It stays put while the head turns a
     * little and follows lazily once the user really looks away, so it is always within reach but
     * never glued to the nose.
     */
    @Volatile private var panelYaw = 0f
    private var panelFollows = true

    private fun followWithPanel() {
        if (!panelFollows) return
        val head = FloatArray(16)
        tracker.copyHead(head)
        // Straight ahead in world coordinates is the head's own -z.
        val forward = FloatArray(4)
        Matrix.multiplyMV(forward, 0, head, 0, floatArrayOf(0f, 0f, -1f, 0f), 0)
        val headYaw = yawOf(forward)
        var delta = headYaw - panelYaw
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        val beyond = abs(delta) - PANEL_DEAD_ZONE
        if (beyond <= 0f) return
        panelYaw += (if (delta > 0) beyond else -beyond) * PANEL_CATCH_UP
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        /** The user's own eyes and lenses, read when the headset starts. */
        private var eyes = Eyes.DEFAULT
        /** The place around the user, when it is not the real room. */
        private var environmentTexture = 0
        private var hasEnvironment = false
        private var environmentMesh: FloatArray? = null

        /** Puts a place around the user; null brings the real room back. Call on the GL thread. */
        fun setEnvironment(bitmap: Bitmap?) {
            if (bitmap == null) {
                hasEnvironment = false
                return
            }
            if (environmentTexture == 0) {
                environmentTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            if (environmentMesh == null) environmentMesh = sphereMesh()
            hasEnvironment = true
        }

        /** The inside of a sphere as triangles: x, y, z, u, v. */
        private fun sphereMesh(): FloatArray {
            val stacks = 32
            val slices = 64
            val data = ArrayList<Float>(stacks * slices * 30)
            fun put(u: Float, v: Float) {
                val lon = (u - .5f) * 2f * Math.PI.toFloat()
                val lat = (.5f - v) * Math.PI.toFloat()
                data += kotlin.math.sin(lon) * kotlin.math.cos(lat) * 20f
                data += kotlin.math.sin(lat) * 20f
                data += -kotlin.math.cos(lon) * kotlin.math.cos(lat) * 20f
                data += u
                data += v
            }
            for (stack in 0 until stacks) {
                val v0 = stack.toFloat() / stacks
                val v1 = (stack + 1f) / stacks
                for (slice in 0 until slices) {
                    val u0 = slice.toFloat() / slices
                    val u1 = (slice + 1f) / slices
                    put(u0, v0); put(u0, v1); put(u1, v0)
                    put(u1, v0); put(u0, v1); put(u1, v1)
                }
            }
            return data.toFloatArray()
        }
        private var panelTexture = 0
        private var cameraTexture = 0
        private var controlsTexture = 0
        private var desktopControlsTexture = 0
        private var keyboardTexture = 0
        /** ARCore draws the camera into this external texture (6DoF passthrough). */
        private var arTexture = 0
        private var warningTexture = 0
        private var tracingTexture = 0
        private var textureProgram = 0
        private var externalProgram = 0
        private var colorProgram = 0
        private var roundedProgram = 0
        private var roundedExternalProgram = 0
        /** Final Cardboard pass: both eyes are rendered off-screen, then warped for the lenses. */
        private var cardboardProgram = 0
        private var cardboardTexture = 0
        private var cardboardFramebuffer = 0
        private val toolbarTextures = HashMap<String, Pair<Int, Int>>()
        private var hasCamera = false
        private var cameraAspect = 16f / 9f
        private var width = 1
        private var height = 1
        private val head = FloatArray(16)
        private val worldToHead = FloatArray(16)
        private val projection = FloatArray(16)
        private val eye = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val modelView = FloatArray(16)
        private val mvp = FloatArray(16)
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            eyes = Eyes.load(this@VrHomeActivity)
            panelFollows = Settings.panelFollows(this@VrHomeActivity)
            textureProgram = CinemaRenderer.program(TEXTURE_VERTEX, TEXTURE_FRAGMENT)
            externalProgram = CinemaRenderer.program(TEXTURE_VERTEX, EXTERNAL_FRAGMENT)
            colorProgram = CinemaRenderer.program(COLOR_VERTEX, COLOR_FRAGMENT)
            roundedProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_FRAGMENT)
            roundedExternalProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_EXTERNAL_FRAGMENT)
            cardboardProgram = CinemaRenderer.program(TEXTURE_VERTEX, CARDBOARD_FRAGMENT)
            val ids = IntArray(4)
            GLES20.glGenTextures(4, ids, 0)
            panelTexture = ids[0]
            cameraTexture = ids[1]
            controlsTexture = ids[2]
            desktopControlsTexture = ids[3]
            for (id in ids) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, controlsTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawControls(), 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopControlsTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawDesktopControls(), 0)
            keyboardTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            keyboardRedraw.set(true)
            arTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            ar?.attachTexture(arTexture)
            onboardingTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, onboardingTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            warningTexture = bannerTexture("Вы вышли за границу — вернитесь назад", Color.rgb(255, 69, 58))
            tracingTexture = bannerTexture("Закончить сканирование · щипок", Color.rgb(10, 132, 255))
            redraw.set(true)
        }

        private fun bannerTexture(text: String, color: Int): Int {
            val bitmap = Bitmap.createBitmap(1400, 180, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(225, 28, 28, 32)
            canvas.drawRoundRect(RectF(0f, 0f, 1400f, 180f), 90f, 90f, paint)
            paint.color = color
            canvas.drawCircle(95f, 90f, 34f, paint)
            paint.color = Color.WHITE
            paint.textSize = 54f
            canvas.drawText(text, 160f, 108f, paint)
            val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            return id
        }

        /** ARCore started after the GL surface: give it the camera texture and the eye size. */
        fun attachAr(tracker6: ArTracker) {
            tracker6.attachTexture(arTexture)
            @Suppress("DEPRECATION")
            tracker6.setDisplay(windowManager.defaultDisplay.rotation, width / 2, height)
        }

        override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
            width = w
            height = h
            createCardboardTarget(w, h)
            @Suppress("DEPRECATION")
            ar?.setDisplay(windowManager.defaultDisplay.rotation, w / 2, h)
        }

        /** Google Cardboard's rendering order: draw eyes to a texture, then lens-distort it. */
        private fun createCardboardTarget(w: Int, h: Int) {
            if (cardboardTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(cardboardTexture), 0)
            if (cardboardFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(cardboardFramebuffer), 0)
            cardboardTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cardboardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            cardboardFramebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cardboardFramebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, cardboardTexture, 0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Cardboard framebuffer is incomplete"
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /** 6DoF: one ARCore step — head position, and a camera frame for the hands when they are free. */
        private fun updateAr(tracker6: ArTracker) {
            tracker.copyHead(head)
            val job = tracker6.update(head, wantImage = !busy.get())
            if (job != null && busy.compareAndSet(false, true)) {
                trackingExecutor.execute {
                    try {
                        val frame = job()
                        if (frame != null) {
                            arFrameMap = floatArrayOf(frame.viewLeft, frame.viewTop, frame.viewWidth, frame.viewHeight)
                            handTracker?.detect(frame.bitmap, frame.timestampNs / 1_000_000L)
                            val old = arPhoto
                            arPhoto = frame.bitmap
                            old?.recycle()
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "ARCore frame failed", error)
                    } finally {
                        busy.set(false)
                    }
                }
            }
            synchronized(headPosition) { synchronized(tracker6.position) { System.arraycopy(tracker6.position, 0, headPosition, 0, 3) } }
            if (boundary.tracing != null && tracker6.tracking && boundary.addPoint(headPosition[0], headPosition[2])) {
                toast("Граница сохранена")
            }
        }

        override fun onDrawFrame(unused: GL10?) {
            while (true) glTasks.poll()?.invoke() ?: break
            followWithPanel()
            val tracker6 = ar
            if (tracker6 != null) updateAr(tracker6)
            if (redraw.getAndSet(false)) {
                synchronized(panel) {
                    panel.draw(hoveredPanel, pressing)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, panel.bitmap, 0)
                }
            }
            val setup = onboarding
            if (setup != null) {
                // The setup animates every frame (hello, progress, the cursor in the name field).
                synchronized(setup) {
                    if (setup.draw()) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, onboardingTexture)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, setup.bitmap, 0)
                    }
                }
            }
            val typing = keyboardWindow()
            if (typing != null && keyboardRedraw.getAndSet(false)) {
                keyboard.draw(hoveredKey)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, keyboard.bitmap, 0)
            }
            synchronized(frameLock) {
                val bitmap = frame
                if (bitmap != null && frameFresh.getAndSet(false) && !bitmap.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    cameraAspect = bitmap.width.toFloat() / bitmap.height
                    hasCamera = true
                }
            }
            for (window in windows) {
                if (framesReady.remove(window.id)) surfaceTextures[window.id]?.updateTexImage()
                window.content.takeBitmap()?.let { bitmap ->
                    textures[window.id]?.let { id ->
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                        synchronized(window.content) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
                    }
                }
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cardboardFramebuffer)
            GLES20.glClearColor(.08f, .08f, .1f, 1f)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            tracker.copyHead(head)
            Matrix.transposeM(worldToHead, 0, head, 0)
            // 6DoF: the world moves opposite to the head.
            val position = synchronized(headPosition) { headPosition.copyOf() }
            Matrix.translateM(worldToHead, 0, -position[0], -position[1], -position[2])
            val eyeWidth = width / 2
            val lenses = eyes
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val eyeAspect = eyeWidth.toFloat() / height
            // Keep the pointer mapping in step with how the camera image is laid out below.
            if (tracker6 != null) {
                // ARCore landmarks are already placed on the eye's view; its projection sets the angles.
                viewScaleX = 1f / projection[0]
                viewScaleY = 1f / projection[5]
            } else if (eyeAspect < cameraAspect) {
                viewScaleX = cameraAspect
                viewScaleY = 1f
            } else {
                viewScaleX = eyeAspect
                viewScaleY = eyeAspect / cameraAspect
            }
            for (index in 0..1) {
                if (tracker6 != null) synchronized(tracker6.projection) { System.arraycopy(tracker6.projection, 0, projection, 0, 16) }
                else Matrix.perspectiveM(projection, 0, 90f, eyeWidth.toFloat() / height, .05f, 100f)
                lenses.shift(projection, index, eyeWidth)
                GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
                // Passthrough fills each eye; the camera image is cropped to the eye's shape.
                // A chosen place takes the room's stead and is drawn below, once the view is known.
                if (hasEnvironment) Unit
                else if (tracker6 != null && tracker6.hasUv) {
                    val uv = FloatArray(8)
                    synchronized(tracker6.passthroughUv) { tracker6.passthroughUv.position(0); tracker6.passthroughUv.get(uv); tracker6.passthroughUv.position(0) }
                    quad(externalProgram, arTexture, identity, floatArrayOf(
                        -1f, -1f, 0f, uv[0], uv[1], 1f, -1f, 0f, uv[2], uv[3], -1f, 1f, 0f, uv[4], uv[5], 1f, 1f, 0f, uv[6], uv[7]
                    ), external = true)
                } else if (hasCamera) {
                    val (u0, u1, v0, v1) = if (eyeAspect < cameraAspect) {
                        val span = eyeAspect / cameraAspect
                        listOf(.5f - span / 2, .5f + span / 2, 0f, 1f)
                    } else {
                        val span = cameraAspect / eyeAspect
                        listOf(0f, 1f, .5f - span / 2, .5f + span / 2)
                    }
                    quad(textureProgram, cameraTexture, identity, floatArrayOf(-1f, -1f, 0f, u0, v1, 1f, -1f, 0f, u1, v1, -1f, 1f, 0f, u0, v0, 1f, 1f, 0f, u1, v0))
                }
                Matrix.setIdentityM(eye, 0)
                Matrix.translateM(eye, 0, if (index == 0) lenses.halfIpd else -lenses.halfIpd, 0f, 0f)
                Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)

                // The place around the user, behind everything else.
                Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
                if (hasEnvironment) environmentMesh?.let { mesh ->
                    GLES20.glUseProgram(textureProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgram, "uTexture"), 0)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(textureProgram, "uMvp"), 1, false, mvp, 0)
                    triangles(textureProgram, mesh)
                }

                // Home icons, turned to where the panel hangs.
                val panelPlace = FloatArray(16)
                Matrix.setRotateM(panelPlace, 0, panelYaw, 0f, 1f, 0f)
                val panelMvp = FloatArray(16)
                Matrix.multiplyMM(panelMvp, 0, mvp, 0, panelPlace, 0)
                if (onboarding != null) {
                    val sw = PANEL_WIDTH / 2
                    val sh = sw * Onboarding.HEIGHT / Onboarding.WIDTH
                    val sz = -PANEL_RADIUS
                    quad(textureProgram, onboardingTexture, panelMvp, floatArrayOf(-sw, -sh, sz, 0f, 1f, sw, -sh, sz, 1f, 1f, -sw, sh, sz, 0f, 0f, sw, sh, sz, 1f, 0f))
                } else if (panelVisible) {
                    // After setup the home flies in from a little further away and grows into place.
                    val appear = if (appearStart == 0L) 1f else ((SystemClock.elapsedRealtime() - appearStart) / 900f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - appear) * (1f - appear) * (1f - appear)
                    val grow = .6f + .4f * ease
                    val pw = PANEL_WIDTH / 2 * grow
                    val ph = PANEL_HEIGHT / 2 * grow
                    val pz = -PANEL_RADIUS - (1f - ease) * .9f
                    quad(textureProgram, panelTexture, panelMvp, floatArrayOf(-pw, -ph, pz, 0f, 1f, pw, -ph, pz, 1f, 1f, -pw, ph, pz, 0f, 0f, pw, ph, pz, 1f, 0f))
                }

                // Windows, focused last so it is on top.
                val ordered = windows.filter { !it.minimized }.sortedBy { it == focused }
                for (window in ordered) {
                    val texture = textures[window.id] ?: continue
                    Matrix.setRotateM(model, 0, window.yaw, 0f, 1f, 0f)
                    Matrix.translateM(model, 0, 0f, window.height, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val w = window.width / 2
                    val h = window.heightM / 2
                    val z = -VrWindow.RADIUS
                    val uv = window.content.uv(index)
                    val top = uv[1]
                    val bottom = uv[3]
                    if (window.arcDegrees > 0f) {
                        val program = if (window.content.external) externalProgram else textureProgram
                        triangles(program, texture, mvp, curvedWindow(window, h, uv), window.content.external)
                    } else {
                        val program = if (window.content.external) roundedExternalProgram else roundedProgram
                        GLES20.glUseProgram(program)
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHalf"), window.width / 2, window.heightM / 2)
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRadius"), CORNER_RADIUS)
                        quad(program, texture, mvp, floatArrayOf(-w, -h, z, uv[0], bottom, w, -h, z, uv[2], bottom, -w, h, z, uv[0], top, w, h, z, uv[2], top), window.content.external)
                    }
                    window.content.toolbarTitle()?.let { title -> toolbar(window, title, mvp, h, z) }
                    // Controls under the window: minimize, move bar, close.
                    val barY = -h - BAR_OFFSET
                    val hovered = hit
                    val dragging = drag
                    control(mvp, -BUTTON_X - KEYBOARD_BUTTON_GAP, barY, .035f, .035f, .8f, 1f, hovered is Hit.KeyboardButton && hovered.window == window || typing == window, z)
                    control(mvp, -BUTTON_X, barY, .035f, .035f, 0f, .2f, hovered is Hit.Minimize && hovered.window == window, z)
                    control(mvp, 0f, barY, .2f, .018f, .2f, .6f, hovered is Hit.Bar && hovered.window == window || dragging?.window == window && !dragging.resize, z)
                    control(mvp, BUTTON_X, barY, .035f, .035f, .6f, .8f, hovered is Hit.Close && hovered.window == window, z)
                    resizeHandle(mvp, w, h, z, hovered is Hit.Resize && hovered.window == window || dragging?.window == window && dragging.resize)
                    if (window.id == "desktop") desktopControls(mvp, barY - .15f, z)
                }
                if (typing != null) {
                    Matrix.setRotateM(model, 0, typing.yaw, 0f, 1f, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val kw = KEYBOARD_W / 2
                    val kh = KEYBOARD_H / 2
                    val ky = keyboardCenterY(typing)
                    val kz = -KEYBOARD_RADIUS
                    quad(textureProgram, keyboardTexture, mvp, floatArrayOf(-kw, ky - kh, kz, 0f, 1f, kw, ky - kh, kz, 1f, 1f, -kw, ky + kh, kz, 0f, 0f, kw, ky + kh, kz, 1f, 0f))
                }
                drawBoundary(position)
                hand()
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            // Compensate for Cardboard lenses only after the complete stereo scene exists.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(cardboardProgram)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cardboardProgram, "uEyeAspect"), (width * .5f) / height)
            quad(cardboardProgram, cardboardTexture, identity, floatArrayOf(
                -1f, -1f, 0f, 0f, 0f,
                 1f, -1f, 0f, 1f, 0f,
                -1f,  1f, 0f, 0f, 1f,
                 1f,  1f, 0f, 1f, 1f,
            ))
        }

        /** visionOS-style corner arc at the bottom right: drag it to resize the window. */
        private fun resizeHandle(mvp: FloatArray, w: Float, h: Float, z: Float, active: Boolean) {
            val alpha = if (active) 1f else .7f
            val thick = if (active) .014f else .01f
            val points = ArrayList<Float>()
            val cx = w - CORNER_RADIUS
            val cy = -h + CORNER_RADIUS
            val r = CORNER_RADIUS + .035f
            for (i in 0..12) {
                val angle = Math.toRadians(-90.0 + i * 90.0 / 12).toFloat()
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                for (radius in floatArrayOf(r - thick, r + thick)) {
                    points += listOf(cx + cos * radius, cy + sin * radius, z, 0f, 0f)
                }
            }
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, alpha)
            val data = points.toFloatArray()
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, data.size / 5)
        }

        private fun control(mvp: FloatArray, x: Float, y: Float, hw: Float, hh: Float, u0: Float, u1: Float, active: Boolean, z: Float) {
            val grow = if (active) 1.25f else 1f
            val w = hw * grow
            val h = hh * grow
            quad(textureProgram, controlsTexture, mvp, floatArrayOf(x - w, y - h, z, u0, 1f, x + w, y - h, z, u1, 1f, x - w, y + h, z, u0, 0f, x + w, y + h, z, u1, 0f))
        }

        private fun desktopControls(mvp: FloatArray, y: Float, z: Float) {
            val w = .57f; val h = .075f
            quad(textureProgram, desktopControlsTexture, mvp, floatArrayOf(
                -w, y - h, z, 0f, 1f, w, y - h, z, 1f, 1f,
                -w, y + h, z, 0f, 0f, w, y + h, z, 1f, 0f,
            ))
        }

        /** Textured cylinder segment for the computer display, from flat through almost 360°. */
        private fun curvedWindow(window: VrWindow, halfHeight: Float, uv: FloatArray): FloatArray {
            val segments = 48
            val span = Math.toRadians((window.arcDegrees * window.widthScale).coerceAtMost(330f).toDouble()).toFloat()
            val out = ArrayList<Float>(segments * 30)
            fun point(step: Int, top: Boolean) {
                val s = step.toFloat() / segments
                val angle = (s - .5f) * span
                out += kotlin.math.sin(angle) * VrWindow.RADIUS
                out += if (top) halfHeight else -halfHeight
                out += -kotlin.math.cos(angle) * VrWindow.RADIUS
                out += uv[0] + (uv[2] - uv[0]) * s
                out += if (top) uv[1] else uv[3]
            }
            for (i in 0 until segments) {
                point(i, false); point(i + 1, false); point(i, true)
                point(i, true); point(i + 1, false); point(i + 1, true)
            }
            return out.toFloatArray()
        }

        /** Safari-style bar above a window: back, forward, address, reload. */
        private fun toolbar(window: VrWindow, title: String, mvp: FloatArray, h: Float, z: Float) {
            val version = window.content.toolbarVersion
            val cached = toolbarTextures[window.id]
            val texture = if (cached == null || cached.second != version) {
                val id = cached?.first ?: IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawToolbar(title), 0)
                toolbarTextures[window.id] = id to version
                id
            } else cached.first
            val y = h + TOOLBAR_GAP + TOOLBAR_H / 2
            val w = TOOLBAR_W / 2
            val th = TOOLBAR_H / 2
            quad(textureProgram, texture, mvp, floatArrayOf(-w, y - th, z, 0f, 1f, w, y - th, z, 1f, 1f, -w, y + th, z, 0f, 0f, w, y + th, z, 1f, 0f))
        }

        private fun drawToolbar(title: String): Bitmap {
            val bitmap = Bitmap.createBitmap(1200, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(185, 70, 64, 58)
            canvas.drawRoundRect(RectF(0f, 0f, 1200f, 140f), 70f, 70f, paint)
            paint.color = Color.argb(110, 30, 26, 22)
            canvas.drawRoundRect(RectF(270f, 22f, 1050f, 118f), 48f, 48f, paint)
            paint.color = Color.WHITE
            paint.textSize = 56f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("‹", 70f, 88f, paint)
            canvas.drawText("›", 190f, 88f, paint)
            canvas.drawText("↻", 1130f, 90f, paint)
            paint.textSize = 42f
            canvas.drawText(title, 660f, 84f, paint)
            return bitmap
        }

        /**
         * The user's hands as see-through white shapes over the passthrough, and the cursor on the
         * aim point. Drawn in head space with the passthrough's own mapping, so they sit on the real hands.
         */
        /**
         * The play-area boundary: while tracing, the path walked so far; afterwards blue walls that
         * fade in near the edge, and a warning in front of the eyes once outside.
         */
        private fun drawBoundary(position: FloatArray) {
            if (ar == null) return
            val tracing = boundary.tracing
            val outline = boundary.outline
            if (tracing == null && outline.size < 6) return
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            val location = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            // Keep the trail above the estimated floor so it remains visible through Cardboard.
            val floor = -1.25f
            if (tracing != null) {
                // The final point is the current head position, so the blue line visibly follows
                // the user even before another 15 cm sample is committed to Boundary.
                val live = tracing + floatArrayOf(position[0], position[2])
                val line = FloatArray(live.size / 2 * 3) { i -> when (i % 3) { 0 -> live[i / 3 * 2]; 1 -> floor; else -> live[i / 3 * 2 + 1] } }
                GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), .05f, .65f, 1f, 1f)
                GLES20.glLineWidth(16f)
                drawArray(line, GLES20.GL_LINE_STRIP, location)
                // Keep an unmistakable marker under the user even before they have walked far
                // enough for the first sampled segment to be stored.
                val px = position[0]
                val pz = position[2]
                drawArray(floatArrayOf(
                    px - .11f, floor, pz, px + .11f, floor, pz,
                    px, floor, pz - .11f, px, floor, pz + .11f,
                ), GLES20.GL_LINES, location)
                // A head-locked finish pill follows the user; pinching anywhere activates it.
                banner(tracingTexture, -.62f)
                return
            }
            val inside = boundary.contains(position[0], position[2])
            val distance = boundary.distance(position[0], position[2])
            boundaryWarning = !inside
            val strength = if (!inside) 1f else ((Boundary.WARN_DISTANCE - distance) / Boundary.WARN_DISTANCE).coerceIn(0f, 1f)
            if (strength <= 0f) return
            val walls = ArrayList<Float>()
            val grid = ArrayList<Float>()
            var j = outline.size - 2
            var i = 0
            while (i < outline.size) {
                val ax = outline[j]; val az = outline[j + 1]; val bx = outline[i]; val bz = outline[i + 1]
                val bottom = Boundary.WALL_BOTTOM; val top = Boundary.WALL_TOP
                walls += listOf(ax, bottom, az, bx, bottom, bz, bx, top, bz, ax, bottom, az, bx, top, bz, ax, top, az)
                // A grid on the wall: vertical lines every 25 cm, horizontal every 30 cm.
                val length = hypot(bx - ax, bz - az)
                val steps = (length / .25f).toInt().coerceAtLeast(1)
                for (k in 0..steps) {
                    val t = k.toFloat() / steps
                    val x = ax + (bx - ax) * t; val z = az + (bz - az) * t
                    grid += listOf(x, bottom, z, x, top, z)
                }
                var y = bottom
                while (y <= top) {
                    grid += listOf(ax, y, az, bx, y, bz)
                    y += .3f
                }
                j = i
                i += 2
            }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), .2f, .55f, 1f, .18f * strength)
            drawArray(walls.toFloatArray(), GLES20.GL_TRIANGLES, location)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), .45f, .8f, 1f, .75f * strength)
            GLES20.glLineWidth(3f)
            drawArray(grid.toFloatArray(), GLES20.GL_LINES, location)
            if (!inside) banner(warningTexture, 0f)
        }

        /** A message fixed in front of the eyes at height [y] (head space, tangent units). */
        private fun banner(texture: Int, y: Float) {
            val head = FloatArray(16)
            Matrix.multiplyMM(head, 0, projection, 0, eye, 0)
            val w = .62f; val h = w * 180f / 1400f
            quad(textureProgram, texture, head, floatArrayOf(-w, y - h, -1f, 0f, 1f, w, y - h, -1f, 1f, 1f, -w, y + h, -1f, 0f, 0f, w, y + h, -1f, 1f, 0f))
        }

        private fun drawArray(data: FloatArray, mode: Int, location: Int) {
            if (data.isEmpty()) return
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            GLES20.glVertexAttribPointer(location, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glDrawArrays(mode, 0, data.size / 3)
        }

        private var handMenuTexture = 0

        /**
         * The hand menu, the user's real hands cut out of the camera picture on top of everything
         * (so they are never hidden behind a window), and the cursor on the aim point.
         */
        private fun hand() {
            Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
            handMenu?.let { menu -> if (!menu.x.isNaN()) drawHandMenu(menu) }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            val point = pinchPoint ?: return
            val x = (point[0] - .5f) * 2f * viewScaleX
            val y = (.5f - point[1]) * 2f * viewScaleY
            val r = if (pressing) .010f else .016f
            // A ring cursor: dark edge, white centre, readable over any background.
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 0f, 0f, 0f, .45f)
            disc(x, y, r * 1.45f, position)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, 1f)
            disc(x, y, r, position)
        }

        /** Where a point of the eye's view (0..1, y down) is in ARCore's camera texture. */
        private fun arUv(c: FloatArray, u: Float, v: Float): FloatArray {
            val s = u; val t = 1f - v
            val bottomU = c[0] + (c[2] - c[0]) * s; val bottomV = c[1] + (c[3] - c[1]) * s
            val topU = c[4] + (c[6] - c[4]) * s; val topV = c[5] + (c[7] - c[5]) * s
            return floatArrayOf(bottomU + (topU - bottomU) * t, bottomV + (topV - bottomV) * t)
        }

        private fun drawHandMenu(menu: HandMenu) {
            if (handMenuTexture == 0) {
                handMenuTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handMenuTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                menu.dirty = true
            }
            if (menu.dirty || menuShown !== menu) {
                menu.draw()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handMenuTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, menu.bitmap, 0)
                menuShown = menu
            }
            val w = HandMenu.HALF_W; val h = HandMenu.HALF_H
            quad(textureProgram, handMenuTexture, mvp, floatArrayOf(
                menu.x - w, menu.y - h, -1f, 0f, 1f, menu.x + w, menu.y - h, -1f, 1f, 1f,
                menu.x - w, menu.y + h, -1f, 0f, 0f, menu.x + w, menu.y + h, -1f, 1f, 0f
            ))
        }

        private var menuShown: HandMenu? = null

        private fun triangles(program: Int, texture: Int, matrix: FloatArray, data: FloatArray, external: Boolean = false) {
            if (data.isEmpty()) return
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, matrix, 0)
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / 5)
        }

        private fun disc(x: Float, y: Float, r: Float, position: Int) {
            val data = FloatArray(3 * 18) { i ->
                val k = i / 3
                when (i % 3) {
                    0 -> if (k == 0) x else x + kotlin.math.cos(((k - 1) * 2 * Math.PI / 16).toFloat()) * r
                    1 -> if (k == 0) y else y + kotlin.math.sin(((k - 1) * 2 * Math.PI / 16).toFloat()) * r
                    else -> -1f
                }
            }
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 18)
        }

        private fun quad(program: Int, texture: Int, matrix: FloatArray, data: FloatArray, external: Boolean = false) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, matrix, 0)
            draw(program, data, true)
        }

        /** Draws a mesh of textured triangles, which a quad's two-triangle strip cannot hold. */
        private fun triangles(program: Int, data: FloatArray) {
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / 5)
        }

        private fun draw(program: Int, data: FloatArray, textured: Boolean) {
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            if (textured) {
                val uv = GLES20.glGetAttribLocation(program, "aUv")
                buffer.position(3)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
                GLES20.glEnableVertexAttribArray(uv)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Atlas: minimize button, move bar, close button, keyboard button (visionOS window controls). */
        private fun drawDesktopControls(): Bitmap {
            val bitmap = Bitmap.createBitmap(900, 120, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(220, 44, 44, 50)
            canvas.drawRoundRect(RectF(0f, 0f, 900f, 120f), 60f, 60f, paint)
            paint.color = Color.rgb(75, 75, 82)
            canvas.drawRect(299f, 18f, 301f, 102f, paint); canvas.drawRect(599f, 18f, 601f, 102f, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 54f
            canvas.drawText("− ширина", 150f, 78f, paint)
            canvas.drawText("+ ширина", 450f, 78f, paint)
            canvas.drawText("изгиб 360°", 750f, 78f, paint)
            return bitmap
        }

        private fun drawControls(): Bitmap {
            val bitmap = Bitmap.createBitmap(640, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(170, 60, 60, 66)
            canvas.drawCircle(64f, 64f, 56f, paint)
            canvas.drawCircle(448f, 64f, 56f, paint)
            paint.color = Color.argb(215, 255, 255, 255)
            canvas.drawRoundRect(RectF(136f, 44f, 376f, 84f), 20f, 20f, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 9f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(40f, 64f, 88f, 64f, paint)
            canvas.drawLine(426f, 42f, 470f, 86f, paint)
            canvas.drawLine(470f, 42f, 426f, 86f, paint)
            paint.color = Color.argb(170, 60, 60, 66)
            canvas.drawCircle(576f, 64f, 56f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawRoundRect(RectF(540f, 44f, 612f, 86f), 8f, 8f, paint)
            paint.style = Paint.Style.FILL
            for (row in 0..1) for (column in 0..3) canvas.drawCircle(551f + column * 16.5f, 56f + row * 12f, 3.5f, paint)
            canvas.drawLine(556f, 79f, 596f, 79f, paint)
            return bitmap
        }
    }

    companion object {
        private const val TAG = "PhoneXR-Home"
        const val MINECRAFT = "com.mojang.minecraftpe"
        private const val ID_BROWSER = "own:browser"
        private const val ID_STORE = "own:store"
        private const val ID_PHOTOS = "own:photos"
        private const val ID_MINECRAFT = "own:minecraft"
        private const val MENU_RECENTER = "menu:recenter"
        private const val MENU_HOME = "menu:home"
        private const val MENU_EXIT = "menu:exit"
        private const val MENU_PHOTO = "menu:photo"
        private const val MENU_TOGGLE_APPS = "menu:toggle-apps"
        private const val MENU_BOUNDARY = "menu:boundary"
        private const val ID_DESKTOP = "desktop"
        private const val DIRECT_TOUCH_PALM = .17f
        private const val REQUEST_PERSONA = 42
        private const val ID_SETTINGS = "own:settings"
        private const val ID_ANDROID = "own:android"
        private const val ID_CALLS = "own:calls"
        private const val ID_ELIX = "own:elix"
        private const val LONG_PRESS_MS = 700L
        private const val ID_PERSONA = "own:persona"
        private const val ID_LEOS = "own:leos"
        private const val KEYBOARD_W = 1.7f
        private val KEYBOARD_H = KEYBOARD_W * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH
        private const val KEYBOARD_RADIUS = 1.15f
        private const val KEYBOARD_BUTTON_GAP = .1f
        private const val MIN_SCALE = .45f
        private const val MAX_SCALE = 2.2f
        private val recent = ArrayList<String>()

        /** How far the head may turn before the panel starts to follow, and how fast it catches up. */
        private const val PANEL_DEAD_ZONE = 16f
        private const val PANEL_CATCH_UP = .06f
        private const val PANEL_SWIPE_SLOP = .04f
        private const val PANEL_SWIPE_THRESHOLD = .12f
        private const val PANEL_RADIUS = 1.7f
        private const val PANEL_WIDTH = 2.2f
        private val PANEL_HEIGHT = PANEL_WIDTH * HomePanel.HEIGHT / HomePanel.WIDTH
        private const val BAR_OFFSET = .07f
        private const val BUTTON_X = .3f
        private const val CORNER_RADIUS = .06f
        private const val TOOLBAR_W = .95f
        private val TOOLBAR_H = TOOLBAR_W * 140f / 1200f
        private const val TOOLBAR_GAP = .03f
        /** Share of the bar width taken by each button at its ends. */
        private const val TOOLBAR_BUTTON = .1f

        private const val TEXTURE_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val TEXTURE_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        /**
         * Pre-warps each half of the stereo texture so a simple Cardboard lens straightens it.
         * Separate RGB radii also remove most of the coloured fringe near inexpensive lens edges.
         */
        private const val CARDBOARD_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform float uEyeAspect;
            varying vec2 vUv;

            vec2 sourceUv(vec2 eye, float amount) {
                vec2 p = (eye - vec2(0.5)) * 2.0;
                float r2 = dot(p, p);
                p *= 1.0 + amount * r2 + 0.06 * r2 * r2;
                return p * 0.5 + vec2(0.5);
            }

            void main() {
                float rightEye = step(0.5, vUv.x);
                vec2 eye = vec2(vUv.x * 2.0 - rightEye, vUv.y);
                // Circular physical lens aperture. UV space itself is wide on a landscape phone,
                // so x must be corrected by the per-eye viewport aspect.
                vec2 shape = (eye - vec2(0.5)) * 2.0;
                shape.x *= uEyeAspect;
                float lensRadius = 0.97;
                float edge = length(shape) / lensRadius;
                if (edge > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 red = sourceUv(eye, 0.205);
                vec2 green = sourceUv(eye, 0.215);
                vec2 blue = sourceUv(eye, 0.225);
                if (min(min(green.x, green.y), min(1.0 - green.x, 1.0 - green.y)) < 0.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float base = rightEye * 0.5;
                float r = texture2D(uTexture, vec2(base + red.x * 0.5, red.y)).r;
                float g = texture2D(uTexture, vec2(base + green.x * 0.5, green.y)).g;
                float b = texture2D(uTexture, vec2(base + blue.x * 0.5, blue.y)).b;
                float vignette = 1.0 - smoothstep(0.88, 1.0, edge);
                gl_FragColor = vec4(vec3(r, g, b) * vignette, 1.0);
            }"""
        private const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        /** Windows with rounded corners, like visionOS; uHalf is half the size in metres. */
        private const val ROUNDED_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() { vUv = aUv; vLocal = aPosition.xy; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val ROUNDED_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                vec4 color = texture2D(uTexture, vUv);
                gl_FragColor = vec4(color.rgb, color.a * alpha);
            }"""
        private const val ROUNDED_EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, alpha);
            }"""
        private const val COLOR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }"""
    }
}
