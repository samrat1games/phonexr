package com.samrat.cardboardhands

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary AndroidManifest.xml editor.
 *
 * Two kinds of edit:
 *  - in place, rewriting a value that is already in the file: targetSdkVersion drops to 29, which
 *    switches package visibility filtering off; headset features stop being required, so the
 *    installer accepts the APK on a phone; the "install the other split APKs first" flags are
 *    cleared for APKs taken from Google Play; debugging and library extraction are turned off;
 *  - rebuilding the file, which appends strings to the pool and whole elements to the tree. That is
 *    how a Quest build gets what it never had a reason to carry: the OpenXR permissions, the
 *    <queries> block for the runtime broker and the VR category on its launcher activity. Without
 *    them the game finds no OpenXR runtime and draws nothing — the black screen the patcher used to
 *    leave behind.
 */
object AndroidManifestPatcher {
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val CHUNK_CDATA = 0x0104
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val MAX_TARGET_SDK = 29

    /** Resource ids of the attributes added elements use; a name string only counts with its id. */
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_AUTHORITIES = 0x01010018

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val IMMERSIVE_HMD = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
    private const val BROKER_AUTHORITIES =
        "org.khronos.openxr.runtime_broker;org.khronos.openxr.system_runtime_broker"

    /** Permissions an OpenXR game needs to reach the PhoneXR runtime and PhoneXR's own tracking. */
    private val PERMISSIONS = listOf(
        "org.khronos.openxr.permission.OPENXR",
        "org.khronos.openxr.permission.OPENXR_SYSTEM",
        // The tracking stream (PH5) arrives over a local UDP socket, and a socket needs INTERNET.
        "android.permission.INTERNET",
        // A patched game carries the PhoneXR signature, so it may start hand tracking itself.
        "com.samrat.cardboardhands.permission.START_HAND_TRACKING",
    )

    /** Packages the game is allowed to see even with package visibility filtering on. */
    private val VISIBLE_PACKAGES = listOf(
        PhoneXrRuntime.PACKAGE,
        "org.khronos.openxr.runtime_broker",
        "com.samrat.cardboardhands",
    )

    /** OpenXR services the loader looks for through the broker. */
    private val OPENXR_SERVICES = listOf(
        "org.khronos.openxr.OpenXRRuntimeService",
        "org.khronos.openxr.OpenXRApiLayerService",
    )

    data class Result(val bytes: ByteArray, val changes: List<String>)

    /**
     * Whether the game, as it is, can reach an OpenXR runtime on a phone. A build made for a headset
     * cannot: from targetSdk 30 on, package visibility hides the runtime broker from anyone who does
     * not ask for it in <queries>, and all the player sees is a black screen. Unreadable manifests
     * count as fine — such a game is left alone.
     */
    fun findsRuntime(manifest: ByteArray): Boolean = Tree.parse(manifest)?.findsRuntime() ?: true

    /**
     * Names of the <meta-data> and <category> elements of a build — what tells a Quest game from a
     * Gear VR one, since both draw through VrApi and look alike from the outside.
     */
    fun markers(manifest: ByteArray): Set<String> = Tree.parse(manifest)?.markers() ?: emptySet()

    fun patch(manifest: ByteArray): Result {
        val tree = Tree.parse(manifest) ?: return Result(manifest, emptyList())
        val changes = mutableListOf<String>()
        tree.rewriteValues(changes)
        tree.addMissingElements(changes)
        return Result(tree.build(), changes)
    }

    private fun isHeadsetFeature(name: String) = name.startsWith("oculus.") ||
        name.startsWith("com.oculus.") || name.startsWith("android.hardware.vr") ||
        name.startsWith("wave.feature") || name.startsWith("picovr")

    /** A string value of an element to be written, e.g. android:name="…". */
    private class Attribute(val namespace: Int, val name: Int, val value: Int)

    private sealed class Node {
        /** A chunk kept as it is in the source file, edits included. */
        class Original(val offset: Int, val size: Int) : Node()
        class Added(val data: ByteArray) : Node()
    }

