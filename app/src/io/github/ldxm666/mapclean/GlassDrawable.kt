package io.github.ldxm666.mapclean

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * White-background liquid glass.
 *
 * <p>The reference (Kyant0/AndroidLiquidGlass) builds glass from five layers: vibrancy ->
 * tiny blur -> lens -> a 45 degree specular highlight (white 50 %, additive) -> an inner
 * shadow (black 15 %, radius 24 dp, offset down) -> a thin rim. On a pure white page the
 * first three have nothing to act on, so the character comes from the highlight, the inner
 * shadow and the rim: the pane reads as a polished, slightly cool glass tile that catches
 * the light from the top-left.
 *
 * <p>Layers implemented here, bottom to top:
 * <ol>
 *   <li>cool glass fill (vertical gradient, a touch darker than the page),</li>
 *   <li>45 degree white specular sweep fading out by 55 %,</li>
 *   <li>inner bottom shadow (blurred inset stroke),</li>
 *   <li>1 dp rim: white top-left fading to translucent grey bottom-right.</li>
 * </ol>
 */
class GlassDrawable(host: View, private val radius: Float, private val tint: Int, private val dark: Boolean) :
    Drawable() {

    private val density: Float = host.resources.displayMetrics.density
    private val stroke: Float = Math.max(1f, 1.0f * density)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val inset = RectF()
    private val clip = Path()

    init {
        inner.style = Paint.Style.FILL
        inner.color = 0x1A000000
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = stroke
    }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        val r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f)

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        // Light: cool white pane. Dark: the pane must stay *darker* than white text --
        // blending toward white here was what turned every card into flat grey in night mode.
        val top = if (dark) UiKit.blend(tint, 0xFF252A33.toInt(), 0.55f) else UiKit.blend(tint, 0xFFFFFFFF.toInt(), 0.45f)
        val bottom = if (dark) UiKit.blend(tint, 0xFF0B0D12.toInt(), 0.45f) else UiKit.blend(tint, 0xFFE7EBF2.toInt(), 0.22f)
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(top, bottom), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fill)

        sheen.shader = LinearGradient(
            rect.left, rect.top,
            rect.left + rect.width() * 0.85f, rect.top + rect.height() * 0.85f,
            intArrayOf(if (dark) 0x17FFFFFF else 0x73FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.58f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, sheen)

        // Inner shadow, offset down like the reference (0, +24 dp): only the lower part of
        // the pane darkens, which reads as thickness instead of a dirty border all round.
        inner.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x00000000, 0x00000000, if (dark) 0x66000000 else 0x1A000000.toInt()),
            floatArrayOf(0f, 0.70f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, inner)

        canvas.restore()

        // Contrast pair: a 1 dp dark rim outside a 0.8 dp white inner rim. On white this is
        // what actually defines the pane's edge (a white-only rim disappears).
        rim.shader = null
        rim.color = if (dark) 0x99000000.toInt() else 0x1A0B1220
        rim.strokeWidth = Math.max(1f, density)
        val half = Math.max(1f, density) / 2f
        canvas.drawRoundRect(
            RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half),
            r - half, r - half, rim
        )

        rim.color = if (dark) 0x24FFFFFF else 0xD9FFFFFF.toInt()
        rim.strokeWidth = 0.8f * density
        val ins = 1.4f * density
        canvas.drawRoundRect(
            RectF(rect.left + ins, rect.top + ins, rect.right - ins, rect.bottom - ins),
            Math.max(0f, r - ins), Math.max(0f, r - ins), rim
        )
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}