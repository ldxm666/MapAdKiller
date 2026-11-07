package io.github.ldxm666.mapclean

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The always-present entry point: a draggable glass orb that snaps to the nearest screen
 * edge and auto-hides when the finger lifts.
 *
 * <h3>Interaction model</h3>
 * <ul>
 *   <li><b>Drag</b> -- follows the finger with a slight elastic lag, and the halo brightens
 *       with drag distance so the gesture feels connected.</li>
 *   <li><b>Release</b> -- springs to the nearest vertical edge (left or right), whichever the
 *       orb is closer to. It never rests mid-screen, so it cannot sit on top of the map's own
 *       controls for long.</li>
 *   <li><b>Auto-hide</b> -- once parked, the orb slides most of the way off-screen leaving a
 *       thin grab tab. Tapping or dragging the tab brings it back.</li>
 *   <li><b>Tap</b> -- opens the panel; only fires when the finger barely moved, so a drag can
 *       never be mistaken for a click.</li>
 * </ul>
 *
 * <h3>Why a custom View rather than a Button</h3>
 * The orb draws its own glass treatment (outer glow, rim, inner specular) and needs to report
 * a hit area larger than its visible circle. A stock view cannot do either, and the module
 * builds against `android.jar` only -- no AndroidX, no Material Components.
 */
class FloatingBall(c: Context) : View(c) {

    /** Notified when the user taps (not drags) the orb. */
    interface Listener {
        fun onTap()
        fun onMoved(left: Int, top: Int)
    }

    /**
     * Turns the orb's centre into a position on screen.
     *
     * <p>Exists because the orb no longer sits in a full-screen container: `FloatingController`
     * hosts it in a FrameLayout that is only as large as the orb itself (a full-screen
     * transparent view above the map's SurfaceView measurably hurt frame pacing). In that
     * layout the <b>container</b> moves and the orb stays at its own origin; while the sheet is
     * open the container is grown to full size instead and the orb moves inside it. Both cases
     * are the host's business, not the orb's, so the orb just reports its centre.
     */
    interface Frame {
        /** Position the host so the orb's drawn centre lands on (cx, cy). */
        fun place(cx: Float, cy: Float)

        /** Width of the coordinate space the orb parks in (the Activity content root). */
        fun spaceWidth(): Int

        /** Height of that space. */
        fun spaceHeight(): Int
    }

