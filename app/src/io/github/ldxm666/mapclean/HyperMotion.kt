package io.github.ldxm666.mapclean

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared motion contract for the settings app and embedded sheet.
 *
 * Durations are milliseconds; spring response is seconds. Spring parameters follow
 * miuix 0.9.4's folmeSpring / Switch. No AndroidX dependency is added to host processes.
 * See docs/ui-motion-contract.md for sources, coverage and verification.
 */
class HyperMotion private constructor() {
    companion object {
        const val D_STANDARD = 0.9f
        const val D_SOFT = 0.85f
        const val D_NONE = 1f
        const val D_SLIGHT = 0.95f
        const val D_TIGHT = 0.96f

        const val K_SWITCH_MOVE = 987f
        const val K_SWITCH_SCALE = 987f
        const val K_TRACK_COLOR = 438.6f
        const val D_SWITCH_MOVE = 0.7f
        const val D_SWITCH_SCALE = 0.6f
        const val D_TRACK_COLOR = 0.99f

        const val R_PAGE = 0.38f
        const val R_SOFT = 0.40f
        const val R_POSITION = 0.35f
        const val R_QUICK = 0.30f
        const val R_INSTANT = 0.20f
        const val R_SLOW = 0.60f

        // One duration ladder. Components reference roles, never arbitrary timings.
        const val DUR_PRESS = 80L
        const val DUR_TINT = 160L
        const val DUR_MOVE = 240L
        const val DUR_PAGE = 320L
        const val DUR_EXIT = 160L
        const val STAGGER = 32L
        const val STAGGER_MAX = 128L
        const val DISTANCE_SMALL = 8f
        const val DISTANCE_PAGE = 24f

        @JvmField val DECELERATE: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        @JvmField val ACCELERATE: TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        @JvmField val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        @JvmField val SOFT: TimeInterpolator = PathInterpolator(0.22f, 0.9f, 0.3f, 1f)

        @JvmStatic
        fun enabled(): Boolean = try { ValueAnimator.areAnimatorsEnabled() } catch (_: Throwable) { true }

        @JvmStatic
        fun enabled(view: View): Boolean = enabled() && view.isAttachedToWindow

        @JvmStatic
        fun durationScale(context: Context?): Float {
            if (!enabled()) return 0f
            if (context == null) return 1f
            return try {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                    .coerceAtLeast(0f)
            } catch (_: Throwable) { 1f }
        }

        @JvmStatic
        fun drive(damping: Float, response: Float, initial: Float, setter: Driver.Sink): Driver =
            Driver(damping, response, initial, setter)

        @JvmStatic
        fun driveToggle(initial: Float, setter: Driver.Sink): Driver =
            Driver(Spring.ofStiffness(D_SWITCH_MOVE, K_SWITCH_MOVE, initial), setter)

        @JvmStatic
        fun driveRelease(initial: Float, setter: Driver.Sink): Driver =
            Driver(D_NONE, R_INSTANT, initial, setter)

        @JvmStatic
        fun drivePage(initial: Float, setter: Driver.Sink): Driver =
            Driver(Spring.at(D_STANDARD, R_PAGE, initial, 0.25f), setter)

        @JvmStatic
        fun tint(from: Float, to: Float, durationMs: Long, easing: TimeInterpolator?, setter: Driver.Sink): ValueAnimator {
            val a = ValueAnimator.ofFloat(from, to)
            a.duration = if (enabled()) durationMs else 0L
            a.interpolator = easing ?: DECELERATE
            a.addUpdateListener { setter.onValue(it.animatedValue as Float) }
            a.start()
            return a
        }

        @JvmStatic
        fun tint(from: Float, to: Float, setter: Driver.Sink): ValueAnimator =
            tint(from, to, DUR_TINT, DECELERATE, setter)

        private val presses = WeakHashMap<View, Press>()

        /** Independent scale channel: an entrance/exit animator cannot cancel release. */
        @JvmStatic
        fun press(view: View?, down: Boolean, pressedScale: Float) {
            if (view == null) return
            var state = presses[view]
            if (state == null) {
                state = Press(view)
                presses[view] = state
            }
            if (!state.driver.isRunning()) state.driver.snapTo(view.scaleX)
            state.driver.to(if (down) pressedScale else 1f)
        }

        private class Press(view: View) {
            private val ref = WeakReference(view)
            val driver = Driver(Spring.at(D_SOFT, R_INSTANT, view.scaleX, 0.0005f), Driver.Sink { scale ->
                ref.get()?.let { it.scaleX = scale; it.scaleY = scale }
            }).bindTo(view)
        }
    }

