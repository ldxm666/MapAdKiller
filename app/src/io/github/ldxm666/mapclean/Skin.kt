package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design tokens + component factory for the module settings screen.
 *
 * <p>Visual language: soft "liquid glass" — a tinted aurora backdrop, frosted translucent
 * plates with a bright specular rim, one accent colour, and a strict spacing/type scale.
 * Implemented with plain framework API because this module compiles with {@code javac}
 * against {@code android.jar} — no AndroidX, no Compose, no XML.
 */
class Skin(private val context: Context, @JvmField val dark: Boolean) {

    // 色板唯一来源：HyperDesign。此前这里是一份手抄的 Liquid Glass 色板，
    // 悬浮面板里还有第二份（cBg/cGlass/…），改配色要动两个地方。
    private val design: HyperDesign = HyperDesign.of(dark)

    @JvmField
    val bg: Int = design.background
    @JvmField
    val surface: Int = design.surfaceContainer          // 卡片底：浅色白 / 深色 #242424
    @JvmField
    val surface2: Int = design.surface                  // 次级面：浅色 #F7F7F7 / 深色纯黑
    // 描边渐变的两端：HyperOS 不用高光边，两端都取 outline，
    // 只让左上略亮一点点，保留一点方向感而不抢眼。
    @JvmField
    val rimHi: Int = HyperDesign.mix(design.outline, 0xFFFFFFFF.toInt(), if (dark) 0.10f else 0.55f)
    @JvmField
    val rimLo: Int = design.outline
    @JvmField
    val stroke: Int = design.outline
    @JvmField
    val divider: Int = design.dividerLine
    @JvmField
    val text: Int = design.onSurface
    @JvmField
    val text2: Int = design.onSurfaceVariantSummary
    @JvmField
    val text3: Int = design.onSurfaceContainerVariant
    @JvmField
    val accent: Int = design.primary
    @JvmField
    val accentSoft: Int = design.tertiaryContainer
    @JvmField
    val onAccent: Int = design.onPrimary
    @JvmField
    val success: Int = if (dark) 0xFF34D399.toInt() else 0xFF12A150.toInt()
    @JvmField
    val warn: Int = if (dark) 0xFFFBBF24.toInt() else 0xFFC77700.toInt()
    @JvmField
    val danger: Int = design.error
    @JvmField
    val chipBg: Int = design.surfaceContainerHigh
    @JvmField
    val navBg: Int = design.surfaceContainer

    private var frostTile: Bitmap? = null


    /** Base tint painted over the blurred backdrop inside every glass pane. */
    fun glassTint(): Int = surface

