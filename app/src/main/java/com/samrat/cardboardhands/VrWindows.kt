package com.samrat.cardboardhands

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.ContentUris
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.concurrent.thread
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A window floating in the VR home. Its picture comes from a [Content]: an Android surface (web page,
 * an app on a virtual display) or a bitmap (photos). Windows sit on a sphere around the user and are
 * moved with the bar under them.
 */
class VrWindow(val id: String, val title: String, val iconId: String, val content: Content) {
    /** Turn around the user in degrees, height relative to the eyes, both changed by the move bar. */
    @Volatile var yaw = 0f
    @Volatile var height = 0f
    @Volatile var minimized = false
    /** Width and height are independent: the corner follows the hand instead of locking aspect. */
    @Volatile var widthScale = 1f
    @Volatile var heightScale = 1f
    /** Desktop stream only: 0 is flat, otherwise the screen wraps around the viewer. */
    @Volatile var arcDegrees = 0f
    val width get() = WIDTH_M * widthScale
    val heightM get() = WIDTH_M * content.pixelHeight / content.pixelWidth * heightScale

    interface Content {
        val pixelWidth: Int
        val pixelHeight: Int
        /** True when the picture is a surface texture (GL_TEXTURE_EXTERNAL_OES). */
        val external: Boolean
        /** Texture coordinates for an eye: left and right halves for side-by-side photos. */
        fun uv(eye: Int): FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
        /** Called on the GL thread with the texture the window draws; returns the surface to render into, if any. */
        fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit)
        /** A new bitmap to upload, for bitmap content. */
        fun takeBitmap(): Bitmap? = null
        /** Touch at 0..1 window coordinates. Action is MotionEvent.ACTION_DOWN / MOVE / UP. */
        fun touch(action: Int, u: Float, v: Float)
        fun key(event: KeyEvent) = Unit
        /** Safari-style bar above the window: the address to show, or null for no bar. */
        fun toolbarTitle(): String? = null
        /** A bar button: "back", "forward", "reload", "home". */
        fun toolbarAction(action: String) = Unit
        /** Bumped when the bar should be redrawn. */
        val toolbarVersion: Int get() = 0
        fun motion(event: MotionEvent) = Unit
        /** True while a text field in the content wants the VR keyboard. */
        val keyboardRequested: Boolean get() = false
        /** A key from the VR keyboard: text, or "backspace" / "enter". */
        fun type(key: String) = Unit
        fun hideKeyboard() = Unit
        fun release()
    }

    companion object {
        const val RADIUS = 1.45f
        const val WIDTH_M = 1.5f
    }
}

/** Builds touch events with one down time per gesture. */
private class TouchEvents(private val width: Int, private val height: Int, private val source: Int) {
    private var downTime = 0L

    fun event(action: Int, u: Float, v: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        return MotionEvent.obtain(downTime, now, action, u * width, v * height, 0).apply { this.source = source }
    }
}

/** The PhoneXR browser: a WebView on the app's own virtual display, drawn into the window. */
class BrowserContent(private val startUrl: String, private val onWebXr: (String) -> Unit = {}) : VrWindow.Content {
    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = true
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private val touches = TouchEvents(pixelWidth, pixelHeight, InputDevice.SOURCE_TOUCHSCREEN)
    @Volatile private var currentUrl = startUrl
    @Volatile private var version = 0
    @Volatile private var keyboard = false

    override val keyboardRequested get() = keyboard

    /** Called from the page script: text fields and WebXR sessions. */
    private inner class Bridge {
        @JavascriptInterface fun keyboard(show: Boolean) { keyboard = show }
        @JavascriptInterface fun enterXr(url: String) { onWebXr(url) }
    }

    override fun type(key: String) {
        val script = when (key) {
            "backspace" -> "window.__pxrType && __pxrType('', 1)"
            "enter" -> "window.__pxrType && __pxrType('\\n', 0)"
            else -> "window.__pxrType && __pxrType(${org.json.JSONObject.quote(key)}, 0)"
        }
        main.post { webView?.evaluateJavascript(script, null) }
    }

