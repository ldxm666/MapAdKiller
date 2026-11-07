package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Tiny line-icon set drawn with [Canvas] — no vector assets, no dependency.
 *
 * <p>The module ships without XML resources for its settings UI (the build is plain
 * {@code javac} + {@code android.jar}), and text glyphs look cheap at 11 sp. Every icon here
 * lives on the same 24 × 24 grid, 1.9 dp stroke, round caps, so the bottom bar and rows read
 * as one family.
 */
class Icon(context: Context, private val kind: Int, color: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var tint: Int = color
    private var strokeDp: Float = 1.9f

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun stroke(dp: Float): Icon {
        strokeDp = dp
        invalidate()
        return this
    }

    fun setColor(value: Int) {
        if (tint == value) return
        tint = value
        invalidate()
    }

    fun color(): Int = tint

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        val size = Math.min(w, h)
        if (size <= 0) return
        val scale = size / 24f
        canvas.save()
        canvas.translate((w - size) / 2f, (h - size) / 2f)
        canvas.scale(scale, scale)
        paint.color = tint
        // Canvas is scaled by `scale`, so pre-divide to land on strokeDp real dp after it.
        paint.strokeWidth = strokeDp * density() / scale
        path.reset()

        when (kind) {
            HOME -> {
                path.moveTo(4f, 10.6f)
                path.lineTo(12f, 3.9f)
                path.lineTo(20f, 10.6f)
                path.moveTo(6.2f, 9.4f)
                path.lineTo(6.2f, 19.6f)
                path.lineTo(17.8f, 19.6f)
                path.lineTo(17.8f, 9.4f)
                path.moveTo(10f, 19.6f)
                path.lineTo(10f, 14.2f)
                path.lineTo(14f, 14.2f)
                path.lineTo(14f, 19.6f)
                canvas.drawPath(path, paint)
            }
            TUNE -> {
                path.moveTo(4f, 7f); path.lineTo(7.1f, 7f)
                path.moveTo(11.2f, 7f); path.lineTo(20f, 7f)
                path.moveTo(4f, 12f); path.lineTo(13.1f, 12f)
                path.moveTo(17.2f, 12f); path.lineTo(20f, 12f)
                path.moveTo(4f, 17f); path.lineTo(5.1f, 17f)
                path.moveTo(9.2f, 17f); path.lineTo(20f, 17f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9.1f, 7f, 2.1f, paint)
                canvas.drawCircle(15.1f, 12f, 2.1f, paint)
                canvas.drawCircle(7.1f, 17f, 2.1f, paint)
                paint.style = Paint.Style.STROKE
            }
            ARCHIVE -> {
                path.moveTo(3.6f, 4.6f)
                path.lineTo(20.4f, 4.6f)
                path.lineTo(20.4f, 8.2f)
                path.lineTo(3.6f, 8.2f)
                path.close()
                path.moveTo(5.2f, 8.2f)
                path.lineTo(5.2f, 19.4f)
                path.lineTo(18.8f, 19.4f)
                path.lineTo(18.8f, 8.2f)
                path.moveTo(9.8f, 12.2f)
                path.lineTo(14.2f, 12.2f)
                canvas.drawPath(path, paint)
            }
            INFO -> {
                oval.set(3.6f, 3.6f, 20.4f, 20.4f)
                canvas.drawOval(oval, paint)
                path.moveTo(12f, 10.8f)
                path.lineTo(12f, 16.6f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 7.6f, 1.1f, paint)
                paint.style = Paint.Style.STROKE
            }
            CHEVRON -> {
                path.moveTo(9.6f, 5.4f)
                path.lineTo(16.1f, 12f)
                path.lineTo(9.6f, 18.6f)
                canvas.drawPath(path, paint)
            }
            DOWNLOAD -> {
                path.moveTo(12f, 4f)
                path.lineTo(12f, 14.6f)
                path.moveTo(7.8f, 10.4f)
                path.lineTo(12f, 14.6f)
                path.lineTo(16.2f, 10.4f)
                path.moveTo(4.5f, 19.6f)
                path.lineTo(19.5f, 19.6f)
                canvas.drawPath(path, paint)
            }
            UPLOAD -> {
                path.moveTo(12f, 19.6f)
                path.lineTo(12f, 9f)
                path.moveTo(7.8f, 13.2f)
                path.lineTo(12f, 9f)
                path.lineTo(16.2f, 13.2f)
                path.moveTo(4.5f, 4.4f)
                path.lineTo(19.5f, 4.4f)
                canvas.drawPath(path, paint)
            }
            REFRESH -> {
                path.moveTo(19.4f, 8.2f)
                path.cubicTo(18.1f, 5.0f, 15.3f, 3.2f, 12f, 3.2f)
                path.cubicTo(7.1f, 3.2f, 3.2f, 7.1f, 3.2f, 12f)
                path.cubicTo(3.2f, 16.9f, 7.1f, 20.8f, 12f, 20.8f)
                path.cubicTo(16.1f, 20.8f, 19.4f, 18.2f, 20.6f, 14.7f)
                path.moveTo(19.4f, 3.1f)
                path.lineTo(19.4f, 8.2f)
                path.lineTo(14.3f, 8.2f)
                canvas.drawPath(path, paint)
            }
            TRASH -> {
                path.moveTo(4f, 7f)
                path.lineTo(20f, 7f)
                path.moveTo(9.4f, 7f)
                path.lineTo(9.4f, 4.4f)
                path.lineTo(14.6f, 4.4f)
                path.lineTo(14.6f, 7f)
                path.moveTo(6.4f, 7f)
                path.lineTo(7.4f, 19.6f)
                path.lineTo(16.6f, 19.6f)
                path.lineTo(17.6f, 7f)
                path.moveTo(10.4f, 10.8f)
                path.lineTo(10.4f, 16.2f)
                path.moveTo(13.6f, 10.8f)
                path.lineTo(13.6f, 16.2f)
                canvas.drawPath(path, paint)
            }
            COPY -> {
                path.moveTo(9f, 9f)
                path.lineTo(19.6f, 9f)
                path.lineTo(19.6f, 19.6f)
                path.lineTo(9f, 19.6f)
                path.close()
                path.moveTo(15f, 9f)
                path.lineTo(15f, 5.4f)
                path.lineTo(4.4f, 5.4f)
                path.lineTo(4.4f, 15f)
                path.lineTo(9f, 15f)
                canvas.drawPath(path, paint)
            }
            PASTE -> {
                path.moveTo(6f, 5.4f)
                path.lineTo(4.6f, 5.4f)
                path.lineTo(4.6f, 20.2f)
                path.lineTo(19.4f, 20.2f)
                path.lineTo(19.4f, 5.4f)
                path.lineTo(18f, 5.4f)
                path.moveTo(9f, 3.4f)
                path.lineTo(9f, 6.2f)
                path.lineTo(15f, 6.2f)
                path.lineTo(15f, 3.4f)
                path.close()
                path.moveTo(8.6f, 10.4f)
                path.lineTo(15.4f, 10.4f)
                path.moveTo(8.6f, 14.4f)
                path.lineTo(15.4f, 14.4f)
                path.moveTo(8.6f, 18.2f)
                path.lineTo(13f, 18.2f)
                canvas.drawPath(path, paint)
            }
            SHIELD -> {
                path.moveTo(12f, 3.2f)
                path.lineTo(19f, 6f)
                path.lineTo(19f, 11.2f)
                path.cubicTo(19f, 15.9f, 16.2f, 18.9f, 12f, 20.8f)
                path.cubicTo(7.8f, 18.9f, 5f, 15.9f, 5f, 11.2f)
                path.lineTo(5f, 6f)
                path.close()
                canvas.drawPath(path, paint)
            }
            SEND -> {
                path.moveTo(21f, 4.2f)
                path.lineTo(2.9f, 11.2f)
                path.lineTo(8.6f, 13.2f)
                path.lineTo(10.8f, 19.4f)
                path.lineTo(13.8f, 15.2f)
                path.lineTo(18.6f, 18.6f)
                path.close()
                path.moveTo(8.6f, 13.2f)
                path.lineTo(21f, 4.2f)
                canvas.drawPath(path, paint)
            }
            SEARCH -> {
                oval.set(4f, 4f, 17.2f, 17.2f)
                canvas.drawOval(oval, paint)
                path.moveTo(15.8f, 15.8f)
                path.lineTo(20.2f, 20.2f)
                canvas.drawPath(path, paint)
            }
            CHECK -> {
                path.moveTo(5f, 12.6f)
                path.lineTo(9.7f, 17.3f)
                path.lineTo(19.2f, 7.2f)
                canvas.drawPath(path, paint)
            }
            EXTERNAL -> {
                path.moveTo(13.8f, 4.2f)
                path.lineTo(19.8f, 4.2f)
                path.lineTo(19.8f, 10.2f)
                path.moveTo(19.8f, 4.2f)
                path.lineTo(10.8f, 13.2f)
                path.moveTo(17.4f, 14.4f)
                path.lineTo(17.4f, 19.2f)
                path.lineTo(4.8f, 19.2f)
                path.lineTo(4.8f, 6.6f)
                path.lineTo(9.6f, 6.6f)
                canvas.drawPath(path, paint)
            }
            SAVE -> {
                path.moveTo(4.6f, 5.4f)
                path.lineTo(15.4f, 5.4f)
                path.lineTo(19.4f, 9.4f)
                path.lineTo(19.4f, 19.4f)
                path.lineTo(4.6f, 19.4f)
                path.close()
                path.moveTo(8f, 5.4f)
                path.lineTo(8f, 10.2f)
                path.lineTo(15.2f, 10.2f)
                path.lineTo(15.2f, 5.4f)
                path.moveTo(8f, 19.4f)
                path.lineTo(8f, 14f)
                path.lineTo(16f, 14f)
                path.lineTo(16f, 19.4f)
                canvas.drawPath(path, paint)
            }
            BOLT -> {
                path.moveTo(13.2f, 2.6f)
                path.lineTo(5.4f, 13.2f)
                path.lineTo(11.2f, 13.2f)
                path.lineTo(10.6f, 21.4f)
                path.lineTo(18.6f, 10.4f)
                path.lineTo(12.8f, 10.4f)
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> {
            }
        }
        canvas.restore()
    }

    private fun density(): Float = resources.displayMetrics.density

    companion object {
        const val HOME = 0
        const val TUNE = 1
        const val ARCHIVE = 2
        const val INFO = 3
        const val CHEVRON = 4
        const val DOWNLOAD = 5
        const val UPLOAD = 6
        const val REFRESH = 7
        const val TRASH = 8
        const val COPY = 9
        const val PASTE = 10
        const val SHIELD = 11
        const val SEND = 12
        const val SEARCH = 13
        const val CHECK = 14
        const val EXTERNAL = 15
        const val SAVE = 16
        const val BOLT = 17
    }
}