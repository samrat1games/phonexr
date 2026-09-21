package com.samrat.cardboardhands

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Shizuku user service: runs as the shell user, like scrcpy. Shell may create a trusted virtual
 * display, start any app on it and inject input there, which a normal app may not.
 */
class DisplayUserService(context: Context) : IDisplayService.Stub() {
    /** The display service checks that the calling uid owns the package name it is given. */
    private val shell = object : ContextWrapper(context) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
    }
    private var display: VirtualDisplay? = null

    constructor() : this(dummyContext())

    override fun destroy() {
        releaseDisplay()
        exitProcess(0)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        releaseDisplay()
        return try {
            val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(shell)
            var flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (Build.VERSION.SDK_INT >= 33) {
                flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or
                    FLAG_TOUCH_FEEDBACK_DISABLED
            }
            if (Build.VERSION.SDK_INT >= 34) flags = flags or FLAG_OWN_FOCUS
            display = manager.createVirtualDisplay("PhoneXR Cinema", width, height, dpi, surface, flags)
            display?.display?.displayId ?: -1
        } catch (error: Throwable) {
            Log.e(TAG, "Virtual display failed", error)
            -1
        }
    }

    override fun releaseDisplay() {
        display?.release()
        display = null
    }

    override fun launch(component: String, displayId: Int): String? = try {
        val process = ProcessBuilder(
            "am", "start", "--display", displayId.toString(), "--activity-clear-task",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (output.contains("Error", ignoreCase = true)) output.trim() else null
    } catch (error: Throwable) {
        error.message ?: error.javaClass.simpleName
    }

    override fun injectKey(event: KeyEvent, displayId: Int) = inject(event, displayId)

    override fun injectMotion(event: MotionEvent, displayId: Int) = inject(event, displayId)

    override fun installApk(apk: ParcelFileDescriptor, size: Long): String? = try {
        val process = ProcessBuilder("pm", "install", "-r", "-S", size.toString())
            .redirectErrorStream(true).start()
        ParcelFileDescriptor.AutoCloseInputStream(apk).use { input ->
            process.outputStream.use { output -> input.copyTo(output) }
        }
        val message = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0 && message.contains("Success", ignoreCase = true)) null
        else message.ifBlank { "Android не установил игру (код $code)" }
    } catch (error: Throwable) {
        error.message ?: error.javaClass.simpleName
    }

    private fun inject(event: InputEvent, displayId: Int) {
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            injector.invoke(injectorInstance, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
        } catch (error: Throwable) {
            Log.w(TAG, "Input injection failed", error)
        }
    }

    private val injectorInstance: Any by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
    }

    private val injector by lazy {
        injectorInstance.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    companion object {
        private const val TAG = "PhoneXR-Cinema"
        private const val SHELL = "com.android.shell"

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*; several are hidden from the public SDK.
        private const val FLAG_PUBLIC = 1
        private const val FLAG_PRESENTATION = 1 shl 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14

        /** Older Shizuku creates the service without a Context; the system context stands in. */
        private fun dummyContext(): Context {
            val thread = Class.forName("android.app.ActivityThread")
            val current = thread.getMethod("currentActivityThread").invoke(null)
                ?: thread.getMethod("systemMain").invoke(null)
            return thread.getMethod("getSystemContext").invoke(current) as Context
        }
    }
}
