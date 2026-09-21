package com.samrat.cardboardhands

import android.app.Activity
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Installs a prepared game through PhoneXR's Shizuku service without opening Android's APK UI. */
object InternalInstaller {
    fun install(activity: Activity, apk: File, finished: (String?) -> Unit) {
        if (VirtualScreen.access() != VirtualScreen.Access.READY) {
            finished("Для внутренней установки запустите Shizuku и разрешите доступ PhoneXR")
            return
        }
        val delivered = AtomicBoolean(false)
        lateinit var connection: android.content.ServiceConnection
        connection = VirtualScreen.bind(activity) { service ->
            if (service == null) {
                if (delivered.compareAndSet(false, true)) finished("Служба Shizuku отключилась")
                return@bind
            }
            thread(name = "PhoneXR internal installer") {
                val problem = runCatching {
                    ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        service.installApk(descriptor, apk.length())
                    }
                }.getOrElse { it.localizedMessage ?: it.javaClass.simpleName }
                activity.runOnUiThread {
                    runCatching { VirtualScreen.unbind(activity, connection) }
                    if (delivered.compareAndSet(false, true)) finished(problem)
                }
            }
        }
    }
}
