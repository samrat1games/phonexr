package com.samrat.cardboardhands

import java.security.MessageDigest
import java.util.zip.Adler32

/** Small, dependency-free DEX edit used for APKs already injected with a crashing Frida gadget. */
object DexPatcher {
    data class Result(val bytes: ByteArray, val disabledCalls: Int)

    fun disableSystemLoadLibrary(source: ByteArray, library: String): Result {
        if (source.size < 112 || source.copyOfRange(0, 4).decodeToString() != "dex\n") return Result(source, 0)
        val strings = strings(source) ?: return Result(source, 0)
        val libraryIndex = strings.indexOf(library)
        val loadNameIndex = strings.indexOf("loadLibrary")
        val systemDescriptorIndex = strings.indexOf("Ljava/lang/System;")
        if (libraryIndex < 0 || loadNameIndex < 0 || systemDescriptorIndex < 0) return Result(source, 0)

        val typeSize = source.u32(0x40)
        val typeOffset = source.u32(0x44)
        val systemType = (0 until typeSize).firstOrNull { source.u32(typeOffset + it * 4) == systemDescriptorIndex }
            ?: return Result(source, 0)
        val methodSize = source.u32(0x58)
        val methodOffset = source.u32(0x5c)
        val loadMethod = (0 until methodSize).firstOrNull { index ->
            val offset = methodOffset + index * 8
            source.u16(offset) == systemType && source.u32(offset + 4) == loadNameIndex
        } ?: return Result(source, 0)

        val output = source.copyOf()
        var calls = 0
        var offset = 0
        while (offset + 10 <= output.size) {
            val opcode = output[offset].toInt() and 0xff
            val constantSize = when {
                opcode == 0x1a && output.u16(offset + 2) == libraryIndex -> 4
                opcode == 0x1b && output.u32(offset + 2) == libraryIndex -> 6
                else -> 0
            }
            val invoke = offset + constantSize
            if (constantSize > 0 && invoke + 6 <= output.size &&
                (output[invoke].toInt() and 0xff) == 0x71 && output.u16(invoke + 2) == loadMethod
            ) {
                // invoke-static is three 16-bit code units. NOPs preserve every code/debug offset.
                output.fill(0, invoke, invoke + 6)
                calls++
                offset = invoke + 6
            } else offset++
        }
        if (calls > 0) updateHashes(output)
        return Result(if (calls > 0) output else source, calls)
    }

    private fun strings(bytes: ByteArray): List<String>? {
        val size = bytes.u32(0x38)
        val table = bytes.u32(0x3c)
        if (size < 0 || table < 0 || table + size * 4 > bytes.size) return null
        return List(size) { index ->
            var cursor = bytes.u32(table + index * 4)
            if (cursor !in bytes.indices) return null
            // UTF-16 length encoded as ULEB128. The strings we match are plain ASCII.
            do {
                if (cursor !in bytes.indices) return null
            } while ((bytes[cursor++].toInt() and 0x80) != 0)
            val end = (cursor until bytes.size).firstOrNull { bytes[it].toInt() == 0 } ?: return null
            String(bytes, cursor, end - cursor, Charsets.UTF_8)
        }
    }

    private fun updateHashes(bytes: ByteArray) {
        val signature = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
        signature.copyInto(bytes, 12)
        val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt()
        bytes.put32(8, checksum)
    }

    private fun ByteArray.u16(offset: Int) =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(offset: Int) = u16(offset) or (u16(offset + 2) shl 16)

    private fun ByteArray.put32(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
