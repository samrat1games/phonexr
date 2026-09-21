package com.samrat.cardboardhands

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object PxrPackage {
    /** Creates a portable PhoneXR package around an already prepared Android build. */
    fun packAndroid(context: Context, apk: File, title: String = apk.nameWithoutExtension): File {
        require(apk.isFile && apk.length() > 0) { "APK не найден" }
        val folder = File(context.filesDir, "pxr-exports").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-zА-Яа-я0-9._-]+"), "-").trim('-').ifEmpty { "PhoneXR-app" }
        val output = File(folder, "$safe.pxr")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "com.phonexr.pxr").put("version", 1)
                .put("title", title).put("runtime", "android").toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload/android/game.apk"))
            apk.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return output
    }

    /** The APK inside a .pxr package as a file; anything else is returned as it is. */
    fun androidApk(context: Context, source: Uri): File = unpack(context, source)
        ?: File(requireNotNull(source.path) { "Не удалось открыть файл" })

    fun androidPayload(context: Context, source: Uri): Uri {
        val apk = unpack(context, source) ?: return source
        return FileProvider.getUriForFile(context, "${context.packageName}.patched.apks", apk)
    }

    /** Unpacks the Android payload of a .pxr package, or null when [source] is not one. */
    private fun unpack(context: Context, source: Uri): File? {
        val name = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: source.lastPathSegment.orEmpty()
        if (!name.lowercase().endsWith(".pxr")) return null

        val folder = File(context.cacheDir, "patched").apply { mkdirs() }
        val output = File(folder, "PhoneXR-package-game.apk").apply { delete() }
        var validManifest = false
        var foundAndroid = false
        context.contentResolver.openInputStream(source).use { raw ->
            requireNotNull(raw) { "Не удалось открыть .pxr" }
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name.removePrefix("./")) {
                        "manifest.json" -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            validManifest = text.contains("com.phonexr.pxr") && text.contains("\"version\"")
                        }
                        "payload/android/game.apk" -> {
                            FileOutputStream(output).use { zip.copyTo(it) }
                            foundAndroid = true
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(validManifest) { "Это не пакет PhoneXR .pxr" }
        require(foundAndroid && output.length() > 0) { "В .pxr нет Android APK" }
        return output
    }
}
