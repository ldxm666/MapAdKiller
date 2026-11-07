package io.github.ldxm666.mapclean

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

import java.util.Collections
import java.util.WeakHashMap

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
 * A [WeakHashMap] keyed by root view prevents duplicates across rebuilds.
 *
 * 转译说明：Java 的 `static` 全部进了 [Companion]，对外入口（被其它 .java 直接按
 * `FloatingController.xxx(...)` 调用的那些）标了 `@JvmStatic`，签名一字未改。所有
 * `a == b` 的对象比较都改成 `===`：原版要的就是身份比较，Kotlin 的 `==` 会走 equals。
 */
class FloatingController private constructor() {

    companion object {

        private const val TAG = "MapCleanFloat"

        /** Root views we already decorated. Weak so a rebuilt tree can be re-decorated. */
        private val attached: MutableSet<ViewGroup> = Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>()))

        /** Live layers, so a theme change can retint them without rebuilding. */
        private val layers: MutableSet<Layer> = Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap<Layer, Boolean>()))

        /**
         * Every Activity the lifecycle hooks accepted, so "turn it back on" can re-attach at once
         * instead of waiting for the next resume. Weak: a finished Activity must not be kept alive.
         */
        private val hosts: MutableSet<Activity> = Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))

        /**
         * Activities whose window we already watch with an {@code OnGlobalLayoutListener}.
         *
         * Weak and one-shot per Activity: the listener lives exactly as long as the window it is
         * registered on, so this only prevents registering the same watch twice.
         */
        private val watching: MutableSet<Activity> = Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))

        /**
         * ★ 2.3.1：哨兵每次 layout 都可能想补挂一次，这里给每个 Activity 记一个“上次尝试时刻”，
         * 250 ms 内不重复尝试。
         *
         * 为什么必须有这个闸门：[watchContentRoot] 挂在 `decor` 上，
         * `onGlobalLayout` 是**每次布局**都回调（拖动面板/软键盘/卡片滑动时一帧一次）。
         * 补挂失败的情形是真实存在的 —— `contentRoot()` 已经量到尺寸但
         * `host.isAttachedToWindow()` 还是 false、`isEnabled()` 关着、
         * Activity 正在 finish。没有节流的话每一次布局都会 `decor.post()` 一个
         * `attach()` 任务，一帧一个 Runnable，主线程消息队列被这些注定失败的重试灌满，
         * 表现就是“滑一下卡一下”。有节流后最坏 4 次/秒，且不会随帧率放大。
         */
        private val watchAttempt: MutableMap<Activity, Long> = Collections.synchronizedMap(
                WeakHashMap<Activity, Long>())

        /** 同一个 Activity 两次补挂尝试的最小间隔。 */
        private const val WATCH_RETRY_MS = 250L

        /**
         * The 悬浮窗 switch, resolved from storage on first use.
         *
         * `null` means "not read yet". It is a tri-state on purpose: [setEnabled]
         * must be able to pin the value without the lazy read overwriting it on the next attach.
         */
        @Volatile
        private var enabled: Boolean? = null

        /** Set by [setEnabled] when turning on, so the ball shows up right away. */
        @Volatile
        private var revealOnAttach = false

        /**
         * ★ 2.3.0：**当前唯一的浮层宿主 Activity**。
         *
         * 腾讯地图的首个 Activity 是隐私页 `WelcomeActivity`。用户点「同意并继续」后它
         * `finish()`，挂在其 DecorView 上的浮层跟着窗口一起消失；主地图 Activity 起来时
         * 旧代码什么都不做（`onActivityResumed` 只认「这个 root 还没挂过」），于是球再也不出现。
         * 真机日志：
         * <pre>
         *   floating layer attached to com.tencent.map.WelcomeActivity
         *   ... 用户点同意 ...
         *   (此后没有任何 attach 记录)
         * </pre>
         *
         * 所以记一个「谁现在是宿主」：新 Activity 接管时先把旧宿主摘掉，同一时刻只留一个浮层。
         */
        @Volatile
        private var currentHost: Activity? = null

        /** A ball + panel pair living inside one Activity. */
        private class Layer(
                val activity: Activity,
                val ball: FloatingBall,
                val panel: FloatingPanel,
                val host: FrameLayout,
                /** The Activity content root the host lives in: the coordinate space the orb parks in. */
                val root: ViewGroup,
                var dark: Boolean) {
            /**
             * True while the host covers the whole content root because the sheet is open.
             *
             * 一层全屏透明 view 盖在高德的 SurfaceView 上会改变合成路径，实测是真实的掉帧
             * 回归，所以只有面板真正打开的那段时间才铺满。
             */
            var expanded = false
        }

        /**
         * Turn the overlay on or off, and make the change visible immediately.
         *
         * Off: every live layer is removed from its Activity's content root on the UI thread, so
         * the orb and the sheet are gone before the click that asked for it returns. On: the
         * Activities the lifecycle hooks have seen are re-attached right away, with the first
         * auto-hide skipped so the user actually sees the orb come back rather than a thin tab.
         *
         * Idempotent: [attach] keeps its own `attached` set, so a redundant
         * `setEnabled(true)` cannot add a second orb.
         */
        @JvmStatic
        fun setEnabled(value: Boolean) {
            // 双保险：模块模式下永远不进入「挂载」分支（关闭分支照常执行，用于清理残留）。
            if (value) {
                try {
                    if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
                        Log.i(TAG, "event=floating_skipped reason=module_mode via=setEnabled")
                        return
                    }
                } catch (ignored: Throwable) {
                    return
                }
            }
            enabled = value
            if (value) {
                revealOnAttach = true
                reattach()
            } else {
                // 关掉时把这个一次性标记收回来：否则「开 → 还没挂上又关 → 再开」这条路上，
                // 残留的 true 会让下一次无关的 attach 跳过自动收起，球一直杵在屏幕上。
                revealOnAttach = false
                detachAll()
            }
            Log.i(TAG, "floating " + (if (value) "enabled" else "disabled"))
        }

        /**
         * 服务绑定后重新判定一次总开关。
         *
         * 为什么需要：[isEnabled] 会缓存第一次读到的值，而那次读发生在进程刚起来、
         * 框架服务还没绑定的时候 —— 那时读不到 `float_enabled`，只能回落到默认值（内置版 = 开）。
         * 结果就是「用户明明关掉了悬浮窗，重开 App 又出现」。服务一绑定，这里把缓存丢掉重判一次。
         */
        @JvmStatic
        fun refreshEnabled() {
            try {
                enabled = null
                val v = isEnabled()
                Log.i(TAG, "event=float_enabled_refreshed value=" + v)
                if (v) reattach() else detachAll()
            } catch (t: Throwable) {
                Log.w(TAG, "refreshEnabled failed: " + t.javaClass.simpleName)
            }
        }

        /**
         * The persisted switch, loaded once from [FloatingConfig].
         *
         * Read lazily rather than at class-init: the controller is first touched from
         * `Application.onCreate`, which can be before the module's preferences are reachable.
         */
        @JvmStatic
        fun isEnabled(): Boolean {
            val current = enabled
            if (current != null) return current
            var loaded = true
            try {
                loaded = FloatingConfig.floatEnabled()
            } catch (t: Throwable) {
                // Cannot read the switch -> keep the orb. Losing the only entry point in the
                // 免 root build is far worse than an unwanted orb.
                loaded = true
            }
            enabled = loaded
            return loaded
        }

        /** Remove every live layer; safe from any thread. */
        private fun detachAll() {
            val snapshot: Array<Layer>
            synchronized(layers) {
                snapshot = layers.toTypedArray()
                layers.clear()
            }
            currentHost = null
            for (layer in snapshot) {
                val task = Runnable {
                    val host = layer.host
                    try {
                        val parent = host.parent
                        if (parent is ViewGroup) {
                            parent.removeView(host)
                            attached.remove(parent)
                        }
                        // Drop the ball and the sheet too, so nothing keeps a callback alive.
                        host.removeAllViews()
                    } catch (t: Throwable) {
                        Log.w(TAG, "detach failed: " + t.javaClass.name)
                    }
                }
                // Activity#runOnUiThread, not View#post: a detached View's post() is queued until it
                // is attached again, which never happens once we are removing it.
                try {
                    layer.activity.runOnUiThread(task)
                } catch (t: Throwable) {
                    task.run()
                }
            }
            if (snapshot.isNotEmpty()) Log.i(TAG, "floating layers removed: " + snapshot.size)
        }

        /**
         * ★ 2.3.0：宿主 Activity 正在销毁 —— **先摘层，再让它走**。
         *
         * 腾讯地图 `WelcomeActivity.finish()` 那一刻，挂在其 content root 上的
         * `host` 还活着；不主动 removeView，窗口与 View 的引用链会在
         * `WindowManager` 那边留下一条 `WindowLeaked`，而 `attached` 里还记着
         * 那个 root，重建同名 Activity 时会被去重闸门误挡。
         *
         * 必须在 `onDestroy` 里同步做完：`post()` 到已销毁窗口的队列不会再跑。
         * 主线程直接执行，非主线程退回 `runOnUiThread`（那时窗口已经不可靠，尽力而为）。
         */
        @JvmStatic
        fun detachFor(activity: Activity?, reason: String) {
            if (activity == null) return
            // ★ 2.3.1：Activity 真的销毁了才清哨兵登记 —— 这个登记是“这扇窗口已经挂过监听”的
            //   去重表，用的是 WeakHashMap，但 key 只有在没有其它强引用时才回收；Activity 进
            //   回退栈后长时间存活，探测表会跟着窗口一起长。销毁时同步摘掉，语义也正确：
            //   下一个复用的 Activity 实例是新的对象，本来就该重新挂一次。
            watching.remove(activity)
            watchAttempt.remove(activity)
            val layer = layerOf(activity)
            if (layer == null) {
                synchronized(hosts) { hosts.remove(activity) }
                if (currentHost === activity) currentHost = null
                return
            }
            removeLayer(layer, reason)
        }

        /** Remove one layer right now: main thread inline, otherwise hop to it. */
        private fun removeLayer(layer: Layer?, reason: String) {
            if (layer == null) return
            val task = Runnable {
                synchronized(layers) { layers.remove(layer) }
                attached.remove(layer.root)
                synchronized(hosts) { hosts.remove(layer.activity) }
                if (currentHost === layer.activity) currentHost = null
                try {
                    val parent = layer.host.parent
                    if (parent is ViewGroup) {
                        parent.removeView(layer.host)
                    }
                    layer.host.removeAllViews()
                } catch (t: Throwable) {
                    Log.w(TAG, "float detach failed: " + t.javaClass.name)
                }
                Log.i(TAG, "event=float_detach reason=" + reason
                        + " activity=" + simpleName(layer.activity))
            }
            if (Looper.myLooper() === Looper.getMainLooper()) {
                task.run()
            } else {
                try {
                    layer.activity.runOnUiThread(task)
                } catch (t: Throwable) {
                    task.run()
                }
            }
        }

        /**
         * ★ 2.3.0：把 `activity` 立为**唯一**浮层宿主，先摘掉其它还活着的层。
         *
         * 这就是「换成新 Activity ⇒ 重新挂载」与「不能每次 resume 叠一层」的同一条闸门：
         * 同一时刻只有一个 [currentHost]，同一棵 root 只会被 `attached` 放行一次。
         * 旧宿主若已经 finish/destroy/没有可见窗口，它的层在这里被同步摘掉；若它仍然可见
         * （高德 17.02 的双 Activity 边界：resume 的那个和真正绘制窗口的那个不是同一个），
         * 摘掉也没什么可丢的 —— 用户看的是刚 resume 的这一屏。
         */
        private fun adoptAsHost(activity: Activity?) {
            if (activity == null) return
            if (currentHost === activity && layerOf(activity) != null) return
            val snapshot: Array<Layer>
            synchronized(layers) {
                snapshot = layers.toTypedArray()
            }
            for (other in snapshot) {
                if (other.activity === activity) continue
                removeLayer(other, "host_switch")
            }
            val previous = currentHost
            currentHost = activity
            if (previous !== activity) {
                Log.i(TAG, "event=float_host switch from=" + simpleName(previous)
                        + " to=" + simpleName(activity))
            }
        }

        /**
         * ★ 2.3.0：给这扇窗口挂一个「层还在不在」的哨兵。
         *
         * 为什么不能用生命周期回调代替：腾讯地图首启是**同一个 Activity 换内容**
         * （隐私页 → 主地图），走的是再调 `setContentView()`；
         * `PhoneWindow.setContentView()` 里那句 `mContentParent.removeAllViews()`
         * 会把我们的 host 从树上摘掉，而 Activity 的 `onResume` 不会再发一次。
         * 真机证据（2.3.0 修复前）：
         * <pre>
         *   12:54:39.311 floating layer attached to com.tencent.map.WelcomeActivity
         *   —— 用户点「同意并继续」——
         *   (再无任何 attach 记录；点球没反应，视图树里也没有 FloatingBall)
         * </pre>
         *
         * 哨兵只做一件事：每次 layout 时看一眼「这个 Activity 的层还在树上吗」，不在就补挂。
         * 判断成本是一次集合查找，挂载走 [attach] 的去重闸门，重复调用无副作用。
         */
        private fun watchContentRoot(activity: Activity) {
            if (!watching.add(activity)) return
            try {
                val decor = if (activity.window == null) null else activity.window.decorView
                if (decor == null) {
                    // 没有窗口就挂不上监听。原版这里直接 return，但 `watching` 里已经写进
                    // 这个 Activity —— 从此这扇窗口永远补不上哨兵。撤掉登记再走。
                    watching.remove(activity)
                    return
                }
                decor.viewTreeObserver.addOnGlobalLayoutListener(
                        object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                try {
                                    val layer = layerOf(activity)
                                    if (layer != null && layer.host.isAttachedToWindow) return
                                    if (!isEnabled()) return
                                    if (activity.isFinishing || activity.isDestroyed) return
                                    val root = contentRoot(activity)
                                    if (root == null || !root.isAttachedToWindow
                                            || root.width <= 0 || root.height <= 0) return
                                    // ★ 2.3.1（性能）：节流。见 watchAttempt 的注释 ——
                                    //   没有这一句，一次布局就可能压一个 attach() 到主线程队列。
                                    val now = android.os.SystemClock.uptimeMillis()
                                    val last = watchAttempt[activity]
                                    if (last != null && now - last < WATCH_RETRY_MS) return
                                    watchAttempt[activity] = now
                                    decor.post(Runnable { attach(activity) })
                                } catch (t: Throwable) {
                                    // 一次异常不能把 layout 拖垮：摘掉哨兵，等下一次 resume 重挂。
                                    Log.w(TAG, "float watch failed: " + t.javaClass.name)
                                }
                            }
                        })
            } catch (t: Throwable) {
                Log.w(TAG, "float watch unavailable: " + t.javaClass.name)
            }
        }

        private fun simpleName(activity: Activity?): String {
            if (activity == null) return "null"
            try {
                return activity.javaClass.name
            } catch (t: Throwable) {
                return "?"
            }
        }

        /** Public recovery entry: re-attach the orb to every remembered window. */
        @JvmStatic
        fun reattachAll() {
            reattach()
        }

        /** Re-attach to every Activity we know about; safe from any thread. */
        private fun reattach() {
            val snapshot: Array<Activity>
            synchronized(hosts) {
                snapshot = hosts.toTypedArray()
            }
            if (snapshot.isEmpty()) {
                // Diagnostic: "turn it back on" with nothing to attach to looks like a dead switch.
                // FloatingHooks.remember() is what keeps this list populated while the overlay is off.
                Log.i(TAG, "reattach: no remembered activities yet")
            }
            for (activity in snapshot) {
                try {
                    activity.runOnUiThread(Runnable {
                        // 「把球打开」要恢复的是**每一扇记住过的窗口**，不是接管：
                        // 这里不走 adoptAsHost，否则多窗口设备上会把先挂的那个摘掉。
                        attach(activity, false)
                        reveal(activity)
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "reattach failed: " + t.javaClass.name)
                }
            }
        }

        /** Pull every orb of this Activity back into view (it may be parked as a grab tab). */
        private fun reveal(activity: Activity) {
            synchronized(layers) {
                for (layer in layers) {
                    if (layer.activity !== activity) continue
                    try {
                        val spaceW = layer.root.width
                        if (spaceW > 0) layer.ball.reveal(spaceW)
                    } catch (t: Throwable) {
                        // best effort only
                    }
                }
            }
        }

        /**
         * Record an Activity as a window the overlay may use, without attaching anything.
         *
         * [attach] already does this, but it is only reached once the Activity owns a
         * visible window. With the overlay switched **off** that never happens, so
         * `hosts` stayed empty -- and [setEnabled] then had nothing to
         * re-attach to, which makes "turn the floating window back on" look broken until the next
         * cold start. Recording here is cheap and has no side effects.
         */
        @JvmStatic
        fun remember(activity: Activity?) {
            if (activity != null) hosts.add(activity)
        }

        /**
         * The layer of one Activity, or null.
         *
         * Named `layerOf` rather than `find` because a sibling change on this file
         * removed a same-purpose helper; keeping the name distinct avoids a silent duplicate.
         */
        private fun layerOf(activity: Activity?): Layer? {
            if (activity == null) return null
            synchronized(layers) {
                for (layer in layers) {
                    if (layer.activity === activity) return layer
                }
            }
            return null
        }

        /**
         * Grow the host to cover the content root, or shrink it back to the orb's footprint.
         *
         * The sheet is a MATCH_PARENT child of the host and needs the whole window (plus the
         * scrim behind it), so the host is only full-screen for as long as the sheet is up. This
         * is the compromise the composition measurement asked for: the map keeps its SurfaceView
         * path except while the user is actually looking at the sheet.
         */
        private fun setExpanded(layer: Layer, expanded: Boolean) {
            if (layer.expanded == expanded) return
            layer.expanded = expanded
            val lp = layer.host.layoutParams ?: return
            lp.width = if (expanded) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = if (expanded) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT
            layer.host.layoutParams = lp
            if (expanded) {
                // Pinned to the root's origin; the orb is placed inside it from now on.
                layer.host.x = 0f
                layer.host.y = 0f
            }
            // Re-seat the orb so it stays exactly where it was on screen in both modes.
            layer.ball.moveTo(layer.ball.centerX(), layer.ball.centerY(), layer.root.width)
            // Log the requested layout params, not the current width: the layout pass for this
            // change has not run yet, so getWidth() would still report the previous mode.
            Log.i(TAG, "host expanded=" + expanded
                    + " lp=" + lp.width + "x" + lp.height
                    + " space=" + layer.root.width + "x" + layer.root.height)
        }

        /**
         * Open the sheet, growing the host first.
         *
         * Every entry point goes through here: the orb tap, [openPanelNear], and the
         * rescue path. Opening the sheet while the host is still orb-sized would measure the
         * MATCH_PARENT card against a 62dp parent for one frame.
         */
        private fun openSheet(layer: Layer?) {
            if (layer == null) return
            setExpanded(layer, true)
            layer.host.requestLayout()
            try {
                layer.panel.open()
            } catch (t: Throwable) {
                Log.w(TAG, "openSheet failed: " + t.javaClass.name)
            }
        }

        /**
         * Open the sheet of whichever layer lives in the same window as `anyViewInWindow`.
         *
         * This is the Activity-free entry: the layer's host is a child of the Activity's content
         * root, so `host.getRootView()` is the same object as the pressed tab's
         * `getRootView()`. Matching on that identity sidesteps the whole "which Activity owns
         * the window" problem (on 17.02 the resumed Activity and the window owner are different).
         */
        @JvmStatic
        fun openPanelNear(anyViewInWindow: View?): Boolean {
            if (anyViewInWindow == null) return false
            val root = anyViewInWindow.rootView ?: return false
            if (!root.isAttachedToWindow) return false
            var target: Layer? = null
            synchronized(layers) {
                for (layer in layers) {
                    if (layer.host.rootView === root) {
                        target = layer
                        break
                    }
                }
                if (target == null) {
                    // Fallback: the only attached layer (single-window builds).
                    for (layer in layers) {
                        if (layer.host.isAttachedToWindow) {
                            target = layer
                            break
                        }
                    }
                }
            }
            val open = target
            if (open == null) {
                // Nothing is attached to this window yet. That is the normal state when the
                // lifecycle hooks could not attach (no Activity of ours owns a visible window
                // early on), so use the view that is definitely in a real window to find its
                // owner and attach there. Without this the orb can only be opened from a code
                // path that already holds an Activity.
                val owner = activityOf(anyViewInWindow) ?: return false
                val rescue = Runnable {
                    attach(owner)
                    val created = layerOf(owner) ?: return@Runnable
                    try {
                        openSheet(created)
                    } catch (t: Throwable) {
                        Log.w(TAG, "openPanelNear failed: " + t.javaClass.name)
                    }
                }
                if (Looper.myLooper() === Looper.getMainLooper()) {
                    rescue.run()
                    return layerOf(owner) != null
                }
                anyViewInWindow.post(rescue)
                return true
            }
            open.host.post(Runnable {
                try {
                    openSheet(open)
                } catch (t: Throwable) {
                    Log.w(TAG, "openPanelNear failed: " + t.javaClass.name)
                }
            })
            return true
        }

        /**
         * Insert the layer into an Activity if it is not there yet.
         *
         * Safe to call repeatedly (onResume, onConfigurationChanged, after a layout pass); the
         * `attached` set makes it idempotent.
         *
         * ★ 2.3.0：这条入口同时是**宿主移交**：新 Activity 接管时旧宿主的层会被摘掉，
         * 所以「用户点完首启隐私页 → 主地图起来」必然重新挂载，且不会叠层。
         */
        @JvmStatic
        fun attach(activity: Activity) {
            attach(activity, true)
        }

        /**
         * @param exclusiveHost true = 接管为唯一宿主（生命周期路径）；
         *                      false = 只补挂、不动别人（[reattach] 恢复开关时用）
         */
        private fun attach(activity: Activity?, exclusiveHost: Boolean) {
            if (activity == null) return
            // 双保险：模块模式（root/LSPosed）永不挂载悬浮层，即使有人从别处调进来。
            try {
                if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) return
            } catch (ignored: Throwable) {
                return
            }
            // Record the Activity before the switch is consulted: when the user turns the overlay
            // back on, reattach() needs to know which windows to put it into, and by then the
            // switch has been off for the whole session.
            hosts.add(activity)
            // ★ 2.3.0：盯住这扇窗口的每一次 layout。腾讯地图首启是「同一个 Activity 换内容」——
            //   隐私页 → 主地图走的是再调一次 setContentView()，而 PhoneWindow.setContentView()
            //   会 mContentParent.removeAllViews()，把我们的 host 连同旧页面一起清掉；
            //   这不触发任何 Activity 生命周期回调，所以只靠 onResume 永远补不回来。
            watchContentRoot(activity)
            if (!isEnabled() || activity.isFinishing) return
            val root = contentRoot(activity)
            if (root == null) {
                Log.w(TAG, "no content root for " + activity.javaClass.name)
                return
            }
            // A root that is not attached to a window, or has no size, belongs to an Activity
            // that is not being composited. 高德 17.02 keeps NewMapActivity in exactly that
            // state: attaching there put the orb into a tree nobody draws, which is why the user
            // saw no floating ball at all. Give up on this Activity instead of retrying -- the
            // retry used to loop on a root that never gains a size -- and let the next resume
            // (the Activity that really owns the visible window) do the attach.
            if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) {
                Log.i(TAG, "skip attach, no visible root: " + activity.javaClass.simpleName)
                return
            }
            // 只在真的要挂的时候才接管：没有可见窗口的 Activity 不该把上一个宿主挤掉
            // （高德的 resume 无窗口边界就是靠这条保住的）。
            if (exclusiveHost) adoptAsHost(activity)
            // ★ 2.3.0：去重闸门必须能分辨「挂过」和「还挂着」。
            //   宿主 setContentView() 会把我们的 host 一起 removeAllViews() 掉，但 content root
            //   这个对象没变 —— 只认 root 的集合从此永久误判「已经挂过」，球再也回不来。
            val existing = layerOf(activity)
            if (existing != null && existing.host.isAttachedToWindow) return
            if (existing != null) {
                removeLayer(existing, "stale_root")
            }
            if (!attached.add(root)) {
                // 记录还在、层已经不在树上：清掉记录，重建一次。
                attached.remove(root)
                if (!attached.add(root)) return
                Log.i(TAG, "event=float_reattach reason=root_replaced activity="
                        + activity.javaClass.name)
            }

            try {
                val dark = isDark(activity)
                val panel = FloatingPanel(activity, dark)
                panel.visibility = View.GONE

                val ball = FloatingBall(activity)
                // 自绘 View 不在无障碍树里；给个描述既利于读屏，也方便自动化验证时定位这颗球。
                try {
                    ball.contentDescription = "MapAdKiller 设置"
                } catch (ignored: Throwable) {
                }
                applyPalette(ball, dark)
                // The 外观 page previews size/opacity on this very orb, so the sheet needs the
                // reference; the ball reads the persisted values in its own constructor.
                panel.setBall(ball)

                // ---- host: only as big as the orb ------------------------------------
                // It used to be MATCH_PARENT, i.e. a full-screen transparent FrameLayout on top
                // of the map. 高德 draws the map in its own SurfaceView, and a full-screen view
                // above it changes the composition path -- measured p99 frame gap 82.77 ms with
                // 6.1% dropped frames while every rendering stage stayed under 10 ms. So the host
                // wraps the orb and is grown to full size only while the sheet is open (a modal
                // sheet plus its scrim needs the whole window).
                val host = FrameLayout(activity)
                host.clipChildren = false
                host.clipToPadding = false
                // Nothing to draw, no hardware layer, and never a touch target of its own: a
                // WRAP_CONTENT host that painted or intercepted would keep adding per-frame work,
                // which is the exact cost this change removes. FrameLayout's default
                // onInterceptTouchEvent already returns false, so only the orb is clickable.
                host.background = null
                host.setWillNotDraw(true)
                host.setLayerType(View.LAYER_TYPE_NONE, null)
                host.isClickable = false
                host.isFocusable = false

                host.addView(panel, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                host.addView(ball, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                host.visibility = View.VISIBLE

                val layer = Layer(activity, ball, panel, host, root, dark)

                // The orb keeps its centre in content-root coordinates; this turns that centre
                // into a position. Wrapped: the host travels with the orb. Expanded: the host is
                // pinned at (0,0) and the orb moves inside it.
                ball.setFrame(object : FloatingBall.Frame {
                    override fun place(cx: Float, cy: Float) {
                        if (layer.expanded) {
                            ball.x = cx - ball.width / 2f
                            ball.y = cy - ball.height / 2f
                        } else {
                            // The host wraps the orb, so host size == orb size; using the orb's own
                            // width keeps this independent of a layout pass that may not have run
                            // yet after a resize.
                            ball.x = 0f
                            ball.y = 0f
                            host.x = cx - ball.width / 2f
                            host.y = cy - ball.height / 2f
                        }
                    }

                    override fun spaceWidth(): Int = root.width

                    override fun spaceHeight(): Int = root.height
                })

                panel.setOnClose(Runnable {
                    // Returning from the sheet re-parks the orb instead of leaving it out. The
                    // delay lets the close animation finish before the host shrinks -- shrinking
                    // first would resize the sheet mid-animation.
                    ball.postDelayed(Runnable {
                        // Re-opened during the close animation: shrinking now would resize
                        // the sheet the user is looking at.
                        if (layer.panel.isOpen()) return@Runnable
                        setExpanded(layer, false)
                        ball.moveTo(ball.centerX(), ball.centerY(), root.width)
                        if (!ball.isHidden()) ball.hide(root.width)
                    }, 260L)
                })
                ball.setListener(object : FloatingBall.Listener {
                    override fun onTap() {
                        openSheet(layer)
                    }

                    override fun onMoved(left: Int, top: Int) {
                        // Persist nothing: the resting edge is recomputed on every settle, and a
                        // stale position would fight a rotation or a split-screen resize.
                    }
                })

                root.addView(host, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT))
                layers.add(layer)

                // A rotation or a split-screen resize moves the edges. The signal must be the
                // content root, not the host: the host is orb-sized now and would also fire when
                // the sheet expands it.
                root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int,
                                                bottom: Int, oldLeft: Int, oldTop: Int,
                                                oldRight: Int, oldBottom: Int) {
                        val w = right - left
                        val h = bottom - top
                        if (w <= 0 || h <= 0) return
                        if (w == oldRight - oldLeft && h == oldBottom - oldTop) return
                        if (!ball.isParked()) return
                        ball.settle(false, w, h)
                        if (!panel.isOpen()) ball.hide(w)
                    }
                })

                // One line so a verification run can prove the host is no longer full screen.
                host.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int,
                                                bottom: Int, oldLeft: Int, oldTop: Int,
                                                oldRight: Int, oldBottom: Int) {
                        if (host.width <= 0) return
                        host.removeOnLayoutChangeListener(this)
                        Log.i(TAG, "floating host size=" + host.width + "x" + host.height
                                + " attached=" + host.isAttachedToWindow
                                + " space=" + root.width + "x" + root.height)
                    }
                })

                // Park it once the real size is known, then auto-hide -- unless this attach is the
                // direct result of the user switching the overlay back on. In that case the orb
                // stays fully visible: an orb that hides 1.4 s after "已开启" reads as "still off".
                val keepVisible = revealOnAttach
                revealOnAttach = false
                host.post(Runnable {
                    // The coordinate space is the content root, not the (orb-sized) host.
                    val spaceW = root.width
                    val spaceH = root.height
                    if (spaceW <= 0 || spaceH <= 0) return@Runnable
                    // Position via moveTo(): setX/setY would leave the orb's internal centre at
                    // (0,0) and settle() would then snap it into the top-left corner.
                    ball.moveTo(spaceW - ball.width / 2f - UiKit.dp(activity, 4f),
                            spaceH * 0.42f, spaceW)
                    ball.settle(false, spaceW, spaceH)
                    if (keepVisible) return@Runnable
                    ball.postDelayed(Runnable { ball.hide(root.width) }, 1400L)
                })
                Log.i(TAG, "floating layer attached to " + activity.javaClass.name)
            } catch (t: Throwable) {
                attached.remove(root)
                Log.w(TAG, "floating layer failed: " + t.javaClass.name + ": " + t.message)
            }
        }

        /** Retint every live layer; called when the host flips between light and dark. */
        @JvmStatic
        fun refreshTheme(activity: Activity?) {
            val dark = isDark(activity)
            synchronized(layers) {
                for (layer in layers) {
                    if (layer.activity !== activity || layer.dark == dark) continue
                    layer.dark = dark
                    applyPalette(layer.ball, dark)
                }
            }
        }

        private fun applyPalette(ball: FloatingBall, dark: Boolean) {
            ball.setPalette(
                    if (dark) 0xF21D2027.toInt() else 0xF7FFFFFF.toInt(),
                    if (dark) 0xFF7AB1FF.toInt() else 0xFF0A6CF5.toInt(),
                    if (dark) 0xFF9CC3FF.toInt() else 0xFF0A6CF5.toInt())
        }

        /**
         * The Activity that owns the window `anyViewInWindow` lives in, or null.
         *
         * Same unwrap loop `EmbeddedSettings.open` uses: a decor view's context is
         * usually a `ContextThemeWrapper` around the Activity, not the Activity itself.
         * Bounded so a pathological context chain cannot spin.
         */
        private fun activityOf(anyViewInWindow: View): Activity? {
            try {
                var owner: android.content.Context? = anyViewInWindow.context
                for (depth in 0 until 16) {
                    if (owner == null) break
                    if (owner is Activity) return owner
                    if (owner !is android.content.ContextWrapper) break
                    val base = owner.baseContext
                    if (base === owner) break
                    owner = base
                }
            } catch (t: Throwable) {
                Log.w(TAG, "activity lookup failed: " + t.javaClass.name)
            }
            return null
        }

        /** The FrameLayout the Activity's content was inflated into. */
        private fun contentRoot(activity: Activity): ViewGroup? {
            try {
                // android.R.id.content is the container the Activity's layout is inflated into.
                // Adding the overlay there keeps it above the map but below dialogs, which is
                // exactly the layer an in-app entry point belongs in.
                val content = activity.findViewById<View>(android.R.id.content)
                if (content is ViewGroup) return content
                val decor = activity.window.decorView
                if (decor is ViewGroup) return decor
                return null
            } catch (t: Throwable) {
                return null
            }
        }

        private fun isDark(activity: Activity?): Boolean {
            if (activity == null) return false
            try {
                val mask = activity.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                return mask == Configuration.UI_MODE_NIGHT_YES
            } catch (t: Throwable) {
                return false
            }
        }
    }
}
