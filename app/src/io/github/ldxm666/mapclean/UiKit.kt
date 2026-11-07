package io.github.ldxm666.mapclean

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator

/**
 * Shared motion + material kit for the settings UI.
 *
 * Everything here is plain framework API: this module builds with {@code javac} against
 * {@code android.jar} only, so there is no AndroidX, no Compose and no Material Components.
 * The glass look approximates the reference design language (Kyant0/AndroidLiquidGlass) --
 * layered translucent plates with a bright specular top rim and a soft inner falloff.
 * Real backdrop blur needs RenderEffect (API 31+) and this module ships minSdk 26, so depth
 * is built from layered alpha and elevation instead.
 */
class UiKit private constructor() {

    companion object {
        /* -------------------------------------------------------------- 缓动曲线 */

        /** Fast-out, slow-in -- enter transitions and dialogs popping in. */
        @JvmField
        val DECELERATE: TimeInterpolator = interpolator(0.05f, 0.7f, 0.1f, 1f)
        /** Slow-out, fast-in -- exit transitions. */
        @JvmField
        val ACCELERATE: TimeInterpolator = interpolator(0.3f, 0f, 0.8f, 0.15f)
        /** Neutral easing for size and position changes. */
        @JvmField
        val STANDARD: TimeInterpolator = interpolator(0.2f, 0f, 0f, 1f)
        /** Slight overshoot -- toggles, chips, page settle, press release. */
        @JvmField
        val SPRING: TimeInterpolator = interpolator(0.34f, 1.56f, 0.64f, 1f)

        const val DUR_QUICK = 140L
        const val DUR_BASE = 240L
        const val DUR_SLOW = 380L

        /** dp -> px. The floating layer positions itself in raw pixels, never in dp. */
        @JvmStatic
        fun dp(c: android.content.Context, value: Float): Int {
            return Math.round(value * c.resources.displayMetrics.density)
        }

        /** dp -> px, float form (corner radii, stroke widths). */
        @JvmStatic
        fun dpf(c: android.content.Context, value: Float): Float {
            return value * c.resources.displayMetrics.density
        }

        /** PathInterpolator is API 21+, but guard anyway so a bad ROM cannot crash the UI. */
        private fun interpolator(a: Float, b: Float, c: Float, d: Float): TimeInterpolator {
            try {
                return PathInterpolator(a, b, c, d)
            } catch (ignored: Throwable) {
                return object : TimeInterpolator {
                    override fun getInterpolation(t: Float): Float {
                        return if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
                    }
                }
            }
        }

        /* -------------------------------------------------------------- 玻璃材质 */

        /**
         * Layered glass plate: specular rim gradient on top, translucent body inset by
         * {@code stroke} px, so the edge reads as glass thickness.
         */
        @JvmStatic
        fun glass(body: Int, rimHi: Int, rimLo: Int, radius: Float, stroke: Int): Drawable {
            val rim = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(rimHi, rimLo)
            )
            rim.shape = GradientDrawable.RECTANGLE
            rim.cornerRadius = radius

            val inner = GradientDrawable()
            inner.shape = GradientDrawable.RECTANGLE
            inner.setColor(body)
            inner.cornerRadius = Math.max(0f, radius - stroke)

            val ld = LayerDrawable(arrayOf<Drawable>(rim, inner))
            val s = Math.max(1, stroke)
            ld.setLayerInset(1, s, s, s, s)
            return ld
        }

        /** Solid rounded plate. */
        @JvmStatic
        fun plate(color: Int, radius: Float): GradientDrawable {
            val g = GradientDrawable()
            g.shape = GradientDrawable.RECTANGLE
            g.setColor(color)
            g.cornerRadius = radius
            return g
        }

