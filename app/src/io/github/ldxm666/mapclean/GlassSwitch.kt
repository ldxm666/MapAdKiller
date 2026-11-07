package io.github.ldxm666.mapclean

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Capsule switch drawn from scratch.
 *
 * Replaces the framework {@code Switch}: the stock thumb/track scaling looks foreign next
 * to the glass surfaces and cannot animate its colours. This one animates a single 0..1
 * progress value, driving track tint, thumb travel and a subtle thumb squash so the throw
 * feels physical.
 *
 * <h3>Contract (v2.2.0 rewrite)</h3>
 * <ul>
 *   <li>The view owns its own gesture: a tap anywhere on the capsule toggles it and fires
 *       [OnCheckedChange]. Callers must <b>not</b> also set an {@code OnClickListener}
 *       that flips the state -- that double-toggles.</li>
 *   <li>[setChecked] is the <i>programmatic</i> path and never notifies, so hydrating
 *       from storage cannot write back to storage.</li>
 *   <li>The drawn state always tracks [progress]. An earlier build forced the track
 *       to OFF whenever the view was disabled, so a switch that was genuinely ON still
 *       painted grey -- the whole pill looked dead and never changed colour on toggle.</li>
 *   <li>The touch target is a full 48dp tall even though the capsule is only 30dp, and the
 *       reported size is {@code width x 48dp} so the parent row gets a comfortable hit box.</li>
 * </ul>
 */
