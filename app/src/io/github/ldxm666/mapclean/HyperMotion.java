package io.github.ldxm666.mapclean;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;

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
final class HyperMotion {

    private HyperMotion() {}

    /* ------------------------------------------------------------------ 弹簧预设 */

    /** 阻尼比。1.0 = 临界阻尼（无过冲），&lt;1.0 = 有回弹，&gt;1.0 = 过阻尼。 */
    static final float D_STANDARD = 0.9f;
    static final float D_SOFT = 0.85f;
    static final float D_NONE = 1.0f;
    static final float D_SLIGHT = 0.95f;
    static final float D_TIGHT = 0.96f;

    /** miuix 开关的三组刚度（Compose spring 的第二参数）。 */
    static final float K_SWITCH_MOVE = 987f;    // 滑块位移 spring(0.7, 987)
    static final float K_SWITCH_SCALE = 987f;   // 滑块缩放 spring(0.6, 987)
    static final float K_TRACK_COLOR = 438.6f;  // 轨道换色 spring(0.99, 438.6)
    static final float D_SWITCH_MOVE = 0.7f;
    static final float D_SWITCH_SCALE = 0.6f;
    static final float D_TRACK_COLOR = 0.99f;

    /** 响应时间，秒。越小越快。 */
    static final float R_PAGE = 0.38f;   // 页面 / 列表过渡（最常用）
    static final float R_SOFT = 0.40f;   // 柔和弹出
    static final float R_POSITION = 0.35f; // 无过冲的位置变化
    static final float R_QUICK = 0.30f;  // 快速回弹（开关）
    static final float R_INSTANT = 0.20f; // 即时跟随（拖动松手）
    static final float R_SLOW = 0.60f;   // 缓慢落位

    /* ------------------------------------------------------------------ 补间时长 */

    /** 颜色、透明度、涟漪。 */
    static final long DUR_TINT = 150L;
    /** 位置、尺寸（弹簧退化时用）。 */
    static final long DUR_MOVE = 200L;
    /** 大位移、页面切换。 */
    static final long DUR_PAGE = 300L;
    /** 按压反馈的起点。 */
    static final long DUR_PRESS = 90L;

    /* ------------------------------------------------------------------ 缓动 */

    /** 进入：快出慢入。等价 miuix 的 DecelerateEasing。 */
    static final TimeInterpolator DECELERATE = curve(0.05f, 0.7f, 0.1f, 1f);
    /** 退出：慢出快入。等价 miuix 的 AccelerateEasing。 */
    static final TimeInterpolator ACCELERATE = curve(0.3f, 0f, 0.8f, 0.15f);
    /** 中性：尺寸与位置。 */
    static final TimeInterpolator STANDARD = curve(0.2f, 0f, 0f, 1f);

    private static TimeInterpolator curve(float a, float b, float c, float d) {
        try {
            return new PathInterpolator(a, b, c, d);
        } catch (Throwable ignored) {
            return new TimeInterpolator() {
                @Override public float getInterpolation(float t) {
                    return t < 0.5f ? 2f * t * t : 1f - 2f * (1f - t) * (1f - t);
                }
            };
        }
    }

    /* ------------------------------------------------------------------ 弹簧积分器 */

    /**
     * 一维弹簧。用法：构造 → {@link #to(float)} → 每帧 {@link #advance(float)} 直到返回 false。
     *
     * <p>半隐式欧拉积分（先更新速度再更新位置），在 60/90/120 Hz 下都稳定。
     * 步长被钳在 32 ms 以内 —— 掉帧时不会因为 dt 过大而发散。
     */
    static final class Spring {

        private final float k;   // 刚度
        private final float c;   // 阻尼系数
        private float x;         // 当前位置
        private float v;         // 当前速度
        private float target;

        Spring(float damping, float response) {
            this(damping, (float) Math.pow(2.0 * Math.PI / Math.max(0.02f, response), 2), true);
        }

        /**
         * 直接给刚度。miuix 的 Compose {@code spring(dampingRatio, stiffness)} 用这种写法，
         * 例如开关位移 {@code spring(0.7, 987)}、轨道换色 {@code spring(0.99, 438.6)}。
         * 和 {@link #Spring(float, float)} 是同一个物理模型，只是参数化方式不同。
         */
        static Spring ofStiffness(float damping, float stiffness, float initial) {
            Spring s = new Spring(damping, stiffness, true);
            s.x = initial;
            s.target = initial;
            return s;
        }

        private Spring(float damping, float stiffness, boolean rawStiffness) {
            this.k = Math.max(1f, stiffness);
            this.c = 2f * Math.max(0.01f, damping) * (float) Math.sqrt(this.k);
        }

        /** 从静止开始。 */
        static Spring at(float damping, float response, float initial) {
            Spring s = new Spring(damping, response);
            s.x = initial;
            s.target = initial;
            return s;
        }

        float value() { return x; }

        /** 把当前速度保留下来再改目标 —— 连续打断不跳变，这是弹簧相对补间的核心优势。 */
        void to(float next) { target = next; }

        /** 带着外部速度改目标（例如手指抬起时的滑动速度，px/s）。 */
        void to(float next, float velocityPxPerSec) {
            target = next;
            v = velocityPxPerSec;
        }