    override fun hideKeyboard() {
        keyboard = false
        main.post { webView?.evaluateJavascript("document.activeElement && document.activeElement.blur()", null) }
    }

    override val toolbarVersion get() = version

    override fun toolbarTitle(): String = when {
        currentUrl.startsWith("file:") -> "Поиск или адрес"
        else -> Uri.parse(currentUrl).host?.removePrefix("www.") ?: currentUrl
    }

    override fun toolbarAction(action: String) {
        main.post {
            val view = webView ?: return@post
            when (action) {
                "back" -> if (view.canGoBack()) view.goBack()
                "forward" -> if (view.canGoForward()) view.goForward()
                "reload" -> view.reload()
                "home" -> view.loadUrl(HOME)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        val surface = Surface(texture)
        main.post {
            val manager = context.getSystemService(DisplayManager::class.java)
            // A private display owned by PhoneXR: no special permission needed for our own content.
            // 280 dpi reads like a tablet at arm's length in VR.
            val created = manager.createVirtualDisplay("PhoneXR Browser", pixelWidth, pixelHeight, 280, surface, 0)
            display = created
            val view = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView, address: String, isReload: Boolean) {
                        currentUrl = address
                        version++
                    }

                    override fun onPageStarted(view: WebView, address: String?, favicon: Bitmap?) {
                        keyboard = false
                        view.evaluateJavascript(PAGE_SCRIPT, null)
                    }

                    override fun onPageCommitVisible(view: WebView, address: String?) = view.evaluateJavascript(PAGE_SCRIPT, null)

                    override fun onPageFinished(view: WebView, address: String?) = view.evaluateJavascript(PAGE_SCRIPT, null)
                }
                webChromeClient = WebChromeClient()
                addJavascriptInterface(Bridge(), "PhoneXR")
                loadUrl(startUrl)
            }
            webView = view
            presentation = Presentation(context, created.display).apply {
                setContentView(view)
                show()
            }
            onReady()
        }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        main.post {
            val event = touches.event(action, u, v)
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        main.post {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> webView?.takeIf { it.canGoBack() }?.goBack()
                KeyEvent.KEYCODE_BUTTON_L1 -> webView?.pageUp(false)
                KeyEvent.KEYCODE_BUTTON_R1 -> webView?.pageDown(false)
            }
        }
    }

    override fun release() {
        main.post {
            presentation?.dismiss()
            webView?.destroy()
            display?.release()
        }
    }

    companion object {
        /** Start page with shortcuts, so browsing works without a keyboard. */
        const val HOME = "file:///android_asset/browser/home.html"

        /**
         * Runs in every page: reports focused text fields so the VR keyboard shows, types into them,
         * and offers WebXR. The WebView has no WebXR of its own, so an immersive session opens the page
         * in the PhoneXR browser (Wolvic engine, OpenXR).
         */
        private val PAGE_SCRIPT = """
            (function() {
              if (window.__pxrType) return;
              var field = null;
              function editable(e) {
                if (!e || !e.tagName) return false;
                var tag = e.tagName.toLowerCase();
                if (tag == 'textarea') return !e.readOnly;
                if (tag == 'input') return ['text','search','email','url','tel','password','number',''].indexOf((e.type || '').toLowerCase()) >= 0 && !e.readOnly;
                return e.isContentEditable;
              }
              function pick(ev) { var t = ev.target; if (editable(t)) { field = t; PhoneXR.keyboard(true); } }
              document.addEventListener('focusin', pick, true);
              document.addEventListener('click', pick, true);
              document.addEventListener('focusout', function(ev) {
                if (ev.target === field) setTimeout(function() { if (document.activeElement !== field) PhoneXR.keyboard(false); }, 150);
              }, true);
              window.__pxrType = function(text, back) {
                var e = field || document.activeElement;
                if (!editable(e)) return;
                if (e.isContentEditable) {
                  e.focus();
                  if (back) document.execCommand('delete'); else if (text == '\n') document.execCommand('insertParagraph'); else document.execCommand('insertText', false, text);
                  return;
                }
                if (text == '\n' && e.tagName.toLowerCase() != 'textarea') {
                  var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true};
                  var go = e.dispatchEvent(new KeyboardEvent('keydown', opts));
                  e.dispatchEvent(new KeyboardEvent('keyup', opts));
                  if (go && e.form) { if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
                  PhoneXR.keyboard(false);
                  return;
                }
                var start = e.selectionStart, end = e.selectionEnd;
                if (start == null) { start = end = e.value.length; }
                if (back) { if (start == end && start > 0) start--; text = ''; }
                var proto = e.tagName.toLowerCase() == 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(e, e.value.slice(0, start) + text + e.value.slice(end));
                try { e.setSelectionRange(start + text.length, start + text.length); } catch (x) {}
                e.dispatchEvent(new InputEvent('input', {bubbles: true, data: text, inputType: back ? 'deleteContentBackward' : 'insertText'}));
              };
              if (!navigator.xr) {
                var xr = {
                  isSessionSupported: function(mode) { return Promise.resolve(mode == 'immersive-vr' || mode == 'immersive-ar' || mode == 'inline'); },
                  requestSession: function(mode) {
                    PhoneXR.enterXr(location.href);
                    return Promise.reject(new DOMException('Opening in the PhoneXR immersive browser', 'NotSupportedError'));
                  },
                  addEventListener: function() {}, removeEventListener: function() {}, ondevicechange: null
                };
                try { Object.defineProperty(navigator, 'xr', {value: xr, configurable: true}); } catch (x) {}
                window.dispatchEvent(new Event('vrdisplayactivate'));
              }
            })();
        """.trimIndent()
    }
}

