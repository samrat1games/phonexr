package com.samrat.cardboardhands

import android.content.Context
import android.net.Uri
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkPatcher {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val LOADER = "libopenxr_loader.so"
    private const val VRAPI = "libvrapi.so"
    /** Oculus store purchase check libraries. Left in place untouched; the user is only warned. */
    private val ENTITLEMENT = setOf(
        "libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so"
    )

    /**
     * ABI folders PhoneXR can serve, with the assets that go into them: the OpenXR loader and the
     * Gear VR adapter (gearvr-shim: VrApi served through OpenXR, so Gear VR games need no Samsung phone).
     * Old 32-bit games keep their libraries in "armeabi"; a 64-bit phone runs them with the v7a build.
     */
    private enum class Abi(val folder: String, val loaderAsset: String, val vrapiAsset: String, val title: String) {
        ARM64("arm64-v8a", "libopenxr_loader.so", "libvrapi.so", "64 бита"),
        ARMV7("armeabi-v7a", "libopenxr_loader32.so", "libvrapi32.so", "32 бита"),
        ARMEABI("armeabi", "libopenxr_loader32.so", "libvrapi32.so", "32 бита");

        companion object {
            fun of(name: String): Abi? =
                if (!name.startsWith("lib/")) null
                else entries.firstOrNull { name.startsWith("lib/${it.folder}/") }
        }
    }

    /** [vrApi] is true when the game draws through VrApi and got the PhoneXR adapter. */
    data class Result(
        val apk: File,
        val changes: List<String>,
        val vrApi: Boolean,
        /** Which headset the build was made for, so the app calls it by its own name. */
        val headset: GameLibrary.Headset = GameLibrary.Headset.UNKNOWN
    )

    fun patch(context: Context, source: Uri): Result {
        val directory = File(context.cacheDir, "patched").apply { mkdirs() }
        val copy = File(directory, "source.apk")
        val input = sourceFile(context, source, copy)
        return try {
            patch(input, directory) { name ->
                runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
            }
        } finally {
            copy.delete()
        }
    }

    /**
     * The patcher itself, with nothing of Android in it: [input] is the game, [directory] is where
     * the result goes and [assets] serves PhoneXR's own files (the OpenXR loader, the Gear VR
     * adapter, the signing key). Written this way so a test can run the whole thing on a real APK.
     */
    fun patch(input: File, directory: File, assets: (String) -> ByteArray?): Result {
        directory.mkdirs()
        val unsigned = File(directory, "unsigned.apk")
        val output = File(directory, "PhoneXR-patched.apk")
        unsigned.delete()
        output.delete()
        val loaded = HashMap<String, ByteArray?>()
        fun asset(name: String) = loaded.getOrPut(name) { assets(name) }
        val changes = mutableListOf<String>()
        var sawManifest = false
        var unpackedLibs = false
        var saved = 0L
        var vrApi = false
        var markers = emptySet<String>()
        var checksPurchase = false
        val abis = sortedSetOf<Abi>()
        val loaderIn = HashSet<Abi>()
        val vrapiIn = HashSet<Abi>()
        var otherLibs = false

        // The archive is read through its central directory, the way Android's installer reads it.
        // Walking local headers instead breaks on APKs that carry stray duplicate entries.
        run {
            val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsigned)))
            ZipFile(input).use { archive ->
                val names = archive.entries().toList().mapTo(HashSet()) { it.name }
                val hasUnityOpenXr = names.any { it.endsWith("/libUnityOpenXR.so") } &&
                    "assets/bin/Data/UnitySubsystems/UnityOpenXR/UnitySubsystemsManifest.json" in names
                val hasFridaInjection = names.any { it.endsWith("/libfrda.so") } &&
                    names.any { it.endsWith("/libfrda.config.so") }
                var fridaCallsDisabled = 0
                var unityLoaderChanged = false
                // Builds for other processors are dead weight on the phone and go before anything is written.
                val redundant = redundantAbis(archive.entries().toList())
                ZipOutputStream(counting).use { zip ->
                    val written = HashSet<String>()
                    for (original in archive.entries()) {
                        val name = original.name
                        if (original.isDirectory || isOldSignature(name) || !written.add(name)) continue
                        if (name.startsWith("lib/") && name.removePrefix("lib/").substringBefore('/') in redundant) {
                            saved += original.compressedSize.coerceAtLeast(0)
                            continue
                        }
                        val fileName = name.substringAfterLast('/')
                        val abi = Abi.of(name)
                        if (abi != null) abis += abi
                        else if (name.startsWith("lib/") && name.endsWith(".so")) otherLibs = true
                        if (name.startsWith("lib/") && fileName in ENTITLEMENT && !checksPurchase) {
                            checksPurchase = true
                            changes += "в игре есть проверка покупки Oculus ($fileName). PhoneXR её не трогает: " +
                                "если игра действительно её требует, она не запустится"
                        }

                        val data: ByteArray? = when {
                            name == MANIFEST -> {
                                sawManifest = true
                                val source = archive.getInputStream(original).use { it.readBytes() }
                                markers = AndroidManifestPatcher.markers(source)
                                val patched = AndroidManifestPatcher.patch(source)
                                changes += patched.changes
                                patched.bytes
                            }
                            hasFridaInjection && name.matches(Regex("classes(\\d*)\\.dex")) -> {
                                val source = archive.getInputStream(original).use { it.readBytes() }
                                DexPatcher.disableSystemLoadLibrary(source, "frda").also {
                                    fridaCallsDisabled += it.disabledCalls
                                }.bytes
                            }
                            hasUnityOpenXr && name == "assets/bin/Data/boot.config" -> {
                                val source = archive.getInputStream(original).use { it.readBytes() }
                                val text = source.toString(Charsets.UTF_8)
                                val changed = text.replace(
                                    Regex("(?m)^xrsdk-pre-init-library=OculusXRPlugin$"),
                                    "xrsdk-pre-init-library=UnityOpenXR"
                                )
                                if (changed != text) unityLoaderChanged = true
                                changed.toByteArray()
                            }
                            hasUnityOpenXr && name == "assets/bin/Data/RuntimeInitializeOnLoads.json" -> {
                                val source = archive.getInputStream(original).use { it.readBytes() }
                                val text = source.toString(Charsets.UTF_8)
                                val oculusStartup = Regex(
                                    """\{(?=[^{}]*\"assemblyName\"\s*:\s*\"Unity\.XR\.Oculus\")(?=[^{}]*\"className\"\s*:\s*\"OculusLoader\")(?=[^{}]*\"methodName\"\s*:\s*\"RuntimeLoadOVRPlugin\")[^{}]*\},?"""
                                )
                                val changed = oculusStartup.replace(text, "").replace(Regex(",\\s*]"), "]")
                                if (changed != text) unityLoaderChanged = true
                                changed.toByteArray()
                            }
                            abi != null && name == "lib/${abi.folder}/$LOADER" -> {
                                loaderIn += abi
                                changes += "OpenXR loader (${abi.title}) заменён на сборку PhoneXR"
                                requireNotNull(asset(abi.loaderAsset)) { "В PhoneXR нет OpenXR loader для ${abi.title}" }
                            }
                            abi != null && name == "lib/${abi.folder}/$VRAPI" -> {
                                vrapiIn += abi
                                vrApi = true
                                changes += "libvrapi.so (${abi.title}) заменён переходником Gear VR → OpenXR"
                                requireNotNull(asset(abi.vrapiAsset)) {
                                    "Это игра Gear VR (${abi.title}), а в эту сборку PhoneXR не вложен переходник для неё"
                                }
                            }
                            else -> null
                        }

                        val entry = ZipEntry(name).apply { time = original.time }
                        val nativeLib = name.startsWith("lib/") && name.endsWith(".so")
                        if (data != null) {
                            if (nativeLib || original.method == ZipEntry.STORED) {
                                entry.method = ZipEntry.STORED
                                entry.size = data.size.toLong()
                                entry.compressedSize = data.size.toLong()
                                entry.crc = CRC32().apply { update(data) }.value
                            }
                        } else if (nativeLib || original.method == ZipEntry.STORED) {
                            // Stored game data can be hundreds of megabytes: copy it as a stream, never into memory.
                            // Libraries are always stored, so Android maps them out of the APK instead of
                            // unpacking a second copy on installation.
                            if (nativeLib && original.method != ZipEntry.STORED) unpackedLibs = true
                            entry.method = ZipEntry.STORED
                            entry.size = original.size
                            entry.compressedSize = original.size
                            entry.crc = original.crc
                        }
                        // Stored entries keep zipalign's alignment: native libraries on 16K pages, the
                        // rest (resources.arsc above all) on 4 bytes. Android refuses targetSdk 30+ APKs
                        // whose resources.arsc is unaligned — the installer then only says "not installed".
                        if (entry.method == ZipEntry.STORED) {
                            entry.extra = alignmentExtra(counting.count, name, if (nativeLib) 16_384 else 4)
                        }
                        zip.putNextEntry(entry)
                        if (data != null) zip.write(data) else archive.getInputStream(original).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // A Gear VR game ships without OpenXR; the adapter loads it from the game's lib folder.
                    for (abi in vrapiIn - loaderIn) {
                        val loader = requireNotNull(asset(abi.loaderAsset)) { "В PhoneXR нет OpenXR loader для ${abi.title}" }
                        val name = "lib/${abi.folder}/$LOADER"
                        zip.putNextEntry(storedEntry(name, loader, counting.count))
                        zip.write(loader)
                        zip.closeEntry()
                        changes += "добавлен OpenXR loader PhoneXR (${abi.title})"
                    }
                }
                if (fridaCallsDisabled > 0) {
                    changes += "отключён несовместимый Frida-инжектор, который падал до запуска Unity"
                }
                if (unityLoaderChanged) {
                    changes += "Unity переключён с жёсткого OculusXR на стандартный OpenXR"
                }
            }
        }
        require(sawManifest) { "Это не APK: внутри нет AndroidManifest.xml" }
        require(abis.isNotEmpty() || !otherLibs) {
            "В APK нет библиотек для ARM (arm64-v8a или armeabi-v7a) — на телефоне такая сборка не запустится"
        }
        if (Abi.ARM64 !in abis && abis.isNotEmpty()) changes += "32-битная игра: PhoneXR запустит её в 32-битном режиме"
        if (saved > 0) changes += "оптимизация: убраны библиотеки для других процессоров (−${size(saved)})"
        if (unpackedLibs) changes += "оптимизация: библиотеки лежат в APK без сжатия — игра запускается быстрее"
        sign(::asset, unsigned, output)
        unsigned.delete()
        if (changes.isEmpty()) changes += "APK уже подходит, изменена только подпись"
        return Result(output, changes, vrApi, GameLibrary.headsetOf(markers, Abi.ARM64 in abis))
    }

    /**
     * ABI folders the phone will never load: builds for other processors, and a 32-bit build the
     * 64-bit one fully covers. Nothing is dropped when the APK has no ARM build at all — that case
     * is an error the caller reports instead.
     */
    private fun redundantAbis(entries: List<ZipEntry>): Set<String> {
        val libraries = HashMap<String, MutableSet<String>>()
        for (entry in entries) {
            if (entry.isDirectory || !entry.name.startsWith("lib/")) continue
            val parts = entry.name.removePrefix("lib/").split('/')
            if (parts.size >= 2) libraries.getOrPut(parts[0]) { mutableSetOf() } += parts.last()
        }
        val arm = libraries.keys.filter { folder -> Abi.entries.any { it.folder == folder } }
        if (arm.isEmpty()) return emptySet()
        val redundant = libraries.keys.filterNot { it in arm }.toMutableSet()
        val sixtyFour = libraries[Abi.ARM64.folder]
        if (sixtyFour != null) {
            // A 32-bit copy goes only when the 64-bit build carries every library it has.
            arm.filter { it != Abi.ARM64.folder }
                .filter { sixtyFour.containsAll(libraries.getValue(it)) }
                .forEach { redundant += it }
        }
        return redundant
    }

    private fun size(bytes: Long) = if (bytes >= 1L shl 20) "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    else "%.0f КБ".format(bytes / 1024.0)

    /** A file to open as a zip: installed games already are files, picked documents are copied first. */
    private fun sourceFile(context: Context, source: Uri, copy: File): File {
        if (source.scheme == "file") return File(requireNotNull(source.path))
        copy.delete()
        context.contentResolver.openInputStream(source).use { raw ->
            requireNotNull(raw) { "Не удалось открыть файл" }
            FileOutputStream(copy).use { raw.copyTo(it) }
        }
        return copy
    }

    private fun storedEntry(name: String, data: ByteArray, offset: Long) = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = CRC32().apply { update(data) }.value
        extra = alignmentExtra(offset, name, 16_384)
    }

    private fun sign(asset: (String) -> ByteArray?, input: File, output: File) {
        val store = KeyStore.getInstance("PKCS12")
        val key64 = requireNotNull(asset("phonexr-signing.p12")) { "В сборке PhoneXR нет ключа подписи" }
        key64.inputStream().use { store.load(it, "android".toCharArray()) }
        val key = store.getKey("androiddebugkey", "android".toCharArray()) as java.security.PrivateKey
        val certificate = store.getCertificate("androiddebugkey") as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder("PhoneXR", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            // Signing rewrites the archive, and left alone it re-aligns every uncompressed entry on
            // 4 bytes, throwing away the 16 KB page alignment the libraries above were given. Android
            // maps an uncompressed library straight out of the APK, so a library off its page makes
            // the installer answer "приложение не установлено" and nothing else.
            .setAlignmentPreserved(true)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun isOldSignature(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") &&
            (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") ||
                upper.endsWith(".SF") || upper == "META-INF/MANIFEST.MF")
    }

    private fun alignmentExtra(offset: Long, name: String, alignment: Int): ByteArray {
        val nameLength = name.toByteArray(Charsets.UTF_8).size
        val base = offset + 30 + nameLength + 4
        val payload = ((alignment - (base % alignment)) % alignment).toInt()
        return ByteArray(payload + 4).also {
            it[0] = 0x35
            it[1] = 0xD9.toByte()
            it[2] = (payload and 0xff).toByte()
            it[3] = ((payload ushr 8) and 0xff).toByte()
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
            private set
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(data: ByteArray, offset: Int, length: Int) {
            out.write(data, offset, length)
            count += length
        }
    }
}
