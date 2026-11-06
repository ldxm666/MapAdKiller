package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.util.concurrent.locks.LockSupport;

import io.github.libxposed.api.XposedInterface;

/**
 * 高德帧率上限（实验）：关 / 60 / 90 / 120，默认关。
 *
 * <h3>Where the cap actually lands, and why</h3>
 * The load-bearing hook is {@code GLMapEngine.OnEGLRenderFrame(int, int)} — the engine's
 * render callback — and the cap is a fixed-cadence {@link LockSupport#parkNanos} hold.
 *
 * <p>Rewriting the engine's own fps arguments cannot work on its own. {@code
 * setRenderFpsWithTimer(IIZ)} substitutes {@code GLMapStaticValue.RENDER_FPS_GESTURE_ACTION}
 * for whatever {@code getAdviseFPS} says <em>while the user is touching the map</em> — i.e.
 * exactly when a frame cap is noticeable. AmapTune measured this on 17.00.0.2005 with a spy:
 * {@code setRenderFps} / {@code setMaxFps} / {@code setMinFps} / {@code setViewRenderFps} /
 * {@code setFpsControllerEnable} are <b>never called</b> during navigation. They are kept as
 * belt-and-braces because other builds and other scenes do call them, and on those a forced
 * value raises a throttled engine up to the chosen target.
 *
 * <h3>The cadence, and the bug it exists to avoid</h3>
 * The next hold is aimed at {@code previous + period}, not at "one period after I woke up".
 * The naive version accumulates {@code parkNanos} overshoot every frame and undershot the
 * 120 stop by ~4 fps (116 measured). Aiming at absolute instants keeps 60 and 120 exact.
 *
 * <h3>What is deliberately NOT hooked</h3>
 * {@code android.view.Surface.setFrameRate}. AmapTune hooked it as a framework-level fallback;
 * 高德 never calls it (measured), and if it ever did the caller would most likely be the in-app
 * video player, where forcing 60 would be a visible defect rather than a frame cap. A hook that
 * cannot fire on this host but can break video is not worth installing.
 */
final class AmapFpsCap {

    /** Advertised in the panel: the slider offers exactly these. */
    static final int DEFAULT_TARGET = 60;
    private static final int MIN_TARGET = 30;
    private static final int MAX_TARGET = 240;

    /** How long a config read is reused before the next one. Keeps the render loop cheap. */
    private static final long TARGET_TTL_MS = 1000L;
    /** Sampling window for {@code event=fps_sample}. */
    private static final long SAMPLE_WINDOW_MS = 1500L;

    private static volatile int cachedTarget;
    private static volatile long cachedAt = -1L;

    private AmapFpsCap() {}