class GlassSwitch @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    /** Notified only for user-initiated toggles (tap), never for [setChecked]. */
    fun interface OnCheckedChange {
        fun onCheckedChanged(view: GlassSwitch, checked: Boolean)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val trackW: Float
    private val trackH: Float
    private val thumbSize: Float
    private val pad: Float
    private val radius: Float
    private val viewH: Float

    private var progress: Float = 0f          // 0 = off, 1 = on (may overshoot mid-spring)
    private var checkedState: Boolean = false
    private var enabledState: Boolean = true
    private var pressed: Boolean = false

    private var anim: ValueAnimator? = null
    private var driver: HyperMotion.Driver? = null
    private var listener: OnCheckedChange? = null
    private var label: String? = null

    private var trackOff = 0xFFE6E6E6.toInt()   // secondary
    private var trackOn = 0xFF3482FF.toInt()    // primary
    private var thumbOff = 0xFFFFFFFF.toInt()
    private var thumbOn = 0xFFFFFFFF.toInt()

    init {
        isClickable = true
        isFocusable = true
        val d = c.resources.displayMetrics.density
        trackW = TRACK_W_DP * d
        trackH = TRACK_H_DP * d
        thumbSize = THUMB_DP * d
        pad = PAD_DP * d
        radius = trackH / 2f
        viewH = TOUCH_H_DP * d
        refreshDescription()
    }

    fun setTrackColors(off: Int, on: Int) {
        trackOff = off
        trackOn = on
        invalidate()
    }

    fun setThumbColors(off: Int, on: Int) {
        thumbOff = off
        thumbOn = on
        invalidate()
    }

    /** Row title, used for the accessibility description ("…，已开启"). */
    fun setLabel(value: String?) {
        label = value
        refreshDescription()
    }

    fun setOnCheckedChange(l: OnCheckedChange?) {
        listener = l
    }

    fun isChecked(): Boolean = checkedState

    fun setChecked(value: Boolean) {
        setChecked(value, true)
    }

    /** {@code animate=false} snaps without motion -- used while hydrating from storage. */
    fun setChecked(value: Boolean, animate: Boolean) {
        val was = checkedState
        checkedState = value
        val target = if (value) 1f else 0f
        if (!animate) {
            cancelAnim()
            progress = target
            if (was != value) refreshDescription()
            invalidate()
            return
        }
        if (was == value && progress == target) return
        if (was != value) refreshDescription()
        animateTo(target)
    }

    /**
     * 滑块位移走物理弹簧 {@code spring(0.7, 987)}（miuix Switch.kt:87）。
     *
     * <p>用弹簧而不是补间，是因为连续点击时补间会从当前值重新起步、视觉上"顿一下"；
     * 弹簧把当前速度带进下一段，中途打断是连续的。
     */
    private fun animateTo(target: Float) {
        cancelAnim()
        if (driver == null) {
            driver = HyperMotion.Driver(
                HyperMotion.Spring.ofStiffness(
                    HyperMotion.D_SWITCH_MOVE, HyperMotion.K_SWITCH_MOVE, progress
                ),
                object : HyperMotion.Driver.Sink {
                    override fun onValue(value: Float) {
                        progress = value
                        invalidate()
                    }
                })
        }
        driver?.to(target)
    }

    private fun cancelAnim() {
        driver?.cancel()
        val a = anim
        if (a != null) {
            a.cancel()
            anim = null
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnim()
        super.onDetachedFromWindow()
    }

    /**
     * Cosmetic only. The switch stays touchable either way: the surrounding row is the
     * alternate hit target, and a disabled view that still claims {@code clickable}
     * silently swallows every tap (that is the "grey pill that does nothing" report).
     */
    override fun setEnabled(value: Boolean) {
        if (enabledState == value) return
        enabledState = value
        invalidate()
    }

    override fun isEnabled(): Boolean = enabledState

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(Math.ceil(trackW.toDouble()).toInt(), Math.ceil(viewH.toDouble()).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledState) return false     // let the parent row take the tap
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                HyperMotion.press(this, true, HyperDesign.PRESS_SWITCH)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x >= 0 && e.x <= width && e.y >= 0 && e.y <= height
                if (pressed != inside) {
                    pressed = inside
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                releasePress()
                if (pressed) {
                    pressed = false
                    invalidate()
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                releasePress()
                invalidate()
                return true
            }
            else -> return super.onTouchEvent(e)
        }
    }

    private fun releasePress() {
        HyperMotion.press(this, false, HyperDesign.PRESS_SWITCH)
    }

    /** A user tap: flip, animate, notify. Programmatic [setChecked] stays silent. */
    override fun performClick(): Boolean {
        super.performClick()
        if (!enabledState) return true
        val next = !checkedState
        setChecked(next, true)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        listener?.onCheckedChanged(this, next)
        return true
    }

    private fun refreshDescription() {
        contentDescription = (label ?: "开关") + (if (checkedState) "，已开启" else "，已关闭")
    }

    /**
     * HyperOS 开关绘制 —— 平实轨道 + 纯白滑块，没有任何高光或玻璃质感。
     *
     * <p>改自 Liquid Glass：那一版有"内圈 1 px 高光 + 滑块顶部月牙高光 + 按下时轨道外
     * 发光"，读起来是玻璃胶囊。HyperOS 是纯色填充，厚度感只靠滑块下方一层极淡的投影。
     *
     * <p>位移用 clamp 后的 progress，弹簧过冲时滑块不会冲出轨道；缩放用原始值，
     * 让放大动作带一点过冲的弹性。
     */
    override fun onDraw(canvas: Canvas) {
        val p = Math.max(0f, Math.min(1f, progress))
        val raw = progress
        val track = UiKit.blend(trackOff, trackOn, p)
        val thumb = UiKit.blend(thumbOff, thumbOn, p)
        val dim = if (enabledState) 255 else 120

        val left = (width - trackW) / 2f
        val cy = height / 2f
        val top = cy - trackH / 2f

        paint.style = Paint.Style.FILL
        paint.alpha = dim

        // 轨道：纯色全圆矩形，无描边无内高光。
        rect.set(left, top, left + trackW, top + trackH)
        paint.color = track
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 滑块：按下时放大到 1.127，用弹簧过冲值驱动，松手弹回。
        var scale = 1f
        if (pressed) {
            scale = THUMB_PRESS_SCALE
        } else if (Math.abs(raw - p) > 0.001f) {
            // 弹簧过冲中：把超出的部分转成一点点放大，读作惯性
            scale = 1f + Math.min(0.08f, Math.abs(raw - p) * 0.5f)
        }
        val r = thumbSize * 0.5f * scale

        // 行程：未选中距左 4，选中距右 4，行程 = 49 - 20 - 8 = 21 dp
        val travel = trackW - pad * 2f - thumbSize
        val cx = left + pad + thumbSize * 0.5f + travel * p

        // 滑块投影：极淡，只制造一点离面感（HyperOS 不用阴影做层级）
        paint.color = 0x1F000000
        canvas.drawCircle(cx, cy + r * 0.10f, r, paint)

        paint.color = thumb
        canvas.drawCircle(cx, cy, r, paint)

        paint.alpha = 255
    }

    companion object {
        // miuix Switch.kt：轨道 49x28，滑块 20，上下各留 4，行程 21 dp。
        private const val TRACK_W_DP = 49f
        private const val TRACK_H_DP = 28f
        private const val THUMB_DP = 20f
        private const val PAD_DP = 4f
        /** 按下 / 拖动时滑块放大倍数（miuix Switch.kt:105）。 */
        private const val THUMB_PRESS_SCALE = 1.127f
        /** Vertical footprint: keeps the capsule centred inside a finger-sized hit box. */
        private const val TOUCH_H_DP = 48f
    }
}