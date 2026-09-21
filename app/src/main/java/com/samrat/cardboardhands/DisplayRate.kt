package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * How often the phone redraws its own screen. In VR that is how often each eye gets a new picture,
 * so 90 or 120 Hz makes head movement feel smooth — at the cost of battery, which is why the user
 * picks it in Settings.
 */
object DisplayRate {
    /** Refresh rates the phone can actually show at its current resolution, sorted. */
    fun available(context: Context): List<Int> {
        val display = display(context) ?: return emptyList()
        val current = display.mode ?: return emptyList()
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { Math.round(it.refreshRate) }
            .distinct()
            .sorted()
    }

    /**
     * Asks for the fastest mode the phone has up to what the user chose. "Automatically" leaves the
     * choice to Android, which usually means the rate drops when nothing moves.
     */
    fun apply(activity: Activity) {
        val wanted = Settings.refresh(activity)
        if (wanted == Settings.Refresh.AUTO) return
        val display = display(activity) ?: return
        val current = display.mode ?: return
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate <= wanted.hz + .5f }
            .maxByOrNull { it.refreshRate } ?: return
        activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = mode.modeId }
    }

    @Suppress("DEPRECATION")
    private fun display(context: Context): Display? =
        if (Build.VERSION.SDK_INT >= 30) context.display
        else context.getSystemService(WindowManager::class.java)?.defaultDisplay
}
