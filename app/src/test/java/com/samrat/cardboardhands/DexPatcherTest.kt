package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Adler32

class DexPatcherTest {
    @Test
    fun disablesOnlyTheInjectedLibraryLoadAndRepairsDexHashes() {
        val source = Base64.getDecoder().decode(FIXTURE)
        val result = DexPatcher.disableSystemLoadLibrary(source, "frda")

        assertEquals(1, result.disabledCalls)
        assertFalse(source.contentEquals(result.bytes))
        assertEquals(0, DexPatcher.disableSystemLoadLibrary(result.bytes, "frda").disabledCalls)
        assertTrue(
            MessageDigest.getInstance("SHA-1").digest(result.bytes.copyOfRange(32, result.bytes.size))
                .contentEquals(result.bytes.copyOfRange(12, 32))
        )
        val adler = Adler32().apply { update(result.bytes, 12, result.bytes.size - 12) }.value.toInt()
        val stored = (0..3).sumOf { (result.bytes[8 + it].toInt() and 0xff) shl (it * 8) }
        assertEquals(adler, stored)
    }

    companion object {
        // D8 output for a class whose static initializer loads "safe" and then "frda".
        private const val FIXTURE =
            "ZGV4CjAzNQCCiHPezAVZuC1ZxNleMqqcRpVuoaUpTvhMAwAAcAAAAHhWNBIAAAAAAAAAAKwCAAANAAAAcAAAAAUAAACkAAAAAgAAALgAAAAAAAAAAAAAAAQAAADQAAAAAQAAAPAAAAA8AgAAEAEAAGIBAABsAQAAdAEAAIUBAACbAQAArwEAAMMBAADXAQAA2gEAAN4BAADkAQAA8QEAAPcBAAADAAAABAAAAAUAAAAGAAAABwAAAAcAAAAEAAAAAAAAAAgAAAAEAAAAXAEAAAAAAAAAAAAAAAAAAAEAAAABAAAAAQAAAAMAAQAKAAAAAAAAABEAAAABAAAAAAAAAAIAAAAAAAAAlQIAAAAAAAABAAAAAQAAAFABAAALAAAAGgALAHEQAwAAABoACQBxEAMAAAAOAAAAAQABAAEAAABWAQAABAAAAHAQAgAAAA4ABAAOWloAAgAOAAAAAQAAAAIACDxjbGluaXQ+AAY8aW5pdD4AD0RleEZpeHR1cmUuamF2YQAUTGZpeHR1cmUvRGV4Rml4dHVyZTsAEkxqYXZhL2xhbmcvT2JqZWN0OwASTGphdmEvbGFuZy9TdHJpbmc7ABJMamF2YS9sYW5nL1N5c3RlbTsAAVYAAlZMAARmcmRhAAtsb2FkTGlicmFyeQAEc2FmZQCbAX5+RDh7ImJhY2tlbmQiOiJkZXgiLCJjb21waWxhdGlvbi1tb2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MSwic2hhLTEiOiIwODRhODkxMjZhZTA1OTZjNTk5MTQzZGY1N2E2NTQ2NDZhZjI4MzEzIiwidmVyc2lvbiI6IjkuMi40LWRldiJ9AAAAAgAAiIAEkAIBgYAEuAIAAAAAAAAADQAAAAAAAAABAAAAAAAAAAEAAAANAAAAcAAAAAIAAAAFAAAApAAAAAMAAAACAAAAuAAAAAUAAAAEAAAA0AAAAAYAAAABAAAA8AAAAAEgAAACAAAAEAEAAAMgAAACAAAAUAEAAAEQAAABAAAAXAEAAAIgAAANAAAAYgEAAAAgAAABAAAAlQIAAAMQAAABAAAAqAIAAAAQAAABAAAArAIAAA=="
    }
}