    companion object {
        /** Hit area is wider than the visible orb so the tab is easy to grab while hidden. */
        private const val TOUCH_PAD_DP = 8f

        /** How far off-screen the orb parks, as a fraction of its own width. */
        private const val HIDE_FRACTION = 0.62f
        private const val DRAG_SLOP_DP = 6f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val icon = Path()

    /**
     * Diameter and radius are <b>not</b> final any more: the 外观 page resizes the orb live.
     * Only a discrete size change (a slider release, or a new attach) touches them, so the
     * layout pass this triggers can never run per frame.
     */
    private var size = 0f
    private var radius = 0f
    private val pad: Float
    private val slop: Float
    private var sizeDp = 0
    private var alphaPercent = FloatingConfig.BALL_ALPHA_DEFAULT

    private var listener: Listener? = null

    /** Turns the orb's centre into a position; installed by `FloatingController.attach`. */
    private var frame: Frame? = null

    private var restX = 0f
    private var restY = 0f              // parked (visible) centre
    private var centerX = 0f
    private var centerY = 0f            // current animated centre
    private var downRawX = 0f
    private var downRawY = 0f           // finger position at ACTION_DOWN
    private var grabDX = 0f
    private var grabDY = 0f             // offset from centre to finger
    private var dragging = false
    private var moved = false
    private var hidden = false
    private var sideLeft = false        // parked against the left edge?
    private var dragGlow = 0f           // 0..1, brightens while dragging

    // 字段名刻意与 java 版的方法名错开：`settle(...)` 是本类的公开方法，
    // 同名属性会让人读代码时误判「这句是不是在调方法」。
    private var settleAnimator: ValueAnimator? = null

    private var accent = 0xFF0A6CF5.toInt()
    private var body = 0xF2FFFFFF.toInt()
    private var glyph = 0xFF0A6CF5.toInt()

    init {
        isClickable = true
        isFocusable = true
        pad = TOUCH_PAD_DP * c.resources.displayMetrics.density
        slop = DRAG_SLOP_DP * c.resources.displayMetrics.density
        // Read the persisted appearance here rather than at first draw: the controller builds
        // a fresh ball per Activity, so a rotation or a re-attach picks the new values up.
        setSizeDp(FloatingConfig.ballSizeDp())
        setAlphaPercent(FloatingConfig.ballAlphaPercent())
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Install the positioner; see [Frame]. */
    fun setFrame(f: Frame?) {
        frame = f
    }

    /** Current diameter in dp (the value the 外观 row shows). */
    fun getSizeDp(): Int = sizeDp

    /**
     * Resize the orb.
     *
     * <p>Requests a layout pass -- but only when the number actually changed, and only from a
     * discrete user action (slider release/preview, attach). The drag path never calls this,
     * so the "no requestLayout per frame" rule still holds for dragging the orb itself.
     *
     * <p>[onSizeChanged] re-parks the orb afterwards: `settle()`,
     * `hide()` and the drag clamp all read `radius`, so a resize without a
     * re-park would leave the orb resting half off the edge (or drifting out of bounds).
     */
    fun setSizeDp(dp: Int) {
        val clamped = Math.max(
            FloatingConfig.BALL_SIZE_MIN,
            Math.min(FloatingConfig.BALL_SIZE_MAX, dp)
        )
        if (clamped == sizeDp && size > 0f) return
        sizeDp = clamped
        size = clamped * resources.displayMetrics.density
        radius = size / 2f
        requestLayout()
        invalidate()
    }

    /** Current opacity in percent. */
    fun getAlphaPercent(): Int = alphaPercent

    /**
     * Opacity of the whole orb, drawn glass included.
     *
     * <p>Applied with [View.setAlpha] rather than per-`paint.setAlpha` calls: the
     * orb paints four different layers (halo, plate, rims, glyph) plus the view-level press
     * scale animation, and a single view alpha keeps them all consistent instead of
     * re-deriving every literal in [onDraw]. It also invalidates by itself, so there
     * is no extra redraw call here.
     */
    fun setAlphaPercent(percent: Int) {
        val clamped = Math.max(
            FloatingConfig.BALL_ALPHA_MIN,
            Math.min(FloatingConfig.BALL_ALPHA_MAX, percent)
        )
        alphaPercent = clamped
        alpha = clamped / 100f
    }

    /** Dark-mode tinting, applied by the controller when the host theme changes. */
    fun setPalette(bodyColor: Int, accentColor: Int, glyphColor: Int) {
        body = bodyColor
        accent = accentColor
        glyph = glyphColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Math.ceil((size + pad * 2f).toDouble()).toInt()
        val h = Math.ceil((size + pad * 2f).toDouble()).toInt()
        setMeasuredDimension(w, h)
    }

    /**
     * Re-park after the orb's own size changed.
     *
     * <p>`settle()`, `hide()` and the drag clamp are all expressed in terms of
     * `radius`, so a resize leaves the stored resting position pointing at where the
     * <i>old</i> orb used to be -- with a bigger orb that is off the screen edge, with a
     * smaller one it floats away from the edge. Recomputing here (and not from the caller)
     * means every path that changes the size is covered: slider preview, a fresh attach, and
     * a restored configuration.
     *
     * <p>`oldw == 0` is the first measure, where the controller parks the orb itself;
     * it is skipped so this cannot fight `attach()`.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw <= 0 || oldh <= 0 || !isParked()) return
        val spaceW = spaceWidth()
        val spaceH = spaceHeight()
        if (spaceW <= 0 || spaceH <= 0) return
        val wasHidden = hidden
        hidden = false                       // hide() early-returns while hidden
        settle(false, spaceW, spaceH)
        if (wasHidden) hide(spaceW)          // slide the tab back off the edge at the new size
    }

    /**
     * Width of the coordinate space the orb parks in.
     *
     * <p>Not `getParent().getWidth()`: the host wraps the orb, so its width is the orb's
     * own width. The real space is the Activity content root, which the frame reports.
     */
    private fun spaceWidth(): Int {
        val f = frame
        if (f != null) {
            val w = f.spaceWidth()
            if (w > 0) return w
        }
        val p = parent
        return if (p is View) p.width else width
    }

    private fun spaceHeight(): Int {
        val f = frame
        if (f != null) {
            val h = f.spaceHeight()
            if (h > 0) return h
        }
        val p = parent
        return if (p is View) p.height else height
    }

    /** Centre currently drawn, in parent coordinates. */
    fun centerX(): Float = this.centerX

    fun centerY(): Float = this.centerY

    fun parkedLeft(): Boolean = sideLeft

    fun isHidden(): Boolean = hidden

    /** True once the orb has a real size and position (i.e. it can be re-parked safely). */
    fun isParked(): Boolean = width > 0 && height > 0 && centerY > 0f

    /**
     * Move the orb so its drawn centre lands on (cx, cy), keeping the internal state in sync.
     *
     * Callers must use this instead of [View.setX]/[View.setY]: the settle and
     * hide maths read `centerX`/`centerY`, so positioning the view directly left
     * those fields at 0 and the orb always snapped to the top-left corner.
     */
    fun moveTo(cx: Float, cy: Float, parentW: Int) {
        centerX = cx
        centerY = cy
        applyTranslation(parentW)
    }

    /**
     * Park the orb against the nearer edge.
     *
     * @param animate slide rather than jump (used after a drag release)
     * @param parentW width of the container the orb lives in
     * @param parentH height of the container
     */
    fun settle(animate: Boolean, parentW: Int, parentH: Int) {
        // The arguments are only a hint from the caller. The host wraps the orb now, so its
        // own width is the orb's width; the authoritative space is the content root the frame
        // reports. Re-reading it here means a stale call site can never park the orb inside a
        // 62dp box (which would look like the orb teleporting to the top-left corner).
        val spaceW = spaceWidth()
        val spaceH = spaceHeight()
        val minX = pad + radius
        var maxX = spaceW - pad - radius
        if (maxX < minX) maxX = minX
        sideLeft = centerX < (minX + maxX) / 2f
        restX = if (sideLeft) minX else maxX
        // Keep the orb fully inside the vertical range, and never behind the status bar.
        val minY = pad + radius + UiKit.dp(context, 28f)
        var maxY = spaceH - pad - radius - UiKit.dp(context, 40f)
        if (maxY < minY) maxY = minY
        restY = Math.max(minY, Math.min(maxY, centerY))

        if (!animate) {
            centerX = restX
            centerY = restY
            applyTranslation(spaceW)
            return
        }
        animateTo(restX, restY, spaceW, true)
    }

    /** Slide out of the way, leaving only a grab tab. */
    fun hide(parentW: Int) {
        if (hidden) return
        hidden = true
        val offset = size * HIDE_FRACTION
        val target = if (sideLeft) restX - offset else restX + offset
        animateTo(target, restY, parentW, false)
    }

    /** Bring the orb fully back into view. */
    fun reveal(parentW: Int) {
        if (!hidden) return
        hidden = false
        animateTo(restX, restY, parentW, false)
    }

    private fun animateTo(tx: Float, ty: Float, parentW: Int, snap: Boolean) {
        cancelSettle()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        settleAnimator = anim
        anim.duration = if (snap) 260L else 200L
        anim.interpolator = if (snap) UiKit.SPRING else UiKit.STANDARD
        val fx = centerX
        val fy = centerY
        anim.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            override fun onAnimationUpdate(a: ValueAnimator) {
                val t = a.animatedValue as Float
                centerX = fx + (tx - fx) * t
                centerY = fy + (ty - fy) * t
                applyTranslation(parentW)
            }
        })
        anim.start()
    }