/** Another app (Minecraft first of all) on a Shizuku virtual display, played with a gamepad or by pinching. */
class ShizukuAppContent(context: Context, private val packageName: String, private val onError: (String) -> Unit) : VrWindow.Content {
    private val portrait = requestedPortrait(context, packageName)
    override val pixelWidth = if (portrait) 1080 else 1920
    override val pixelHeight = if (portrait) 1920 else 1080
    override val external = true
    private var connection: ServiceConnection? = null
    private var service: IDisplayService? = null
    private var displayId = -1
    private var context: Context? = null
    private val touches = TouchEvents(pixelWidth, pixelHeight, InputDevice.SOURCE_TOUCHSCREEN)

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        this.context = context
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        val surface = Surface(texture)
        Handler(Looper.getMainLooper()).post {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) {
                onError("Запустите Shizuku и разрешите доступ PhoneXR")
                return@post
            }
            connection = VirtualScreen.bind(context) { bound ->
                service = bound ?: return@bind
                thread {
                    val id = runCatching { bound.createDisplay(surface, pixelWidth, pixelHeight, 320) }.getOrDefault(-1)
                    if (id < 0) return@thread onError("Не удалось создать экран")
                    displayId = id
                    val component = VirtualScreen.launcherComponent(context, packageName)
                        ?: return@thread onError("Приложение не установлено")
                    runCatching { bound.launch(component, id) }.getOrNull()?.let(onError)
                    onReady()
                }
            }
        }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        val shell = service ?: return
        if (displayId < 0) return
        val event = touches.event(action, u, v)
        runCatching { shell.injectMotion(event, displayId) }
        event.recycle()
    }

    override fun key(event: KeyEvent) {
        if (displayId >= 0) runCatching { service?.injectKey(event, displayId) }
    }

    override fun motion(event: MotionEvent) {
        if (displayId >= 0) runCatching { service?.injectMotion(event, displayId) }
    }

    override fun type(key: String) {
        if (displayId < 0) return
        val events = when (key) {
            "backspace" -> keyPress(KeyEvent.KEYCODE_DEL)
            "enter" -> keyPress(KeyEvent.KEYCODE_ENTER)
            else -> KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(key.toCharArray())?.toList()
                // Letters the key map lacks (Cyrillic) go as a character event.
                ?: listOf(KeyEvent(SystemClock.uptimeMillis(), key, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
        events.forEach { runCatching { service?.injectKey(it, displayId) } }
    }

    private fun keyPress(code: Int): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        return listOf(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    override fun release() {
        runCatching { service?.releaseDisplay() }
        val ctx = context
        connection?.let { if (ctx != null) VirtualScreen.unbind(ctx, it) }
    }

    companion object {
        /** Honour the launched activity's Android orientation; ordinary phone apps default portrait. */
        private fun requestedPortrait(context: Context, packageName: String): Boolean {
            val component = VirtualScreen.launcherComponent(context, packageName)?.let(ComponentName::unflattenFromString)
            val orientation = component?.let {
                runCatching { context.packageManager.getActivityInfo(it, 0).screenOrientation }.getOrNull()
            }
            return when (orientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> false
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> true
                else -> runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0).category != ApplicationInfo.CATEGORY_GAME
                }.getOrDefault(true)
            }
        }
    }
}

/** Monitor streamed by the native Windows/Linux/macOS PhoneXR companion. */
class DesktopStreamContent(private val onStatus: (String) -> Unit) : VrWindow.Content {
    override val pixelWidth = 1600
    override val pixelHeight = 900
    override val external = false
    @Volatile private var next: Bitmap? = null
    @Volatile private var running = true
    private var discovery: DatagramSocket? = null
    private var socket: Socket? = null

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread(name = "PhoneXR desktop stream") {
            while (running) {
                runCatching {
                    onStatus("Ищу PhoneXR Desktop в локальной сети…")
                    val udp = DatagramSocket(null).also { discovery = it; it.reuseAddress = true; it.bind(InetSocketAddress(24819)) }
                    val bytes = ByteArray(512); val packet = DatagramPacket(bytes, bytes.size)
                    udp.receive(packet)
                    val parts = String(packet.data, 0, packet.length).split(' ', limit = 3)
                    require(parts.firstOrNull() == "PHONEXR_DESKTOP_V1")
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 24820
                    udp.close(); discovery = null
                    val tcp = Socket().also { socket = it; it.connect(InetSocketAddress(packet.address, port), 4000); it.tcpNoDelay = true }
                    onStatus("Подключено: ${parts.getOrNull(2) ?: packet.address.hostAddress}")
                    val input = DataInputStream(tcp.getInputStream())
                    while (running) {
                        val magic = ByteArray(4); input.readFully(magic)
                        require(String(magic) == "PXS1")
                        input.readUnsignedShort(); input.readUnsignedShort()
                        val size = input.readInt(); require(size in 1..20_000_000)
                        val jpeg = ByteArray(size); input.readFully(jpeg)
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { frame ->
                            val old = next; next = frame; old?.takeIf { it !== frame }?.recycle(); onReady()
                        }
                    }
                }.onFailure { if (running) { onStatus("Связь с ПК потеряна, переподключаюсь…"); Thread.sleep(700) } }
                runCatching { socket?.close() }; socket = null
            }
        }
    }

    override fun takeBitmap(): Bitmap? = next.also { next = null }
    override fun touch(action: Int, u: Float, v: Float) = Unit
    override fun release() { running = false; runCatching { discovery?.close() }; runCatching { socket?.close() }; next?.recycle(); next = null }
}