        /** Vertical gradient plate -- accent buttons. */
        @JvmStatic
        fun gradient(top: Int, bottom: Int, radius: Float): GradientDrawable {
            val g = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, bottom)
            )
            g.shape = GradientDrawable.RECTANGLE
            g.cornerRadius = radius
            return g
        }

        /** Outline-only plate -- selected chips, focus rings. */
        @JvmStatic
        fun outlined(fill: Int, strokeColor: Int, strokePx: Int, radius: Float): GradientDrawable {
            val g = GradientDrawable()
            g.shape = GradientDrawable.RECTANGLE
            g.setColor(fill)
            g.cornerRadius = radius
            g.setStroke(Math.max(1, strokePx), strokeColor)
            return g
        }

        /** Wrap content in a bounded ripple. */
        @JvmStatic
        fun ripple(color: Int, content: Drawable?, radius: Float): Drawable {
            val mask = GradientDrawable()
            mask.shape = GradientDrawable.RECTANGLE
            mask.cornerRadius = radius
            mask.setColor(0xFF000000.toInt())
            return RippleDrawable(ColorStateList.valueOf(color), content, mask)
        }

        /** Ripple with no static content -- transparent until pressed. */
        @JvmStatic
        fun rippleBare(color: Int, radius: Float): Drawable {
            return ripple(color, null, radius)
        }

        /* -------------------------------------------------------------- 变换工具 */

        /** Clip a view's children to a rounded outline. */
        @JvmStatic
        fun clipRounded(v: View, radius: Float) {
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            v.clipToOutline = true
        }

        /**
         * Springy press feedback: shrink to {@code scale} while held, overshoot slightly on
         * release. This is the tactile "press" gesture used by every tappable surface.
         */
        @JvmStatic
        fun pressable(v: View, scale: Float) {
            v.setOnTouchListener(object : View.OnTouchListener {
                private var down = false
                private var anim: ValueAnimator? = null

                /**
                 * Drives scaleX/scaleY from our own ValueAnimator rather than
                 * {@code view.animate()}. Sharing the ViewPropertyAnimator meant that any
                 * later {@code animate().cancel()} on the same view -- e.g. the pager tabs
                 * re-tinting themselves in selectTab() -- killed the release animation
                 * mid-flight and left the chip permanently squashed to 0.95.
                 */
                private fun animateScaleTo(target: Float) {
                    anim?.cancel()
                    val from = v.scaleX
                    if (from == target) return
                    val a = ValueAnimator.ofFloat(from, target)
                    a.duration = if (target < from) DUR_QUICK else DUR_BASE
                    // 松手那一下用 **CycleInterpolator** 而不是普通的弹簧曲线。
                    //
                    // 手法抄自 android_ui_motion_suite/ElasticViews（skydoves，MIT）：
                    // 它把「按下 → 缩」和「松手 → 弹回」拆成两段，回程用一条会越过终点的
                    // 振荡曲线，于是字面上"弹"一下 —— 而不是单调地滑回原尺寸。
                    //
                    // 1.4 个周期：越过 1.0 一次再回来，多了会显得廉价、少了感觉不到。
                    // 振幅随缩放比例走：压得越深，回弹越大，手感才一致。
                    a.interpolator = if (target < from) DECELERATE
                    else android.view.animation.CycleInterpolator(1.4f)
                    a.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                        override fun onAnimationUpdate(animator: ValueAnimator) {
                            val s = animator.animatedValue as Float
                            v.scaleX = s
                            v.scaleY = s
                        }
                    })
                    a.start()
                    anim = a
                }

                override fun onTouch(view: View, e: android.view.MotionEvent): Boolean {
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            down = true
                            animateScaleTo(scale)
                            // 触感反馈：让"按到了"这件事越过视觉、直接到手上。
                            // KEYBOARD_TAP 比 LONG_PRESS 轻，适合行/卡片这种高频轻点；
                            // 用 performHapticFeedback 而不是 Vibrator —— 尊重系统
                            // 「触摸振动」开关，用户关了就不震。
                            try {
                                v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            } catch (ignored: Throwable) {
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            if (down) {
                                down = false
                                animateScaleTo(1f)
                            }
                        }
                        else -> {
                        }
                    }
                    return false   // never consume: clicks and ripples still work
                }
            })
        }

        /**
         * Animate a view's alpha in with a small upward drift.
         *
         * The view is hidden and the drift applied first so there is no flash, but the
         * animation always terminates at alpha 1 and clears the translation, and
         * {@code withEndAction} force-corrects the final state. An earlier build left views
         * at alpha 0 whenever the animation was cancelled mid-flight by a layout pass,
         * which made the whole header and pager invisible.
         */
        @JvmStatic
        fun enter(v: View, fromY: Float, delay: Long) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = fromY
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(DUR_SLOW)
                .setInterpolator(DECELERATE)
                .withEndAction(object : Runnable {
                    override fun run() {
                        v.alpha = 1f
                        v.translationY = 0f
                    }
                }).start()
        }

        /** Guarantee a view is visible; used as a safety net after any entrance run. */
        @JvmStatic
        fun reveal(v: View?) {
            if (v == null) return
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
        }

        /**
         * Staggered entrance for a list of children: each row fades up a little later than
         * the last, giving the page a deliberate "settling" feel instead of popping in.
         */
        @JvmStatic
        fun enterStaggered(parent: ViewGroup, fromY: Float, step: Long, startDelay: Long) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                var d = startDelay + i * step
                if (d > 900L) d = 900L             // never make the tail feel slow
                enter(child, fromY, d)
            }
        }

        /** Animate an integer property with a named interpolator. */
        @JvmStatic
        fun animateInt(
            from: Int, to: Int, ip: TimeInterpolator?, duration: Long,
            cb: ValueAnimator.AnimatorUpdateListener
        ) {
            val a = ValueAnimator.ofInt(from, to)
            a.duration = duration
            a.interpolator = ip
            a.addUpdateListener(cb)
            a.start()
        }

        /** Animate a float property with a named interpolator. */
        @JvmStatic
        fun animateFloat(
            from: Float, to: Float, ip: TimeInterpolator?, duration: Long,
            cb: ValueAnimator.AnimatorUpdateListener
        ) {
            val a = ValueAnimator.ofFloat(from, to)
            a.duration = duration
            a.interpolator = ip
            a.addUpdateListener(cb)
            a.start()
        }

        /** Blend two ARGB colours. */
        @JvmStatic
        fun blend(from: Int, to: Int, t: Float): Int {
            if (t <= 0f) return from
            if (t >= 1f) return to
            val a = (android.graphics.Color.alpha(from) +
                (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).toInt()
            val r = (android.graphics.Color.red(from) +
                (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).toInt()
            val g = (android.graphics.Color.green(from) +
                (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).toInt()
            val b = (android.graphics.Color.blue(from) +
                (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).toInt()
            return android.graphics.Color.argb(a, r, g, b)
        }
    }
}