    private fun cancelSettle() {
        val s = settleAnimator
        if (s != null) {
            s.cancel()
            settleAnimator = null
        }
    }

    /**
     * Position the orb so that its drawn centre lands on (centerX, centerY).
     *
     * <p>Delegates to the [Frame]: with an orb-sized host the host moves and the orb
     * stays at its origin; while the sheet is open the host is full-screen and the orb moves
     * inside it. The fallback keeps the old full-screen-parent behaviour for any caller that
     * never installed a frame.
     */
    private fun applyTranslation(parentW: Int) {
        val f = frame
        if (f != null) {
            f.place(centerX, centerY)
            return
        }
        x = centerX - width / 2f
        y = centerY - height / 2f
    }

    override fun onDetachedFromWindow() {
        cancelSettle()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val parentW = spaceWidth()
        val parentH = spaceHeight()

        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSettle()
                downRawX = e.rawX
                downRawY = e.rawY
                grabDX = centerX - downRawX
                grabDY = centerY - downRawY
                dragging = true
                moved = false
                animate().cancel()
                animate().scaleX(1.08f).scaleY(1.08f).setDuration(90L).start()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = e.rawX
                val rawY = e.rawY
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) > slop.toDouble()) {
                    moved = true
                    // A drag from a hidden tab must first bring the orb back.
                    if (hidden) {
                        hidden = false
                        grabDX = -radius   // keep the orb under the finger, not the tab
                        grabDY = 0f
                        centerX = rawX + grabDX
                        centerY = rawY + grabDY
                    }
                }
                if (moved) {
                    centerX = rawX + grabDX
                    centerY = rawY + grabDY
                    val minX = pad + radius
                    var maxX = parentW - pad - radius
                    if (maxX < minX) maxX = minX
                    val minY = pad + radius + UiKit.dp(context, 24f)
                    var maxY = parentH - pad - radius - UiKit.dp(context, 32f)
                    if (maxY < minY) maxY = minY
                    centerX = Math.max(minX, Math.min(maxX, centerX))
                    centerY = Math.max(minY, Math.min(maxY, centerY))
                    applyTranslation(parentW)
                    dragGlow = Math.min(1f, dragGlow + 0.08f)
                    invalidate()
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                release(parentW, parentH, true)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                release(parentW, parentH, false)
                true
            }

            else -> super.onTouchEvent(e)
        }
    }

    private fun release(parentW: Int, parentH: Int, allowTap: Boolean) {
        if (!dragging) return
        dragging = false
        animate().cancel()
        animate().scaleX(1f).scaleY(1f).setDuration(UiKit.DUR_BASE)
            .setInterpolator(UiKit.SPRING).start()
        fadeGlow()

        if (!moved && allowTap) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            performClick()
            return
        }
        if (moved) {
            settle(true, parentW, parentH)
            listener?.onMoved(Math.round(centerX), Math.round(centerY))
        }
    }

    private fun fadeGlow() {
        if (dragGlow == 0f) return
        UiKit.animateFloat(
            dragGlow, 0f, UiKit.STANDARD, UiKit.DUR_SLOW,
            object : ValueAnimator.AnimatorUpdateListener {
                override fun onAnimationUpdate(a: ValueAnimator) {
                    dragGlow = a.animatedValue as Float
                    invalidate()
                }
            }
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val held = if (dragging) 1f else 0f

        // Outer halo: soft, and brighter while dragging or held.
        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = (46 + 52 * Math.max(dragGlow, held)).toInt()
        canvas.drawCircle(cx, cy, radius + pad * 0.5f, paint)

        // Body plate.
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.alpha = 255
        paint.color = body
        canvas.drawRoundRect(rect, radius, radius, paint)

        // Specular top rim: a hairline of light so the orb reads as glass, not a flat dot.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = UiKit.dpf(context, 1.2f)
        paint.color = 0x59FFFFFF
        rect.set(cx - radius + 1f, cy - radius + 1f, cx + radius - 1f, cy + radius - 1f)
        canvas.drawRoundRect(rect, radius - 1f, radius - 1f, paint)

        // Accent ring.
        paint.strokeWidth = UiKit.dpf(context, 1.6f)
        paint.color = accent
        paint.alpha = 190
        canvas.drawRoundRect(rect, radius - 1f, radius - 1f, paint)

        // Glyph: a three-slider "tune" mark -- reads as settings at a glance and is
        // distinct from the map's own icons.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = UiKit.dpf(context, 2f)
        paint.color = glyph
        paint.alpha = 255
        val w = radius * 0.52f
        val ys = floatArrayOf(-radius * 0.34f, 0f, radius * 0.34f)
        for (i in ys.indices) {
            val y = cy + ys[i]
            canvas.drawLine(cx - w, y, cx + w, y, paint)
        }
        val kx = floatArrayOf(cx - w * 0.42f, cx + w * 0.46f, cx - w * 0.08f)
        for (i in kx.indices) {
            canvas.drawCircle(kx[i], cy + ys[i], UiKit.dpf(context, 2.1f), paint)
        }
        paint.alpha = 255
    }
}
