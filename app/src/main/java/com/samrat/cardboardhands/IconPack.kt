package com.samrat.cardboardhands

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * PhoneXR icons for the VR home, light or dark (assets/icons/light, assets/icons/dark), found
 * by package name. Apps without one keep their own icon.
 */
object IconPack {
    enum class Theme(val folder: String) { LIGHT("light"), DARK("dark") }

    private const val PREFS = "icon_pack"
    private val cache = HashMap<String, Drawable?>()
    private var files: Map<Theme, List<String>>? = null

    /** Icons for PhoneXR's own apps, taken from the pack. */
    val OWN = mapOf(
        "own:browser" to "com.android.browser",
        "own:photos" to "com.miui.gallery",
        "own:settings" to "com.android.settings",
        "own:store" to "com.android.vending",
        "own:calls" to "com.google.android.apps.tachyon",
        "own:android" to "com.android.fileexplorer",
        "own:desktop" to "own.desktop",
        "own:leos" to "own.leos",
    )

    fun theme(context: Context): Theme =
        runCatching { Theme.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", Theme.LIGHT.name)!!) }
            .getOrDefault(Theme.LIGHT)

    fun setTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", theme.name).apply()
    }

    /** The pack's icon for [key] (a package name) in [theme], falling back to the other theme. */
    @Synchronized
    fun icon(context: Context, key: String, theme: Theme = theme(context)): Drawable? {
        val cacheKey = "${theme.folder}/$key"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]
        val found = find(context, key, theme) ?: find(context, key, if (theme == Theme.LIGHT) Theme.DARK else Theme.LIGHT)
        val drawable = found?.let { path ->
            runCatching { context.assets.open(path).use { BitmapDrawable(context.resources, BitmapFactory.decodeStream(it)) } }.getOrNull()
        }
        cache[cacheKey] = drawable
        return drawable
    }

    private fun find(context: Context, key: String, theme: Theme): String? {
        val all = files ?: Theme.values().associateWith { t ->
            context.assets.list("icons/${t.folder}")?.toList().orEmpty()
        }.also { files = it }
        val names = all[theme].orEmpty()
        // "com.android.camera.Camera.png" also belongs to "com.android.camera".
        val name = names.firstOrNull { it == "$key.png" } ?: names.firstOrNull { it.startsWith("$key.") } ?: return null
        return "icons/${theme.folder}/$name"
    }
}