        /** @return true 表示还在动，需要继续下一帧。 */
        boolean advance(float dtSec) {
            if (dtSec <= 0f) return true;
            float dt = Math.min(dtSec, 0.032f);
            float a = -k * (x - target) - c * v;
            v += a * dt;
            x += v * dt;
            // 收敛判据：位置与速度都足够小。0.5 px / 5 px·s⁻¹ 在 3x 屏上不可见。
            return Math.abs(x - target) > 0.5f || Math.abs(v) > 5f;
        }

        void settle() { x = target; v = 0f; }
    }

    /**
     * 把弹簧绑到任意 float 属性上，用 ValueAnimator 当帧时钟（它比自建 Choreographer
     * 更省事，且能自动在 View detach 时取消）。
     *
     * <p>返回的对象可以立刻再调 {@link Driver#to} 打断 —— 当前速度会带过去。
     */
    static final class Driver {

        interface Sink { void onValue(float value); }

        private final Spring spring;
        private final Sink sink;
        private ValueAnimator clock;
        private long lastNs;

        Driver(float damping, float response, float initial, Sink sink) {
            this.spring = Spring.at(damping, response, initial);
            this.sink = sink;
        }

        Driver(Spring spring, Sink sink) {
            this.spring = spring;
            this.sink = sink;
        }

        float value() { return spring.value(); }

        void to(float target) {
            spring.to(target);
            start();
        }

        void to(float target, float velocityPxPerSec) {
            spring.to(target, velocityPxPerSec);
            start();
        }

        private void start() {
            if (clock != null && clock.isRunning()) return;
            if (clock == null) {
                clock = ValueAnimator.ofFloat(0f, 1f);
                clock.setDuration(1L);
                clock.setRepeatCount(ValueAnimator.INFINITE);
                clock.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        long now = System.nanoTime();
                        float dt = lastNs == 0L ? 0.016f : (now - lastNs) / 1_000_000_000f;
                        lastNs = now;
                        boolean moving = spring.advance(dt);
                        sink.onValue(spring.value());
                        if (!moving) {
                            spring.settle();
                            sink.onValue(spring.value());
                            a.cancel();
                            lastNs = 0L;
                        }
                    }
                });
            }
            lastNs = 0L;
            clock.start();
        }

        void cancel() {
            if (clock != null) { clock.cancel(); lastNs = 0L; }
        }
    }

    /* ------------------------------------------------------------------ 便捷入口 */

    /**
     * 弹簧驱动一个 float 属性。{@code setter} 每帧收到新值。
     *
     * <p>返回 Driver，调用方可以持有它以便后续 {@link Driver#to} 打断。
     */
    static Driver drive(float damping, float response, float initial, Driver.Sink setter) {
        return new Driver(damping, response, initial, setter);
    }

    /** 开关滑块横向移动：弹簧 (0.9, 0.3)。 */
    static Driver driveToggle(float initial, Driver.Sink setter) {
        return new Driver(D_STANDARD, R_QUICK, initial, setter);
    }

    /** 拖动松手回位：弹簧 (1.0, 0.2)，无过冲且极快。 */
    static Driver driveRelease(float initial, Driver.Sink setter) {
        return new Driver(D_NONE, R_INSTANT, initial, setter);
    }

    /** 页面 / 指示器过渡：弹簧 (0.9, 0.38)。 */
    static Driver drivePage(float initial, Driver.Sink setter) {
        return new Driver(D_STANDARD, R_PAGE, initial, setter);
    }

    /**
     * 纯补间（颜色 / 透明度）。不走弹簧 —— 颜色的过冲看起来像闪烁。
     *
     * @return 已启动的 ValueAnimator，调用方可 cancel。
     */
    static ValueAnimator tint(float from, float to, long durationMs,
                              TimeInterpolator easing, final Driver.Sink setter) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(durationMs);
        if (easing != null) a.setInterpolator(easing);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator anim) {
                setter.onValue((Float) anim.getAnimatedValue());
            }
        });
        a.start();
        return a;
    }

    /** 颜色补间，默认 150 ms + DECELERATE。 */
    static ValueAnimator tint(float from, float to, Driver.Sink setter) {
        return tint(from, to, DUR_TINT, DECELERATE, setter);
    }

    /**
     * 按压缩放。进 90 ms 线性压下，出用弹簧 —— 这是"手感"最容易被感知的一处。
     *
     * <p>不要用 {@code View.animate().scaleX()} 做回弹：那是补间，松手快时看不出回弹，
     * 松手慢时又拖沓。弹簧的初速度来自当前值，所以两种节奏都对。
     */
    static void press(final android.view.View v, boolean down, final float pressedScale) {
        if (v == null) return;
        float from = v.getScaleX();
        float to = down ? pressedScale : 1f;
        if (down) {
            v.animate().scaleX(to).scaleY(to)
                    .setDuration(DUR_PRESS)
                    .setInterpolator(STANDARD)
                    .start();
        } else {
            v.animate().scaleX(to).scaleY(to)
                    .setDuration(DUR_MOVE)
                    .setInterpolator(curve(0.34f, 1.28f, 0.64f, 1f))
                    .start();
        }
    }
}
