package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidManifestPatcherTest {
    private fun manifest(name: String = "open-saber-manifest.bin"): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun lowersTargetSdkSoPackageVisibilityStopsHidingTheRuntimeBroker() {
        val result = AndroidManifestPatcher.patch(manifest())
        assertTrue(result.changes.toString(), result.changes.any { it.startsWith("targetSdk 34 → 29") })
        // Patching again finds nothing left to change.
        assertEquals(emptyList<String>(), AndroidManifestPatcher.patch(result.bytes).changes)
    }

    @Test
    fun clearsRequiredHeadsetFeatures() {
        // Same manifest with the headset feature marked required, the way Quest builds ship it.
        val result = AndroidManifestPatcher.patch(manifest("quest-style-manifest.bin"))
        assertTrue(result.changes.toString(), result.changes.any { it.contains("android.hardware.vr.headtracking") })
        // Nothing is left to clear on a second pass.
        assertEquals(emptyList<String>(), AndroidManifestPatcher.patch(result.bytes).changes)
    }

    // ---------------------------------------------------------------- a build made for a Quest

    @Test
    fun givesAQuestBuildThePermissionsItNeedsToFindTheRuntime() {
        val patched = Xml.parse(AndroidManifestPatcher.patch(manifest(QUEST)).bytes)
        val permissions = patched.filter { it.tag == "uses-permission" }.mapNotNull { it.attributes["name"] }
        assertTrue(permissions.toString(), permissions.containsAll(listOf(
            "org.khronos.openxr.permission.OPENXR",
            "org.khronos.openxr.permission.OPENXR_SYSTEM",
            "android.permission.INTERNET",
            "com.samrat.cardboardhands.permission.START_HAND_TRACKING"
        )))
        // What the game already asked for stays.
        assertTrue(permissions.toString(), "com.oculus.permission.HAND_TRACKING" in permissions)
    }

    @Test
    fun letsAQuestBuildSeeTheOpenXrBroker() {
        val patched = Xml.parse(AndroidManifestPatcher.patch(manifest(QUEST)).bytes)
        assertTrue(patched.any { it.tag == "queries" })
        assertTrue(patched.any {
            it.tag == "provider" && it.attributes["authorities"]?.contains("openxr.runtime_broker") == true
        })
        assertTrue(patched.any { it.tag == "package" && it.attributes["name"] == PhoneXrRuntime.PACKAGE })
        assertTrue(patched.any {
            it.tag == "action" && it.attributes["name"] == "org.khronos.openxr.OpenXRRuntimeService"
        })
    }

    @Test
    fun marksTheLauncherActivityAsVrSoPhoneXrStartsIt() {
        val patched = Xml.parse(AndroidManifestPatcher.patch(manifest(QUEST)).bytes)
        val categories = patched.filter { it.tag == "category" }.mapNotNull { it.attributes["name"] }
        assertTrue(categories.toString(), "org.khronos.openxr.intent.category.IMMERSIVE_HMD" in categories)
    }

    @Test
    fun optimisesTheQuestBuildForThePhone() {
        val result = AndroidManifestPatcher.patch(manifest(QUEST))
        val application = Xml.parse(result.bytes).first { it.tag == "application" }
        assertEquals("false", application.attributes["debuggable"])
        assertEquals("false", application.attributes["extractNativeLibs"])
        assertTrue(result.changes.toString(), result.changes.any { it.startsWith("оптимизация") })
    }

    @Test
    fun aSecondPassOverAPatchedQuestBuildChangesNothing() {
        val once = AndroidManifestPatcher.patch(manifest(QUEST))
        assertTrue(once.changes.isNotEmpty())
        assertEquals(emptyList<String>(), AndroidManifestPatcher.patch(once.bytes).changes)
        // And the file the second pass read is still the same file.
        assertEquals(Xml.parse(once.bytes).size, Xml.parse(AndroidManifestPatcher.patch(once.bytes).bytes).size)
    }

    @Test
    fun seesThatAQuestBuildWouldNotFindTheRuntime() {
        assertFalse(AndroidManifestPatcher.findsRuntime(manifest(QUEST)))
        // Patched, it does — that is how the game list stops asking to patch it again.
        assertTrue(AndroidManifestPatcher.findsRuntime(AndroidManifestPatcher.patch(manifest(QUEST)).bytes))
        // A game built for PhoneXR needs nothing: it asks for the permission and queries the broker.
        assertTrue(AndroidManifestPatcher.findsRuntime(manifest()))
    }

    @Test
    fun keepsEveryElementOfTheOriginal() {
        val before = Xml.parse(manifest(QUEST))
        val after = Xml.parse(AndroidManifestPatcher.patch(manifest(QUEST)).bytes)
        // Values the patcher rewrites (targetSdk, required features) differ, the elements do not.
        before.forEach { element ->
            assertTrue("пропал $element", after.any {
                it.tag == element.tag && it.attributes["name"] == element.attributes["name"]
            })
        }
        assertTrue(after.size > before.size)
    }

    private companion object {
        const val QUEST = "quest-game-manifest.bin"
    }

    /**
     * A reader of binary XML written for the test alone: the patcher must produce a file someone
     * else can parse, not only a file it can read back itself.
     */
    private data class Element(val tag: String, val attributes: Map<String, String>)

    private object Xml {
        fun parse(bytes: ByteArray): List<Element> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(buffer.getInt(4) == bytes.size) { "размер в заголовке не совпадает с файлом" }
            var strings = emptyList<String>()
            val elements = mutableListOf<Element>()
            var offset = 8
            while (offset + 8 <= bytes.size) {
                val type = buffer.getShort(offset).toInt() and 0xffff
                val size = buffer.getInt(offset + 4)
                check(size > 0 && offset + size <= bytes.size) { "чанк $type выходит за файл" }
                when (type) {
                    0x0001 -> strings = strings(buffer, offset)
                    0x0102 -> elements += element(buffer, offset, strings)
                }
                offset += size
            }
            return elements
        }

        private fun element(buffer: ByteBuffer, offset: Int, strings: List<String>): Element {
            val start = buffer.getShort(offset + 24).toInt() and 0xffff
            val size = buffer.getShort(offset + 26).toInt() and 0xffff
            val count = buffer.getShort(offset + 28).toInt() and 0xffff
            val attributes = (0 until count).associate { index ->
                val attribute = offset + 16 + start + index * size
                val name = strings[buffer.getInt(attribute + 4)]
                val dataType = buffer.get(attribute + 15).toInt() and 0xff
                val data = buffer.getInt(attribute + 16)
                name to when (dataType) {
                    0x03 -> strings[data]
                    0x12 -> if (data == 0) "false" else "true"
                    else -> data.toString()
                }
            }
            return Element(strings[buffer.getInt(offset + 20)], attributes)
        }

        private fun strings(buffer: ByteBuffer, offset: Int): List<String> {
            val count = buffer.getInt(offset + 8)
            val utf8 = buffer.getInt(offset + 16) and 0x100 != 0
            val start = offset + buffer.getInt(offset + 20)
            return (0 until count).map { index ->
                var cursor = start + buffer.getInt(offset + 28 + index * 4)
                if (utf8) {
                    fun length(): Int {
                        val value = buffer.get(cursor++).toInt() and 0xff
                        return if (value and 0x80 == 0) value
                        else ((value and 0x7f) shl 8) or (buffer.get(cursor++).toInt() and 0xff)
                    }
                    length()
                    val data = ByteArray(length())
                    (data.indices).forEach { data[it] = buffer.get(cursor + it) }
                    String(data, Charsets.UTF_8)
                } else {
                    val length = buffer.getShort(cursor).toInt() and 0xffff
                    cursor += 2
                    String(CharArray(length) { buffer.getShort(cursor + it * 2).toInt().toChar() })
                }
            }
        }
    }
}
