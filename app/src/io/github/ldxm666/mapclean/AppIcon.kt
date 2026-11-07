package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 未安装地图 App 的「常态图标」：品牌色圆角方形 + 白色定位针。
 *
 * <h3>为什么不用真实图标</h3>
 * 已安装的 App 走 {@code ApplicationInfo.loadIcon()} 拿系统里的真图标（见
 * {@code MainActivity.appIcon}）。未安装的 App 在设备上**根本没有图标可取** —— 之前这里
 * 退化成一个 20dp 的下载箭头，看起来像「待下载的文件」而不是一个 App，用户明确嫌丑。
 *
 * <p>本类只负责「看起来像一个正经 App 图标」：圆角方形 + 品牌主色 + 白色定位针，
 * 与已安装图标的 42dp 圆角规格一致。**装了对应 App 就会自动显示它自己的真图标**，
 * 这里的画法只服务「未安装」这一种状态。
 */
class AppIcon(c: Context, initialVendor: Int) : View(c) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val pin = Path()
    private var vendor: Int = Math.max(0, Math.min(BRAND_TOP.size - 1, initialVendor))

    init {
        white.color = 0xFFFFFFFF.toInt()
        white.style = Paint.Style.FILL
    }

    fun setVendor(v: Int) {
        vendor = Math.max(0, Math.min(BRAND_TOP.size - 1, v))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val r = Math.min(w, h) * 0.235f          // 与系统自适应图标接近的圆角比例
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        fill.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            BRAND_TOP[vendor], BRAND_BOTTOM[vendor], Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(box, r, r, fill)
        fill.shader = null

        // 白色定位针：上圆 + 下尖，中心挖一个品牌色小圆
        val cx = w * 0.5f
        val cy = h * 0.42f
        val rad = Math.min(w, h) * 0.20f
        pin.reset()
        pin.addCircle(cx, cy, rad, Path.Direction.CW)
        pin.moveTo(cx - rad * 0.62f, cy + rad * 0.62f)
        pin.lineTo(cx, h * 0.80f)
        pin.lineTo(cx + rad * 0.62f, cy + rad * 0.62f)
        pin.close()
        canvas.drawPath(pin, white)

        fill.color = BRAND_BOTTOM[vendor]
        canvas.drawCircle(cx, cy, rad * 0.40f, fill)
    }

    companion object {
        /** 各厂商品牌主色（上浅下深做渐变，模拟图标的光泽）。 */
        @JvmField
        val BRAND_TOP = intArrayOf(0xFF3D8BFF.toInt(), 0xFF4E9BFF.toInt(), 0xFF35C07A.toInt())

        @JvmField
        val BRAND_BOTTOM = intArrayOf(0xFF0B57D0.toInt(), 0xFF1A6BE0.toInt(), 0xFF12875A.toInt())
    }
}