    /**
     * The parsed file: its string pool, the chunks before the tree (the resource map) and the tree
     * itself as a flat list of chunks — which is what the format is, elements opening and closing
     * in order.
     */
    private class Tree(
        private val bytes: ByteArray,
        private val buffer: ByteBuffer,
        private val strings: MutableList<String>,
        private val utf8: Boolean,
        private val poolFlags: Int,
        private val styleOffsets: IntArray,
        private val styleBytes: ByteArray,
        private val resourceIds: IntArray,
        private val middle: List<IntArray>,
        private val nodes: MutableList<Node>
    ) {
        /** Set once the file can no longer be kept as it is: strings or elements were added. */
        private var rebuilt = false

        // ------------------------------------------------------------ editing values in place

        fun rewriteValues(changes: MutableList<String>) {
            for (node in nodes.toList()) {
                if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT) continue
                rewriteElement(node, changes)
            }
        }

        private fun rewriteElement(node: Node.Original, changes: MutableList<String>) {
            val element = name(node) ?: return
            var featureName: String? = null
            var required = -1
            forEachAttribute(node) { attributeName, dataType, data ->
                when {
                    attributeName == "name" -> featureName = strings.getOrNull(buffer.getInt(data))
                    attributeName == "required" -> required = data
                    attributeName == "targetSdkVersion" && element == "uses-sdk" && dataType == TYPE_INT_DEC -> {
                        val current = buffer.getInt(data)
                        if (current > MAX_TARGET_SDK) {
                            buffer.putInt(data, MAX_TARGET_SDK)
                            changes += "targetSdk $current → $MAX_TARGET_SDK"
                        }
                    }
                    attributeName == "isSplitRequired" && dataType == TYPE_INT_BOOLEAN -> {
                        if (buffer.getInt(data) != 0) {
                            buffer.putInt(data, 0)
                            changes += "снят запрет на установку без дополнительных файлов"
                        }
                    }
                    // Play-delivered APKs also name the splits they expect; an empty list installs alone.
                    (attributeName == "requiredSplitTypes" || attributeName == "splitTypes") &&
                        dataType == TYPE_STRING -> {
                        if (strings.getOrNull(buffer.getInt(data)).isNullOrEmpty().not()) {
                            val empty = stringIndex("")
                            buffer.putInt(data - 8, empty) // the raw value sits right before the typed one
                            buffer.putInt(data, empty)
                            changes += "убран список дополнительных файлов ($attributeName)"
                        }
                    }
                    attributeName == "extractNativeLibs" && element == "application" && dataType == TYPE_INT_BOOLEAN -> {
                        if (buffer.getInt(data) != 0) {
                            buffer.putInt(data, 0)
                            changes += "оптимизация: библиотеки читаются прямо из APK, без распаковки"
                        }
                    }
                    attributeName == "debuggable" && element == "application" && dataType == TYPE_INT_BOOLEAN -> {
                        if (buffer.getInt(data) != 0) {
                            buffer.putInt(data, 0)
                            changes += "оптимизация: выключена отладочная сборка — игра идёт быстрее"
                        }
                    }
                }
            }
            // <uses-feature> carries its name and required flag in the same element, so decide afterwards.
            val feature = featureName
            if (element == "uses-feature" && required >= 0 && feature != null && isHeadsetFeature(feature)) {
                if (buffer.getInt(required) != 0) {
                    buffer.putInt(required, 0)
                    changes += "$feature больше не обязательна"
                }
            }
        }

        // ------------------------------------------------------------ adding elements

