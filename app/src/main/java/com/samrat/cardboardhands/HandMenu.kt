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

/**
 * The system menu that opens on the hand: palm toward the face and a pinch. A small glass bar with
 * round buttons that floats just above the hand which called it and moves with it; the other hand
 * points and pinches to choose.
 */
class HandMenu(val holderLeft: Boolean, private val items: List<Item>) {
    class Item(val id: String, val label: String, val icon: Drawable?)

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0); maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL) }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    /** Where the menu sits, head space (tangent units): centre x, y. Follows the holder's hand. */
    @Volatile var x = Float.NaN
    @Volatile var y = Float.NaN
    @Volatile var hovered = -1
        private set
    @Volatile var dirty = true
    var lastSeen = System.currentTimeMillis()

    /** Moves toward the holder's palm, smoothly so the menu does not shake with the hand. */
    fun follow(palmX: Float, palmY: Float, palmSize: Float) {
        val targetX = palmX
        val targetY = palmY + palmSize * 1.9f + HALF_H
        if (x.isNaN()) { x = targetX; y = targetY } else { x += (targetX - x) * .35f; y += (targetY - y) * .35f }
        lastSeen = System.currentTimeMillis()
    }

    /** The item under a head-space point, or -1. */
    fun itemAt(px: Float, py: Float): Int {
        if (x.isNaN()) return -1
        val u = (px - (x - HALF_W)) / (HALF_W * 2)
        val v = ((y + HALF_H) - py) / (HALF_H * 2)
        if (u !in 0f..1f || v !in 0f..1f) return -1
        return ((u * WIDTH - PAD) / slot).toInt().takeIf { it in items.indices } ?: -1
    }

    fun contains(px: Float, py: Float) = !x.isNaN() && kotlin.math.abs(px - x) <= HALF_W && kotlin.math.abs(py - y) <= HALF_H

    fun hover(index: Int) {
        if (index != hovered) { hovered = index; dirty = true }
    }

    fun item(index: Int) = items.getOrNull(index)

    fun draw() {
        bitmap.eraseColor(Color.TRANSPARENT)
        val card = RectF(12f, 12f, WIDTH - 12f, HEIGHT - 12f)
        canvas.drawRoundRect(card, 90f, 90f, shadow)
        paint.color = Color.argb(205, 38, 38, 46)
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.color = Color.argb(40, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.style = Paint.Style.FILL
        items.forEachIndexed { i, item ->
            val cx = PAD + slot * i + slot / 2
            val cy = 100f
            val r = if (i == hovered) 62f else 54f
            if (i == hovered) {
                paint.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(RectF(cx - slot / 2 + 8f, 22f, cx + slot / 2 - 8f, HEIGHT - 22f), 50f, 50f, paint)
            }
            val circle = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.save()
            canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
            item.icon?.let { it.setBounds((circle.left - 8).toInt(), (circle.top - 8).toInt(), (circle.right + 8).toInt(), (circle.bottom + 8).toInt()); it.draw(canvas) }
            canvas.restore()
            canvas.drawText(item.label, cx, 206f, label)
        }
        dirty = false
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 240
        private const val PAD = 30f
        /** Size in head space (tangent units, about metres at arm's length). */
        const val HALF_W = .24f
        const val HALF_H = HALF_W * HEIGHT / WIDTH
    }

    private val slot get() = (WIDTH - 2 * PAD) / items.size.coerceAtLeast(1)
}
