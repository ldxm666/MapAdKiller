package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The module's slider: the same glass language as [GlassSwitch], drawn by hand.
 *
 * <h3>Why not a stock SeekBar</h3>
 * A framework {@code SeekBar} brings its own thumb drawable, its own track drawable and its
 * own padding rules, none of which can be made to match the sheet's glass plates without
 * shipping {@code res/} assets. This module builds against {@code android.jar} only and
 * generates every drawable in code, so the slider is drawn here with the same primitives the
 * switch uses: a translucent channel, a specular top rim, and a white thumb with an accent
 * ring.
 *
 * <h3>Positions, not values</h3>
 * The view works in <b>discrete positions</b> {@code 0..stops-1}; the caller owns the mapping
 * from a position to a stored value (see {@code FloatingPanel.Metric}). That is what lets the
 * panel-width row carry a "自动" stop in front of its numeric range without special-casing
 * arithmetic in the widget, and it makes every drag snap to a legal stored value.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>[setPosition] is the programmatic path and never notifies, so hydrating
 *       from storage cannot write back to storage.</li>
 *   <li>[OnPositionChange] fires with {@code settled=false} while the finger moves
 *       (live preview) and {@code settled=true} once on lift -- the moment to persist.</li>
 *   <li>No animation and no repeating invalidate: the view redraws only when the position,
 *       the pressed state or the size actually changes.</li>
 * </ul>
 */
class GlassSlider(c: Context) : View(c) {

    /** Notified on user interaction only; {@code settled} marks the end of the gesture. */
    fun interface OnPositionChange {
        fun onPositionChanged(view: GlassSlider, position: Int, settled: Boolean)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val trackH: Float
    private val thumbR: Float
    private val viewH: Float
    private val overhang: Float
    private val radius: Float

    private var stops: Int = 2
    private var pos: Int = 0
    private var pressed: Boolean = false
    private var tracking: Boolean = false

    private var trackOff = 0x2E8A94A4
    private var trackOn = 0xFF0A6CF5.toInt()
    private var trackOnTop = 0xFF3D82FF.toInt()
    private var fill: Shader? = null

    private var listener: OnPositionChange? = null

    /** Height of the interactive band measured from the view's bottom edge; 0 = centred. */
    private var trackBand: Float = 0f

    init {
        val d = c.resources.displayMetrics.density
        trackH = TRACK_H_DP * d
        thumbR = THUMB_DP * d / 2f
        viewH = TOUCH_H_DP * d
        overhang = OVERHANG_DP * d
        radius = trackH / 2f
        isClickable = true
        isFocusable = true
    }

    /**
     * Anchor the drawn channel to the bottom {@code dp}-tall band instead of the middle.
     *
     * <p>This is what lets one view own a whole row -- label and value on top, track below --
     * so there is no "wrong half" of the row to press. The touch surface grows to the whole
     * row; the picture stays exactly where it was.
     */
    fun setTrackBandDp(dp: Float) {
        trackBand = dp * resources.displayMetrics.density
        rebuildFill()
        invalidate()
    }

    /** Vertical centre of the channel: the middle of the bottom band, or of the view. */
    private fun channelCenterY(): Float {
        val h = height.toFloat()
        if (trackBand <= 0f || trackBand >= h) return h / 2f
        return h - trackBand / 2f
    }

    /** Number of discrete stops; the caller supplies the value behind each one. */
    fun setStops(count: Int) {
        val next = Math.max(2, count)
        if (next == stops) return
        stops = next
        if (pos >= stops) pos = stops - 1
        invalidate()
    }

    /** Current stop. */
    fun getPosition(): Int = pos

    /** Programmatic set: clamps, redraws, never notifies. */
    fun setPosition(value: Int) {
        val next = Math.max(0, Math.min(stops - 1, value))
        if (next == pos) return
        pos = next
        invalidate()
    }

    fun setTrackColors(off: Int, on: Int, onTop: Int) {
        trackOff = off
        trackOn = on
        trackOnTop = onTop
        rebuildFill()
        invalidate()
    }

    fun setOnPositionChange(l: OnPositionChange?) {
        listener = l
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        var w = MeasureSpec.getSize(wSpec)
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) {
            w = Math.ceil((thumbR * 8f).toDouble()).toInt()
        }
        // Honour an explicit height: the metric row gives this view a fixed, taller-than-48dp
        // box so the label band above the track is part of the same touch surface.
        var h = if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) {
            Math.ceil(viewH.toDouble()).toInt()
        } else {
            MeasureSpec.getSize(hSpec)
        }
        val minH = Math.ceil(viewH.toDouble()).toInt()
        if (h < minH) h = minH   // never below the 48dp hit box
        setMeasuredDimension(Math.max(w, Math.ceil((thumbR * 2f).toDouble()).toInt()), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuildFill()
    }

    /** The accent fill is a vertical gradient, so it has to be rebuilt when geometry changes. */
    private fun rebuildFill() {
        val cy = channelCenterY()
        fill = LinearGradient(
            0f, cy - trackH / 2f, 0f, cy + trackH / 2f,
            trackOnTop, trackOn, Shader.TileMode.CLAMP
        )
    }