/**
 * 3D memories: the gallery as a grid of photos and videos; one opens large. What holds two eyes
 * (side by side or one over the other) is shown in 3D, each eye taking its own half — see [Spatial].
 * A video opens in its own window, through [onVideo].
 */
class PhotosContent(
    private val context: Context,
    private val onVideo: (Uri, String, Int, Int) -> Unit = { _, _, _, _ -> },
    /** A 360° or 180° memory does not fit a window: it goes around the viewer instead. */
    private val onPanorama: (Uri, String, Int, Int, Boolean) -> Unit = { _, _, _, _, _ -> },
) : VrWindow.Content {
    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = false
    private data class Photo(
        val uri: Uri,
        val name: String,
        val width: Int,
        val height: Int,
        val video: Boolean = false,
    ) {
        val layout get() = Spatial.layout(name, width, height)
        val shape get() = Spatial.shape(name, width, height, layout)
        val stereo get() = layout != Spatial.Layout.MONO
    }

    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var fresh = true
    @Volatile private var photos = emptyList<Photo>()
    private val thumbs = HashMap<Uri, Bitmap>()
    @Volatile private var open: Photo? = null
    private var page = 0
    /** What the window says while a flat photo is being turned into a 3D one. */
    @Volatile private var status: String? = null
    @Volatile private var busy = false

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread(name = "PhoneXR photos") {
            photos = query()
            draw()
            onReady()
        }
    }

    override fun takeBitmap(): Bitmap? = if (fresh) bitmap.also { fresh = false } else null

    override fun toolbarTitle(): String = open?.name?.takeIf { it.isNotEmpty() } ?: "Фото · ${photos.size}"
    override val toolbarVersion get() = (open?.hashCode() ?: 0) + photos.size

    override fun toolbarAction(action: String) {
        thread {
            when (action) {
                "back" -> open = null
                "forward" -> open?.let { current -> photos.getOrNull(photos.indexOf(current) + 1)?.let { open = it } }
                "reload", "home" -> { open = null; page = 0; photos = query() }
            }
            draw()
        }
    }

    /** Re-reads the gallery, e.g. after a photo was taken in VR. */
    fun reload() = toolbarAction("reload")

    override fun uv(eye: Int): FloatArray {
        val photo = open ?: return floatArrayOf(0f, 0f, 1f, 1f)
        // The picture fills the window, so each eye's half of the window is that eye's half of it.
        return Spatial.uv(photo.layout, eye)
    }

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        thread {
            val current = open
            if (current != null) {
                // The button under a flat photo turns it into a 3D one; anywhere else closes it.
                if (v > .88f && u < .3f && !current.stereo && !current.video) makeStereo(current)
                else open = null
            } else {
                val column = (u * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)
                when {
                    v > .92f && u < .2f -> page = (page - 1).coerceAtLeast(0)
                    v > .92f && u > .8f -> page = (page + 1).coerceAtMost((photos.size - 1) / PER_PAGE)
                    v <= .92f -> {
                        val row = (v / .92f * ROWS).toInt().coerceIn(0, ROWS - 1)
                        val picked = photos.getOrNull(page * PER_PAGE + row * COLUMNS + column)
                        when {
                            picked == null -> open = null
                            picked.shape != Spatial.Shape.FLAT ->
                                onPanorama(picked.uri, picked.name, picked.width, picked.height, picked.video)
                            // A video plays in a window of its own, with a timeline under it.
                            picked.video -> onVideo(picked.uri, picked.name, picked.width, picked.height)
                            else -> open = picked
                        }
                    }
                }
            }
            draw()
        }
    }

    /** Photos and videos of the gallery, newest first. */
    /**
     * Turns a flat photo into a 3D one: the depth network says how far everything is, and the photo
     * is drawn again for each eye. The copy goes to the gallery and opens right away.
     */
    private fun makeStereo(photo: Photo) {
        if (busy) return
        busy = true
        thread {
            try {
                if (!DepthModel.installed(context)) {
                    status = "Нейросеть глубины не скачана: включите её в настройках PhoneXR (${DepthModel.MEGABYTES} МБ)"
                    return@thread
                }
                val made = SpatialPhoto.create(context, photo.uri, photo.name) { stage ->
                    status = stage
                    draw()
                }
                photos = query()
                open = photos.firstOrNull { it.uri == made } ?: open
                status = "Готово: 3D‑копия лежит в галерее"
            } catch (failure: Throwable) {
                status = failure.message ?: "Не получилось сделать 3D"
            } finally {
                busy = false
                draw()
            }
        }
    }

    private fun query(): List<Photo> = images() + videos()

    private fun images(): List<Photo> = runCatching {
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && list.size < 400) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                list += Photo(uri, cursor.getString(1) ?: "", cursor.getInt(2), cursor.getInt(3))
            }
        }
        list
    }.getOrDefault(emptyList())

    private fun videos(): List<Photo> = runCatching {
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && list.size < 200) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                list += Photo(uri, cursor.getString(1) ?: "", cursor.getInt(2), cursor.getInt(3), video = true)
            }
        }
        list
    }.getOrDefault(emptyList())

    @Synchronized
    private fun draw() {
        bitmap.eraseColor(Color.rgb(28, 28, 32))
        val photo = open
        if (photo != null) {
            val full = runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(2400, 1400), null) }.getOrNull()
                ?: runCatching { context.contentResolver.openInputStream(photo.uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (full != null) {
                // Side-by-side photos fill the window; each eye then samples its own half.
                val scale = if (photo.stereo) minOf(pixelWidth.toFloat() / full.width, pixelHeight.toFloat() / full.height)
                else minOf(pixelWidth.toFloat() / full.width, pixelHeight.toFloat() / full.height)
                val w = full.width * scale
                val h = full.height * scale
                canvas.drawBitmap(full, null, RectF((pixelWidth - w) / 2, (pixelHeight - h) / 2, (pixelWidth + w) / 2, (pixelHeight + h) / 2), paint)
            }
            // A flat photo can be made into a 3D one, right here.
            if (!photo.stereo && !photo.video) {
                paint.color = Color.argb(220, 10, 132, 255)
                canvas.drawRoundRect(RectF(40f, pixelHeight - 110f, 360f, pixelHeight - 30f), 40f, 40f, paint)
                paint.color = Color.WHITE
                paint.textSize = 36f
                canvas.drawText(if (busy) "Делаю 3D…" else "Сделать 3D", 90f, pixelHeight - 58f, paint)
            }
            status?.let { text ->
                paint.color = Color.argb(200, 0, 0, 0)
                canvas.drawRoundRect(RectF(400f, pixelHeight - 110f, pixelWidth - 40f, pixelHeight - 30f), 40f, 40f, paint)
                paint.color = Color.WHITE
                paint.textSize = 32f
                canvas.drawText(text, 430f, pixelHeight - 58f, paint)
            }
        } else {
            val start = page * PER_PAGE
            val cellW = pixelWidth / COLUMNS.toFloat()
            val cellH = pixelHeight * .92f / ROWS
            photos.drop(start).take(PER_PAGE).forEachIndexed { index, item ->
                val left = (index % COLUMNS) * cellW
                val top = (index / COLUMNS) * cellH
                val thumb = thumbs.getOrPut(item.uri) {
                    runCatching { context.contentResolver.loadThumbnail(item.uri, Size(400, 300), null) }.getOrNull()
                        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                canvas.drawBitmap(thumb, null, RectF(left + 8, top + 8, left + cellW - 8, top + cellH - 8), paint)
                val badge = when {
                    item.shape != Spatial.Shape.FLAT && item.stereo -> "3D 360"
                    item.shape != Spatial.Shape.FLAT -> "360"
                    item.stereo -> "3D"
                    item.video -> "▶"
                    else -> null
                }
                if (badge != null) {
                    paint.color = Color.argb(200, 0, 0, 0)
                    paint.textSize = 28f
                    val badgeWidth = paint.measureText(badge) + 52
                    canvas.drawRoundRect(RectF(left + 18, top + 18, left + 18 + badgeWidth, top + 62), 20f, 20f, paint)
                    paint.color = Color.WHITE
                    canvas.drawText(badge, left + 44, top + 52, paint)
                }
            }
            paint.color = Color.WHITE
            paint.textSize = 36f
            if (photos.isEmpty()) canvas.drawText("Нет фото или нет доступа к галерее", 480f, 480f, paint)
            canvas.drawText("‹", 60f, pixelHeight - 25f, paint)
            canvas.drawText("›", pixelWidth - 80f, pixelHeight - 25f, paint)
        }
        fresh = true
    }

    override fun release() = Unit

    companion object {
        private const val COLUMNS = 4
        private const val ROWS = 3
        private const val PER_PAGE = COLUMNS * ROWS
    }
}
