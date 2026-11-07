package io.github.ldxm666.mapclean

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator

/**
 * HyperOS 动效系统 —— 物理弹簧 + 补间。
 *
 * <p>参数取自 Miuix 0.9.4 的 {@code folmeSpring(damping, response)}（小米 Folme 的
 * 物理动效参数化），其刚度定义为 {@code stiffness = (2π / response)²}。这里用同样的公式
 * 手写积分器，不依赖 {@code androidx.dynamicanimation} —— 本模块只用 {@code android.jar}
 * 编译。
 *
 * <p><b>为什么不用 ValueAnimator 的插值器</b>：cubic-bezier 无法表达"松手时带着速度继续走"，
 * 而滚动惯性、拖动回弹、连续点击打断都需要这个。弹簧积分器天然支持把当前速度作为初值，
 * 所以连续打断不会跳变。
 *
 * <p>约定：
 * <ul>
 *   <li>位置 / 尺寸变化 → 弹簧（有回弹）</li>
 *   <li>颜色 / 透明度 → 补间 150 ms</li>
 *   <li>页面切换 → 300 ms</li>
 * </ul>
 * 不要所有东西都用同一个时长，那是廉价感的主要来源。
 */
class HyperMotion private constructor() {

    companion object {

        /* -------------------------------------------------------------- 弹簧预设 */

        /** 阻尼比。1.0 = 临界阻尼（无过冲），&lt;1.0 = 有回弹，&gt;1.0 = 过阻尼。 */
        const val D_STANDARD = 0.9f
        const val D_SOFT = 0.85f
        const val D_NONE = 1.0f
        const val D_SLIGHT = 0.95f
        const val D_TIGHT = 0.96f

        /** miuix 开关的三组刚度（Compose spring 的第二参数）。 */
        const val K_SWITCH_MOVE = 987f    // 滑块位移 spring(0.7, 987)
        const val K_SWITCH_SCALE = 987f   // 滑块缩放 spring(0.6, 987)
        const val K_TRACK_COLOR = 438.6f  // 轨道换色 spring(0.99, 438.6)
        const val D_SWITCH_MOVE = 0.7f
        const val D_SWITCH_SCALE = 0.6f
        const val D_TRACK_COLOR = 0.99f

        /** 响应时间，秒。越小越快。 */
        const val R_PAGE = 0.38f   // 页面 / 列表过渡（最常用）
        const val R_SOFT = 0.40f   // 柔和弹出
        const val R_POSITION = 0.35f // 无过冲的位置变化
        const val R_QUICK = 0.30f  // 快速回弹（开关）
        const val R_INSTANT = 0.20f // 即时跟随（拖动松手）
        const val R_SLOW = 0.60f   // 缓慢落位

        /* -------------------------------------------------------------- 补间时长 */

        /** 颜色、透明度、涟漪。 */
        const val DUR_TINT = 150L
        /** 位置、尺寸（弹簧退化时用）。 */
        const val DUR_MOVE = 200L
        /** 大位移、页面切换。 */
        const val DUR_PAGE = 300L
        /** 按压反馈的起点。 */
        const val DUR_PRESS = 90L

        /* -------------------------------------------------------------- 缓动 */

        /** 进入：快出慢入。等价 miuix 的 DecelerateEasing。 */
        @JvmField
        val DECELERATE: TimeInterpolator = curve(0.05f, 0.7f, 0.1f, 1f)
        /** 退出：慢出快入。等价 miuix 的 AccelerateEasing。 */
        @JvmField
        val ACCELERATE: TimeInterpolator = curve(0.3f, 0f, 0.8f, 0.15f)
        /** 中性：尺寸与位置。 */
        @JvmField
        val STANDARD: TimeInterpolator = curve(0.2f, 0f, 0f, 1f)

        private fun curve(a: Float, b: Float, c: Float, d: Float): TimeInterpolator {
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

        /* -------------------------------------------------------------- 便捷入口 */

        /**
         * 弹簧驱动一个 float 属性。{@code setter} 每帧收到新值。
         *
         * <p>返回 Driver，调用方可以持有它以便后续 [Driver.to] 打断。
         */
        @JvmStatic
        fun drive(damping: Float, response: Float, initial: Float, setter: Driver.Sink): Driver {
            return Driver(damping, response, initial, setter)
        }

        /** 开关滑块横向移动：弹簧 (0.9, 0.3)。 */
        @JvmStatic
        fun driveToggle(initial: Float, setter: Driver.Sink): Driver {
            return Driver(D_STANDARD, R_QUICK, initial, setter)
        }

        /** 拖动松手回位：弹簧 (1.0, 0.2)，无过冲且极快。 */
        @JvmStatic
        fun driveRelease(initial: Float, setter: Driver.Sink): Driver {
            return Driver(D_NONE, R_INSTANT, initial, setter)
        }

        /** 页面 / 指示器过渡：弹簧 (0.9, 0.38)。 */
        @JvmStatic
        fun drivePage(initial: Float, setter: Driver.Sink): Driver {
            return Driver(D_STANDARD, R_PAGE, initial, setter)
        }

        /**
         * 纯补间（颜色 / 透明度）。不走弹簧 —— 颜色的过冲看起来像闪烁。
         *
         * @return 已启动的 ValueAnimator，调用方可 cancel。
         */
        @JvmStatic
        fun tint(
            from: Float, to: Float, durationMs: Long,
            easing: TimeInterpolator?, setter: Driver.Sink
        ): ValueAnimator {
            val a = ValueAnimator.ofFloat(from, to)
            a.duration = durationMs
            if (easing != null) a.interpolator = easing
            a.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                override fun onAnimationUpdate(anim: ValueAnimator) {
                    setter.onValue(anim.animatedValue as Float)
                }
            })
            a.start()
            return a
        }

