package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * The photographic backdrop used by the liquid-glass surfaces.
 *
 * <p>A frosted pane only reads as glass when there is something behind it to blur. This
 * class loads the bundled wallpaper once, centre-crops it to the screen, and keeps a small,
 * heavily blurred copy. Glass panes sample that blurred copy at their own screen position,
 * so every card shows the real colour of what is behind it instead of a flat tint.
 */
class Backdrop private constructor() {

    companion object {
        private var source: Bitmap? = null
        private var blurred: Bitmap? = null
        private var SCALE: Float = 1f

        @JvmStatic
        fun prepare(context: Context, width: Int, height: Int) {
            if (source != null || width <= 0 || height <= 0) return
            // Java 的 `static synchronized` 锁的是 Class 对象，不是 Companion —— 显式写出来，
            // 免得以后有人往这里加第二个同步入口时锁了个不同的监视器。
            synchronized(Backdrop::class.java) {
                prepareLocked(context, width, height)
            }
        }

        private fun prepareLocked(context: Context, width: Int, height: Int) {
            if (source != null || width <= 0 || height <= 0) return
            try {
                var drawable: Drawable? = ModuleResources.drawable(context, "glass_bg")
                if (drawable !is BitmapDrawable) {
                    val id = context.resources.getIdentifier(
                        "glass_bg", "drawable",
                        context.packageName
                    )
                    if (id != 0) drawable = context.resources.getDrawable(id, null)
                }
                if (drawable !is BitmapDrawable) return
                val raw: Bitmap = drawable.bitmap ?: return

                val cropped = cropToFill(raw, width, height)
                source = cropped

                val smallW = Math.max(1, width / 6)
                val smallH = Math.max(1, height / 6)
                val small = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)
                boxBlur(small, 3)
                blurred = small
                SCALE = width / smallW.toFloat()
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun source(): Bitmap? = source

        @JvmStatic
        fun blurred(): Bitmap? = blurred

        /** How many screen pixels one pixel of [blurred] covers. */
        @JvmStatic
        fun scale(): Float = SCALE

        private fun cropToFill(raw: Bitmap, width: Int, height: Int): Bitmap {
            val factor = Math.max(width / raw.width.toFloat(), height / raw.height.toFloat())
            val w = Math.round(raw.width * factor)
            val h = Math.round(raw.height * factor)
            val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
            val x = Math.max(0, (w - width) / 2)
            val y = Math.max(0, (h - height) / 2)
            return Bitmap.createBitmap(scaled, x, y, Math.min(width, w), Math.min(height, h))
        }

        /** Three-pass box blur: cheap on the 1/6-size bitmap, invisible banding after upscale. */
        private fun boxBlur(bitmap: Bitmap, radius: Int) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val temp = IntArray(pixels.size)
            for (pass in 0 until 2) {
                blurHorizontal(pixels, temp, w, h, radius)
                blurVertical(temp, pixels, w, h, radius)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun blurHorizontal(input: IntArray, out: IntArray, w: Int, h: Int, radius: Int) {
            val span = radius * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (i in -radius..radius) {
                    val color = input[row + clamp(i, w)]
                    a += ((color ushr 24) and 0xFF).toLong()
                    r += ((color shr 16) and 0xFF).toLong()
                    g += ((color shr 8) and 0xFF).toLong()
                    b += (color and 0xFF).toLong()
                }
                for (x in 0 until w) {
                    out[row + x] = ((a / span).toInt() shl 24) or ((r / span).toInt() shl 16) or
                        ((g / span).toInt() shl 8) or (b / span).toInt()
                    val add = input[row + clamp(x + radius + 1, w)]
                    val sub = input[row + clamp(x - radius, w)]
                    a += (((add ushr 24) and 0xFF) - ((sub ushr 24) and 0xFF)).toLong()
                    r += (((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)).toLong()
                    g += (((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)).toLong()
                    b += ((add and 0xFF) - (sub and 0xFF)).toLong()
                }
            }
        }

        private fun blurVertical(input: IntArray, out: IntArray, w: Int, h: Int, radius: Int) {
            val span = radius * 2 + 1
            for (x in 0 until w) {
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (i in -radius..radius) {
                    val color = input[clamp(i, h) * w + x]
                    a += ((color ushr 24) and 0xFF).toLong()
                    r += ((color shr 16) and 0xFF).toLong()
                    g += ((color shr 8) and 0xFF).toLong()
                    b += (color and 0xFF).toLong()
                }
                for (y in 0 until h) {
                    out[y * w + x] = ((a / span).toInt() shl 24) or ((r / span).toInt() shl 16) or
                        ((g / span).toInt() shl 8) or (b / span).toInt()
                    val add = input[clamp(y + radius + 1, h) * w + x]
                    val sub = input[clamp(y - radius, h) * w + x]
                    a += (((add ushr 24) and 0xFF) - ((sub ushr 24) and 0xFF)).toLong()
                    r += (((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)).toLong()
                    g += (((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)).toLong()
                    b += ((add and 0xFF) - (sub and 0xFF)).toLong()
                }
            }
        }

        private fun clamp(value: Int, max: Int): Int {
            return if (value < 0) 0 else (if (value >= max) max - 1 else value)
        }
    }
}