        fun addMissingElements(changes: MutableList<String>) {
            // An added android:name only means anything when its string carries the attribute's
            // resource id; a pool without one cannot be extended this way.
            val nameAttribute = resourceIds.indexOfFirst { it == ATTR_NAME }
            val namespace = strings.indexOf(ANDROID_NS)
            if (nameAttribute < 0 || nameAttribute >= strings.size || namespace < 0) return
            val manifest = nodes.indexOfFirst { it is Node.Original && type(it) == CHUNK_START_ELEMENT && name(it) == "manifest" }
            if (manifest < 0) return

            val inserts = sortedMapOf<Int, MutableList<ByteArray>>()
            fun insert(at: Int, chunks: List<ByteArray>) {
                inserts.getOrPut(at) { mutableListOf() } += chunks
            }

            val missing = PERMISSIONS - declaredPermissions()
            if (missing.isNotEmpty()) {
                missing.forEach { permission ->
                    insert(manifest + 1, element("uses-permission", Attribute(namespace, nameAttribute, stringIndex(permission))))
                }
                changes += "добавлены разрешения: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            }

            val queries = queriesAddition(namespace, nameAttribute)
            if (queries.isNotEmpty()) {
                insert(queriesEnd() ?: (manifest + 1), queries)
                changes += "добавлен доступ к OpenXR Runtime Broker — игра находит рантайм PhoneXR"
            }

            val filters = launcherFiltersWithoutVrCategory()
            if (filters.isNotEmpty()) {
                val category = stringIndex(IMMERSIVE_HMD)
                filters.forEach { at -> insert(at, element("category", Attribute(namespace, nameAttribute, category))) }
                changes += "игра помечена как VR — PhoneXR запускает её сам"
            }

            if (inserts.isEmpty()) return
            // Back to front, so the positions found above stay valid.
            inserts.keys.sortedDescending().forEach { at -> nodes.addAll(at, inserts.getValue(at).map { Node.Added(it) }) }
            rebuilt = true
        }

        /** See [AndroidManifestPatcher.findsRuntime]. */
        // Below targetSdk 30 nothing is hidden from the game, so the broker is there to find.
        fun findsRuntime(): Boolean = targetSdk() <= MAX_TARGET_SDK || queriesBroker()

        /** The target SDK, or 0 when the manifest does not say — then nothing is hidden either. */
        private fun targetSdk(): Int {
            var target = 0
            nodes.forEach { node ->
                if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT || name(node) != "uses-sdk") return@forEach
                forEachAttribute(node) { attributeName, dataType, data ->
                    if (attributeName == "targetSdkVersion" && dataType == TYPE_INT_DEC) target = buffer.getInt(data)
                }
            }
            return target
        }