    private fun channelLeft(): Float = overhang

    private fun channelRight(): Float = Math.max(channelLeft() + 1f, width - overhang)

    /** Thumb centres stay a full radius inside the view, so the disc is never clipped. */
    private fun travelLeft(): Float = thumbR

    private fun travelRight(): Float = Math.max(travelLeft(), width - thumbR)

    private fun thumbCenterX(): Float {
        val l = travelLeft()
        val r = travelRight()
        if (r <= l) return width / 2f
        val t = if (stops <= 1) 0f else pos / (stops - 1).toFloat()
        return l + (r - l) * t
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                pressed = true
                // Claim the gesture from *every* ancestor, not just the direct parent: the
                // sheet's horizontal category swipe is several ViewGroups up, and it used to
                // win the gesture -- dragging the slider flipped the page to another category
                // and the value never changed (device report r6).
                claimGesture(true)
                setPositionFromX(e.x, true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                setPositionFromX(e.x, true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) {
                    releaseGesture()      // never leave an ancestor disallowed
                    return false
                }
                tracking = false
                pressed = false
                setPositionFromX(e.x, true)
                releaseGesture()
                invalidate()
                performClick()      // emits the settled callback exactly once
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) {
                    releaseGesture()
                    return false
                }
                tracking = false
                pressed = false
                releaseGesture()
                invalidate()
                listener?.onPositionChanged(this, pos, true)
                return true
            }

            else -> return super.onTouchEvent(e)
        }
    }

    /**
     * Ask the whole ancestor chain to stop intercepting.
     *
     * <p>Walked by hand up to the root: {@code requestDisallowInterceptTouchEvent} is
     * implemented per {@code ViewGroup} and does not propagate upwards by itself, so a single
     * call on the immediate parent left the sheet's swipe handler free to steal the drag.
     * Bounded so a pathological hierarchy cannot spin.
     */
    private fun claimGesture(claim: Boolean) {
        // ViewParent 自己就有 getParent()，不用再 instanceof View 转一层；ViewRootImpl 也返回 null。
        var parent: android.view.ViewParent? = this.parent
        var guard = 0
        while (guard < 32) {
            val p: android.view.ViewParent = parent ?: break
            p.requestDisallowInterceptTouchEvent(claim)
            parent = p.parent
            guard++
        }
    }

    private fun releaseGesture() {
        claimGesture(false)
    }

    /**
     * Release the ancestor claim when this view leaves the hierarchy mid-gesture (a page
     * rebuild while the finger is down). Without it a half-finished drag could leave the
     * sheet's swipe handler disabled for the rest of the gesture.
     */
    override fun onDetachedFromWindow() {
        releaseGesture()
        tracking = false
        pressed = false
        super.onDetachedFromWindow()
    }

    private fun setPositionFromX(x: Float, fromUser: Boolean) {
        val l = travelLeft()
        val r = travelRight()
        val t = if (r <= l) 0f else (x - l) / (r - l)
        var next = Math.round(t * (stops - 1))
        if (next < 0) next = 0
        if (next > stops - 1) next = stops - 1
        if (next == pos) return
        pos = next
        invalidate()
        // A tick per stop is what makes a discrete slider feel detented instead of mushy.
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (fromUser && listener != null) listener!!.onPositionChanged(this, pos, false)
    }

    /** Activation is not a value change; only the settled notification is emitted here. */
    override fun performClick(): Boolean {
        super.performClick()
        listener?.onPositionChanged(this, pos, true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cy = channelCenterY()
        val l = channelLeft()
        val r = channelRight()
        val top = cy - trackH / 2f
        val bottom = cy + trackH / 2f
        val cx = thumbCenterX()

        paint.style = Paint.Style.FILL
        paint.shader = null

        // Channel: same translucent tone the switch uses for its OFF track.
        paint.color = trackOff
        rect.set(l, top, r, bottom)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // Filled part up to the thumb, in the accent gradient the selected chip uses.
        val gradient = fill
        if (cx > l && gradient != null) {
            paint.shader = gradient
            rect.set(l, top, Math.min(cx, r), bottom)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }

        // Thumb: soft contact shadow, white disc, accent ring. Same recipe as GlassSwitch.
        paint.color = if (pressed) 0x40000000 else 0x2E000000
        canvas.drawCircle(cx, cy + thumbR * 0.10f, thumbR, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, thumbR, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = UiKit.dpf(context, 1.4f)
        paint.color = trackOn
        paint.alpha = if (pressed) 255 else 170
        canvas.drawCircle(cx, cy, thumbR - paint.strokeWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    companion object {
        private const val TRACK_H_DP = 6f
        private const val THUMB_DP = 20f
        /** Finger-sized hit box, same height as [GlassSwitch]'s, keeps the row even. */
        private const val TOUCH_H_DP = 48f
        /** How far the channel pokes out past the thumb's resting centre at either end. */
        private const val OVERHANG_DP = 5f
    }
}