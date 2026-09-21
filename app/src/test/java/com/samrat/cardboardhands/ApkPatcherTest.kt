package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcherTest {
    /** PhoneXR's own files, read from the module they are packed from. */
    private fun asset(name: String): ByteArray? =
        listOf(File("src/main/assets/$name"), File("../gearvr-shim/prebuilt/$name"))
            .firstOrNull { it.isFile }?.readBytes()

    /**
     * A build with its libraries compressed, the way a game from a headset store ships them. The
     * patcher stores them uncompressed instead, so Android can map them out of the APK.
     */
    private fun questGame(folder: File): File {
        val manifest = javaClass.classLoader!!.getResourceAsStream("quest-game-manifest.bin")!!.readBytes()
        val apk = File(folder, "game.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            listOf("lib/arm64-v8a/libgame.so", "lib/arm64-v8a/libunity.so", "lib/x86_64/libgame.so").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(300_000) { (it % 251).toByte() })
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("assets/level.dat"))
            zip.write(ByteArray(50_000))
            zip.closeEntry()
        }
        return apk
    }

    private fun patch(folder: File): ApkPatcher.Result =
        ApkPatcher.patch(questGame(folder), File(folder, "out"), ::asset)

    @Test
    fun keepsEveryLibraryOnItsPageSoAndroidCanInstallTheGame() {
        val folder = createTempDir()
        val result = patch(folder)
        val libraries = dataOffsets(result.apk).filterKeys { it.startsWith("lib/") && it.endsWith(".so") }
        assertTrue("в сборке не осталось библиотек", libraries.isNotEmpty())
        libraries.forEach { (name, offset) ->
            // Signing rewrites the archive; left to itself it re-aligns entries on 4 bytes, and a
            // library off its page makes the installer answer "приложение не установлено".
            assertEquals("$name не на границе страницы 16 КБ", 0L, offset % 16_384)
        }
        ZipFile(result.apk).use { zip ->
            libraries.keys.forEach { name ->
                assertEquals("$name сжата", ZipEntry.STORED, zip.getEntry(name).method)
            }
        }
    }

    @Test
    fun dropsTheBuildsThePhoneCannotRun() {
        val folder = createTempDir()
        val result = patch(folder)
        val names = ZipFile(result.apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(names.toString(), names.none { it.startsWith("lib/x86_64/") })
        assertTrue(names.toString(), "lib/arm64-v8a/libgame.so" in names)
        assertTrue(result.changes.toString(), result.changes.any { it.contains("других процессоров") })
    }

    @Test
    fun tellsAQuestBuildFromAGearVrOne() {
        val folder = createTempDir()
        // The fixture carries com.oculus.supportedDevices, which only a Quest build has.
        assertEquals(GameLibrary.Headset.QUEST, patch(folder).headset)
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "phonexr-patch-${System.nanoTime()}").apply { mkdirs() }

    /**
     * Where each entry's bytes start in the file. The zip API does not tell, so the central
     * directory is read the way Android's own installer reads it.
     */
    private fun dataOffsets(apk: File): Map<String, Long> {
        val bytes = RandomAccessFile(apk, "r").use { file ->
            ByteArray(file.length().toInt()).also { file.readFully(it) }
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // The end of central directory sits in the last 64 KB, after the signing block.
        var end = bytes.size - 22
        while (end >= 0 && buffer.getInt(end) != 0x06054b50) end--
        check(end >= 0) { "в APK нет центрального каталога" }
        val count = buffer.getShort(end + 10).toInt() and 0xffff
        var entry = buffer.getInt(end + 16)
        val offsets = LinkedHashMap<String, Long>()
        repeat(count) {
            val nameLength = buffer.getShort(entry + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(entry + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(entry + 32).toInt() and 0xffff
            val local = buffer.getInt(entry + 42)
            val name = String(bytes, entry + 46, nameLength, Charsets.UTF_8)
            // The local header repeats the name and may pad the extra field for alignment.
            val localName = buffer.getShort(local + 26).toInt() and 0xffff
            val localExtra = buffer.getShort(local + 28).toInt() and 0xffff
            offsets[name] = (local + 30 + localName + localExtra).toLong()
            entry += 46 + nameLength + extraLength + commentLength
        }
        return offsets
    }
}