        /** Either the game already queries the broker, or an earlier PhoneXR patch added the block. */
        private fun queriesBroker(): Boolean = nodes.any { node ->
            if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT) return@any false
            when (name(node)) {
                "provider" -> attributeValue(node, "authorities")?.contains("openxr.runtime_broker") == true
                "package" -> attributeValue(node, "name") == PhoneXrRuntime.PACKAGE
                else -> false
            }
        }

        /** See [AndroidManifestPatcher.markers]. */
        fun markers(): Set<String> = nodes.mapNotNullTo(mutableSetOf()) { node ->
            if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT) null
            else if (name(node) != "meta-data" && name(node) != "category") null
            else attributeValue(node, "name")
        }

        /** Permission names the manifest already asks for. */
        private fun declaredPermissions(): Set<String> = nodes.mapNotNull { node ->
            if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT || name(node) != "uses-permission") null
            else attributeValue(node, "name")
        }.toSet()

        /**
         * The <queries> children that tell Android this game may see the OpenXR broker. The block is
         * skipped whole when the game already queries the broker.
         */
        private fun queriesAddition(namespace: Int, nameAttribute: Int): List<ByteArray> {
            if (queriesBroker()) return emptyList()
            val children = mutableListOf<ByteArray>()
            val authorities = resourceIds.indexOfFirst { it == ATTR_AUTHORITIES }
            if (authorities in strings.indices) {
                children += element("provider", Attribute(namespace, authorities, stringIndex(BROKER_AUTHORITIES)))
            }
            OPENXR_SERVICES.forEach { service ->
                children += wrap("intent", element("action", Attribute(namespace, nameAttribute, stringIndex(service))))
            }
            VISIBLE_PACKAGES.forEach { target ->
                children += element("package", Attribute(namespace, nameAttribute, stringIndex(target)))
            }
            // An existing <queries> element takes the children as they are; otherwise it is added too.
            return if (queriesEnd() != null) children else wrap("queries", children)
        }

        /** Position of the closing tag of an existing <queries>, or null when the game has none. */
        private fun queriesEnd(): Int? {
            val start = nodes.indexOfFirst {
                it is Node.Original && type(it) == CHUNK_START_ELEMENT && name(it) == "queries"
            }
            if (start < 0) return null
            return closingTag(start)
        }

        /**
         * Launcher (or Oculus VR) intent filters that do not yet say the activity is an OpenXR one.
         * Positions point at the closing tag, where a <category> is added.
         */
        private fun launcherFiltersWithoutVrCategory(): List<Int> {
            val found = mutableListOf<Int>()
            nodes.forEachIndexed { index, node ->
                if (node !is Node.Original || type(node) != CHUNK_START_ELEMENT || name(node) != "intent-filter") return@forEachIndexed
                val end = closingTag(index) ?: return@forEachIndexed
                var main = false
                var launcher = false
                var immersive = false
                for (child in index + 1 until end) {
                    val element = nodes[child] as? Node.Original ?: continue
                    if (type(element) != CHUNK_START_ELEMENT) continue
                    val value = attributeValue(element, "name")
                    when (name(element)) {
                        "action" -> if (value == "android.intent.action.MAIN") main = true
                        "category" -> when (value) {
                            "android.intent.category.LAUNCHER", "com.oculus.intent.category.VR" -> launcher = true
                            IMMERSIVE_HMD -> immersive = true
                        }
                    }
                }
                if (main && launcher && !immersive) found += end
            }
            return found
        }

        /** Index of the chunk closing the element opened at [start]. */
        private fun closingTag(start: Int): Int? {
            var depth = 0
            for (index in start until nodes.size) {
                val node = nodes[index] as? Node.Original ?: continue
                when (type(node)) {
                    CHUNK_START_ELEMENT -> depth++
                    CHUNK_END_ELEMENT -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            return null
        }

        // ------------------------------------------------------------ reading chunks

        private fun type(node: Node.Original) = buffer.getShort(node.offset).toInt() and 0xffff

        /** Element name of a start or end tag; both keep it right after the namespace field. */
        private fun name(node: Node.Original) = strings.getOrNull(buffer.getInt(node.offset + 20))

        private fun attributeValue(node: Node.Original, attribute: String): String? {
            var found: String? = null
            forEachAttribute(node) { attributeName, dataType, data ->
                if (found == null && attributeName == attribute && dataType == TYPE_STRING) {
                    found = strings.getOrNull(buffer.getInt(data))
                }
            }
            return found
        }

        /** Calls [action] with the name, value type and offset of the value of every attribute. */
        private inline fun forEachAttribute(node: Node.Original, action: (String, Int, Int) -> Unit) {
            val offset = node.offset
            // Attribute offsets live in the element header and count from the namespace field at +16.
            val start = buffer.getShort(offset + 24).toInt() and 0xffff
            val size = buffer.getShort(offset + 26).toInt() and 0xffff
            val count = buffer.getShort(offset + 28).toInt() and 0xffff
            if (size < 20) return
            for (index in 0 until count) {
                val attribute = offset + 16 + start + index * size
                val attributeName = strings.getOrNull(buffer.getInt(attribute + 4)) ?: continue
                action(attributeName, buffer.get(attribute + 15).toInt() and 0xff, attribute + 16)
            }
        }

        // ------------------------------------------------------------ writing chunks

        private fun stringIndex(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings += value
            rebuilt = true
            return strings.size - 1
        }

        /** A self-closing element: its opening and closing tags. */
        private fun element(tag: String, vararg attributes: Attribute): List<ByteArray> {
            val name = stringIndex(tag)
            return listOf(startTag(name, attributes.toList()), endTag(name))
        }

        private fun wrap(tag: String, children: List<ByteArray>): List<ByteArray> {
            val name = stringIndex(tag)
            return listOf(startTag(name, emptyList())) + children + endTag(name)
        }

        private fun startTag(name: Int, attributes: List<Attribute>): ByteArray {
            val size = 16 + 20 + attributes.size * 20
            val chunk = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            chunk.putShort(CHUNK_START_ELEMENT.toShort())
            chunk.putShort(16)
            chunk.putInt(size)
            chunk.putInt(1)  // line number
            chunk.putInt(-1) // no comment
            chunk.putInt(-1) // no namespace on the element itself
            chunk.putInt(name)
            chunk.putShort(20) // attributes follow the header
            chunk.putShort(20) // and are 20 bytes each
            chunk.putShort(attributes.size.toShort())
            chunk.putShort(0) // no android:id
            chunk.putShort(0) // no class
            chunk.putShort(0) // no style
            attributes.forEach { attribute ->
                chunk.putInt(attribute.namespace)
                chunk.putInt(attribute.name)
                chunk.putInt(attribute.value) // the value as written in the source XML
                chunk.putShort(8)             // size of the typed value
                chunk.put(0)
                chunk.put(TYPE_STRING.toByte())
                chunk.putInt(attribute.value)
            }
            return chunk.array()
        }

        private fun endTag(name: Int): ByteArray {
            val chunk = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            chunk.putShort(CHUNK_END_ELEMENT.toShort())
            chunk.putShort(16)
            chunk.putInt(24)
            chunk.putInt(1)
            chunk.putInt(-1)
            chunk.putInt(-1)
            chunk.putInt(name)
            return chunk.array()
        }

        fun build(): ByteArray {
            if (!rebuilt) return bytes
            val out = ByteArrayOutputStream()
            out.write(bytes, 0, 8) // the file header, its size fixed below
            out.write(stringPool())
            middle.forEach { out.write(bytes, it[0], it[1]) }
            nodes.forEach { node ->
                when (node) {
                    is Node.Original -> out.write(bytes, node.offset, node.size)
                    is Node.Added -> out.write(node.data)
                }
            }
            val result = out.toByteArray()
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
            return result
        }

        /** The pool written out again, with the appended strings at the end where no resource id is expected. */
        private fun stringPool(): ByteArray {
            val encoded = strings.map { encode(it) }
            val offsets = IntArray(encoded.size)
            var cursor = 0
            encoded.forEachIndexed { index, data ->
                offsets[index] = cursor
                cursor += data.size
            }
            val padding = (4 - cursor % 4) % 4
            val stringsStart = 28 + 4 * encoded.size + 4 * styleOffsets.size
            val stylesStart = if (styleBytes.isEmpty()) 0 else stringsStart + cursor + padding
            val size = stringsStart + cursor + padding + styleBytes.size
            val chunk = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            chunk.putShort(CHUNK_STRING_POOL.toShort())
            chunk.putShort(28)
            chunk.putInt(size)
            chunk.putInt(encoded.size)
            chunk.putInt(styleOffsets.size)
            // Appended strings break the sorted order the flag would promise.
            chunk.putInt(poolFlags and SORTED.inv())
            chunk.putInt(stringsStart)
            chunk.putInt(stylesStart)
            offsets.forEach { chunk.putInt(it) }
            styleOffsets.forEach { chunk.putInt(it) }
            encoded.forEach { chunk.put(it) }
            repeat(padding) { chunk.put(0) }
            chunk.put(styleBytes)
            return chunk.array()
        }

        private fun encode(value: String): ByteArray {
            val out = ByteArrayOutputStream()
            if (utf8) {
                val data = value.toByteArray(Charsets.UTF_8)
                writeLength8(out, value.length) // characters, as UTF-16 counts them
                writeLength8(out, data.size)    // then bytes
                out.write(data)
                out.write(0)
            } else {
                writeLength16(out, value.length)
                value.forEach { character ->
                    out.write(character.code and 0xff)
                    out.write((character.code ushr 8) and 0xff)
                }
                out.write(0)
                out.write(0)
            }
            return out.toByteArray()
        }

        private fun writeLength8(out: ByteArrayOutputStream, length: Int) {
            if (length > 0x7f) out.write(0x80 or (length ushr 8))
            out.write(length and 0xff)
        }

        private fun writeLength16(out: ByteArrayOutputStream, length: Int) {
            if (length > 0x7fff) {
                out.write((0x8000 or (length ushr 16)) and 0xff)
                out.write(((0x8000 or (length ushr 16)) ushr 8) and 0xff)
            }
            out.write(length and 0xff)
            out.write((length ushr 8) and 0xff)
        }

        companion object {
            private const val SORTED = 0x1
            private const val UTF8 = 0x100

            fun parse(bytes: ByteArray): Tree? {
                if (bytes.size < 8) return null
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var pool: IntArray? = null
                val middle = mutableListOf<IntArray>()
                val nodes = mutableListOf<Node>()
                var resourceIds = IntArray(0)
                var offset = 8 // skip the file header
                while (offset + 8 <= bytes.size) {
                    val type = buffer.getShort(offset).toInt() and 0xffff
                    val size = buffer.getInt(offset + 4)
                    if (size <= 0 || offset + size > bytes.size) break
                    when {
                        type in CHUNK_START_NAMESPACE..CHUNK_CDATA -> nodes += Node.Original(offset, size)
                        type == CHUNK_STRING_POOL && pool == null -> pool = intArrayOf(offset, size)
                        else -> {
                            if (type == CHUNK_RESOURCE_MAP) {
                                val header = buffer.getShort(offset + 2).toInt() and 0xffff
                                resourceIds = IntArray((size - header) / 4) { buffer.getInt(offset + header + it * 4) }
                            }
                            middle += intArrayOf(offset, size)
                        }
                    }
                    offset += size
                }
                val poolChunk = pool ?: return null
                val flags = buffer.getInt(poolChunk[0] + 16)
                val styleCount = buffer.getInt(poolChunk[0] + 12)
                val stylesStart = buffer.getInt(poolChunk[0] + 24)
                val strings = parseStrings(buffer, poolChunk[0], poolChunk[1]) ?: return null
                // Styled strings are unheard of in a manifest, but keep them byte for byte if they are there.
                val styled = styleCount > 0 && stylesStart > 0
                val styleOffsets = IntArray(if (styled) styleCount else 0) {
                    buffer.getInt(poolChunk[0] + 28 + 4 * strings.size + it * 4)
                }
                val styleBytes = if (!styled) ByteArray(0) else
                    bytes.copyOfRange(poolChunk[0] + stylesStart, poolChunk[0] + poolChunk[1])
                return Tree(
                    bytes, buffer, strings.toMutableList(), flags and UTF8 != 0, flags,
                    styleOffsets, styleBytes, resourceIds, middle, nodes
                )
            }

            private fun parseStrings(buffer: ByteBuffer, offset: Int, size: Int): List<String>? {
                val count = buffer.getInt(offset + 8)
                if (count < 0) return null
                val flags = buffer.getInt(offset + 16)
                val stringsStart = offset + buffer.getInt(offset + 20)
                val utf8 = (flags and UTF8) != 0
                return (0 until count).map { index ->
                    val start = stringsStart + buffer.getInt(offset + 28 + index * 4)
                    if (start < 0 || start >= offset + size) return@map ""
                    if (utf8) readUtf8(buffer, start) else readUtf16(buffer, start)
                }
            }

            private fun readUtf8(buffer: ByteBuffer, start: Int): String {
                var cursor = start
                // A length is one byte, or two when the high bit marks a long string.
                fun length(): Int {
                    val value = buffer.get(cursor++).toInt() and 0xff
                    if (value and 0x80 == 0) return value
                    return ((value and 0x7f) shl 8) or (buffer.get(cursor++).toInt() and 0xff)
                }
                length() // character count, the byte count follows
                val bytes = ByteArray(length())
                val copy = buffer.duplicate()
                copy.position(cursor)
                copy.get(bytes)
                return String(bytes, Charsets.UTF_8)
            }

            private fun readUtf16(buffer: ByteBuffer, start: Int): String {
                var length = buffer.getShort(start).toInt() and 0xffff
                var cursor = start + 2
                if (length and 0x8000 != 0) {
                    length = ((length and 0x7fff) shl 16) or (buffer.getShort(cursor).toInt() and 0xffff)
                    cursor += 2
                }
                val characters = CharArray(length) { buffer.getShort(cursor + it * 2).toInt().toChar() }
                return String(characters)
            }
        }
    }
}
