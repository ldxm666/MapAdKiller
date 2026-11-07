package io.github.ldxm666.mapclean

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * HyperOS 按压反馈 —— 整块矩形淡色叠加，不是圆形涟漪。
 *
 * <p>miuix 的 {@code MiuixIndication} 用 {@code drawRect} 铺满整个组件，按下时把前景色
 * 以 alpha 0.10 叠上去（hover 0.06 / focus 0.08），配合 0.94 缩放。圆形涟漪一出现就
 * 立刻"不是 HyperOS"—— 这是整套视觉里最容易露馅的一处，也是改造成本最低的一处。
 *
 * <p>alpha 用弹簧驱动而不是补间：快速连点时补间会从当前值重新起步，看起来一卡一卡；
 * 弹簧把当前速度带进去，连续按下是平滑的。
 *
 * <p>用法：{@code view.setBackground(new HyperRipple(color, radiusPx))}。View 的
 * pressed/enabled 状态会自动通过 [onStateChange] 传进来。
 */
class HyperRipple(color: Int, radiusPx: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val color: Int = color
    private val radius: Float = radiusPx
    private var circular: Boolean = false
    private val area = Rect()

    private var level: Float = 0f
    private var driver: HyperMotion.Driver? = null

    init {
        paint.style = Paint.Style.FILL
    }

    /** 圆形叠加 —— 用于悬浮球这类本身就是圆的控件。 */
    private constructor(color: Int) : this(color, 0f) {
        circular = true
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        var target = 0f
        for (s in state) {
            if (s == android.R.attr.state_pressed) {
                target = ALPHA_PRESS
                break
            }
            if (s == android.R.attr.state_focused) target = ALPHA_FOCUS
            else if (s == android.R.attr.state_hovered && target == 0f) target = ALPHA_HOVER
        }
        animateTo(target)
        return true
    }

    private fun animateTo(target: Float) {
        if (driver == null) {
            driver = HyperMotion.Driver(
                HyperMotion.Spring.ofStiffness(0.8f, 600f, level),
                object : HyperMotion.Driver.Sink {
                    override fun onValue(value: Float) {
                        level = value
                        invalidateSelf()
                    }
                })
        }
        driver!!.to(target)
    }

    /** 立刻设为按下态，不播动画 —— 用于列表复用时重置。 */
    fun snap(pressed: Boolean) {
        driver?.cancel()
        level = if (pressed) ALPHA_PRESS else 0f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (level <= 0.001f) return
        val a = (Math.min(1f, level) * 255f + 0.5f).toInt()
        paint.color = color
        paint.alpha = a
        area.set(bounds)
        if (area.isEmpty) return
        if (circular) {
            canvas.drawCircle(
                area.centerX().toFloat(), area.centerY().toFloat(),
                Math.min(area.width(), area.height()) / 2f, paint
            )
        } else if (radius > 0f) {
            canvas.drawRoundRect(
                area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(),
                radius, radius, paint
            )
        } else {
            canvas.drawRect(area, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        // 叠加量由 level 控制，外部 alpha 不参与 —— 否则会和状态机打架
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 按下叠加量（miuix MiuixIndication）。 */
        const val ALPHA_PRESS = 0.10f
        /** 悬停叠加量 —— 触屏设备基本用不到，留给鼠标/手写笔。 */
        const val ALPHA_HOVER = 0.06f
        /** 焦点叠加量 —— 键盘导航。 */
        const val ALPHA_FOCUS = 0.08f

        /** 圆形叠加 —— 用于悬浮球这类本身就是圆的控件。 */
        @JvmStatic
        fun circular(color: Int): HyperRipple = HyperRipple(color)
    }
}