    /** Current cap, or 0 when the feature is off. Read on every render frame. */
    static int target() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - cachedAt < TARGET_TTL_MS) return cachedTarget;
        int value = 0;
        try {
            if (AmapTuneGate.on(Config.K_AMAP_FPS_ON)) {
                int raw = AmapTuneGate.target(
                        Config.getInt(Config.K_AMAP_FPS_TARGET, DEFAULT_TARGET));
                value = raw >= MIN_TARGET && raw <= MAX_TARGET ? raw : DEFAULT_TARGET;
            }
        } catch (Throwable ignored) {
            value = 0;
        }
        cachedTarget = value;
        cachedAt = now;
        return value;
    }

    static void install(ClassLoader cl) {
        Class<?> engine = H.cls(cl, "com.autonavi.jni.ae.gmap.GLMapEngine");
        if (engine == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=fps"
                    + " name=com.autonavi.jni.ae.gmap.GLMapEngine");
            return;
        }

        AmapHookArgs.Value fps = new AmapHookArgs.Value() {
            @Override public Object get() {
                int value = target();
                return value == 0 ? null : Integer.valueOf(value);
            }
        };

        // (engineId, fps) — index 0 is the engine handle. Writing it corrupts rendering.
        H.hookSig(engine, "setMaxFps", "amap_fps_setMaxFps",
                AmapHookArgs.arg(1, fps), int.class, int.class);
        H.hookSig(engine, "setMinFps", "amap_fps_setMinFps",
                AmapHookArgs.arg(1, fps), int.class, int.class);
        H.hookSig(engine, "setRenderFps", "amap_fps_setRenderFps",
                AmapHookArgs.arg(1, fps), int.class, int.class);
        H.hookSig(engine, "setViewRenderFps", "amap_fps_setViewRenderFps",
                AmapHookArgs.arg(1, fps), int.class, int.class);
        // (engineId, fps, enabled): the fps alone is inert when enabled arrives as false.
        H.hookSig(engine, "setRenderFpsWithTimer", "amap_fps_setRenderFpsWithTimer",
                AmapHookArgs.argPlusFlag(1, fps, 2), int.class, int.class, boolean.class);
        // The engine's own controller fights the cap by re-advising fps; pin it off.
        H.hookSig(engine, "setFpsControllerEnable", "amap_fps_setFpsControllerEnable",
                AmapHookArgs.arg(1, new AmapHookArgs.Value() {
                    @Override public Object get() {
                        return target() == 0 ? null : Boolean.FALSE;
                    }
                }), int.class, boolean.class);
        // Auto-drop would remove frames underneath the pacer and make the count unreadable.
        H.hookSig(engine, "OnEGLAutoDropFrame", "amap_fps_OnEGLAutoDropFrame", H.VOID,
                int.class, int.class, boolean.class);
        // What the engine asks the platform for. Return the cap so the intent is consistent.
        H.hookSig(engine, "getAdviseFPS", "amap_fps_getAdviseFPS",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        int value = target();
                        return value == 0 ? chain.proceed() : Integer.valueOf(value);
                    }
                }, int.class);

        // 节流点：引擎自己的 EGL 渲染回调，跑在 GL 线程 {@code GNaviMap-GL-0} 上（实测），
        // 平移地图时 ~106–120 fps。这是唯一一个"每帧都调、又不在主线程"的点。
        H.hookSig(engine, "OnEGLRenderFrame", "amap_fps_OnEGLRenderFrame",
                new Pacer(true, "egl"), int.class, int.class);

        // 只计数、不节流的第二观察点：高德自己的 vsync 桥
        // {@code com.autonavi.jni.ae.gmap.vsync.AChoreographer.doFrame(long)}。
        //
        // 本来把它当主力，实测**不成立**：17.02 平移地图时它的频率只有 9–26 fps，
        // 而且极不规律（它是动画/请求帧那条线，不是渲染循环）。在它上面 park 只会
        // 把动画钉慢、拦不住地图。留着是为了换版本后能从日志看出"这一版是不是改成它驱动了"。
        Class<?> choreographer = H.cls(cl, "com.autonavi.jni.ae.gmap.vsync.AChoreographer");
        if (choreographer == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=fps"
                    + " name=com.autonavi.jni.ae.gmap.vsync.AChoreographer");
        } else {
            H.hookSig(choreographer, "doFrame", "amap_fps_doFrame",
                    new Pacer(false, "choreographer"), long.class);
        }

        Class<?> scene = H.cls(cl, "com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
        if (scene == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=fps"
                    + " name=com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
            return;
        }
        // (sceneBundle, fps) — index 0 is the scene bundle name, not the value.
        H.hookSig(scene, "setMaxRenderFPS", "amap_fps_setMaxRenderFPS",
                AmapHookArgs.arg(1, fps), String.class, int.class);
        H.hookSig(scene, "setMinRenderFPS", "amap_fps_setMinRenderFPS",
                AmapHookArgs.arg(1, fps), String.class, int.class);
        H.hookSig(scene, "setGlobalMaxRenderFPS", "amap_fps_setGlobalMaxRenderFPS",
                AmapHookArgs.arg(0, fps), int.class);
    }

    /**
     * Fixed-cadence hold on a per-frame callback, plus the one-per-window frame counter.
     *
     * <p>One instance per hooked method, so the cadence state is per callback. {@code nextAt == 0}
     * means "not synchronised yet" and is resolved on the first call.
     */
    private static final class Pacer implements XposedInterface.Hooker {

        private final boolean pace;
        private final String source;
        private long nextAt;
        private long windowStart;
        private int frames;
        private int lastReported = -1;
        private boolean threadLogged;
        /** Resolved once: parking on the main thread would freeze the whole UI, see below. */
        private Boolean onMainThread;

        Pacer(boolean pace, String source) {
            this.pace = pace;
            this.source = source;
        }

        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            int target = target();
            long now = System.nanoTime();
            boolean park = pace && target > 0 && !mainThread();

            if (park) {
                long period = 1_000_000_000L / target;
                if (nextAt == 0L || now - nextAt > period * 8L) {
                    // First frame, or we fell far behind (app was paused / another window was
                    // on top). Re-synchronise instead of trying to "catch up" with a burst.
                    nextAt = now;
                }
                long wait = nextAt + period - now;
                if (wait > 0L && wait <= period) {
                    LockSupport.parkNanos(wait);
                    nextAt += period;
                } else if (wait <= 0L) {
                    nextAt = now;              // already late: resync, do not burn a frame
                } else {
                    nextAt += period;          // woke absurdly early: do not hold at all
                }
            } else {
                nextAt = 0L;
            }

            if (!threadLogged) {
                threadLogged = true;
                H.log(Log.INFO, MainHook.TAG, "event=fps_source src=" + source
                        + " thread=" + Thread.currentThread().getName()
                        + " pacing=" + park + " target=" + target);
            }
            sample(target, now);
            return chain.proceed();
        }

        /**
         * Is this callback on the UI thread?
         *
         * <p>Parking the main thread to cap the frame rate would also cap touch dispatch,
         * scrolling and every dialog in the app — the user would feel the whole client slow
         * down, not just the map. So the hold is applied only off the main thread; on the main
         * thread this hook degrades to a counter and says so in the log.
         */
        private boolean mainThread() {
            Boolean cached = onMainThread;
            if (cached != null) return cached.booleanValue();
            boolean value;
            try {
                value = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
            } catch (Throwable error) {
                value = true;                  // unknown: assume the dangerous case
            }
            onMainThread = Boolean.valueOf(value);
            return value;
        }

        /** Real render-callback rate, independent of anything the host reports. */
        private void sample(int target, long now) {
            if (windowStart == 0L) {
                windowStart = now;
                frames = 0;
                return;
            }
            frames++;
            long elapsed = now - windowStart;
            if (elapsed < SAMPLE_WINDOW_MS * 1_000_000L) return;
            int fps = (int) Math.round(frames * 1_000_000_000.0d / elapsed);
            int windowMs = (int) (elapsed / 1_000_000L);
            // With a cap active this line is the feature's own measurement, so it is emitted
            // every window — that is what makes "60 means 60" checkable rather than asserted.
            // With the cap off the hook can still be resident for a session in which the user
            // just flipped the switch, and then a steady number is pure noise: log only when
            // it moves.
            if (target > 0 || fps != lastReported) {
                lastReported = fps;
                H.log(Log.INFO, MainHook.TAG, "event=fps_sample src=" + source
                        + " frames=" + frames + " fps=" + fps + " window_ms=" + windowMs
                        + " target=" + target);
            }
            windowStart = now;
            frames = 0;
        }
    }
}