    /**
     * Exact damped oscillator solution. The same spring behaves consistently at
     * 60/90/120 Hz and after irregular frames. minimumVisibleChange is in the
     * caller's units: 0..1 progress is not a pixel position.
     */
    class Spring private constructor(
        private val damping: Double,
        private val stiffness: Double,
        initial: Float,
        private val minimumVisibleChange: Float
    ) {
        private var x = initial.toDouble()
        private var v = 0.0
        private var target = initial.toDouble()

        constructor(damping: Float, response: Float) : this(
            damping.coerceAtLeast(0.01f).toDouble(),
            Math.pow(2.0 * Math.PI / response.coerceAtLeast(0.02f), 2.0), 0f, 0.001f
        )

        fun value(): Float = x.toFloat()
        fun velocity(): Float = v.toFloat()
        fun to(next: Float) { target = next.toDouble() }
        fun to(next: Float, velocityPxPerSec: Float) { target = next.toDouble(); v = velocityPxPerSec.toDouble() }
        fun snapTo(next: Float) { target = next.toDouble(); settle() }
        fun settle() { x = target; v = 0.0 }

        /** Returns false only once both position and velocity are imperceptible. */
        fun advance(dtSec: Float): Boolean {
            if (!dtSec.isFinite() || dtSec <= 0f) return true
            val dt = dtSec.coerceAtMost(0.5f).toDouble()
            val omega = sqrt(stiffness)
            val decay = damping * omega
            val y = x - target
            val nextY: Double
            val nextV: Double
            if (abs(damping - 1.0) < 0.001) {
                val b = v + omega * y
                val e = exp(-omega * dt)
                nextY = e * (y + b * dt)
                nextV = e * (v - omega * b * dt)
            } else if (damping < 1.0) {
                val wd = omega * sqrt(1.0 - damping * damping)
                val b = (v + decay * y) / wd
                val e = exp(-decay * dt)
                val co = cos(wd * dt)
                val si = sin(wd * dt)
                nextY = e * (y * co + b * si)
                nextV = e * ((b * wd - decay * y) * co - (y * wd + decay * b) * si)
            } else {
                val root = omega * sqrt(damping * damping - 1.0)
                val r1 = -decay + root
                val r2 = -decay - root
                val a = (v - r2 * y) / (r1 - r2)
                val b = y - a
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                nextY = a * e1 + b * e2
                nextV = a * r1 * e1 + b * r2 * e2
            }
            x = target + nextY
            v = nextV
            if (abs(nextY) <= minimumVisibleChange && abs(v) <= minimumVisibleChange * omega) {
                settle()
                return false
            }
            return true
        }

        companion object {
            @JvmStatic @JvmOverloads
            fun ofStiffness(damping: Float, stiffness: Float, initial: Float, minimumVisibleChange: Float = 0.001f): Spring =
                Spring(damping.coerceAtLeast(0.01f).toDouble(), stiffness.coerceAtLeast(1f).toDouble(),
                    initial, minimumVisibleChange.coerceAtLeast(0.00001f))

            @JvmStatic @JvmOverloads
            fun at(damping: Float, response: Float, initial: Float, minimumVisibleChange: Float = 0.001f): Spring =
                Spring(damping.coerceAtLeast(0.01f).toDouble(),
                    Math.pow(2.0 * Math.PI / response.coerceAtLeast(0.02f), 2.0),
                    initial, minimumVisibleChange.coerceAtLeast(0.00001f))
        }
    }

    /** Retargetable, velocity-preserving frame driver. No repeating ValueAnimator. */
    class Driver(private val spring: Spring, private val sink: Sink) : Choreographer.FrameCallback {
        fun interface Sink { fun onValue(value: Float) }

        constructor(damping: Float, response: Float, initial: Float, sink: Sink) :
            this(Spring.at(damping, response, initial), sink)

        private var owner: WeakReference<View>? = null
        private var choreographer: Choreographer? = null
        private var lastNs = 0L
        private var running = false
        private var scale = 1f
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancel()
                spring.settle()
                sink.onValue(spring.value())
            }
        }

        fun bindTo(view: View): Driver {
            owner?.get()?.removeOnAttachStateChangeListener(attachListener)
            owner = WeakReference(view)
            view.addOnAttachStateChangeListener(attachListener)
            return this
        }

        fun value(): Float = spring.value()
        fun isRunning(): Boolean = running
        fun to(target: Float) { spring.to(target); start() }
        fun to(target: Float, velocityPxPerSec: Float) { spring.to(target, velocityPxPerSec); start() }
        fun snapTo(target: Float) { cancel(); spring.snapTo(target); sink.onValue(target) }

        private fun start() {
            val view = owner?.get()
            scale = durationScale(view?.context)
            if (scale == 0f || (owner != null && (view == null || !view.isAttachedToWindow))) {
                cancel()
                spring.settle()
                sink.onValue(spring.value())
                return
            }
            if (running) return
            running = true
            lastNs = 0L
            val clock = Choreographer.getInstance()
            choreographer = clock
            clock.postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (!enabled()) {
                spring.settle()
                sink.onValue(spring.value())
                cancel()
                return
            }
            // Establish the clock; do not invent a 16ms first frame on a 120Hz screen.
            if (lastNs == 0L) {
                lastNs = frameTimeNanos
                choreographer?.postFrameCallback(this)
                return
            }
            val dt = (frameTimeNanos - lastNs) / 1_000_000_000f / scale.coerceAtLeast(0.01f)
            lastNs = frameTimeNanos
            val moving = spring.advance(dt)
            sink.onValue(spring.value())
            if (moving && running) choreographer?.postFrameCallback(this) else cancel()
        }

        fun cancel() {
            choreographer?.removeFrameCallback(this)
            running = false
            lastNs = 0L
        }
    }
}
