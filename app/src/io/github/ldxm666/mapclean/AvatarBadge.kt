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
 * Circular avatar badge for the sheet header.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Icon</b> -- the module's own launcher drawable, cropped to a circle. This is the
 *       real product mark, so it is preferred whenever the drawable resolves.</li>
 *   <li><b>Monogram</b> -- a gradient disc with the author's initials. Used when the
 *       drawable is missing, which happens in slim builds that drop the launcher surface;
 *       the header must never render an empty hole.</li>
 * </ul>
 *
 * <p>Both modes get the same treatment: a soft ring and a hairline highlight, so the badge
 * reads as a deliberate element rather than a pasted image.
 */
class AvatarBadge : View {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val icon: android.graphics.drawable.Drawable?
    private val monogram: String
    private val ring: Float

    private var ringColor = 0x33000000
    private var gradientTop = 0xFF3D82FF.toInt()
    private var gradientBottom = 0xFF0A5CE0.toInt()

    constructor(c: Context, fallbackMonogram: String?) : this(c, fallbackMonogram, 0)

    /**
     * @param drawableResource id of the author avatar, or 0 to use the module's launcher icon
     */
    constructor(c: Context, fallbackMonogram: String?, drawableResource: Int) : super(c) {
        this.monogram = fallbackMonogram ?: "M"
        this.ring = UiKit.dpf(c, 1.4f)
        this.icon = resolveIcon(c, drawableResource)
    }

    fun setRingColor(value: Int) {
        ringColor = value
        invalidate()
    }

    fun setGradient(top: Int, bottom: Int) {
        gradientTop = top
        gradientBottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = Math.min(w, h)
        if (size <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = size / 2f - ring

        val ic = icon
        if (ic != null) {
            // Clip to the disc and draw the icon scaled to cover it.
            canvas.save()
            pathReset(cx, cy, r)
            canvas.clipPath(circle)
            var iw = ic.intrinsicWidth.toFloat()
            var ih = ic.intrinsicHeight.toFloat()
            if (iw <= 0 || ih <= 0) {
                iw = size
                ih = size
            }
            val scale = Math.max(size / iw, size / ih)
            val dw = iw * scale
            val dh = ih * scale
            ic.setBounds(
                Math.round(cx - dw / 2f), Math.round(cy - dh / 2f),
                Math.round(cx + dw / 2f), Math.round(cy + dh / 2f)
            )
            ic.draw(canvas)
            canvas.restore()
        } else {
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, cy - r, 0f, cy + r,
                gradientTop, gradientBottom, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null

            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = size * 0.42f
            val fm = paint.fontMetrics
            canvas.drawText(monogram, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
        }

        // Ring, then a light arc on top so the badge has a lit edge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = ringColor
        canvas.drawCircle(cx, cy, r + ring / 2f, paint)
        paint.color = 0x59FFFFFF
        canvas.drawArc(rectFor(cx, cy, r + ring / 2f), 200f, 140f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private val circle = Path()

    /** Set once in the constructor so {@code onDraw} allocates nothing. */
    private fun pathReset(cx: Float, cy: Float, r: Float) {
        circle.reset()
        circle.addCircle(cx, cy, r, Path.Direction.CW)
    }

    private fun rectFor(cx: Float, cy: Float, r: Float): RectF {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        return rect
    }

    companion object {
        /**
         * Find the avatar, in decreasing order of preference:
         * <ol>
         *   <li>a drawable id, when one was resolved;</li>
         *   <li>the embedded JPEG in [AvatarAsset] -- this is the path actually used, and
         *       the only one that cannot fail, because it needs nothing beyond this class being
         *       loaded;</li>
         *   <li>the module's {@code res/drawable}, for builds where the resources are reachable.</li>
         * </ol>
         *
         * <p><b>Deliberately not</b> {@code host.getApplicationInfo().loadIcon()}: that returns
         * the <i>host's</i> icon, so the header would quietly display 高德's logo instead of the
         * author's picture. A missing avatar is better than the wrong one.
         */
        private fun resolveIcon(c: Context, drawableResource: Int): android.graphics.drawable.Drawable? {
            if (drawableResource != 0) {
                try {
                    val d = c.getDrawable(drawableResource)
                    if (d != null) return d
                } catch (ignored: Throwable) {
                }
                try {
                    val d = c.resources.getDrawable(drawableResource, c.theme)
                    if (d != null) return d
                } catch (ignored: Throwable) {
                }
            }
            // Embedded data: the only path that cannot fail, since it needs nothing beyond this
            // class having been loaded.
            val embedded = AvatarAsset.drawable(c)
            if (embedded != null) return embedded
            return ModuleResources.drawable(c, "avatar_author")
        }
    }
}