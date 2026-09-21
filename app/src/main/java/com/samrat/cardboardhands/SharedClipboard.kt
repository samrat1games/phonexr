package com.samrat.cardboardhands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * One clipboard for the phone in the headset and the phone in the hand: text copied on one shows up
 * on the other. It travels the same way the pointer does — a shout into the local network, nothing
 * to pair — and only while the user keeps it on.
 */
object SharedClipboard {
    const val PORT = 42428
    private const val PREFIX = "PXCB1 "

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    /** What arrived last, so an echo of our own text is not sent round again. */
    @Volatile private var lastSeen: String? = null

    fun start(context: Context) {
        if (running || !Settings.sharedClipboard(context)) return
        running = true
        thread(name = "PhoneXR clipboard") {
            val open = runCatching { DatagramSocket(PORT).apply { reuseAddress = true } }.getOrNull()
            socket = open ?: return@thread
            val buffer = ByteArray(8192)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                val text = runCatching { open.receive(packet); String(buffer, 0, packet.length) }.getOrNull() ?: continue
                if (!text.startsWith(PREFIX)) continue
                val content = text.removePrefix(PREFIX)
                if (content == lastSeen) continue
                lastSeen = content
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("PhoneXR", content))
                }
            }
            runCatching { open.close() }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    /** Sends what is in this phone's clipboard to the other one. Returns what was sent. */
    fun send(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        if (text.isEmpty()) return null
        lastSeen = text
        thread {
            runCatching {
                DatagramSocket().use { out ->
                    out.broadcast = true
                    val data = (PREFIX + text).toByteArray()
                    out.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT))
                }
            }
        }
        return text
    }
}
