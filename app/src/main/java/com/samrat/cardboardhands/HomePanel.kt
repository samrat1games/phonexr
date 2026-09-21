package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils

/**
 * visionOS-style home: round app icons floating in the room, a few rows per page with page dots.
 * [Mode.STORE] shows web apps from the PhoneXR store, [Mode.MENU] the system menu.
 * Everything is drawn into one transparent bitmap that the VR scene places in front of the user.
 */
class HomePanel {
    enum class Mode { HOME, LIBRARY, STORE, MENU, CUSTOMIZE }

    /** The three things the home can show, chosen on the rail to the left of the icons. */
    enum class Tab { APPS, PEOPLE, ENVIRONMENTS }

    data class Entry(val id: String, val label: String, val icon: Drawable?, val badge: String? = null)

    sealed class Target {
        data class App(val entry: Entry) : Target()
        data class Page(val index: Int) : Target()
        /** Customize: light or dark icons. */
        data class Theme(val dark: Boolean) : Target()
        /** The rail on the left: apps, people, environments. */
        data class Rail(val tab: Tab) : Target()
        /** Opens the full app library from the compact home bar. */
        object Library : Target()
        object Close : Target()
    }

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    var mode = Mode.HOME
    var compact = false
    var page = 0
        private set
    private var home = emptyList<Entry>()
    private var people = emptyList<Entry>()
    private var environments = emptyList<Entry>()
    private var store = emptyList<Entry>()
    private var menu = emptyList<Entry>()
    /** Minimized windows, shown as a dock under the icons. */
    private var dock = emptyList<Entry>()
    private val canvas = Canvas(bitmap)
    private val areas = ArrayList<Pair<RectF, Target>>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }
    private val heading = TextPaint(label).apply { textSize = 46f }

    var tab = Tab.APPS
        private set

    fun setTab(value: Tab) {
        tab = value
        page = 0
    }

    fun setHome(entries: List<Entry>) { home = entries; page = page.coerceAtMost(pages(entries) - 1) }
    fun setPeople(entries: List<Entry>) { people = entries }
    fun setEnvironments(entries: List<Entry>) { environments = entries }
    fun setStore(entries: List<Entry>) { store = entries }
    fun setMenu(entries: List<Entry>) { menu = entries }
    fun setDock(entries: List<Entry>) { dock = entries }

    private var previewLight: Drawable? = null
    private var previewDark: Drawable? = null
    private var darkIcons = false

    /** The customize card: a sample icon in both styles and which one is on. */
    fun setCustomize(light: Drawable?, dark: Drawable?, isDark: Boolean) {
        previewLight = light; previewDark = dark; darkIcons = isDark
    }
    fun homeIcons(): Map<String, Drawable?> = home.associate { it.id to it.icon }

    private fun current() = when (mode) {
        Mode.HOME, Mode.LIBRARY -> when (tab) {
            Tab.APPS -> home
            Tab.PEOPLE -> people
            Tab.ENVIRONMENTS -> environments
        }
        Mode.STORE -> store
        Mode.MENU -> menu
        Mode.CUSTOMIZE -> home
    }

    private fun pageSize() = if (compact && mode == Mode.HOME) COMPACT_PER_PAGE else PER_PAGE
    private fun pages(list: List<Entry>) = maxOf(1, (list.size + pageSize() - 1) / pageSize())

    fun turnPage(delta: Int) {
        page = (page + delta).coerceIn(0, pages(current()) - 1)
    }

    fun showPage(index: Int) {
        page = index.coerceIn(0, pages(current()) - 1)
    }

    fun hit(u: Float, v: Float): Target? {
        val x = u * WIDTH
        val y = v * HEIGHT
        return areas.firstOrNull { it.first.contains(x, y) }?.second
    }

    fun draw(hovered: Target?, pressed: Boolean) {
        areas.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        val list = current()
        if (compact && mode == Mode.HOME) {
            drawCompact(list, hovered, pressed)
            return
        }
        when (mode) {
            Mode.STORE -> canvas.drawText("Магазин веб‑приложений", WIDTH / 2f, 70f, heading)
            Mode.MENU -> canvas.drawText(tr("Меню"), WIDTH / 2f, 70f, heading)
            Mode.CUSTOMIZE -> Unit
            Mode.LIBRARY -> {
                canvas.drawText(tr("Библиотека"), WIDTH / 2f, 70f, heading)
                drawRail(hovered)
            }
            Mode.HOME -> {
                drawRail(hovered)
                if (list.isEmpty()) {
                    canvas.drawText(
                        when (tab) {
                            Tab.PEOPLE -> "Здесь будут друзья — войдите в аккаунт в приложении PhoneXR"
                            Tab.ENVIRONMENTS -> "Здесь будут доступные фоны PhoneXR"
                            Tab.APPS -> ""
                        },
                        WIDTH / 2f, HEIGHT / 2f, heading
                    )
                }
            }
        }
        // Honeycomb rows like visionOS: 4, 5, 4 icons.
        var index = page * PER_PAGE
        for ((row, count) in ROWS.withIndex()) {
            val y = 230f + row * 300f
            for (column in 0 until count) {
                val entry = list.getOrNull(index++) ?: break
                // Leave a comfortable gutter after the navigation rail, especially on five-icon rows.
                val x = GRID_CENTER_X + (column - (count - 1) / 2f) * 310f
                val target = Target.App(entry)
                val hover = target == hovered
                val radius = if (hover) (if (pressed) 100f else 116f) else 104f
                canvas.drawCircle(x, y + 10f, radius, shadow)
                val circle = RectF(x - radius, y - radius, x + radius, y + radius)
                canvas.save()
                canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
                val icon = entry.icon
                if (icon != null) {
                    // A small bleed hides square edges without cutting important artwork off.
                    val grow = radius * .04f
                    icon.setBounds((circle.left - grow).toInt(), (circle.top - grow).toInt(), (circle.right + grow).toInt(), (circle.bottom + grow).toInt())
                    icon.draw(canvas)
                } else {
                    fill.color = Color.rgb(90, 90, 100)
                    canvas.drawOval(circle, fill)
                }
                canvas.restore()
                if (hover) {
                    fill.style = Paint.Style.STROKE
                    fill.strokeWidth = 6f
                    fill.color = Color.argb(220, 255, 255, 255)
                    canvas.drawOval(circle, fill)
                    fill.style = Paint.Style.FILL
                }
                entry.badge?.let { badge ->
                    fill.color = Color.rgb(10, 132, 255)
                    canvas.drawCircle(x + radius * .72f, y - radius * .72f, 30f, fill)
                    canvas.drawText(badge, x + radius * .72f, y - radius * .72f + 11f, label)
                }
                val text = TextUtils.ellipsize(entry.label, label, 300f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, x, y + 158f, label)
                areas += RectF(x - 130f, y - 130f, x + 130f, y + 175f) to target
            }
        }
        // Dock with minimized windows.
        if (mode == Mode.HOME && dock.isNotEmpty()) {
            val y = 1165f
            val span = dock.size * 150f
            fill.color = Color.argb(120, 40, 40, 48)
            canvas.drawRoundRect(RectF(WIDTH / 2f - span / 2 - 30f, y - 80f, WIDTH / 2f + span / 2 + 30f, y + 80f), 80f, 80f, fill)
            dock.forEachIndexed { i, entry ->
                val x = WIDTH / 2f - span / 2 + 75f + i * 150f
                val target = Target.App(entry)
                val radius = if (target == hovered) 64f else 56f
                val circle = RectF(x - radius, y - radius, x + radius, y + radius)
                canvas.save()
                canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
                entry.icon?.let {
                    it.setBounds((circle.left - 10).toInt(), (circle.top - 10).toInt(), (circle.right + 10).toInt(), (circle.bottom + 10).toInt())
                    it.draw(canvas)
                }
                canvas.restore()
                areas += RectF(x - 70f, y - 70f, x + 70f, y + 70f) to target
            }
        }
        // Page dots.
        val count = pages(list)
        if (count > 1) {
            for (i in 0 until count) {
                val x = WIDTH / 2f + (i - (count - 1) / 2f) * 44f
                val target = Target.Page(i)
                fill.color = if (i == page) Color.WHITE else Color.argb(if (target == hovered) 200 else 110, 255, 255, 255)
                canvas.drawCircle(x, HEIGHT - 50f, 11f, fill)
                areas += RectF(x - 22f, HEIGHT - 80f, x + 22f, HEIGHT - 20f) to target
            }
        }
        if (mode == Mode.LIBRARY) drawLibraryButton(WIDTH / 2f, HEIGHT - 170f, Target.Close, hovered)
        if (mode == Mode.CUSTOMIZE) drawCustomize(hovered)
    }

    /** A Horizon-style strip: status on the left, pinned/recent apps in the middle, library at right. */
    private fun drawCompact(list: List<Entry>, hovered: Target?, pressed: Boolean) {
        val bar = RectF(70f, 1030f, WIDTH - 70f, 1245f)
        canvas.drawRoundRect(bar, 90f, 90f, shadow)
        fill.color = Color.argb(235, 45, 45, 50)
        canvas.drawRoundRect(bar, 90f, 90f, fill)
        val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        label.textSize = 34f
        canvas.drawText(now, 175f, 1148f, label)
        val start = page * COMPACT_PER_PAGE
        list.drop(start).take(COMPACT_PER_PAGE).forEachIndexed { index, entry ->
            val x = 390f + index * 150f
            val y = 1135f
            val target = Target.App(entry)
            val radius = if (target == hovered) (if (pressed) 55f else 65f) else 58f
            val rect = RectF(x - radius, y - radius, x + radius, y + radius)
            canvas.save(); canvas.clipPath(Path().apply { addRoundRect(rect, 22f, 22f, Path.Direction.CW) })
            entry.icon?.apply { setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()); draw(canvas) }
                ?: run { fill.color = Color.rgb(90, 90, 100); canvas.drawRoundRect(rect, 22f, 22f, fill) }
            canvas.restore()
            areas += RectF(x - 70f, y - 75f, x + 70f, y + 75f) to target
        }
        val pages = pages(list)
        if (pages > 1) {
            val x = 285f
            label.textSize = 30f
            canvas.drawText("${page + 1}/$pages", x, 1147f, label)
            areas += RectF(x - 55f, 1060f, x + 55f, 1215f) to Target.Page((page + 1) % pages)
        }
        drawLibraryButton(WIDTH - 145f, 1137f, Target.Library, hovered)
        label.textSize = 30f
    }

    /** The nine-dot Library button used only by the compact home and its expanded library. */
    private fun drawLibraryButton(x: Float, y: Float, target: Target, hovered: Target?) {
        val radius = if (target == hovered) 65f else 58f
        fill.color = Color.argb(if (target == hovered) 235 else 170, 255, 255, 255)
        canvas.drawCircle(x, y, radius, fill)
        fill.color = Color.rgb(38, 38, 44)
        for (row in 0..2) for (column in 0..2) {
            canvas.drawCircle(x + (column - 1) * 18f, y + (row - 1) * 18f, 5f, fill)
        }
        areas += RectF(x - 75f, y - 75f, x + 75f, y + 75f) to target
    }

    /**
     * The rail to the left of the icons: apps, people, environments — the three things the home
     * holds. It is drawn as one floating pill, the way visionOS does it.
     */
    private fun drawRail(hovered: Target?) {
        val centreY = HEIGHT / 2f
        val pill = RectF(RAIL_X - 84f, centreY - 260f, RAIL_X + 84f, centreY + 260f)
        canvas.drawRoundRect(pill, 84f, 84f, shadow)
        fill.color = Color.argb(150, 44, 44, 52)
        canvas.drawRoundRect(pill, 84f, 84f, fill)
        Tab.entries.forEachIndexed { index, item ->
            val y = centreY + (index - 1) * 168f
            val target = Target.Rail(item)
            val chosen = item == tab
            if (chosen || target == hovered) {
                fill.color = Color.argb(if (chosen) 235 else 110, 255, 255, 255)
                canvas.drawCircle(RAIL_X, y, 62f, fill)
            }
            fill.color = if (chosen) Color.rgb(30, 30, 36) else Color.WHITE
            when (item) {
                Tab.APPS -> {
                    // Four rounded squares: the app grid.
                    for (row in 0..1) for (column in 0..1) {
                        val left = RAIL_X - 34f + column * 40f
                        val top = y - 34f + row * 40f
                        canvas.drawRoundRect(RectF(left, top, left + 28f, top + 28f), 9f, 9f, fill)
                    }
                }
                Tab.PEOPLE -> {
                    // Two people, one behind the other.
                    canvas.drawCircle(RAIL_X - 14f, y - 16f, 17f, fill)
                    canvas.drawRoundRect(RectF(RAIL_X - 40f, y + 6f, RAIL_X + 12f, y + 38f), 26f, 26f, fill)
                    canvas.drawCircle(RAIL_X + 22f, y - 12f, 13f, fill)
                    canvas.drawRoundRect(RectF(RAIL_X + 2f, y + 10f, RAIL_X + 42f, y + 36f), 20f, 20f, fill)
                }
                Tab.ENVIRONMENTS -> {
                    // A hill with a sun over it: a place to be in.
                    canvas.drawCircle(RAIL_X + 22f, y - 20f, 12f, fill)
                    val hill = Path().apply {
                        moveTo(RAIL_X - 42f, y + 32f)
                        lineTo(RAIL_X - 8f, y - 14f)
                        lineTo(RAIL_X + 12f, y + 10f)
                        lineTo(RAIL_X + 26f, y - 6f)
                        lineTo(RAIL_X + 44f, y + 32f)
                        close()
                    }
                    canvas.drawPath(hill, fill)
                }
            }
            areas += RectF(RAIL_X - 76f, y - 76f, RAIL_X + 76f, y + 76f) to target
        }
    }

    /** PhoneXR customize card: a sun, the title, and the two icon styles to pick from. */
    private fun drawCustomize(hovered: Target?) {
        areas.clear()
        fill.color = Color.argb(110, 0, 0, 0)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), fill)
        val card = RectF(WIDTH / 2f - 520f, 330f, WIDTH / 2f + 520f, 900f)
        canvas.drawRoundRect(card, 80f, 80f, shadow)
        fill.shader = android.graphics.LinearGradient(0f, card.top, 0f, card.bottom,
            Color.argb(235, 92, 136, 150), Color.argb(235, 52, 84, 120), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, 80f, 80f, fill)
        fill.shader = null
        // Sun: brightness / appearance.
        fill.color = Color.WHITE
        val sx = card.left + 90f; val sy = card.top + 85f
        canvas.drawCircle(sx, sy, 16f, fill)
        fill.strokeWidth = 7f
        fill.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 8) {
            val a = k * Math.PI / 4
            canvas.drawLine(sx + (Math.cos(a) * 26).toFloat(), sy + (Math.sin(a) * 26).toFloat(),
                sx + (Math.cos(a) * 36).toFloat(), sy + (Math.sin(a) * 36).toFloat(), fill)
        }
        canvas.drawText(tr("Настроить"), WIDTH / 2f, card.top + 100f, heading)
        for ((index, dark) in listOf(false, true).withIndex()) {
            val cx = WIDTH / 2f + (index - .5f) * 380f
            val icon = RectF(cx - 110f, card.top + 170f, cx + 110f, card.top + 390f)
            val target = Target.Theme(dark)
            if (target == hovered) {
                fill.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(RectF(icon.left - 18f, icon.top - 18f, icon.right + 18f, icon.bottom + 18f), 70f, 70f, fill)
            }
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(icon, 56f, 56f, Path.Direction.CW) })
            val drawable = if (dark) previewDark else previewLight
            if (drawable != null) {
                drawable.setBounds(icon.left.toInt(), icon.top.toInt(), icon.right.toInt(), icon.bottom.toInt())
                drawable.draw(canvas)
            } else {
                fill.color = if (dark) Color.rgb(28, 28, 32) else Color.rgb(230, 232, 236)
                canvas.drawRect(icon, fill)
            }
            canvas.restore()
            val selected = dark == darkIcons
            val title = if (dark) tr("Тёмные") else tr("Светлые")
            if (selected) {
                fill.color = Color.argb(70, 255, 255, 255)
                val w = label.measureText(title) / 2 + 36f
                canvas.drawRoundRect(RectF(cx - w, icon.bottom + 40f, cx + w, icon.bottom + 100f), 30f, 30f, fill)
            }
            canvas.drawText(title, cx, icon.bottom + 81f, label)
            areas += RectF(icon.left - 30f, icon.top - 30f, icon.right + 30f, icon.bottom + 110f) to target
        }
        // Anywhere outside the card closes it.
        areas += RectF(0f, 0f, WIDTH.toFloat(), card.top) to Target.Close
        areas += RectF(0f, card.bottom, WIDTH.toFloat(), HEIGHT.toFloat()) to Target.Close
        areas += RectF(0f, 0f, card.left, HEIGHT.toFloat()) to Target.Close
        areas += RectF(card.right, 0f, WIDTH.toFloat(), HEIGHT.toFloat()) to Target.Close
    }

    companion object {
        /** Where the rail of tabs stands, to the left of the icons. */
        private const val RAIL_X = 120f
        private const val GRID_CENTER_X = 990f
        const val WIDTH = 1800
        const val HEIGHT = 1350
        private val ROWS = intArrayOf(4, 5, 4)
        private val PER_PAGE = ROWS.sum()
        private const val COMPACT_PER_PAGE = 8
    }
}
