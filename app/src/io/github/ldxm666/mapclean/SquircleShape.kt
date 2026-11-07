package io.github.ldxm666.mapclean

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 连续曲率圆角（squircle）—— miuix 的 `miuix-squircle`，**不是**四分之一圆弧。
 *
 * 普通 `GradientDrawable.setCornerRadius` 画的是标准圆角：每段弧都是同样半径的圆弧，
 * 曲率在弧与直边的接点处从 0 突跳到 1/r，肉眼能看出"角是贴上去的"。
 * 连续曲率圆角用三次贝塞尔过渡，曲率变化连续，所以角看起来"长在"矩形上。
 * HyperOS 全部卡片、按钮、对话框、Tab 指示器都用它。
 *
 * 参数取自 miuix `SquirclePath.kt`：
 * - `EXTENSION = 1.1` —— 角部区域 = radius × 1.1
 * - `CONTROL = 0.643` —— 三次贝塞尔控制点比例
 *
 * 路径构造（miuix `SquirclePath.kt:74-97`）：
 * ```
 * tile   = radius × 1.1        // 钳到 min(w,h)/2
 * handle = tile × (1 - 0.643) = tile × 0.357
 *
 * moveTo(tile, 0)
 * lineTo(w - tile, 0)
 * cubicTo(w - handle, 0,      w, handle,      w, tile)
 * lineTo(w, h - tile)
 * cubicTo(w, h - handle,      w - handle, h,  w - tile, h)
 * lineTo(tile, h)
 * cubicTo(handle, h,          0, h - handle,  0, h - tile)
 * lineTo(0, tile)
 * cubicTo(0, handle,          handle, 0,      tile, 0)
 * close()
 * ```
 *
 * 用法：
 * - 当背景：`view.background = SquircleDrawable(fillColor, radiusPx)`
 * - 裁剪子 View：`SquircleOutline.apply(view, radiusPx)`（走 ViewOutlineProvider）
 */
object SquircleShape {

    /** 角部区域相对半径的倍数（miuix `SquircleDefaults.Extension`）。 */
    const val EXTENSION = 1.1f

    /** 三次贝塞尔控制点比例（miuix `SquirclePath.kt:33`）。 */
    const val CONTROL = 0.643f

    /**
     * 把 squircle 路径写进 [path]，覆盖 [0,0]-[w,h] 矩形。
     *
     * @param radius 标称半径（和普通圆角同一个量纲，便于直接替换现有调用）
     */
    fun build(path: Path, w: Float, h: Float, radius: Float) {
        path.reset()
        if (w <= 0f || h <= 0f) return
        // tile 是角部跨度；半径超过短边一半时退化成胶囊/圆，按短边一半钳住
        val tile = (radius * EXTENSION).coerceAtMost(minOf(w, h) / 2f)
        if (tile <= 0.01f) {
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            return
        }
        val hd = tile * (1f - CONTROL)

        path.moveTo(tile, 0f)
        path.lineTo(w - tile, 0f)
        path.cubicTo(w - hd, 0f, w, hd, w, tile)

        path.lineTo(w, h - tile)
        path.cubicTo(w, h - hd, w - hd, h, w - tile, h)

        path.lineTo(tile, h)
        path.cubicTo(hd, h, 0f, h - hd, 0f, h - tile)

        path.lineTo(0f, tile)
        path.cubicTo(0f, hd, hd, 0f, tile, 0f)

        path.close()
    }
}

/**
 * squircle 形状的可绘制背景。填充 + 可选 1px 描边。
 *
 * 描边宽度按 miuix 的 divider 规范默认 0.75dp，但卡片外框用 1px 更稳，
 * 所以由调用方传入。
 */
class SquircleDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    private val stroke: Int = 0,
    private val strokeWidthPx: Float = 0f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val bounds = RectF()

    init {
        paint.style = Paint.Style.FILL
        strokePaint.strokeWidth = strokeWidthPx
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        b.set(getBounds())
        if (b.isEmpty) return
        SquircleShape.build(path, b.width(), b.height(), radiusPx)
        canvas.save()
        canvas.translate(b.left, b.top)
        paint.color = fill
        canvas.drawPath(path, paint)
        if (strokeWidthPx > 0f && stroke != 0) {
            strokePaint.color = stroke
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
        strokePaint.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
