package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Attaches the floating entry point to the map's own window.
 *
 * <h3>Why a decor-level overlay</h3>
 * The module cannot add a real window: an integrated LSPatch build has no
 * {@code SYSTEM_ALERT_WINDOW} grant, and asking the user for one would defeat the point of
 * an embedded build. Instead the layer is added to the Activity's content root, which keeps
 * it inside the app's own window -- no extra permission, and it disappears together with the
 * map, which is exactly the "inside the app" behaviour we want.
 *
 * <h3>Attach timing</h3>
 * The map's root is built late and rebuilt on theme changes, so a single
 * {@code onResume} hook is not enough. The controller watches for the first moment the
 * content view has a parent with non-zero size, then inserts exactly one layer per Activity.
 * A {@link WeakHashMap} keyed by root view prevents duplicates across rebuilds.
 */
public final class FloatingController {

    private static final String TAG = "MapCleanFloat";

    /** Root views we already decorated. Weak so a rebuilt tree can be re-decorated. */
    private static final Set<ViewGroup> attached = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<ViewGroup, Boolean>()));

    /** Live layers, so a theme change can retint them without rebuilding. */
    private static final Set<Layer> layers = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Layer, Boolean>()));

    /**
     * Every Activity the lifecycle hooks accepted, so "turn it back on" can re-attach at once
     * instead of waiting for the next resume. Weak: a finished Activity must not be kept alive.
     */
    private static final Set<Activity> hosts = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    /**
     * Activities whose window we already watch with an {@code OnGlobalLayoutListener}.
     *
     * <p>Weak and one-shot per Activity: the listener lives exactly as long as the window it is
     * registered on, so this only prevents registering the same watch twice.
     */
    private static final Set<Activity> watching = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    /**
     * ★ 2.3.1：哨兵每次 layout 都可能想补挂一次，这里给每个 Activity 记一个“上次尝试时刻”，
     * 250 ms 内不重复尝试。
     *
     * <p>为什么必须有这个闸门：{@link #watchContentRoot} 挂在 {@code decor} 上，
     * {@code onGlobalLayout} 是**每次布局**都回调（拖动面板/软键盘/卡片滑动时一帧一次）。
     * 补挂失败的情形是真实存在的 —— {@code contentRoot()} 已经量到尺寸但
     * {@code host.isAttachedToWindow()} 还是 false、{@code isEnabled()} 关着、
     * Activity 正在 finish。没有节流的话每一次布局都会 {@code decor.post()} 一个
     * {@code attach()} 任务，一帧一个 Runnable，主线程消息队列被这些注定失败的重试灌满，
     * 表现就是“滑一下卡一下”。有节流后最坏 4 次/秒，且不会随帧率放大。
     */
    private static final java.util.Map<Activity, Long> watchAttempt =
            Collections.synchronizedMap(new WeakHashMap<Activity, Long>());

    /** 同一个 Activity 两次补挂尝试的最小间隔。 */
    private static final long WATCH_RETRY_MS = 250L;

    /**
     * The 悬浮窗 switch, resolved from storage on first use.
     *
     * <p>{@code null} means "not read yet". It is a tri-state on purpose: {@link #setEnabled}
     * must be able to pin the value without the lazy read overwriting it on the next attach.
     */
    private static volatile Boolean enabled;

    /** Set by {@link #setEnabled(boolean)} when turning on, so the ball shows up right away. */
    private static volatile boolean revealOnAttach;

    /**
     * ★ 2.3.0：**当前唯一的浮层宿主 Activity**。
     *
     * <p>腾讯地图的首个 Activity 是隐私页 {@code WelcomeActivity}。用户点「同意并继续」后它
     * {@code finish()}，挂在其 DecorView 上的浮层跟着窗口一起消失；主地图 Activity 起来时
     * 旧代码什么都不做（{@code onActivityResumed} 只认「这个 root 还没挂过」），于是球再也不出现。
     * 真机日志：
     * <pre>
     *   floating layer attached to com.tencent.map.WelcomeActivity
     *   ... 用户点同意 ...
     *   (此后没有任何 attach 记录)
     * </pre>
     *
     * <p>所以记一个「谁现在是宿主」：新 Activity 接管时先把旧宿主摘掉，同一时刻只留一个浮层。
     */
    private static volatile Activity currentHost;

    private FloatingController() {}

    /** A ball + panel pair living inside one Activity. */
    private static final class Layer {
        final Activity activity;
        final FloatingBall ball;
        final FloatingPanel panel;
        final FrameLayout host;
        /** The Activity content root the host lives in: the coordinate space the orb parks in. */
        final ViewGroup root;
        boolean dark;
        /**
         * True while the host covers the whole content root because the sheet is open.
         *
         * <p>The host is orb-sized the rest of the time (see {@code attach}): a full-screen
         * transparent view above 高德's map SurfaceView changes the composition path and was
         * measured as a real frame-time regression.
         */
        boolean expanded;

        Layer(Activity activity, FloatingBall ball, FloatingPanel panel, FrameLayout host,
              ViewGroup root, boolean dark) {
            this.activity = activity;
            this.ball = ball;
            this.panel = panel;
            this.host = host;
            this.root = root;
            this.dark = dark;
        }
    }

    /**
     * Turn the overlay on or off, and make the change visible immediately.
     *
     * <p>Off: every live layer is removed from its Activity's content root on the UI thread, so
     * the orb and the sheet are gone before the click that asked for it returns. On: the
     * Activities the lifecycle hooks have seen are re-attached right away, with the first
     * auto-hide skipped so the user actually sees the orb come back rather than a thin tab.
     *
     * <p>Idempotent: {@link #attach} keeps its own {@code attached} set, so a redundant
     * {@code setEnabled(true)} cannot add a second orb.
     */
    public static void setEnabled(boolean value) {
        // 双保险：模块模式下永远不进入「挂载」分支（关闭分支照常执行，用于清理残留）。
        if (value) {
            try {
                if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
                    Log.i(TAG, "event=floating_skipped reason=module_mode via=setEnabled");
                    return;
                }
            } catch (Throwable ignored) {
                return;
            }
        }
        enabled = Boolean.valueOf(value);
        if (value) {
            revealOnAttach = true;
            reattach();
        } else {
            detachAll();
        }
        Log.i(TAG, "floating " + (value ? "enabled" : "disabled"));
    }

    /**
     * The persisted switch, loaded once from {@link FloatingConfig}.
     *
     * <p>Read lazily rather than at class-init: the controller is first touched from
     * {@code Application.onCreate}, which can be before the module's preferences are reachable.
     */
    /**
     * 服务绑定后重新判定一次总开关。
     *
     * <p>为什么需要：{@link #isEnabled()} 会缓存第一次读到的值，而那次读发生在进程刚起来、
     * 框架服务还没绑定的时候 —— 那时读不到 {@code float_enabled}，只能回落到默认值（内置版 = 开）。
     * 结果就是「用户明明关掉了悬浮窗，重开 App 又出现」。服务一绑定，这里把缓存丢掉重判一次。
     */
    public static void refreshEnabled() {
        try {
            enabled = null;
            boolean v = isEnabled();
            Log.i(TAG, "event=float_enabled_refreshed value=" + v);
            if (v) reattach(); else detachAll();
        } catch (Throwable t) {
            Log.w(TAG, "refreshEnabled failed: " + t.getClass().getSimpleName());
        }
    }

    public static boolean isEnabled() {
        Boolean current = enabled;
        if (current == null) {
            boolean loaded;
            try {
                loaded = FloatingConfig.floatEnabled();
            } catch (Throwable t) {
                // Cannot read the switch -> keep the orb. Losing the only entry point in the
                // 免 root build is far worse than an unwanted orb.
                loaded = true;
            }
            current = Boolean.valueOf(loaded);
            enabled = current;
        }
        return current.booleanValue();
    }

    /** Remove every live layer; safe from any thread. */
    private static void detachAll() {
        final Layer[] snapshot;
        synchronized (layers) {
            snapshot = layers.toArray(new Layer[0]);
            layers.clear();
        }
        currentHost = null;
        for (final Layer layer : snapshot) {
            Runnable task = new Runnable() {
                @Override public void run() {
                    FrameLayout host = layer.host;
                    try {
                        if (host.getParent() instanceof ViewGroup) {
                            ViewGroup parent = (ViewGroup) host.getParent();
                            parent.removeView(host);
                            attached.remove(parent);
                        }
                        // Drop the ball and the sheet too, so nothing keeps a callback alive.
                        host.removeAllViews();
                    } catch (Throwable t) {
                        Log.w(TAG, "detach failed: " + t.getClass().getName());
                    }
                }
            };
            // Activity#runOnUiThread, not View#post: a detached View's post() is queued until it
            // is attached again, which never happens once we are removing it.
            try {
                layer.activity.runOnUiThread(task);
            } catch (Throwable t) {
                task.run();
            }
        }
        if (snapshot.length > 0) Log.i(TAG, "floating layers removed: " + snapshot.length);
    }

    /**
     * ★ 2.3.0：宿主 Activity 正在销毁 —— **先摘层，再让它走**。
     *
     * <p>腾讯地图 {@code WelcomeActivity.finish()} 那一刻，挂在其 content root 上的
     * {@code host} 还活着；不主动 removeView，窗口与 View 的引用链会在
     * {@code WindowManager} 那边留下一条 {@code WindowLeaked}，而 {@code attached} 里还记着
     * 那个 root，重建同名 Activity 时会被去重闸门误挡。
     *
     * <p>必须在 {@code onDestroy} 里同步做完：{@code post()} 到已销毁窗口的队列不会再跑。
     * 主线程直接执行，非主线程退回 {@code runOnUiThread}（那时窗口已经不可靠，尽力而为）。
     */
    static void detachFor(final Activity activity, final String reason) {
        if (activity == null) return;
        // ★ 2.3.1：Activity 真的销毁了才清哨兵登记 —— 这个登记是“这扇窗口已经挂过监听”的
        //   去重表，用的是 WeakHashMap，但 key 只有在没有其它强引用时才回收；Activity 进
        //   回退栈后长时间存活，探测表会跟着窗口一起长。销毁时同步摘掉，语义也正确：
        //   下一个复用的 Activity 实例是新的对象，本来就该重新挂一次。
        watching.remove(activity);
        watchAttempt.remove(activity);
        final Layer layer = layerOf(activity);
        if (layer == null) {
            synchronized (hosts) { hosts.remove(activity); }
            if (currentHost == activity) currentHost = null;
            return;
        }
        removeLayer(layer, reason);
    }

    /** Remove one layer right now: main thread inline, otherwise hop to it. */
    private static void removeLayer(final Layer layer, final String reason) {
        if (layer == null) return;
        Runnable task = new Runnable() {
            @Override public void run() {
                synchronized (layers) { layers.remove(layer); }
                attached.remove(layer.root);
                synchronized (hosts) { hosts.remove(layer.activity); }
                if (currentHost == layer.activity) currentHost = null;
                try {
                    if (layer.host.getParent() instanceof ViewGroup) {
                        ((ViewGroup) layer.host.getParent()).removeView(layer.host);
                    }
                    layer.host.removeAllViews();
                } catch (Throwable t) {
                    Log.w(TAG, "float detach failed: " + t.getClass().getName());
                }
                Log.i(TAG, "event=float_detach reason=" + reason
                        + " activity=" + simpleName(layer.activity));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            try {
                layer.activity.runOnUiThread(task);
            } catch (Throwable t) {
                task.run();
            }
        }
    }

    /**
     * ★ 2.3.0：把 {@code activity} 立为**唯一**浮层宿主，先摘掉其它还活着的层。
     *
     * <p>这就是「换成新 Activity ⇒ 重新挂载」与「不能每次 resume 叠一层」的同一条闸门：
     * 同一时刻只有一个 {@link #currentHost}，同一棵 root 只会被 {@code attached} 放行一次。
     * 旧宿主若已经 finish/destroy/没有可见窗口，它的层在这里被同步摘掉；若它仍然可见
     * （高德 17.02 的双 Activity 边界：resume 的那个和真正绘制窗口的那个不是同一个），
     * 摘掉也没什么可丢的 —— 用户看的是刚 resume 的这一屏。
     */
    private static void adoptAsHost(final Activity activity) {
        if (activity == null) return;
        if (currentHost == activity && layerOf(activity) != null) return;
        final Layer[] snapshot;
        synchronized (layers) {
            snapshot = layers.toArray(new Layer[0]);
        }
        for (Layer other : snapshot) {
            if (other == null || other.activity == activity) continue;
            removeLayer(other, "host_switch");
        }
        Activity previous = currentHost;
        currentHost = activity;
        if (previous != activity) {
            Log.i(TAG, "event=float_host switch from=" + simpleName(previous)
                    + " to=" + simpleName(activity));
        }
    }

    /**
     * ★ 2.3.0：给这扇窗口挂一个「层还在不在」的哨兵。
     *
     * <p>为什么不能用生命周期回调代替：腾讯地图首启是**同一个 Activity 换内容**
     * （隐私页 → 主地图），走的是再调 {@code setContentView()}；
     * {@code PhoneWindow.setContentView()} 里那句 {@code mContentParent.removeAllViews()}
     * 会把我们的 host 从树上摘掉，而 Activity 的 {@code onResume} 不会再发一次。
     * 真机证据（2.3.0 修复前）：
     * <pre>
     *   12:54:39.311 floating layer attached to com.tencent.map.WelcomeActivity
     *   —— 用户点「同意并继续」——
     *   (再无任何 attach 记录；点球没反应，视图树里也没有 FloatingBall)
     * </pre>
     *
     * <p>哨兵只做一件事：每次 layout 时看一眼「这个 Activity 的层还在树上吗」，不在就补挂。
     * 判断成本是一次集合查找，挂载走 {@link #attach(Activity)} 的去重闸门，重复调用无副作用。
     */
    private static void watchContentRoot(final Activity activity) {
        if (activity == null) return;
        if (!watching.add(activity)) return;
        try {
            final View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            try {
                                Layer layer = layerOf(activity);
                                if (layer != null && layer.host.isAttachedToWindow()) return;
                                if (!isEnabled()) return;
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                ViewGroup root = contentRoot(activity);
                                if (root == null || !root.isAttachedToWindow()
                                        || root.getWidth() <= 0 || root.getHeight() <= 0) return;
                                // ★ 2.3.1（性能）：节流。见 watchAttempt 的注释 ——
                                //   没有这一句，一次布局就可能压一个 attach() 到主线程队列。
                                long now = android.os.SystemClock.uptimeMillis();
                                Long last = watchAttempt.get(activity);
                                if (last != null && now - last.longValue() < WATCH_RETRY_MS) return;
                                watchAttempt.put(activity, Long.valueOf(now));
                                decor.post(new Runnable() {
                                    @Override public void run() { attach(activity); }
                                });
                            } catch (Throwable t) {
                                // 一次异常不能把 layout 拖垮：摘掉哨兵，等下一次 resume 重挂。
                                Log.w(TAG, "float watch failed: " + t.getClass().getName());
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "float watch unavailable: " + t.getClass().getName());
        }
    }

    private static String simpleName(Activity activity) {
        if (activity == null) return "null";
        try {
            return activity.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Public recovery entry: re-attach the orb to every remembered window. */
    public static void reattachAll() { reattach(); }

    /** Re-attach to every Activity we know about; safe from any thread. */
    private static void reattach() {
        final Activity[] snapshot;
        synchronized (hosts) {
            snapshot = hosts.toArray(new Activity[0]);
        }
        if (snapshot.length == 0) {
            // Diagnostic: "turn it back on" with nothing to attach to looks like a dead switch.
            // FloatingHooks.remember() is what keeps this list populated while the overlay is off.
            Log.i(TAG, "reattach: no remembered activities yet");
        }
        for (final Activity activity : snapshot) {
            if (activity == null) continue;
            try {
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 「把球打开」要恢复的是**每一扇记住过的窗口**，不是接管：
                        // 这里不走 adoptAsHost，否则多窗口设备上会把先挂的那个摘掉。
                        attach(activity, false);
                        reveal(activity);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "reattach failed: " + t.getClass().getName());
            }
        }
    }

    /** Pull every orb of this Activity back into view (it may be parked as a grab tab). */
    private static void reveal(Activity activity) {
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity != activity) continue;
                try {
                    int spaceW = layer.root.getWidth();
                    if (spaceW > 0) layer.ball.reveal(spaceW);
                } catch (Throwable t) {
                    // best effort only
                }
            }
        }
    }

    /**
     * Record an Activity as a window the overlay may use, without attaching anything.
     *
     * <p>{@link #attach} already does this, but it is only reached once the Activity owns a
     * visible window. With the overlay switched <b>off</b> that never happens, so
     * {@code hosts} stayed empty -- and {@link #setEnabled(boolean)} then had nothing to
     * re-attach to, which makes "turn the floating window back on" look broken until the next
     * cold start. Recording here is cheap and has no side effects.
     */
    static void remember(Activity activity) {
        if (activity != null) hosts.add(activity);
    }

    /**
     * The layer of one Activity, or null.
     *
     * <p>Named {@code layerOf} rather than {@code find} because a sibling change on this file
     * removed a same-purpose helper; keeping the name distinct avoids a silent duplicate.
     */
    private static Layer layerOf(Activity activity) {
        if (activity == null) return null;
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity == activity) return layer;
            }
        }
        return null;
    }

    /**
     * Grow the host to cover the content root, or shrink it back to the orb's footprint.
     *
     * <p>The sheet is a MATCH_PARENT child of the host and needs the whole window (plus the
     * scrim behind it), so the host is only full-screen for as long as the sheet is up. This
     * is the compromise the composition measurement asked for: the map keeps its SurfaceView
     * path except while the user is actually looking at the sheet.
     */
    private static void setExpanded(final Layer layer, boolean expanded) {
        if (layer.expanded == expanded) return;
        layer.expanded = expanded;
        ViewGroup.LayoutParams lp = layer.host.getLayoutParams();
        if (lp == null) return;
        lp.width = expanded ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = expanded ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        layer.host.setLayoutParams(lp);
        if (expanded) {
            // Pinned to the root's origin; the orb is placed inside it from now on.
            layer.host.setX(0f);
            layer.host.setY(0f);
        }
        // Re-seat the orb so it stays exactly where it was on screen in both modes.
        layer.ball.moveTo(layer.ball.centerX(), layer.ball.centerY(), layer.root.getWidth());
        // Log the requested layout params, not the current width: the layout pass for this
        // change has not run yet, so getWidth() would still report the previous mode.
        Log.i(TAG, "host expanded=" + expanded
                + " lp=" + lp.width + "x" + lp.height
                + " space=" + layer.root.getWidth() + "x" + layer.root.getHeight());
    }

    /**
     * Open the sheet, growing the host first.
     *
     * <p>Every entry point goes through here: the orb tap, {@link #openPanelNear}, and the
     * rescue path. Opening the sheet while the host is still orb-sized would measure the
     * MATCH_PARENT card against a 62dp parent for one frame.
     */
    private static void openSheet(final Layer layer) {
        if (layer == null) return;
        setExpanded(layer, true);
        layer.host.requestLayout();
        try {
            layer.panel.open();
        } catch (Throwable t) {
            Log.w(TAG, "openSheet failed: " + t.getClass().getName());
        }
    }

    /**
     * Open the sheet of whichever layer lives in the same window as {@code anyViewInWindow}.
     *
     * <p>This is the Activity-free entry: the layer's host is a child of the Activity's content
     * root, so {@code host.getRootView()} is the same object as the pressed tab's
     * {@code getRootView()}. Matching on that identity sidesteps the whole "which Activity owns
     * the window" problem (on 17.02 the resumed Activity and the window owner are different).
     */
    public static boolean openPanelNear(View anyViewInWindow) {
        if (anyViewInWindow == null) return false;
        final View root = anyViewInWindow.getRootView();
        if (root == null || !root.isAttachedToWindow()) return false;
        Layer target = null;
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer == null || layer.host == null) continue;
                if (layer.host.getRootView() == root) { target = layer; break; }
            }
            if (target == null) {
                // Fallback: the only attached layer (single-window builds).
                for (Layer layer : layers) {
                    if (layer != null && layer.host != null && layer.host.isAttachedToWindow()) {
                        target = layer;
                        break;
                    }
                }
            }
        }
        if (target == null) {
            // Nothing is attached to this window yet. That is the normal state when the
            // lifecycle hooks could not attach (no Activity of ours owns a visible window
            // early on), so use the view that is definitely in a real window to find its
            // owner and attach there. Without this the orb can only be opened from a code
            // path that already holds an Activity.
            final Activity owner = activityOf(anyViewInWindow);
            if (owner == null) return false;
            Runnable rescue = new Runnable() {
                @Override public void run() {
                    attach(owner);
                    Layer created = layerOf(owner);
                    if (created == null) return;
                    try {
                        openSheet(created);
                    } catch (Throwable t) {
                        Log.w(TAG, "openPanelNear failed: " + t.getClass().getName());
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                rescue.run();
                return layerOf(owner) != null;
            }
            anyViewInWindow.post(rescue);
            return true;
        }
        final Layer open = target;
        open.host.post(new Runnable() {
            @Override public void run() {
                try {
                    openSheet(open);
                } catch (Throwable t) {
                    Log.w(TAG, "openPanelNear failed: " + t.getClass().getName());
                }
            }
        });
        return true;
    }
    /**
     * Insert the layer into an Activity if it is not there yet.
     *
     * Safe to call repeatedly (onResume, onConfigurationChanged, after a layout pass); the
     * {@code attached} set makes it idempotent.
     *
     * <p>★ 2.3.0：这条入口同时是**宿主移交**：新 Activity 接管时旧宿主的层会被摘掉，
     * 所以「用户点完首启隐私页 → 主地图起来」必然重新挂载，且不会叠层。
     */
    public static void attach(final Activity activity) {
        attach(activity, true);
    }

    /**
     * @param exclusiveHost true = 接管为唯一宿主（生命周期路径）；
     *                      false = 只补挂、不动别人（{@link #reattach()} 恢复开关时用）
     */
    private static void attach(final Activity activity, boolean exclusiveHost) {
        if (activity == null) return;
        // 双保险：模块模式（root/LSPosed）永不挂载悬浮层，即使有人从别处调进来。
        try {
            if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) return;
        } catch (Throwable ignored) {
            return;
        }
        // Record the Activity before the switch is consulted: when the user turns the overlay
        // back on, reattach() needs to know which windows to put it into, and by then the
        // switch has been off for the whole session.
        hosts.add(activity);
        // ★ 2.3.0：盯住这扇窗口的每一次 layout。腾讯地图首启是「同一个 Activity 换内容」——
        //   隐私页 → 主地图走的是再调一次 setContentView()，而 PhoneWindow.setContentView()
        //   会 mContentParent.removeAllViews()，把我们的 host 连同旧页面一起清掉；
        //   这不触发任何 Activity 生命周期回调，所以只靠 onResume 永远补不回来。
        watchContentRoot(activity);
        if (!isEnabled() || activity.isFinishing()) return;
        final ViewGroup root = contentRoot(activity);
        if (root == null) {
            Log.w(TAG, "no content root for " + activity.getClass().getName());
            return;
        }
        // A root that is not attached to a window, or has no size, belongs to an Activity
        // that is not being composited. 高德 17.02 keeps NewMapActivity in exactly that
        // state: attaching there put the orb into a tree nobody draws, which is why the user
        // saw no floating ball at all. Give up on this Activity instead of retrying -- the
        // retry used to loop on a root that never gains a size -- and let the next resume
        // (the Activity that really owns the visible window) do the attach.
        if (!root.isAttachedToWindow() || root.getWidth() <= 0 || root.getHeight() <= 0) {
            Log.i(TAG, "skip attach, no visible root: " + activity.getClass().getSimpleName());
            return;
        }
        // 只在真的要挂的时候才接管：没有可见窗口的 Activity 不该把上一个宿主挤掉
        // （高德的 resume 无窗口边界就是靠这条保住的）。
        if (exclusiveHost) adoptAsHost(activity);
        // ★ 2.3.0：去重闸门必须能分辨「挂过」和「还挂着」。
        //   宿主 setContentView() 会把我们的 host 一起 removeAllViews() 掉，但 content root
        //   这个对象没变 —— 只认 root 的集合从此永久误判「已经挂过」，球再也回不来。
        Layer existing = layerOf(activity);
        if (existing != null && existing.host.isAttachedToWindow()) return;
        if (existing != null) {
            removeLayer(existing, "stale_root");
        }
        if (!attached.add(root)) {
            // 记录还在、层已经不在树上：清掉记录，重建一次。
            attached.remove(root);
            if (!attached.add(root)) return;
            Log.i(TAG, "event=float_reattach reason=root_replaced activity="
                    + activity.getClass().getName());
        }

        try {
            boolean dark = isDark(activity);
            final FloatingPanel panel = new FloatingPanel(activity, dark);
            panel.setVisibility(View.GONE);

            final FloatingBall ball = new FloatingBall(activity);
            // 自绘 View 不在无障碍树里；给个描述既利于读屏，也方便自动化验证时定位这颗球。
            try { ball.setContentDescription("MapAdKiller 设置"); } catch (Throwable ignored) { }
            applyPalette(ball, dark);
            // The 外观 page previews size/opacity on this very orb, so the sheet needs the
            // reference; the ball reads the persisted values in its own constructor.
            panel.setBall(ball);

            // ---- host: only as big as the orb ------------------------------------
            // It used to be MATCH_PARENT, i.e. a full-screen transparent FrameLayout on top
            // of the map. 高德 draws the map in its own SurfaceView, and a full-screen view
            // above it changes the composition path -- measured p99 frame gap 82.77 ms with
            // 6.1% dropped frames while every rendering stage stayed under 10 ms. So the host
            // wraps the orb and is grown to full size only while the sheet is open (a modal
            // sheet plus its scrim needs the whole window).
            final FrameLayout host = new FrameLayout(activity);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            // Nothing to draw, no hardware layer, and never a touch target of its own: a
            // WRAP_CONTENT host that painted or intercepted would keep adding per-frame work,
            // which is the exact cost this change removes. FrameLayout's default
            // onInterceptTouchEvent already returns false, so only the orb is clickable.
            host.setBackground(null);
            host.setWillNotDraw(true);
            host.setLayerType(View.LAYER_TYPE_NONE, null);
            host.setClickable(false);
            host.setFocusable(false);

            host.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            host.addView(ball, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            host.setVisibility(View.VISIBLE);

            final Layer layer = new Layer(activity, ball, panel, host, root, dark);

            // The orb keeps its centre in content-root coordinates; this turns that centre
            // into a position. Wrapped: the host travels with the orb. Expanded: the host is
            // pinned at (0,0) and the orb moves inside it.
            ball.setFrame(new FloatingBall.Frame() {
                @Override public void place(float cx, float cy) {
                    if (layer.expanded) {
                        ball.setX(cx - ball.getWidth() / 2f);
                        ball.setY(cy - ball.getHeight() / 2f);
                    } else {
                        // The host wraps the orb, so host size == orb size; using the orb's own
                        // width keeps this independent of a layout pass that may not have run
                        // yet after a resize.
                        ball.setX(0f);
                        ball.setY(0f);
                        host.setX(cx - ball.getWidth() / 2f);
                        host.setY(cy - ball.getHeight() / 2f);
                    }
                }
                @Override public int spaceWidth() { return root.getWidth(); }
                @Override public int spaceHeight() { return root.getHeight(); }
            });

            panel.setOnClose(new Runnable() {
                @Override public void run() {
                    // Returning from the sheet re-parks the orb instead of leaving it out. The
                    // delay lets the close animation finish before the host shrinks -- shrinking
                    // first would resize the sheet mid-animation.
                    ball.postDelayed(new Runnable() {
                        @Override public void run() {
                            // Re-opened during the close animation: shrinking now would resize
                            // the sheet the user is looking at.
                            if (layer.panel.isOpen()) return;
                            setExpanded(layer, false);
                            ball.moveTo(ball.centerX(), ball.centerY(), root.getWidth());
                            if (!ball.isHidden()) ball.hide(root.getWidth());
                        }
                    }, 260L);
                }
            });
            ball.setListener(new FloatingBall.Listener() {
                @Override public void onTap() {
                    openSheet(layer);
                }
                @Override public void onMoved(int left, int top) {
                    // Persist nothing: the resting edge is recomputed on every settle, and a
                    // stale position would fight a rotation or a split-screen resize.
                }
            });

            root.addView(host, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            layers.add(layer);

            // A rotation or a split-screen resize moves the edges. The signal must be the
            // content root, not the host: the host is orb-sized now and would also fire when
            // the sheet expands it.
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    int w = right - left, h = bottom - top;
                    if (w <= 0 || h <= 0) return;
                    if (w == oldRight - oldLeft && h == oldBottom - oldTop) return;
                    if (!ball.isParked()) return;
                    ball.settle(false, w, h);
                    if (!panel.isOpen()) ball.hide(w);
                }
            });

            // One line so a verification run can prove the host is no longer full screen.
            host.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (host.getWidth() <= 0) return;
                    host.removeOnLayoutChangeListener(this);
                    Log.i(TAG, "floating host size=" + host.getWidth() + "x" + host.getHeight()
                            + " attached=" + host.isAttachedToWindow()
                            + " space=" + root.getWidth() + "x" + root.getHeight());
                }
            });

            // Park it once the real size is known, then auto-hide -- unless this attach is the
            // direct result of the user switching the overlay back on. In that case the orb
            // stays fully visible: an orb that hides 1.4 s after "已开启" reads as "still off".
            final boolean keepVisible = revealOnAttach;
            revealOnAttach = false;
            host.post(new Runnable() {
                @Override public void run() {
                    // The coordinate space is the content root, not the (orb-sized) host.
                    int spaceW = root.getWidth(), spaceH = root.getHeight();
                    if (spaceW <= 0 || spaceH <= 0) return;
                    // Position via moveTo(): setX/setY would leave the orb's internal centre at
                    // (0,0) and settle() would then snap it into the top-left corner.
                    ball.moveTo(spaceW - ball.getWidth() / 2f - UiKit.dp(activity, 4),
                            spaceH * 0.42f, spaceW);
                    ball.settle(false, spaceW, spaceH);
                    if (keepVisible) return;
                    ball.postDelayed(new Runnable() {
                        @Override public void run() { ball.hide(root.getWidth()); }
                    }, 1400L);
                }
            });
            Log.i(TAG, "floating layer attached to " + activity.getClass().getName());
        } catch (Throwable t) {
            attached.remove(root);
            Log.w(TAG, "floating layer failed: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Retint every live layer; called when the host flips between light and dark. */
    public static void refreshTheme(Activity activity) {
        boolean dark = isDark(activity);
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity != activity || layer.dark == dark) continue;
                layer.dark = dark;
                applyPalette(layer.ball, dark);
            }
        }
    }

    private static void applyPalette(FloatingBall ball, boolean dark) {
        ball.setPalette(
                dark ? 0xF21D2027 : 0xF7FFFFFF,
                dark ? 0xFF7AB1FF : 0xFF0A6CF5,
                dark ? 0xFF9CC3FF : 0xFF0A6CF5);
    }

    /**
     * The Activity that owns the window {@code anyViewInWindow} lives in, or null.
     *
     * <p>Same unwrap loop {@code EmbeddedSettings.open} uses: a decor view's context is
     * usually a {@code ContextThemeWrapper} around the Activity, not the Activity itself.
     * Bounded so a pathological context chain cannot spin.
     */
    private static Activity activityOf(View anyViewInWindow) {
        try {
            android.content.Context owner = anyViewInWindow.getContext();
            for (int depth = 0; depth < 16 && owner != null; depth++) {
                if (owner instanceof Activity) return (Activity) owner;
                if (!(owner instanceof android.content.ContextWrapper)) break;
                android.content.Context base =
                        ((android.content.ContextWrapper) owner).getBaseContext();
                if (base == owner) break;
                owner = base;
            }
        } catch (Throwable t) {
            Log.w(TAG, "activity lookup failed: " + t.getClass().getName());
        }
        return null;
    }

    /** The FrameLayout the Activity's content was inflated into. */
    private static ViewGroup contentRoot(Activity activity) {
        try {
            // android.R.id.content is the container the Activity's layout is inflated into.
            // Adding the overlay there keeps it above the map but below dialogs, which is
            // exactly the layer an in-app entry point belongs in.
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) return (ViewGroup) content;
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) return (ViewGroup) decor;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDark(Activity activity) {
        try {
            int mask = activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mask == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
