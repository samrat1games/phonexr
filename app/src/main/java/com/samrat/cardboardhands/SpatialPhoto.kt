package com.samrat.cardboardhands

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.roundToInt

/**
 * 3D out of an ordinary photo: the depth network says how far each point is, and the photo is then
 * drawn twice — once for each eye, with near things moved a little sideways, the way the two eyes
 * really see them. What comes out is one side-by-side picture, which PhoneXR (and any VR player)
 * shows with depth.
 */
object SpatialPhoto {
    /** How far the nearest point moves, as a part of the width. Beyond this the eyes start to ache. */
    private const val PARALLAX = .016f
    /** Depth that stays put, so the picture sits half in front of the window and half behind it. */
    private const val FOCUS = .45f
    /** Photos are worked on at this width: enough to look sharp, small enough to be quick. */
    private const val WORKING_WIDTH = 1600

    /**
     * Makes the 3D copy of [uri] and puts it in the gallery next to the original. Returns where it
     * landed, or throws with a reason a person can read.
     */
    fun create(context: Context, uri: Uri, name: String, onStage: (String) -> Unit = {}): Uri {
        require(DepthModel.installed(context)) { "Сначала скачайте нейросеть глубины" }
        onStage("Открываю фото…")
        val photo = decode(context, uri) ?: throw IllegalStateException("Фото не открылось")
        onStage("Считаю глубину…")
        val depth = DepthModel.depth(context, photo) ?: throw IllegalStateException("Нейросеть не ответила")
        onStage("Собираю 3D…")
        val stereo = sideBySide(photo, depth)
        photo.recycle()
        onStage("Сохраняю…")
        val saved = save(context, stereo, name)
        stereo.recycle()
        return saved
    }

    /** The photo at a size worth working at; huge photos are read down first. */
    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > WORKING_WIDTH * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val full = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        if (full.width <= WORKING_WIDTH) full
        else Bitmap.createScaledBitmap(full, WORKING_WIDTH, full.height * WORKING_WIDTH / full.width, true)
            .also { if (it !== full) full.recycle() }
    }.getOrNull()

    /**
     * The two eyes next to each other. Every pixel is moved sideways by how near it is; where a near
     * thing uncovers what was behind it, the gap is filled from the far side, which is what was
     * hidden there anyway.
     */
    fun sideBySide(photo: Bitmap, depth: FloatArray, depthSize: Int = DepthModel.SIZE): Bitmap {
        val width = photo.width
        val height = photo.height
        val source = IntArray(width * height)
        photo.getPixels(source, 0, width, 0, 0, width, height)
        val (left, right) = eyes(source, width, height, depth, depthSize)
        val out = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888)
        out.setPixels(left, 0, width, 0, 0, width, height)
        out.setPixels(right, 0, width, width, 0, width, height)
        return out
    }

    /** The pixels of the two eyes, the part of the work that is only arithmetic. */
    fun eyes(
        source: IntArray,
        width: Int,
        height: Int,
        depth: FloatArray,
        depthSize: Int = DepthModel.SIZE,
    ): Pair<IntArray, IntArray> {
        val left = IntArray(width * height)
        val right = IntArray(width * height)
        val leftDepth = FloatArray(width)
        val rightDepth = FloatArray(width)
        val shift = (width * PARALLAX / 2).coerceAtLeast(1f)
        val row = FloatArray(width)
        for (y in 0 until height) {
            depthRow(depth, depthSize, y, height, width, row)
            java.util.Arrays.fill(leftDepth, -1f)
            java.util.Arrays.fill(rightDepth, -1f)
            val base = y * width
            for (x in 0 until width) {
                val near = row[x]
                val move = ((near - FOCUS) * shift).roundToInt()
                val colour = source[base + x]
                // Nearer pixels win the place they land on: they are what hides the rest.
                val toLeft = x + move
                if (toLeft in 0 until width && near > leftDepth[toLeft]) {
                    leftDepth[toLeft] = near
                    left[base + toLeft] = colour
                }
                val toRight = x - move
                if (toRight in 0 until width && near > rightDepth[toRight]) {
                    rightDepth[toRight] = near
                    right[base + toRight] = colour
                }
            }
            fillGaps(left, leftDepth, base, width)
            fillGaps(right, rightDepth, base, width)
        }
        return left to right
    }

    /** Pixels nothing landed on take the colour of the farther side of the gap. */
    private fun fillGaps(pixels: IntArray, depths: FloatArray, base: Int, width: Int) {
        var x = 0
        while (x < width) {
            if (depths[x] >= 0f) {
                x++
                continue
            }
            var end = x
            while (end < width && depths[end] < 0f) end++
            val before = if (x > 0) x - 1 else -1
            val after = if (end < width) end else -1
            val fill = when {
                before >= 0 && after >= 0 -> if (depths[before] <= depths[after]) pixels[base + before] else pixels[base + after]
                before >= 0 -> pixels[base + before]
                after >= 0 -> pixels[base + after]
                else -> 0
            }
            for (hole in x until end) pixels[base + hole] = fill
            x = end
        }
    }

    /** One row of the depth map, stretched to the photo's width. */
    private fun depthRow(depth: FloatArray, size: Int, y: Int, height: Int, width: Int, out: FloatArray) {
        val sourceY = ((y + .5f) * size / height - .5f).coerceIn(0f, size - 1f)
        val y0 = sourceY.toInt()
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val ty = sourceY - y0
        for (x in 0 until width) {
            val sourceX = ((x + .5f) * size / width - .5f).coerceIn(0f, size - 1f)
            val x0 = sourceX.toInt()
            val x1 = (x0 + 1).coerceAtMost(size - 1)
            val tx = sourceX - x0
            val top = depth[y0 * size + x0] * (1 - tx) + depth[y0 * size + x1] * tx
            val bottom = depth[y1 * size + x0] * (1 - tx) + depth[y1 * size + x1] * tx
            out[x] = top * (1 - ty) + bottom * ty
        }
    }

    /** Saved into the gallery with "sbs" in the name, which is what marks a 3D photo everywhere. */
    private fun save(context: Context, bitmap: Bitmap, name: String): Uri {
        val base = name.substringBeforeLast('.').ifEmpty { "photo" }
        val fileName = "${base}_sbs_3d.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhoneXR")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Галерея не дала сохранить файл")
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)
        } ?: throw IllegalStateException("Файл не записался")
        return uri
    }
}
