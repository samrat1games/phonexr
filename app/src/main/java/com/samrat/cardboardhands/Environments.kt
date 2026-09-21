package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/**
 * Places to be in instead of the room: a sky at sunset, the night, space, a plain studio — or any
 * selected built-in places. The built-in ones are drawn here rather than shipped as pictures, so
 * they cost nothing to carry and come out sharp at any size.
 */
object Environments {
    /** Nothing around: the real room through the camera, which is where PhoneXR starts. */
    const val REAL_WORLD = "passthrough"

    data class Place(val id: String, val title: String)

    val BUILT_IN = listOf(
        Place(REAL_WORLD, "Реальный мир"),
        Place("sunset", "Закат"),
        Place("night", "Ночь"),
        Place("space", "Космос"),
        Place("studio", "Студия"),
    )

    /** The picture wrapped around the viewer, or null for the real world. */
    fun panorama(context: Context, id: String, width: Int = 2048): Bitmap? {
        if (id == REAL_WORLD) return null
        val height = width / 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (id) {
            "sunset" -> {
                sky(canvas, paint, width, height, Color.rgb(250, 170, 90), Color.rgb(68, 40, 92))
                sun(canvas, paint, width * .5f, height * .52f, height * .09f, Color.rgb(255, 226, 170))
                ground(canvas, paint, width, height, Color.rgb(38, 26, 44))
            }
            "night" -> {
                sky(canvas, paint, width, height, Color.rgb(24, 34, 70), Color.rgb(6, 8, 20))
                stars(canvas, paint, width, height, 900, seed = 7)
                sun(canvas, paint, width * .32f, height * .3f, height * .045f, Color.rgb(236, 240, 255))
                ground(canvas, paint, width, height, Color.rgb(10, 12, 24))
            }
            "space" -> {
                canvas.drawColor(Color.rgb(3, 3, 8))
                paint.shader = RadialGradient(
                    width * .62f, height * .42f, height * .5f,
                    intArrayOf(Color.argb(120, 90, 60, 160), Color.argb(40, 40, 60, 140), Color.TRANSPARENT),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                stars(canvas, paint, width, height, 1600, seed = 21)
            }
            else -> {
                sky(canvas, paint, width, height, Color.rgb(238, 238, 242), Color.rgb(176, 178, 186))
                ground(canvas, paint, width, height, Color.rgb(150, 152, 160))
            }
        }
        return bitmap
    }

    /** A small square of the place, for the icon on the home panel. */
    fun thumbnail(context: Context, id: String): Bitmap? {
        if (id == REAL_WORLD) {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(44, 46, 54))
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 14f
            canvas.drawRoundRect(RectF(48f, 48f, 208f, 208f), 36f, 36f, paint)
            return bitmap
        }
        val full = panorama(context, id, width = 512) ?: return null
        // The middle of the panorama is what lies ahead.
        val side = full.height
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, 0, side, side)
        if (square !== full) full.recycle()
        return square
    }

    private fun sky(canvas: Canvas, paint: Paint, width: Int, height: Int, low: Int, high: Int) {
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), high, low, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun ground(canvas: Canvas, paint: Paint, width: Int, height: Int, colour: Int) {
        paint.shader = LinearGradient(
            0f, height * .55f, 0f, height.toFloat(), Color.argb(255, Color.red(colour), Color.green(colour), Color.blue(colour)),
            Color.rgb(Color.red(colour) / 2, Color.green(colour) / 2, Color.blue(colour) / 2), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, height * .55f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun sun(canvas: Canvas, paint: Paint, x: Float, y: Float, radius: Float, colour: Int) {
        paint.shader = RadialGradient(x, y, radius * 4, intArrayOf(colour, Color.TRANSPARENT), floatArrayOf(.25f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius * 4, paint)
        paint.shader = null
        paint.color = colour
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun stars(canvas: Canvas, paint: Paint, width: Int, height: Int, count: Int, seed: Int) {
        val random = Random(seed)
        repeat(count) {
            val x = random.nextFloat() * width
            // Stars crowd together near the poles of an equirectangular picture; this spreads them.
            val y = (kotlin.math.acos(1 - 2 * random.nextFloat()) / Math.PI).toFloat() * height
            val size = .8f + random.nextFloat() * 1.8f
            paint.color = Color.argb(120 + random.nextInt(135), 255, 255, 255)
            canvas.drawCircle(x, y, size, paint)
        }
    }

}