    /** Soft veil behind a page title so dark type stays legible over the photo. */
    fun headerScrim(): Drawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(HyperDesign.alpha(bg, 0.90f), HyperDesign.alpha(bg, 0f))
        )
    }

    /**
     * 卡片底 —— HyperOS 是实色 + 1 px 描边，不是半透明玻璃。
     *
     * <p>改自 Liquid Glass：那一版是"高光外圈 + 白色内圈 + 半透明body"，靠层叠制造
     * 厚度感。HyperOS 反过来 —— 实色面，用 {@code outline} 一条描边界定边界，浅色下
     * 几乎不用阴影。保留方法名和签名，419 处调用方一行不用改。
     */
    fun glassFor(host: View, radiusDp: Float): Drawable {
        return border(surface, stroke, radiusDp)
    }

    /** 带色调的卡片 —— 用于状态卡（成功/警告/危险）。色调只染 12% 和描边 30%。 */
    fun glassFor(host: View, radiusDp: Float, tint: Int): Drawable {
        val fill = HyperDesign.mix(surface, tint, if (dark) 0.16f else 0.10f)
        val line = HyperDesign.mix(stroke, tint, if (dark) 0.34f else 0.30f)
        return border(fill, line, radiusDp)
    }

    fun dp(value: Float): Int {
        return Math.round(value * context.resources.displayMetrics.density)
    }

    /** Pure white canvas — the requested backdrop. */
    fun aurora(): Drawable = solid(bg, 0f)

    fun display(value: String): TextView = text(value, T_DISPLAY, BOLD, text)

    fun title(value: String): TextView = text(value, T_TITLE, BOLD, text)

    fun rowTitle(value: String): TextView = text(value, T_ROW, MEDIUM, text)

    fun body(value: String): TextView = text(value, T_BODY, REGULAR, text2)

    fun caption(value: String): TextView = text(value, T_CAPTION, REGULAR, text2)

    fun footnote(value: String): TextView = text(value, T_LABEL, REGULAR, text3)

    fun text(value: String, sp: Float, face: Typeface, color: Int): TextView {
        val v = TextView(context)
        v.text = value
        v.setTextSize(sp)
        v.typeface = face
        v.setTextColor(color)
        v.setIncludeFontPadding(false)
        return v
    }

    fun card(): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(S4.toFloat()), dp(S3.toFloat()), dp(S4.toFloat()), dp(S3.toFloat()))
        card.background = glassFor(card, R_CARD.toFloat())
        UiKit.clipRounded(card, dp(R_CARD.toFloat()).toFloat())
        shadow(card, 1f)
        return card
    }

    fun listCard(): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(S1.toFloat()), dp(S2.toFloat()), dp(S1.toFloat()), dp(S2.toFloat()))
        card.background = glassFor(card, R_CARD.toFloat())
        UiKit.clipRounded(card, dp(R_CARD.toFloat()).toFloat())
        shadow(card, 1f)
        return card
    }

    fun glass(radiusDp: Float): Drawable = border(surface, stroke, radiusDp)

    /** White card tinted with the accent — the one block that leads the eye. */
    fun tintedGlass(tintColor: Int, radiusDp: Float): Drawable {
        val fill = UiKit.blend(surface, tintColor, if (dark) 0.16f else 0.06f)
        val line = UiKit.blend(stroke, tintColor, if (dark) 0.22f else 0.10f)
        return border(fill, line, radiusDp)
    }

    /**
     * 带描边的 squircle 底板。
     *
     * <p>原来是 `GradientDrawable.setCornerRadius` —— 那是四分之一圆弧，曲率在弧与直边的
     * 接点处从 0 突跳到 1/r，肉眼能看出「角是贴上去的」。HyperOS 用连续曲率（超椭圆）：
     * 三次贝塞尔过渡，曲率连续变化，角看起来「长在」矩形上。参数与算法见 [SquircleShape]。
     *
     * <p>返回类型从 `GradientDrawable` 放宽到 `Drawable`：所有调用点都只是 setBackground
     * 或往 RippleDrawable 里塞 content，本来就不需要具体类型。
     */
    fun border(fill: Int, line: Int, radiusDp: Float): Drawable {
        return SquircleDrawable(fill, dp(radiusDp).toFloat(), line,
                Math.max(1, dp(1f)).toFloat())
    }

    /** 无描边的 squircle 底板。半径超过短边一半时自动退化成胶囊（[R_PILL] 靠这个生效）。 */
    fun solid(fill: Int, radiusDp: Float): Drawable {
        return SquircleDrawable(fill, dp(radiusDp).toFloat())
    }

    fun pressable(fill: Int, radiusDp: Float): Drawable {
        return UiKit.ripple(if (dark) 0x33FFFFFF else 0x14000000, solid(fill, radiusDp), dp(radiusDp).toFloat())
    }

    fun pressableBorder(fill: Int, line: Int, radiusDp: Float): Drawable {
        return UiKit.ripple(
            if (dark) 0x33FFFFFF else 0x14000000,
            border(fill, line, radiusDp), dp(radiusDp).toFloat()
        )
    }

    /** 行间分割线 —— 左缩进 16 dp，与文字左边缘对齐（HyperOS 不从卡片边缘起笔）。 */
    fun hairline(): View {
        val v = View(context)
        // 0.75 dp：miuix DividerDefaults.Thickness。1 dp 在 3x 屏是 3 px，比笔画粗，像表格线。
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.75f))
        )
        lp.leftMargin = dp(HyperDesign.DIVIDER_INSET.toFloat())
        v.layoutParams = lp
        v.setBackgroundColor(divider)
        return v
    }

    fun row(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(HyperDesign.ROW_H.toFloat())
        row.setPadding(
            dp(HyperDesign.ROW_PAD.toFloat()), dp(S2.toFloat()),
            dp(HyperDesign.ROW_PAD.toFloat()), dp(S2.toFloat())
        )
        row.background = HyperRipple(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), dp(R_INNER.toFloat()).toFloat())
        row.isClickable = true
        return row
    }

    fun rowText(title: String, caption: String?): LinearLayout {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.addView(rowTitle(title))
        if (caption != null && caption.length > 0) {
            val c = caption(caption)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2f)
            col.addView(c, lp)
        }
        return col
    }

    fun chip(value: String, fill: Int, color: Int): TextView {
        val t = text(value, T_LABEL + 0.5f, MEDIUM, color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        t.background = solid(fill, R_PILL.toFloat())
        return t
    }

    fun section(value: String): TextView {
        val t = text(value, T_LABEL + 1f, MEDIUM, text3)
        t.letterSpacing = 0.06f
        t.setPadding(dp(4f), 0, 0, 0)
        return t
    }

    fun addSection(parent: LinearLayout, label: String) {
        val t = section(label)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(S6.toFloat())
        lp.bottomMargin = dp(S2.toFloat())
        parent.addView(t, lp)
    }

    fun primaryButton(label: String): TextView {
        val b = text(label, T_ROW, MEDIUM, onAccent)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(S4.toFloat()), dp(13f), dp(S4.toFloat()), dp(13f))
        b.background = UiKit.ripple(0x33FFFFFF, accentPlate(), dp(R_INNER.toFloat()).toFloat())
        b.isClickable = true
        UiKit.pressable(b, 0.98f)
        return b
    }

    private fun accentPlate(): Drawable {
        val top = UiKit.blend(accent, 0xFFFFFFFF.toInt(), if (dark) 0.06f else 0.18f)
        val bottom = UiKit.blend(accent, 0xFF000000.toInt(), if (dark) 0.10f else 0.06f)
        return UiKit.gradient(top, bottom, dp(R_INNER.toFloat()).toFloat())
    }

    fun secondaryButton(label: String): TextView {
        val b = text(label, T_ROW, MEDIUM, text)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(S4.toFloat()), dp(13f), dp(S4.toFloat()), dp(13f))
        b.background = UiKit.ripple(
            if (dark) 0x33FFFFFF else 0x14000000,
            glassFor(b, R_INNER.toFloat()), dp(R_INNER.toFloat()).toFloat()
        )
        b.isClickable = true
        UiKit.pressable(b, 0.98f)
        return b
    }

    fun textButton(label: String): TextView {
        val b = text(label, T_CAPTION + 0.5f, MEDIUM, accent)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(S3.toFloat()), dp(6f), dp(S3.toFloat()), dp(6f))
        b.background = UiKit.rippleBare(
            if (dark) 0x22FFFFFF else 0x12000000, dp(R_PILL.toFloat()).toFloat()
        )
        b.isClickable = true
        return b
    }

    fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun addWithTop(parent: LinearLayout, child: View, topDp: Int) {
        val lp = matchWrap()
        lp.topMargin = dp(topDp.toFloat())
        parent.addView(child, lp)
    }

    /**
     * 阴影 —— HyperOS 克制使用。
     *
     * <p>浅色下卡片靠 {@code outline} 描边区分层级，阴影只保留 1 dp 的接地感；
     * 深色下卡片本身比背景更黑，再叠阴影会糊成一片，所以进一步压到 0.5 倍。
     *
     * <p>Soft, colour-tinted elevation: framework shadows are neutral grey and read as dirt
     * over a coloured backdrop. API 28+ lets us tint spot and ambient shadows, so the plate
     * looks lit rather than dirty; older versions simply get a lower elevation.
     */
    fun shadow(v: View, elevationDp: Float) {
        val e = if (dark) elevationDp * 0.5f else elevationDp
        v.elevation = dp(e).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = if (dark) 0x66000000 else 0x14000000
            v.outlineAmbientShadowColor = if (dark) 0x33000000 else 0x0D000000
        }
    }

    /** 1 px rim stroked with a corner-to-corner gradient: lit top-left, quiet bottom-right. */
    private class Rim(
        private val radius: Float,
        private val stroke: Float,
        private val hi: Int,
        private val lo: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                hi, lo, Shader.TileMode.CLAMP
            )
            val inset = stroke / 2f
            canvas.drawRoundRect(
                android.graphics.RectF(
                    b.left + inset, b.top + inset, b.right - inset, b.bottom - inset
                ),
                radius - inset, radius - inset, paint
            )
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class Aurora(
        private val base: IntArray,
        private val colors: IntArray,
        private val blobs: Array<FloatArray>
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            basePaint.style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0) return
            basePaint.shader = LinearGradient(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(), base, floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, basePaint)
            val max = Math.max(w, h).toFloat()
            var i = 0
            while (i < colors.size && i < blobs.size) {
                val cx = bounds.left + w * blobs[i][0]
                val cy = bounds.top + h * blobs[i][1]
                val radius = max * blobs[i][2]
                paint.shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(colors[i], colors[i] and 0x00FFFFFF), null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(bounds, paint)
                i++
            }
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            basePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            basePaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    companion object {
        const val S1 = 4
        const val S2 = 8
        const val S3 = 12
        const val S4 = 16
        const val S5 = 20
        const val S6 = 24
        const val S7 = 32

        // 这三个是 (int) HyperDesign.R_* 的转写 —— 保留对 token 的引用，别再抄一遍字面值。
        @JvmField
        val R_CARD: Int = HyperDesign.R_CARD.toInt()        // 16
        @JvmField
        val R_INNER: Int = HyperDesign.R_CONTROL.toInt()     // 12
        @JvmField
        val R_CONTROL: Int = HyperDesign.R_CONTROL.toInt()   // 12
        @JvmField
        val R_PILL: Int = 999

        const val T_DISPLAY = HyperDesign.T_DISPLAY   // 32
        const val T_TITLE = HyperDesign.T_SUBTITLE    // 20
        const val T_ROW = HyperDesign.T_BODY1         // 17
        const val T_BODY = HyperDesign.T_BODY3        // 14
        const val T_CAPTION = HyperDesign.T_CAPTION   // 13
        const val T_LABEL = HyperDesign.T_LABEL       // 11

        @JvmField
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        @JvmField
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        @JvmField
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
}