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
        val DECELERATE: TimeInterpolator = HyperMotion.DECELERATE
        /** Slow-out, fast-in -- exit transitions. */
        @JvmField
        val ACCELERATE: TimeInterpolator = HyperMotion.ACCELERATE
        /** Neutral easing for size and position changes. */
        @JvmField
        val STANDARD: TimeInterpolator = HyperMotion.STANDARD
        /** Slight overshoot -- toggles, chips, page settle, press release. */
        @JvmField
        val SPRING: TimeInterpolator = HyperMotion.SOFT

        const val DUR_QUICK = HyperMotion.DUR_TINT
        const val DUR_BASE = HyperMotion.DUR_MOVE
        const val DUR_SLOW = HyperMotion.DUR_PAGE

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
            val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
            v.setOnTouchListener(object : View.OnTouchListener {
                private var down = false
                private var startX = 0f
                private var startY = 0f

                override fun onTouch(view: View, e: android.view.MotionEvent): Boolean {
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            if (!view.isEnabled) return false
                            startX = e.x
                            startY = e.y
                            down = true
                            HyperMotion.press(view, true, scale)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (down && (kotlin.math.abs(e.x - startX) > slop ||
                                    kotlin.math.abs(e.y - startY) > slop ||
                                    e.x < 0 || e.y < 0 || e.x > view.width || e.y > view.height)) {
                                down = false
                                HyperMotion.press(view, false, scale)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            val click = down && e.actionMasked == android.view.MotionEvent.ACTION_UP
                            down = false
                            HyperMotion.press(view, false, scale)
                            if (click) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    return false
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
            if (!HyperMotion.enabled(v)) { v.alpha = 1f; v.translationY = 0f; return }
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
            val visible = android.graphics.Rect()
            var order = 0
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                if (order >= 6 || !child.getGlobalVisibleRect(visible)) {
                    reveal(child)
                    continue
                }
                enter(child, fromY, (startDelay + order * step).coerceAtMost(HyperMotion.STAGGER_MAX))
                order++
            }
        }

        /** Short shared-axis entrance, used for a category or search-result change. */
        @JvmStatic
        fun contentIn(view: View, direction: Int = 0) {
            view.animate().cancel()
            if (!HyperMotion.enabled(view)) {
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                return
            }
            view.alpha = 0f
            view.translationY = 0f
            view.translationX = dpf(view.context, HyperMotion.DISTANCE_PAGE) * direction
            view.animate().alpha(1f).translationX(0f).setStartDelay(0L)
                .setDuration(HyperMotion.DUR_MOVE).setInterpolator(HyperMotion.DECELERATE)
                .withEndAction { view.alpha = 1f; view.translationX = 0f }.start()
        }

        /**
         * Layout is committed once; Android animates clipping/bounds and sibling movement.
         * Avoid a height animator that remeasures hundreds of switch rows every frame.
         */
        @JvmStatic
        fun setExpanded(sceneRoot: ViewGroup, content: View, expanded: Boolean, animate: Boolean) {
            android.transition.TransitionManager.endTransitions(sceneRoot)
            content.animate().cancel()
            content.alpha = 1f
            content.translationY = 0f
            content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            if (animate && HyperMotion.enabled(sceneRoot)) {
                val bounds = android.transition.ChangeBounds()
                bounds.setResizeClip(true)
                bounds.duration = HyperMotion.DUR_MOVE
                bounds.interpolator = HyperMotion.STANDARD
                for (i in 0 until sceneRoot.childCount) bounds.addTarget(sceneRoot.getChildAt(i))
                bounds.addTarget(content)
                val fade = android.transition.Fade()
                fade.addTarget(content)
                fade.duration = HyperMotion.DUR_TINT
                val transition = android.transition.TransitionSet()
                transition.ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                transition.addTransition(bounds).addTransition(fade)
                android.transition.TransitionManager.beginDelayedTransition(sceneRoot, transition)
            }
            content.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        /** Animate an integer property with a named interpolator. */
        @JvmStatic
        fun animateInt(
            from: Int, to: Int, ip: TimeInterpolator?, duration: Long,
            cb: ValueAnimator.AnimatorUpdateListener
        ) {
            val a = ValueAnimator.ofInt(from, to)
            a.duration = if (HyperMotion.enabled()) duration else 0L
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
            a.duration = if (HyperMotion.enabled()) duration else 0L
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
