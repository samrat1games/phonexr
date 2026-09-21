package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web apps (PWA) from the PhoneXR store and the ones the user added. They open in the PhoneXR
 * browser, which runs in VR.
 *
 * Store format: a file "pwa.json" in the store folder, e.g.
 *   [{"name": "YouTube", "url": "https://m.youtube.com", "icon": "https://.../icon.png"}]
 */
object WebApps {
    data class App(val name: String, val url: String, val icon: String?) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("icon", icon ?: "")
    }

    /** The PhoneXR browser build, then stock Wolvic it is built from. */
    private val browsers = listOf("com.samrat.pxrbrowser", "com.igalia.wolvic")
    private const val PREFS = "web_apps"
    private const val KEY = "installed"

    fun browserPackage(context: Context): String? = browsers.firstOrNull { name ->
        runCatching { context.packageManager.getApplicationInfo(name, 0) }.isSuccess
    }

    fun open(context: Context, url: String): Boolean {
        val browser = browserPackage(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openBrowser(context: Context): Boolean {
        val browser = browserPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(browser) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun installed(context: Context): List<App> = parse(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
    )

    fun add(context: Context, app: App) {
        val list = installed(context).filterNot { it.url == app.url } + app
        save(context, list)
    }

    fun remove(context: Context, app: App) = save(context, installed(context).filterNot { it.url == app.url })

    private fun save(context: Context, list: List<App>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            .apply()
    }

    /** Network call: the store's web apps. */
    fun fromStore(): List<App> = runCatching { parse(GameStore.readText("pwa.json")) }
        .getOrDefault(BUILT_IN).distinctBy { it.url }

    /** Network call: an app icon, cached in memory for the session. */
    fun icon(app: App): Bitmap? {
        val source = app.icon?.takeIf { it.isNotBlank() } ?: return null
        cache[source]?.let { return it }
        return runCatching {
            val connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
        }.getOrNull()?.also { cache[source] = it }
    }

    private val cache = HashMap<String, Bitmap>()

    private fun parse(text: String): List<App> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            App(item.optString("name", url), url, item.optString("icon").takeIf { it.isNotBlank() })
        }
    }

    /** Useful PWAs remain available even when the remote Supabase catalogue is empty/offline. */
    private val BUILT_IN = listOf(
        App("YouTube", "https://m.youtube.com", null), App("YouTube Music", "https://music.youtube.com", null),
        App("Google Maps", "https://maps.google.com", null), App("Google Drive", "https://drive.google.com", null),
        App("Google Docs", "https://docs.google.com", null), App("Google Sheets", "https://sheets.google.com", null),
        App("Google Calendar", "https://calendar.google.com", null), App("Gmail", "https://mail.google.com", null),
        App("Google Photos", "https://photos.google.com", null), App("Google Keep", "https://keep.google.com", null),
        App("Spotify", "https://open.spotify.com", null), App("Discord", "https://discord.com/app", null),
        App("Telegram", "https://web.telegram.org", null), App("WhatsApp", "https://web.whatsapp.com", null),
        App("Reddit", "https://www.reddit.com", null), App("Pinterest", "https://www.pinterest.com", null),
        App("GitHub", "https://github.com", null), App("Figma", "https://www.figma.com", null),
        App("Notion", "https://www.notion.so", null), App("Canva", "https://www.canva.com", null),
        App("Photopea", "https://www.photopea.com", null), App("Excalidraw", "https://excalidraw.com", null),
        App("Wikipedia", "https://www.wikipedia.org", null), App("Internet Archive", "https://archive.org", null),
        App("Chess", "https://lichess.org", null), App("GeForce NOW", "https://play.geforcenow.com", null),
    )
}