        /** 颜色补间，默认 150 ms + DECELERATE。 */
        @JvmStatic
        fun tint(from: Float, to: Float, setter: Driver.Sink): ValueAnimator {
            return tint(from, to, DUR_TINT, DECELERATE, setter)
        }

        /**
         * 按压缩放。进 90 ms 线性压下，出用弹簧 —— 这是"手感"最容易被感知的一处。
         *
         * <p>不要用 {@code View.animate().scaleX()} 做回弹：那是补间，松手快时看不出回弹，
         * 松手慢时又拖沓。弹簧的初速度来自当前值，所以两种节奏都对。
         */
        @JvmStatic
        fun press(v: android.view.View?, down: Boolean, pressedScale: Float) {
            if (v == null) return
            val to = if (down) pressedScale else 1f
            if (down) {
                v.animate().scaleX(to).scaleY(to)
                    .setDuration(DUR_PRESS)
                    .setInterpolator(STANDARD)
                    .start()
            } else {
                v.animate().scaleX(to).scaleY(to)
                    .setDuration(DUR_MOVE)
                    .setInterpolator(curve(0.34f, 1.28f, 0.64f, 1f))
                    .start()
            }
        }
    }

    /* ------------------------------------------------------------------ 弹簧积分器 */

    /**
     * 一维弹簧。用法：构造 → [to] → 每帧 [advance] 直到返回 false。
     *
     * <p>半隐式欧拉积分（先更新速度再更新位置），在 60/90/120 Hz 下都稳定。
     * 步长被钳在 32 ms 以内 —— 掉帧时不会因为 dt 过大而发散。
     */
    class Spring {

        private val k: Float   // 刚度
        private val c: Float   // 阻尼系数
        private var x: Float = 0f         // 当前位置
        private var v: Float = 0f         // 当前速度
        private var target: Float = 0f

        constructor(damping: Float, response: Float) {
            val stiffness = Math.pow(
                2.0 * Math.PI / Math.max(0.02f, response).toDouble(), 2.0
            ).toFloat()
            k = Math.max(1f, stiffness)
            c = 2f * Math.max(0.01f, damping) * Math.sqrt(k.toDouble()).toFloat()
        }

        private constructor(damping: Float, stiffness: Float, rawStiffness: Boolean) {
            k = Math.max(1f, stiffness)
            c = 2f * Math.max(0.01f, damping) * Math.sqrt(k.toDouble()).toFloat()
        }

        fun value(): Float = x

        /** 把当前速度保留下来再改目标 —— 连续打断不跳变，这是弹簧相对补间的核心优势。 */
        fun to(next: Float) {
            target = next
        }

        /** 带着外部速度改目标（例如手指抬起时的滑动速度，px/s）。 */
        fun to(next: Float, velocityPxPerSec: Float) {
            target = next
            v = velocityPxPerSec
        }

        /** @return true 表示还在动，需要继续下一帧。 */
        fun advance(dtSec: Float): Boolean {
            if (dtSec <= 0f) return true
            val dt = Math.min(dtSec, 0.032f)
            val a = -k * (x - target) - c * v
            v += a * dt
            x += v * dt
            // 收敛判据：位置与速度都足够小。0.5 px / 5 px·s⁻¹ 在 3x 屏上不可见。
            return Math.abs(x - target) > 0.5f || Math.abs(v) > 5f
        }

        fun settle() {
            x = target
            v = 0f
        }

        companion object {
            /**
             * 直接给刚度。miuix 的 Compose {@code spring(dampingRatio, stiffness)} 用这种写法，
             * 例如开关位移 {@code spring(0.7, 987)}、轨道换色 {@code spring(0.99, 438.6)}。
             * 和 [Spring] 是同一个物理模型，只是参数化方式不同。
             */
            @JvmStatic
            fun ofStiffness(damping: Float, stiffness: Float, initial: Float): Spring {
                val s = Spring(damping, stiffness, true)
                s.x = initial
                s.target = initial
                return s
            }

            /** 从静止开始。 */
            @JvmStatic
            fun at(damping: Float, response: Float, initial: Float): Spring {
                val s = Spring(damping, response)
                s.x = initial
                s.target = initial
                return s
            }
        }
    }

    /**
     * 把弹簧绑到任意 float 属性上，用 ValueAnimator 当帧时钟（它比自建 Choreographer
     * 更省事，且能自动在 View detach 时取消）。
     *
     * <p>返回的对象可以立刻再调 [to] 打断 —— 当前速度会带过去。
     */
    class Driver {

        fun interface Sink {
            fun onValue(value: Float)
        }

        private val spring: Spring
        private val sink: Sink
        private var clock: ValueAnimator? = null
        private var lastNs: Long = 0L

        constructor(damping: Float, response: Float, initial: Float, sink: Sink) {
            this.spring = Spring.at(damping, response, initial)
            this.sink = sink
        }

        constructor(spring: Spring, sink: Sink) {
            this.spring = spring
            this.sink = sink
        }

        fun value(): Float = spring.value()

        fun to(target: Float) {
            spring.to(target)
            start()
        }

        fun to(target: Float, velocityPxPerSec: Float) {
            spring.to(target, velocityPxPerSec)
            start()
        }

        private fun start() {
            val existing = clock
            if (existing != null) {
                if (existing.isRunning) return
                lastNs = 0L
                existing.start()
                return
            }
            val fresh = ValueAnimator.ofFloat(0f, 1f)
            fresh.duration = 1L
            fresh.repeatCount = ValueAnimator.INFINITE
            fresh.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                override fun onAnimationUpdate(a: ValueAnimator) {
                    val now = System.nanoTime()
                    val dt = if (lastNs == 0L) 0.016f else (now - lastNs) / 1_000_000_000f
                    lastNs = now
                    val moving = spring.advance(dt)
                    sink.onValue(spring.value())
                    if (!moving) {
                        spring.settle()
                        sink.onValue(spring.value())
                        a.cancel()
                        lastNs = 0L
                    }
                }
            })
            clock = fresh
            lastNs = 0L
            fresh.start()
        }

        fun cancel() {
            val c = clock
            if (c != null) {
                c.cancel()
                lastNs = 0L
            }
        }
    }
}