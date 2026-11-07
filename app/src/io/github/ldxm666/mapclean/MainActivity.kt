package io.github.ldxm666.mapclean

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import io.github.ldxm666.mapadkiller.App
import io.github.ldxm666.mapadkiller.CaptureLog
import io.github.ldxm666.mapadkiller.Config
import io.github.ldxm666.mapadkiller.LearnedProvider
import io.github.ldxm666.mapadkiller.MainHook
import io.github.ldxm666.mapadkiller.SdkAutoBlock

import org.json.JSONObject

/**
 * 设置页 — Liquid Glass 风格（设计语言参考 Kyant0/AndroidLiquidGlass）。
 *
 * 视觉体系（纯框架 API 复刻，无 AndroidX / Compose 依赖）：
 *  · 柔和径向光斑（blob）铺底，营造折射底色
 *  · 半透明玻璃卡片：渐变高光描边（LayerDrawable 双层圆角）模拟玻璃厚度
 *  · 分组抽屉：默认折叠、点击展开（LayoutTransition 平滑重排 + 淡入位移）
 *  · 底部悬浮玻璃操作栏（胶囊按钮），避开导航栏
 *  · 胶囊开关（自绘 thumb/track）、行级涟漪、深色模式全适配
 *
 * 配置写入 LSPosed RemotePreferences（App 侧经 libxposed/service，
 * Hook 侧经 XposedModule#getRemotePreferences，同名 group 双侧共享）。
 */
class MainActivity : Activity() {

    private var statusView: TextView? = null
    /** 版本行（绿/黄/灰）与最佳适配行 */
    private var verLine: TextView? = null
    /** 醒目的更新入口：默认「检查版本更新」，有新版时变成「立即更新 vX」 */
    private var updateBtn: TextView? = null
    private var adLine: TextView? = null
    private var updateDialogShown = false
    /** 「已捕获广告 SDK」那一行；服务绑定后要重刷文案，否则一直显示 onCreate 时的空快照 */
    private var sdkRow: TextView? = null

    // 说明：scroll / page / bottomBar / barLp 与 hero / column / overlay / tabBar 是
    // v2.0.0「抽屉 + Pager 顶栏」旧骨架的残留字段。scroll / page / column / overlay / hero
    // 在本类里**只有声明、没有任何赋值**（永远为 null），引用它们的
    // buildHero / animateIn / applyHeroInset / addTab / buildBottomBar 也都是零调用
    // —— 见 docs/current-ui-inventory.md §4 P1。此处保留不删（删除单独提交），
    // Kotlin 侧用可空类型如实表达「可能为 null」。
    private var scroll: ScrollView? = null
    private var list: LinearLayout? = null // 抽屉分组挂载点（LayoutTransition 目标）
    private var page: FrameLayout? = null
    private var bottomBar: LinearLayout? = null
    private var barLp: FrameLayout.LayoutParams? = null

    // ── v2.2.0 版式骨架：vertical root = hero(含 tabs) + pager(weight) + 浮层底栏 ──
    /** 活动内容根：FrameLayout 遮罩层，承载页面列 + 悬浮底栏 */
    private var overlay: FrameLayout? = null
    /** 页面列（垂直）：hero + pager */
    private var column: LinearLayout? = null
    private var hero: LinearLayout? = null
    /** 顶部右侧的框架状态药丸（已连接 / 作用域） */
    private var navPill: LinearLayout? = null
    /** hero 里的实时指标行容器 */
    private var metricRow: LinearLayout? = null

    // ── v2.0.0 分页：百度 / 高德 / 其他，左右滑动切换 ──────────────────────
    private var pager: Pager? = null
    private var tabBar: LinearLayout? = null
    private val tabChips: MutableList<TextView> = ArrayList()
    private val pages: MutableList<ScrollView> = ArrayList()
    /** 百度那一页的开关（配置 group 与高德不同，要单独同步） */
    private val bmapSwitches: MutableMap<String, GlassSwitch> = LinkedHashMap()
    /** 腾讯那一页的开关（第三个 group：tmapclean_config） */
    private val tmapSwitches: MutableMap<String, GlassSwitch> = LinkedHashMap()

    /**
     * 设置页搜索：跨「百度 / 高德 / 腾讯 / 其他」四页过滤所有开关。
     *
     * <p>为什么值得单独一条：腾讯的工具目录在真机上有 90 多项（内置表 + 服务端下发合并），
     * 高德的「更多工具」有 100 多项。没有搜索时用户得在一个折叠抽屉里翻很久，
     * 而这恰恰是他唯一想用的功能。悬浮面板里早有同款（FloatingPanel.buildSearch），
     * 设置页一直缺，这一条补上。
     */
    private var finder: SwitchFinder? = null

    /** 展开状态快照：搜索时全部展开，退出搜索时按这个还原。 */
    private val expandSnapshot: MutableMap<Sec, Boolean> = LinkedHashMap()

    // ── 内置版只读闸（独立设置 App 专用） ────────────────────────────────
    /**
     * 页面 id → 该宿主是不是 LSPatch 内置版。null/缺失 = 还没测出来或测不出来，
     * 一律按模块版处理（fail-open，界面不锁）。
     */
    private val embeddedPages: MutableMap<Int, Boolean> = HashMap()
    /** 已经锁过的页面 id，避免重复遍历整棵视图树。 */
    private val embeddedLocked: MutableSet<Int> = HashSet()
    /** 锁定时显示在配置页顶部的说明条。 */
    private var embeddedNotice: TextView? = null

    // ── 搜索：页下标必须与 onCreate 里 pager.addView / addTab 的顺序一致 ──────────
    // （PAGE_OTHER 只被死掉的 buildOtherPage() 用，模块版用户进不去，见 §4 P1）

    private var dark = false
    private var embeddedHost: String? = null

    private fun embedded(): Boolean {
        return embeddedHost != null
    }

    private fun embeddedAmap(): Boolean {
        return EmbeddedSettingsPolicy.AMAP == embeddedHost
    }

    private fun settingsTargetLabel(): String {
        return if (embedded()) (if (embeddedAmap()) "高德" else "百度") else "高德和百度"
    }

    private fun uiPrefs(): android.content.SharedPreferences {
        return getSharedPreferences(
                if (embedded()) EmbeddedSettingsPolicy.UI_PREFS else App.UI_PREFS,
                Context.MODE_PRIVATE)
    }

    // ---------------------------------------------------------------- 生命周期

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (EmbeddedSettingsPolicy.isHostPackage(packageName)) {
            try {
                io.github.ldxm666.mapclean.App.attachEmbedded(application)
                embeddedHost = packageName
            } catch (error: Throwable) {
                android.util.Log.w("MapCleanEmbedded", "settings connection unavailable", error)
                finish()
                return
            }
        }
        dark = isDarkMode()
        // Two framework themes so AlertDialog follows day/night without a styles.xml.
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
                 else android.R.style.Theme_Material_Light_NoActionBar)
        val sk = Skin(this, dark)
        skin = sk
        window.setBackgroundDrawable(ColorDrawable(sk.bg))
        window.setStatusBarColor(sk.bg)
        window.setNavigationBarColor(if (dark) 0xFF0A0B0E.toInt() else 0xFFFFFFFF.toInt())
        var sysUi = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!dark) sysUi = sysUi or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.decorView.systemUiVisibility = sysUi

        contentHost = FrameLayout(this)
        contentHost.setBackgroundColor(0x00000000)
        val pg = Pager(this)
        pager = pg
        pg.setOnPageChanged(object : Pager.OnPageChanged {
            override fun onPageChanged(index: Int) {
                onTabShown(index)
            }
        })
        contentHost.addView(pg, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 搜索索引必须先于页面构建：registerPageTarget 会在建行之前重置该页索引。
        val fnd = SwitchFinder(this, dark)
        finder = fnd
        fnd.setPageJumper(object : SwitchFinder.PageJumper {
            override fun jump(page: Int) {
                showTab(TAB_CONFIG, true)
                for (i in 0 until appPageIds.size) {
                    if (appPageIds[i] == page) {
                        selectApp(i)
                        break
                    }
                }
            }
        })

        // 配置页先建：首页的「N 个开关」和备份页的统计都读这三张开关表。
        configScreen = buildConfigPage()
        homePage = buildHomePage()
        backupPage = buildBackupPage()
        aboutPage = buildAboutPage()
        pg.addView(homePage)
        pg.addView(configScreen)
        pg.addView(backupPage)
        pg.addView(aboutPage)

        navBar = buildBottomNav()
        val shell = LinearLayout(this)
        shell.orientation = LinearLayout.VERTICAL
        shell.setBackgroundColor(sk.bg)
        shell.addView(contentHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        shell.addView(navBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(shell)

        // 底部标签栏在布局流里（不是浮层）：内容区自然止于栏顶，不需要给每页留 92 dp 的假底。
        shell.setOnApplyWindowInsetsListener(object : View.OnApplyWindowInsetsListener {
            override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
                val top = insets.systemWindowInsetTop
                val bottom = insets.systemWindowInsetBottom
                contentHost.setPadding(0, top, 0, 0)
                navBar.setPadding(sk.dp(16f), sk.dp(6f), sk.dp(16f), bottom + sk.dp(10f))
                return insets
            }
        })

        showTab(TAB_HOME, false)
        renderStatus()
        shell.post(Runnable { UiKit.enter(contentHost, sk.dp(8f).toFloat(), 0L) })

        // 首次进入的强制提示（3 秒后才能点掉）。异常全吞：设置页崩了比少弹一次严重得多。
        // 用户要求：去掉每次打开模块的提示弹窗（FirstRunNotice.maybeShow 调用已移除）

        // 抓取回执独立轮询（v2.3.0）：statusRefresher 只在 onStart~onStop 之间跑，
        // 而抓取期间用户一定被切到高德去了 —— 那段正是「抓完了」的时刻。要在后台也把
        // 结果提示（通知栏）发出去，就得有一条 onCreate~onDestroy 的独立心跳。
        // 它只读一次 prefs，不碰任何 View，代价可以忽略。
        statusHandler.removeCallbacks(captureResultPoller)
        statusHandler.post(captureResultPoller)
    }

    /**
     * 抓取回执轮询：done/failed 且「没提示过」时给一条明确反馈，
     * 成功且用户正看着设置页则 1.2 秒后自动退出。
     */
    private val captureResultPoller: Runnable = object : Runnable {
        override fun run() {
            try {
                if (!embedded()) readCaptureReport()
            } catch (t: Throwable) {
                // 心跳里任何异常都必须留痕：以前就是这里静默吞掉，用户只看到「一直转」。
                android.util.Log.w("MapCleanCapture", "event=capture_poll_fail err=" + t)
            }
            statusHandler.postDelayed(this, CAPTURE_POLL_MS)
        }
    }

    // ── 状态心跳 ────────────────────────────────────────────────────────────
    // v2.2.0 修复：statusRefresher 此前**只被定义、从未 post**。于是 renderStatus()
    // 永不执行 —— 顶部三格指标永远停在「—」、状态药丸永远是个空横线，
    // 而 syncSwitches() 永不执行，所有开关停在构建期 setEnabled(false) 的灰胶囊上。
    // 这一个心跳同时驱动：指标文案 / 状态药丸 / 开关与真实配置对齐。
    protected override fun onStart() {
        super.onStart()
        statusHandler.removeCallbacks(statusRefresher)
        statusHandler.post(statusRefresher)
    }

    protected override fun onStop() {
        super.onStop()
        statusHandler.removeCallbacks(statusRefresher)
    }

    protected override fun onDestroy() {
        statusHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Push the pager down by the measured hero height so the two never overlap. */
    private fun applyHeroInset() {
        try {
            val raw = pager!!.layoutParams
            if (raw !is FrameLayout.LayoutParams) return
            val lp: FrameLayout.LayoutParams = raw
            val want = hero!!.height + dp(4)
            if (lp.topMargin != want) {
                lp.topMargin = want
                pager!!.layoutParams = lp
            }
        } catch (ignored: Throwable) {
        }
    }

    /** Entrance choreography: hero first, then the tabs, then the live page. */
    private fun animateIn() {
        UiKit.enter(hero!!, dp(10).toFloat(), 0L)
        tabBar?.let { UiKit.enter(it, dp(8).toFloat(), 70L) }
        navPill?.let { UiKit.enter(it, dp(12).toFloat(), 130L) }
        val current: View? = if (pager!!.pageCount() > 0) pager!!.getChildAt(pager!!.page()) else null
        if (current != null) UiKit.enter(current, dp(14).toFloat(), 40L)

        // Safety net: whatever happens above (cancelled animation, a mid-flight
        // relayout), make sure the page is visible shortly after.
        overlay!!.postDelayed(Runnable {
            UiKit.reveal(hero!!)
            tabBar?.let { UiKit.reveal(it) }
            if (current != null) UiKit.reveal(current)
        }, 900L)
    }

    private fun isDarkMode(): Boolean {
        val m = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return m == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------------------------------------------------------------- 调色板

    private fun cBg(): Int = if (skin == null) 0xFFF4F5F7.toInt() else skin!!.bg

    private fun cGlass(): Int = if (skin == null) 0xFFFFFFFF.toInt() else skin!!.surface

    private fun cRimHi(): Int = if (skin == null) 0xFFFFFFFF.toInt() else skin!!.rimHi

    private fun cRimLo(): Int = if (skin == null) 0x33FFFFFF else skin!!.rimLo

    private fun cTxP(): Int = if (skin == null) 0xFF14171C.toInt() else skin!!.text

    private fun cTxS(): Int = if (skin == null) 0xFF5B6472.toInt() else skin!!.text2

    private fun cAccent(): Int = if (skin == null) 0xFF2563EB.toInt() else skin!!.accent

    private fun cRipple(): Int = if (dark) 0x22FFFFFF else 0x0F000000

    private fun cTrackOff(): Int = if (skin == null) 0x2E8A94A4 else skin!!.chipBg

    private fun cDotOk(): Int = if (skin == null) 0xFF16A34A.toInt() else skin!!.success

    private fun cDotBad(): Int = if (skin == null) 0xFFDC2626.toInt() else skin!!.danger

    private fun blobColors(): IntArray {
        return if (dark)
            intArrayOf(0x263D6BFF, 0x1E19C37A, 0x227C5CFF)
        else
            intArrayOf(0x1E649BFF, 0x162BD9B8, 0x16A78BFA)
    }

    // ---------------------------------------------------------------- 背景光斑

    /**
     * Decorative bloom behind everything.
     *
     * Previous build added giant radial Views as a weighted band, which stole
     * half the screen and rendered as one enormous disc. Blooms now paint into a single
     * custom Drawable set as the WINDOW background, so they are truly behind the content
     * and cannot intercept touches or consume layout space.
     */
    /**
     * Decorative bloom behind everything.
     *
     * First attempt painted three huge radial gradients as the window background with
     * radii up to 0.62 of the screen diagonal; on a 1080x2400 panel that read as one
     * enormous green disc bleeding through the whole UI. Two lessons applied here:
     *   - the base colour is fully opaque first, so the blooms only tint, never flood;
     *   - alpha stays low (the caller passes faint ARGB) and radii are much smaller,
     *     confined to the top and bottom corners like a real background wash.
     */
    private fun applyWindowBloom() {
        try {
            window.setBackgroundDrawable(BloomDrawable(blobColors(), cBg()))
        } catch (ignored: Throwable) {
            window.setBackgroundDrawable(ColorDrawable(cBg()))
        }
    }

    /** Faint radial washes in the corners; never affects layout and never intercepts input. */
    private class BloomDrawable(private val colors: IntArray, private val base: Int) :
            android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val cx = floatArrayOf(0.05f, 0.95f, 0.50f)
        private val cy = floatArrayOf(0.02f, 0.20f, 1.00f)
        private val rr = floatArrayOf(0.34f, 0.26f, 0.30f)

        private val shaders = arrayOfNulls<android.graphics.Shader>(cx.size)
        private var cachedW = -1
        private var cachedH = -1
        private var cachedLeft = Int.MIN_VALUE
        private var cachedTop = Int.MIN_VALUE

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            // Opaque base first: blooms are a tint over it, not the colour itself.
            canvas.drawColor(base)
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val max = Math.max(w, h)
            // The window background keeps the same bounds for the whole session, so the
            // three radial shaders are built once instead of on every draw() pass -- the
            // old version allocated a fresh RadialGradient per bloom per frame.
            if (shaders[0] == null || w != cachedW.toFloat() || h != cachedH.toFloat()
                    || b.left != cachedLeft || b.top != cachedTop) {
                cachedW = w.toInt()
                cachedH = h.toInt()
                cachedLeft = b.left
                cachedTop = b.top
                var i = 0
                while (i < colors.size && i < cx.size) {
                    val x = b.left + w * cx[i]
                    val y = b.top + h * cy[i]
                    // Clamp radius hard: nothing bigger than a third of the screen.
                    val radius = Math.min(max * rr[i], max * 0.34f)
                    val solid = colors[i]
                    val clear = solid and 0x00FFFFFF
                    shaders[i] = android.graphics.RadialGradient(
                            x, y, radius, solid, clear, android.graphics.Shader.TileMode.CLAMP)
                    i++
                }
            }
            var i = 0
            while (i < shaders.size && i < cx.size) {
                if (shaders[i] == null) {
                    i++
                    continue
                }
                paint.setShader(shaders[i])
                val x = b.left + w * cx[i]
                val y = b.top + h * cy[i]
                val radius = Math.min(max * rr[i], max * 0.34f)
                canvas.drawCircle(x, y, radius, paint)
                i++
            }
            paint.setShader(null)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            paint.setColorFilter(cf)
        }

        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    // ---------------------------------------------------------------- 玻璃材质

    /** 玻璃面板：底层高光 rim + 内层半透明体，双层圆角模拟玻璃厚度 */
    private fun glass(radiusDp: Float): Drawable {
        val s = skin
        if (s != null) return s.glass(radiusDp)
        return UiKit.glass(cGlass(), cRimHi(), cRimLo(), dp(radiusDp).toFloat(), Math.max(1, dp(1f)))
    }

    /** 旧签名保留：直接委托给 UiKit，保证已有调用点仍可编译 */
    private fun ripple(content: Drawable, radiusDp: Float): Drawable {
        return UiKit.ripple(cRipple(), content, dp(radiusDp).toFloat())
    }

    private fun capsule(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.RECTANGLE)
        g.cornerRadius = dp(14f).toFloat()
        g.setColor(color)
        g.setSize(dp(46), dp(28))
        return g
    }

    /** 自绘胶囊开关：轨道染青、thumb 带骨至挤压，抛出带弹道感 */
    private fun makeSwitch(): GlassSwitch {
        val sw = GlassSwitch(this)
        sw.setTrackColors(cTrackOff(), cAccent())
        sw.setThumbColors(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        // 不再用 setAlpha 假装「未就绪」：开关从第一帧起就画真实状态、并且可点。
        // 服务未绑定时点击会走写入失败分支给出 Toast，而不是变成一颗点不动的灰胶囊。
        return sw
    }

    companion object {
        /** 回执轮询间隔。宿主发完广播到本地落盘是毫秒级，1.5 秒足够快又不会烧电。 */
        private const val CAPTURE_POLL_MS = 1500L

        const val PAGE_BAIDU = 0
        const val PAGE_AMAP = 1
        const val PAGE_TMAP = 2
        const val PAGE_OTHER = 3

        private const val GLASS_R = 22 // 卡片圆角 dp
        private const val HEAD_R = 18 // 抽屉头/按钮圆角 dp

        /**
         * 把一个开关报给搜索索引。
         *
         * <p>调用点在开关工厂里而不是页面构建循环里：那样才能同时拿到“界面显示的标题”
         * 和“配置键”，而不必让调用方再传一遍。
         */
        private fun index(f: SwitchFinder?, key: String?, title: String?, note: String?, page: Int) {
            if (f == null || key == null) return
            try {
                f.index(key, title, note, page)
            } catch (ignored: Throwable) {
            }
        }

        // ── 底部标签栏下标 ────────────────────────────────────────────────
        const val TAB_HOME = 0
        const val TAB_CONFIG = 1
        const val TAB_BACKUP = 2
        const val TAB_ABOUT = 3

        // ── 配置管理（死链 showConfigManager → showConfigMore → showConfigFile 用） ──
        private const val CFG_SNAPSHOT = "cfg_snapshot"
        private const val CFG_FILE = "MapAdKiller_config.json"

        /** 绑定前快轮询（等 LSPosed 服务），绑定后降频，避免每 800ms 白跑一遍 UI 刷新。 */
        private const val POLL_FAST = 400L
        private const val POLL_SLOW = 2500L

        /** 行上缓存的 haystack key。用框架不会占用的负数 id，避免再建 ids.xml。 */
        private const val TAG_HAYSTACK = 0x7f0f0001

        /** 已经提示过的抓取回执时间戳，**持久化**在 capture_report prefs 的 ack_at。 */
        private const val K_ACK_AT = "ack_at"

        /** root 直启高德的结果缓存：-1 未知 / 0 不可用 / 1 可用。 */
        private var sRootLaunch = -1

        private fun fixed(s: Skin, wDp: Int, hDp: Int): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(s.dp(wDp.toFloat()), s.dp(hDp.toFloat()))
        }

        private fun fixedMargin(s: Skin, wDp: Int, hDp: Int, rightDp: Int): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(s.dp(wDp.toFloat()), s.dp(hDp.toFloat()))
            lp.rightMargin = s.dp(rightDp.toFloat())
            return lp
        }

        private fun withTop(s: Skin, topDp: Int): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = s.dp(topDp.toFloat())
            return lp
        }

        private fun hostPackageOf(pageId: Int): String? {
            if (pageId == PAGE_BAIDU) return EmbeddedSettingsPolicy.BAIDU
            if (pageId == PAGE_AMAP) return EmbeddedSettingsPolicy.AMAP
            if (pageId == PAGE_TMAP) return EmbeddedSettingsPolicy.TENCENT
            return null
        }

        private fun lockPage(v: View?) {
            if (v == null) return
            if (v !is ScrollView) {
                if (v.isClickable) v.isClickable = false
                if (v.isLongClickable) v.isLongClickable = false
                if (v.isEnabled) v.isEnabled = false
                if (v is GlassSwitch || v is GlassSlider) {
                    v.setOnTouchListener(object : View.OnTouchListener {
                        override fun onTouch(view: View, event: MotionEvent): Boolean {
                            return true // 吞掉，不产生任何状态变化
                        }
                    })
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    lockPage(v.getChildAt(i))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 版本 / 更新

    private val updateCb: UpdateChecker.Callback = UpdateChecker.Callback { latest, outdated, source ->
        renderVersionLines()
        if (outdated && latest != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog(latest)
        }
    }

    /** 版本行配色：已是最新=绿、有新版本=黄、未获取=灰（点一下强制刷新）。 */
    private fun renderVersionLines() {
        if (verLine == null) return
        val latest = UpdateChecker.latest(this)
        val out = UpdateChecker.isOutdated(this)
        val txt: String
        if (latest == null) {
            verLine!!.setTextColor(cTxS())
            txt = "当前 v" + Version.LABEL + " · 线上版本未获取"
        } else if (out) {
            verLine!!.setTextColor(if (skin == null) 0xFFD97706.toInt() else skin!!.warn)
            txt = "当前 v" + Version.LABEL + " · 有新版本 v" + latest
        } else {
            verLine!!.setTextColor(cDotOk())
            txt = "当前 v" + Version.LABEL + " · 已是最新"
        }
        verLine!!.text = txt
        if (updateBtn == null) return
        val pill = dp(999)
        if (out && latest != null) {
            updateBtn!!.text = "立即更新"
            updateBtn!!.setTextColor(0xFFFFFFFF.toInt())
            updateBtn!!.background = UiKit.ripple(0x33FFFFFF,
                    UiKit.plate(if (skin == null) 0xFFD97706.toInt() else skin!!.warn, pill.toFloat()), pill.toFloat())
        } else if (latest == null) {
            updateBtn!!.text = "重试"
            updateBtn!!.setTextColor(0xFFFFFFFF.toInt())
            updateBtn!!.background = UiKit.ripple(0x33FFFFFF,
                    UiKit.plate(cAccent(), pill.toFloat()), pill.toFloat())
        } else {
            updateBtn!!.text = "已是最新"
            updateBtn!!.setTextColor(cDotOk())
            updateBtn!!.background = UiKit.plate(if (dark) 0x1F34D399 else 0x1416A34A, pill.toFloat())
        }
    }

    /** 最佳适配：模块 hook 点验证过的目标版本 vs 本机实际版本 */
    private fun adaptSummary(): String {
        val sb = StringBuilder("已验证版本：")
        for (i in Version.TARGETS.indices) {
            val pkg = Version.TARGETS[i][0]
            if (embedded() && embeddedHost != pkg) continue
            val label = Version.TARGETS[i][1]
            val want = Version.TARGETS[i][2]
            if (sb.length > "已验证版本：".length) sb.append("　")
            sb.append(label).append(' ').append(want)
            val inst = installedVersion(pkg)
            if (inst == null) {
                sb.append("（未安装）")
            } else if (inst == want || inst.startsWith(want)) {
                sb.append("（本机 ").append(inst).append(" ✔）")
            } else {
                sb.append("（本机 ").append(inst).append("，逐项检测兼容）")
            }
        }
        return sb.toString()
    }

    private fun installedVersion(pkg: String): String? {
        try {
            val pm = packageManager
            val pi = pm.getPackageInfo(pkg, 0)
            return pi?.versionName
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * 发现新版本：四个出口 —— **服务器下载**（主）/ GitHub 页（备用）/ Telegram / 稍后更新。
     * 用两行按钮，避免三个按钮塞不下第四个选项。
     */
    private fun showUpdateDialog(latest: String) {
        val dlg = AlertDialog.Builder(this).create()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = skin!!.glassFor(box, GLASS_R.toFloat())
        val pad = dp(20)
        box.setPadding(pad, dp(18), pad, dp(14))

        box.addView(text("发现新版本 v" + latest, 18f, Typeface.BOLD, cTxP()))

        val msg = "当前版本 v" + Version.LABEL + "　→　最新版本 v" + latest +
                "\n\n【直接从服务器下载】「服务器下载」取的是作者服务器上的安装包，" +
                "国内网络可直接打开；内置版会自动选与你当前地图对应的那个包。" +
                "\n\n「GitHub 页」是项目发布页，需要能访问 GitHub，只作备用。" +
                "\n\n更新后建议强停三家地图 App 再重新打开。"
        val mv = text(msg, 13f, Typeface.NORMAL, cTxS())
        mv.setLineSpacing(0f, 1.28f)
        val mlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = dp(10)
        box.addView(mv, mlp)

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        val rlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(16)
        r1.addView(dlgButton("服务器下载", true, Runnable {
            openUrl(mirrorUrl())
            dlg.dismiss()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(spacer(dp(10)))
        r1.addView(dlgButton("GitHub 页", false, Runnable {
            openUrl(Version.RELEASES)
            dlg.dismiss()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(r1, rlp)

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        val rlp2 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp2.topMargin = dp(10)
        r2.addView(dlgButton("TG 通知频道", false, Runnable {
            openUrl(Version.TG)
            dlg.dismiss()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r2.addView(spacer(dp(10)))
        r2.addView(dlgButton("稍后更新", false, Runnable {
            dlg.dismiss()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(r2, rlp2)

        dlg.setView(box)
        dlg.show()
        try {
            val w = dlg.getWindow()
            if (w != null) w.setBackgroundDrawable(ColorDrawable(0))
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 新版本安装包地址：服务端清单优先（内置版自动选宿主对应的包），
     * 拿不到才退回项目发布页（需要能访问 GitHub，只作兜底）。
     */
    private fun mirrorUrl(): String {
        try {
            val url = UpdateChecker.downloadUrl(this)
            if (url != null) return url
        } catch (ignored: Throwable) {
        }
        return Version.RELEASES
    }

    /** 圆角纯色背景（更新按钮用） */
    private fun capsuleBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp.toFloat()).toFloat()
        return g
    }

    private fun dlgButton(label: String, primary: Boolean, action: Runnable): TextView {
        val b = text(label, 14f, Typeface.BOLD, if (primary) 0xFFFFFFFF.toInt() else cAccent())
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(12), dp(8), dp(12))
        val bg = GradientDrawable()
        bg.setColor(if (primary) (if (dark) 0xFF2F6BFF.toInt() else 0xFF0A6CF5.toInt())
        else (if (dark) 0x2E7AB1FF else 0x1F0A6CF5))
        bg.cornerRadius = dp(HEAD_R.toFloat()).toFloat()
        b.background = ripple(bg, HEAD_R.toFloat())
        b.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action.run()
            }
        })
        return b
    }

    // ---------------------------------------------------------------- 区块构建

    /**
     * ⚠ 死代码（docs/current-ui-inventory.md §4 P1）：零调用 —— overlay / column / hero
     * 三个字段也从未赋值，本方法即使被调也会在 glass(24f) 之后崩在 hero 上。
     * 保留不删，仅标注。
     */
    private fun buildHero(host: LinearLayout) {
        // ── 玻璃状态卡：标题 + 右侧状态药丸 + 实时指标条 ──────────────────
        // Old build stacked five full-width text rows (740 px of a 2276 px screen) and
        // let the "verified versions" line wrap onto three lines. Everything that can be
        // a compact pill is one now, and the long version string is collapsed behind tap.
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = glass(24f)
        card.setPadding(dp(18), dp(16), dp(18), dp(14))
        card.elevation = 0f
        UiKit.clipRounded(card, dp(24f).toFloat())

        // 首行：标题 与 状态药丸
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val title = text("MapAdKiller", 21f, Typeface.BOLD, cTxP())
        titleRow.addView(title, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        navPill = LinearLayout(this)
        navPill!!.orientation = LinearLayout.HORIZONTAL
        navPill!!.gravity = Gravity.CENTER_VERTICAL
        navPill!!.setPadding(dp(11), dp(6), dp(13), dp(6))
        navPill!!.background = UiKit.ripple(cRipple(), UiKit.plate(pillBg(), dp(999).toFloat()), dp(999).toFloat())
        navPill!!.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showStatusDetail()
            }
        })
        UiKit.pressable(navPill!!, 0.94f)
        // Exact pixel size: WRAP_CONTENT on a bare View with only a GradientDrawable
        // resolves to nothing, so the drawable expands and floods the parent. That is
        // exactly how a 7dp status dot once rendered as a full-screen green disc.
        val dotLp = LinearLayout.LayoutParams(dp(7), dp(7))
        dotLp.rightMargin = dp(6)
        dotLp.gravity = Gravity.CENTER_VERTICAL
        navPill!!.addView(dot(cDotOk(), 7), dotLp)
        statusPillText = text("—", 12f, Typeface.BOLD, cTxP())
        navPill!!.addView(statusPillText)
        titleRow.addView(navPill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(titleRow)

        // 副标题一行：作用域摘要（过长时省略，详情进弹层）
        statusView = text("", 12f, Typeface.NORMAL, cTxS())
        statusView!!.isSingleLine = true
        statusView!!.ellipsize = android.text.TextUtils.TruncateAt.END
        val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(6)
        card.addView(statusView, slp)

        // 指标条：三个等宽小格子（版本 / 作用域 / 广告 SDK）
        metricRow = LinearLayout(this)
        metricRow!!.orientation = LinearLayout.HORIZONTAL
        val mlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = dp(14)
        card.addView(metricRow, mlp)
        buildMetrics()

        host.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 版本提示行（放在卡片下方，单行、可点）
        verLine = text("", 12f, Typeface.BOLD, cTxS())
        verLine!!.setPadding(dp(6), dp(10), dp(6), 0)
        verLine!!.isSingleLine = true
        verLine!!.ellipsize = android.text.TextUtils.TruncateAt.END
        verLine!!.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                Toast.makeText(this@MainActivity, "正在检查更新…", Toast.LENGTH_SHORT).show()
                UpdateChecker.check(this@MainActivity, true, updateCb)
            }
        })
        host.addView(verLine, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        adLine = text("", 11f, Typeface.NORMAL, cTxS())
        adLine!!.setLineSpacing(0f, 1.2f)
        adLine!!.setPadding(dp(6), dp(4), dp(6), dp(2))
        adLine!!.visibility = View.GONE // 详细适配信息收进弹层，不再撑高首页
        host.addView(adLine)

        updateBtn = text("检查版本更新", 13f, Typeface.BOLD, 0xFFFFFFFF.toInt())
        updateBtn!!.gravity = Gravity.CENTER
        updateBtn!!.setPadding(dp(14), dp(11), dp(14), dp(11))
        val blp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(12)
        updateBtn!!.background = UiKit.ripple(cRipple(), UiKit.gradient(
                cAccentTop(), cAccentBottom(), dp(16).toFloat()), dp(16).toFloat())
        updateBtn!!.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                if (UpdateChecker.isOutdated(this@MainActivity)) {
                    showUpdateDialog(UpdateChecker.latest(this@MainActivity)!!)
                    return
                }
                Toast.makeText(this@MainActivity, "正在检查更新…", Toast.LENGTH_SHORT).show()
                UpdateChecker.check(this@MainActivity, true, updateCb)
            }
        })
        UiKit.pressable(updateBtn!!, 0.97f)
        host.addView(updateBtn, blp)

        renderVersionLines()
    }

    private var statusPillText: TextView? = null

    /** 三格实时指标（等宽，各自可点） */
    private fun buildMetrics() {
        if (metricRow == null) return
        metricRow!!.removeAllViews()
        metricRow!!.addView(metric("版本", versionMetric, Runnable { verLine!!.performClick() }), metricLp(0))
        metricRow!!.addView(metric("作用域", scopeMetric, Runnable { showStatusDetail() }), metricLp(1))
        metricRow!!.addView(metric("广告 SDK", sdkMetric, Runnable { showLearnedSdks() }), metricLp(2))
    }

    private fun metricLp(index: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (index > 0) lp.leftMargin = dp(8)
        return lp
    }

    private var versionMetric = "—"
    private var scopeMetric = "—"
    private var sdkMetric = "—"
    /** 作用域那一格的配色（绿=已勾选 / 红=未勾选 / 灰=读不到）。 */
    private var scopeMetricColor = 0xFF7A8290.toInt()
    /** 已捕获 SDK 数量的短缓存：心跳里不必每次都重建 + 排序整张清单。 */
    private var sdkCacheCount = -1
    private var sdkCacheAt = 0L

    /** 单格指标：值 + 标签，两行，玻璃底 */
    private fun metric(label: String, value: String, action: Runnable?): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(11), dp(9), dp(11), dp(9))
        box.background = UiKit.ripple(cRipple(), UiKit.plate(metricBg(), dp(13).toFloat()), dp(13).toFloat())
        box.contentDescription = label + " " + value
        box.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action?.run()
            }
        })
        UiKit.pressable(box, 0.96f)
        val v = text(value, 14f, Typeface.BOLD, cTxP())
        v.isSingleLine = true
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(v)
        val l = text(label, 10f, Typeface.NORMAL, cTxS())
        l.setPadding(0, dp(2), 0, 0)
        box.addView(l)
        box.tag = v
        return box
    }

    /** 刷新三格指标文案 + 配色 */
    private fun refreshMetrics() {
        if (metricRow == null) return
        versionMetric = "v" + Version.LABEL
        val sdk = sdkCount()
        sdkMetric = sdk.toString()
        for (i in 0 until metricRow!!.childCount) {
            val box = metricRow!!.getChildAt(i)
            val tag = box.tag
            if (tag !is TextView) continue
            val tv = tag
            val value = if (i == 0) versionMetric else if (i == 1) scopeMetric else sdkMetric
            tv.textSize = if (value.length > 9) 11.5f else 14f
            // 三格各自配色：版本=强调色、作用域=绿/红/灰、SDK=有捕获才点亮。
            val color = if (i == 0) cAccent()
            else if (i == 1) scopeMetricColor
            else (if (sdk > 0) cAccent() else cTxS())
            if (!value.contentEquals(tv.text)) tv.text = value
            tv.setTextColor(color)
            box.contentDescription = (if (i == 0) "版本 " else if (i == 1) "作用域 " else "广告 SDK ") + value
        }
    }

    /** SDK 计数（1.5s 短缓存）：learnedListForApp() 会走服务读 + 排序，不该每拍都跑。 */
    private fun sdkCount(): Int {
        val now = android.os.SystemClock.uptimeMillis()
        if (sdkCacheCount >= 0 && now - sdkCacheAt < 1500L) return sdkCacheCount
        var n = 0
        try {
            n = SdkAutoBlock.learnedListForApp().size
        } catch (ignored: Throwable) {
        }
        sdkCacheCount = n
        sdkCacheAt = now
        return n
    }

    private fun pillBg(): Int = if (dark) 0x1FFFFFFF else 0x14FFFFFF

    private fun metricBg(): Int = if (dark) 0x14FFFFFF else 0x0D000000

    private fun cAccentTop(): Int = if (dark) 0xFF4C8DFF.toInt() else 0xFF3D82FF.toInt()

    private fun cAccentBottom(): Int = if (dark) 0xFF2563EB.toInt() else 0xFF0A5CE0.toInt()

    /** 小圆点指示器（尺寸由调用方的 LayoutParams 决定，避免 WRAP_CONTENT 无限拉伸） */
    private fun dot(color: Int, sizeDp: Int): View {
        val v = View(this)
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(color)
        v.background = g
        return v
    }

    /**
     * 去广告卡片：始终开启、不折叠，作为页面第一块「仪表盘」
     *
     * ⚠ 死代码（§4 P1）：零调用，保留不删。
     */
    private fun buildForcedCard(root: LinearLayout) {
        val card = newCard()
        val head = text("高德 · 精确协议适配", 13f, Typeface.BOLD, cAccent())
        head.setPadding(dp(4), dp(10), 0, dp(4))
        card.addView(head)
        addNoteRow(card, "按功能检测原生入口，不因版本号变化停用整个模块。入口不兼容时仅跳过该项；其余功能继续生效。")
        addNoteRow(card, "高德已移除运行时 dex 检索、文本扫描与坐标重排。下方 SDK 清单用于百度、腾讯地图的原有兼容功能。")
        sdkRow = addActionButton(card, sdkSummary(), Runnable { showLearnedSdks() })
        root.addView(card, cardLp())
    }

    /**
     * 当前正在构建/正在被搜索的那一页的抽屉组。
     *
     * <p>不是 final：四页各建一次，每次换一块。正因为 {@link #secs} 只指当前页，
     * 搜索时遍历它才不会把另外三页的抽屉一起展开 —— 那会让 pager 里的每一页都被改坏。
     */
    private var secs: MutableList<Sec> = ArrayList()

    private class Sec {
        lateinit var group: LinearLayout
        lateinit var card: LinearLayout
        /** 卡片内的头行（标题 + 下拉箭头）。搜索态会藏起来，改用卡片外的纯文字标题。 */
        lateinit var head: View
        /** 搜索态专用：卡片外的纯文字分组标题（无背景、无卡片）。 */
        lateinit var label: TextView
        lateinit var chev: View
        var expanded = false
    }

    /** 抽屉分组：一张卡片 = 一个分类。头行常显，展开的内容落在同一张卡里。 */
    private fun newDrawer(title: String): Sec {
        val s = Sec()
        s.expanded = false
        s.group = LinearLayout(this)
        s.group.orientation = LinearLayout.VERTICAL
        // 纯色底 + 1 px 描边，零 elevation。
        // 这里刻意不用 GlassDrawable：它的「1 dp 深色外圈 + 0.8 dp 白色内圈」双层 rim
        // 在白底上会被看成一条浮在卡片外面的灰色阴影带（外圈 224 被两侧 255/253 夹住），
        // 再叠一层 skin.shadow() 就是用户报的「怪状阴影」。
        s.group.background = skin!!.glass(18f)
        UiKit.clipRounded(s.group, dp(18f).toFloat())

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(58)
        head.setPadding(dp(18), dp(12), dp(14), dp(12))
        head.background = UiKit.rippleBare(cRipple(), dp(18f).toFloat())
        UiKit.pressable(head, 0.995f)

        head.addView(skin!!.rowTitle(title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        s.chev = Icon(this, Icon.CHEVRON, cTxS())
        head.addView(s.chev, LinearLayout.LayoutParams(dp(18), dp(18)))

        head.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                toggle(s)
            }
        })
        s.head = head
        s.group.addView(head, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 搜索态的分组标题：卡片外的纯文字，与抽屉头行互斥显示（平时 GONE）。
        s.label = skin!!.section(title)
        val llp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        llp.topMargin = dp(14)
        llp.leftMargin = dp(4)
        s.label.visibility = View.GONE
        list!!.addView(s.label, llp)

        s.card = LinearLayout(this)
        s.card.orientation = LinearLayout.VERTICAL
        s.card.setPadding(dp(6), dp(4), dp(6), dp(10))
        s.card.visibility = View.GONE
        s.group.addView(s.card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val glp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        glp.topMargin = dp(8)
        list!!.addView(s.group, glp)
        secs.add(s)
        return s
    }

    /**
     * Expand / collapse a drawer.
     *
     * The old build toggled visibility and faded a fixed-height card, so the rows below
     * teleported. Here the card's measured height is animated from 0 to its real size
     * while alpha and a small drift ride along, so the list opens like a drawer instead
     * of snapping. The chevron rotates with a spring so the tap feels acknowledged.
     */
    private fun toggle(s: Sec) {
        val expand = !s.expanded
        s.expanded = expand
        // 抽屉高度本身就是逐帧动画；再叠一层 LayoutTransition.CHANGING 会在同一帧里
        // 把整列重新测量两遍（实测展开一次刷了 1300+ 帧）。动画期间先把它摘掉，
        // 结束后再装回去，保证其它布局变化仍然有平滑过渡。
        val host: ViewGroup? = if (s.group.parent is ViewGroup) s.group.parent as ViewGroup else null
        val saved: android.animation.LayoutTransition? = host?.layoutTransition
        if (host != null && saved != null) host.layoutTransition = null
        val restore = Runnable {
            if (host != null && saved != null) host.layoutTransition = saved
        }
        s.chev.animate().cancel()
        s.chev.animate().rotation(if (expand) 90f else 0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start()

        if (expand) {
            s.card.visibility = View.VISIBLE
            s.card.layoutParams.height = 0
            s.card.alpha = 0f
            s.card.requestLayout()
            s.card.post(Runnable {
                val target = measureCard(s.card)
                if (target <= 0) {
                    revealNow(s)
                    restore.run()
                    return@Runnable
                }
                UiKit.animateInt(0, target, UiKit.DECELERATE, UiKit.DUR_SLOW,
                        object : android.animation.ValueAnimator.AnimatorUpdateListener {
                            override fun onAnimationUpdate(a: android.animation.ValueAnimator) {
                                if (!s.expanded) return
                                val h = a.animatedValue as Int
                                s.card.layoutParams.height = h
                                s.card.requestLayout()
                                s.card.alpha = Math.min(1f, h / target.toFloat())
                            }
                        })
                s.card.postDelayed(restore, UiKit.DUR_SLOW + 60L)
            })
        } else {
            val from = s.card.height
            UiKit.animateInt(from, 0, UiKit.ACCELERATE, UiKit.DUR_BASE,
                    object : android.animation.ValueAnimator.AnimatorUpdateListener {
                        override fun onAnimationUpdate(a: android.animation.ValueAnimator) {
                            if (s.expanded) return
                            val h = a.animatedValue as Int
                            s.card.layoutParams.height = h
                            s.card.requestLayout()
                            s.card.alpha = if (from <= 0) 0f else Math.min(1f, h / from.toFloat())
                        }
                    })
            s.card.postDelayed(Runnable {
                if (s.expanded) return@Runnable
                s.card.visibility = View.GONE
                s.card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                s.card.requestLayout()
            }, UiKit.DUR_BASE + 20L)
            s.card.postDelayed(restore, UiKit.DUR_BASE + 60L)
        }
    }

    private fun revealNow(s: Sec) {
        s.card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        s.card.requestLayout()
        s.card.alpha = 0f
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start()
    }

    /** Measure a card at wrap_content without disturbing its current layout. */
    private fun measureCard(card: View): Int {
        try {
            var width = card.width
            if (width <= 0) width = if (overlay == null) 0 else overlay!!.width - dp(32)
            if (width <= 0) return 0
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return card.measuredHeight
        } catch (t: Throwable) {
            return 0
        }
    }

    private fun buildDrawers() {
        var sec: Sec

        sec = newDrawer("高德 · 主页标签栏")
        for (tab in Config.TABS) addSwitch(sec.card, "显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab)

        sec = newDrawer("高德 · 地图标注")
        addNoteRow(sec.card, "统一控制地图上的文字标注：兴趣点图标、店名、道路名与城市名。" +
                "路网、水域与地形照常显示。修改后彻底关闭并重新打开高德生效。")
        addSwitch(sec.card, "显示地图标注", Config.K_MAP_POI)

        sec = newDrawer("高德 · 首页工具宫格")
        addNoteRow(sec.card, "按固定工具 ID 控制显示。关闭全部首页推荐时采用原生工具数据构建等宽宫格，自动补位；图标与点击动作同步保留。")
        addSwitch(sec.card, "显示首页工具宫格", Config.K_TOOLS_VISIBLE)
        addSwitch(sec.card, "自动进入巡航（模块接管）", Config.K_CRUISE_AUTO)
        addCruiseSlider(sec.card, "巡航触发速度下限", Config.K_CRUISE_KMH,
                Config.CRUISE_KMH_MIN, Config.CRUISE_KMH_MAX, Config.CRUISE_KMH_DEFAULT, " km/h")
        addCruiseSlider(sec.card, "巡航触发持续时间", Config.K_CRUISE_SEC,
                Config.CRUISE_SEC_MIN, Config.CRUISE_SEC_MAX, Config.CRUISE_SEC_DEFAULT, " 秒")
        addNoteRow(sec.card, "上面两个值决定「自动进入巡航」何时触发：速度持续超过下限、并保持够久才进入。" +
                "默认 18 km/h / 15 秒；想更灵敏就一起调低（高德官方云控是 15 km/h / 5 秒）。" +
                "\n\n调这两个值（以及上面那个开关）之前，高德必须先开着 —— 模块 App 和地图是两个进程，" +
                "地图没运行时写入没有通道，会提示「未连接地图」。")
        addSwitch(sec.card, "反转开关：仅显示已打开的工具", Config.K_TOOLS_ALLOWLIST)
        addSwitch(sec.card, "工具图标统一彩绘（实验）", Config.K_TOOL_COLOR_ICON)
        addNoteRow(sec.card, "开启反转后，只保留你明确打开的工具；未设置、新增和未知工具全部隐藏。关闭反转可恢复原有默认值。修改后重开高德生效。")
        addNoteRow(sec.card, "以下目录取自高德「更多工具」，按出行、服务等分类。开启的工具会补入首页；新增推广工具默认关闭。同名工具按 ID 分别控制。")
        // 键必须走 AmapPanel.moreToolsKey()：它优先返回目录里学到的真实键
        // （tool_id_86 之类），拿不到才 fallback。此前这里直接拼 fallback 字面量，
        // 而那个键没有任何读取方 —— 用户关掉「更多工具」，关的是空气。
        addSwitch(sec.card, "显示「更多工具」", AmapPanel.moreToolsKey())
        addSwitch(sec.card, "显示「高德出行节」", Config.K_TOOL_PREFIX + "高德出行节")
        for (group in io.github.ldxm666.mapadkiller.AmapToolCatalog.GROUPS) {
            sec = newDrawer("高德 · 工具：" + group)
            for (tool in io.github.ldxm666.mapadkiller.AmapToolCatalog.ENTRIES) {
                if (group != tool[2]) continue
                var name = tool[1]
                if ("145" == tool[0]) name += "（高德入口）"
                if ("502" == tool[0]) name += "（支付宝入口）"
                // 这里遍历的是 AmapToolCatalog.ENTRIES，buildKeys() 保证每个 ENTRIES 条目
                // 都有自己的键（"tool_id_" + id），所以 key() 必然非空；!! 是把这个不变量
                // 写进代码，而不是掩盖一个可能为 null 的路径。
                addSwitch(sec.card, "显示「" + name + "」",
                        io.github.ldxm666.mapadkiller.AmapToolCatalog.key(tool[0])!!)
            }
        }

        // 搜索页金刚位（搜索框下方那一排彩色入口）。
        // ⚠ 这个开关原先只注册在 AmapPanel，而 AmapPanel 只被 FloatingPanel 使用 ——
        //   悬浮窗**只在免 root 内置版存在**，模块模式（LSPosed）根本没有悬浮窗。
        //   ⇒ 模块用户点不到它。所以必须在模块 App 的配置页也注册一份。
        sec = newDrawer("高德 · 搜索页")
        addSwitch(sec.card, "显示搜索页快捷金刚位（实验，关闭会留白）", Config.K_SEARCH_GRID)
        addNoteRow(sec.card, "搜索框下方那一排彩色入口（美食 / 酒店 / 景点门票 / 加油充电 / 超划算 / 扫街榜 等，随版本变化）。" +
                "该排由高德 AJX 页面在运行时绘制，服务端数据、本地缓存、页面预载包三处都试过剔除，" +
                "但页面总会回落到内置默认项（等于换另一批广告），故只能在显示层隐藏。" +
                "代价：进入搜索页会先闪现约 0.3 秒再消失、每次进入重演一次、原位留一条空白。如实告知。")

        sec = newDrawer("高德 · 实验")
        addSwitch(sec.card, "帧率上限（实验）", Config.K_AMAP_FPS_ON)
        addChoiceRow(sec.card, "目标帧率（实验）", Config.K_AMAP_FPS_TARGET,
                intArrayOf(60, 90, 120), 60, " 帧")
        addSwitch(sec.card, "去除建筑物模型（实验）", Config.K_AMAP_BUILDING_ON)
        addNoteRow(sec.card, "默认全关。这两项动的是高德自己的地图渲染，不是广告也不是界面，" +
                "判错就是地图错乱，所以缺省什么都不做。")
        addNoteRow(sec.card, "⚠ 两项都是安装期门控：改完必须强停并重开高德才生效。" +
                "「去除建筑物模型」是原生层补丁，一旦打上是进程级全局的 —— " +
                "之后把开关关掉也要重启高德，楼块才会回来。")
        addNoteRow(sec.card, "帧率上限是「限速」不是「提速」：高德本身跑不满时不会拉高。" +
                "60 与 120 和屏幕刷新率整除，是精确值；90 需要屏幕本身支持 90Hz 输出，" +
                "否则会落在最近的整数分频上（120Hz 屏通常约 80）。" +
                "可在 logcat 搜 event=fps_sample 看实时帧数。")
        addNoteRow(sec.card, "去除建筑物：导航 / 巡航态不再绘制 3D 与扁平楼块，只剩路网、绿地、" +
                "地名与兴趣点。可在 logcat 搜 event=building_patch 确认补丁落点。")

        sec = newDrawer("高德 · 首页推荐内容")
        addSwitch(sec.card, "扫街榜 / 周边精选榜单（首页及分页）", Config.K_FEED_BOARD)
        addSwitch(sec.card, "首页出行节 / 促销活动接口", Config.K_FEED_FESTIVAL)
        addSwitch(sec.card, "地图右侧运营挂件（扫街榜 / 活动入口）", Config.K_MAP_PROMO)
        addSwitch(sec.card, "天气卡片", Config.K_FEED_WEATHER)
        addSwitch(sec.card, "攻略 / 内容流卡片", Config.K_FEED_CONTENT)
        addSwitch(sec.card, "设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS)
        addSwitch(sec.card, "关闭首页搜索框热词轮播（文字流动 / 运营广告词）",
                Config.K_HOTWORD_OFF)
        addNoteRow(sec.card, "首页那支搜索框里的文字会自己流动（预置词/运营词，有时是广告）。" +
                "打开本开关后文字被钉死成系统默认提示词「查找地点、公交、地铁」，" +
                "热词品牌图标一并摘掉；关掉开关即可恢复原样。")
        addNoteRow(sec.card, "适配高德 17.00.0.2005。旧版按文字区分的景区帖、距离帖等开关已合并为榜单和内容流。尚无精确入口的频道栏、快捷入口排不再提供开关。")

        sec = newDrawer("高德 · 地点详情")
        addSwitch(sec.card, "显示「发现好去处」照片推荐", Config.K_PLACE_RECOMMEND)
        addNoteRow(sec.card, "关闭后移除长按选点详情里的推荐标题和照片信息流，刷新和分页同样生效。保留地址、逛逛周边工具、收藏、分享、新增和路线按钮。重新打开地点详情生效。")

        sec = newDrawer("高德 · 「我的」页")
        addSwitch(sec.card, "订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW)
        addSwitch(sec.card, "车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW)
        addSwitch(sec.card, "达人任务卡片", Config.K_MY_TASK)
        addSwitch(sec.card, "热门活动卡", Config.K_MY_PROMO_ROW)
        addSwitch(sec.card, "猜你喜欢", Config.K_MY_GUESS)
        addSwitch(sec.card, "好友动态", Config.K_MY_FRIENDS)
        addNoteRow(sec.card, "按 Mine 卡片类型与入口行数据处理。搜索页运营卡、资质页及其他未完成协议适配的区域暂不提供开关。")
        // 「其他 · 入口与调试」一组已挪到第 3 页（其他），见 buildOtherPage()

        // ── 高德 · 省电 / 后台（v2.2.2 新增）──────────────────────────────────────
        // 这批键以前只注册进了悬浮面板，模块版设置页一条都没有 —— 用户因此说「模块里没有
        // 省电开关」。label 一律写成「关闭…」：这些键的 true 表示「该行为已被拦截」，
        // 而 readState() 走 Config.defaultVisible 兜底 true ⇒ 默认显示为开。
        //
        // 刻意**不注册** labels_on：它是"启用第三方标注过滤"（反义，消费方用
        // Config.selected 严格读、默认 false），混进这一组会让「开 = 已拦截」的读法出错。
        sec = newDrawer("高德 · 省电 / 后台")
        addNoteRow(sec.card, "开 = 该行为已被拦截（默认全开）；关 = 恢复高德原生行为。" +
                "关掉推送长连接后，行程提醒 / 消息 / 家人地图会恢复，但 ACCS 长连接会重新占用 CPU。")
        addSwitch(sec.card, "关闭推送长连接（ACCS，省电最多）", Config.K_PUSH_HARD_OFF)
        addNoteRow(sec.card, "关掉后高德推送（行程提醒 / 活动 / 消息 / 家人地图）会恢复，" +
                "但 ACCS 长连接会重新占用 CPU。")
        addSwitch(sec.card, "关闭推送消息处理", Config.K_PUSH_OFF)
        addSwitch(sec.card, "关闭推送服务壳（激进）", Config.K_PUSH_KILL_OFF)
        addSwitch(sec.card, "关闭周期任务（WorkManager / JobScheduler）", Config.K_SUBPROC_JOB_OFF)
        addNoteRow(sec.card, "关掉后高德的周期后台任务会恢复（更耗电）。")
        addSwitch(sec.card, "关闭桌面小组件刷新", Config.K_SUBPROC_WIDGET_OFF)
        addSwitch(sec.card, "关闭云控调度池（LotusPool）", Config.K_SUBPROC_LOTUSPOOL_OFF)
        addSwitch(sec.card, "关闭阿里埋点 Analytics", Config.K_TRACK_OFF)
        addSwitch(sec.card, "关闭开屏广告请求", Config.K_ALIMAMA_SPLASH_OFF)
        addSwitch(sec.card, "关闭广告曝光上报", Config.K_ALIMAMA_LOG_OFF)
        addSwitch(sec.card, "关闭子进程拦截（总闸，排查用）", Config.K_SUBPROC_OFF)
        addNoteRow(sec.card, "一次性放行所有子进程拦截；怀疑某功能被误伤时先关这个。")
    }

    // ══════════════════════════════════════════════ v2.0.0 分页（百度 / 高德 / 其他）

    private fun newPage(): ScrollView {
        val sv = ScrollView(this)
        sv.setFillViewport(true)
        sv.clipToPadding = false
        sv.isVerticalScrollBarEnabled = false
        pages.add(sv)
        return sv
    }

    private fun pageRoot(sv: ScrollView): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = dp(HyperDesign.PAGE_PAD)
        root.setPadding(p, dp(4), p, dp(24))
        sv.addView(root)
        return root
    }

    /** 抽屉挂载点：CHANGING 过渡让折叠/展开时后续卡片平滑上移/下移 */
    private fun newMount(): LinearLayout {
        val m = LinearLayout(this)
        m.orientation = LinearLayout.VERTICAL
        val lt = android.animation.LayoutTransition()
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING)
        lt.setDuration(190L)
        m.layoutTransition = lt
        return m
    }

    /** 页 0：百度地图 —— 分类来自 io.github.ldxm666.bmapclean.Spec（开关写 bmapclean_config） */
    private fun buildBaiduPage(): ScrollView {
        val sv = newPage()
        val root = pageRoot(sv)
        val mount = newMount()
        root.addView(mount)
        val old = list
        val pageSecs: MutableList<Sec> = ArrayList()
        secs = pageSecs
        list = mount
        expandSnapshot.clear()
        registerPageTarget(PAGE_BAIDU, pageSecs)
        try {
            val cats = io.github.ldxm666.bmapclean.Spec.cats()
            for (i in cats.indices) {
                val cat = cats[i]
                val sec = newDrawer(cat.title ?: "")
                val catSub = cat.subtitle
                if (!catSub.isNullOrEmpty()) addNoteRow(sec.card, catSub)
                for (j in cat.rows.indices) {
                    val row = cat.rows[j]
                    val rowKey = row.key
                    if (rowKey == null) addNoteRow(sec.card, row.title ?: "")
                    else addBmapSwitch(sec.card, row.title ?: "", rowKey, row.note)
                }
                // 全部默认收起：与高德页统一（v2.2.5，用户要求「不要固定展开」）
            }
            // ── 百度 · 省电 / 后台（v2.2.3 新增）──────────────────────────────
            // 与高德那组对齐：键都是「true = 该行为已被拦截」，所以 label 一律写「关闭…」。
            // 开关写 bmapclean_config（addBmapSwitch），由 bmapclean.BaiduPower 在安装期读取：
            // 关掉的档位**一条 hook 都不装**（零开销），代价是改完要强停重开百度地图。
            val power = newDrawer("百度 · 省电 / 后台")
            addNoteRow(power.card, "开 = 该行为已被拦截（默认全开）；关 = 恢复百度原生行为。" +
                    "改动后需**强制停止并重开百度地图**才生效。")
            addBmapSwitch(power.card, "关闭推送长连接（硬档，省电最多）",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_HARD_OFF,
                    "关掉后百度推送（活动 / 提醒）会恢复，但推送连接与周期闹钟会重新占用 CPU。")
            addBmapSwitch(power.card, "关闭推送消息处理",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_OFF,
                    "不建连接、不处理推送消息，但不杀服务。")
            addBmapSwitch(power.card, "关闭推送服务（激进档）",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_KILL_OFF,
                    "通道服务起来即退出。若发现耗电反而上升，先关这一项。")
            addBmapSwitch(power.card, "关闭后台常驻服务",
                    io.github.ldxm666.bmapclean.Cfg.K_BG_SERVICE_OFF,
                    "定位 / 云控 / 统计等自启动服务不再常驻。")
            addBmapSwitch(power.card, "关闭后台周期任务",
                    io.github.ldxm666.bmapclean.Cfg.K_TIMER_OFF,
                    "AlarmManager / JobScheduler / WorkManager 的定时唤醒不再重排。")
            addBmapSwitch(power.card, "关闭桌面小组件刷新",
                    io.github.ldxm666.bmapclean.Cfg.K_WIDGET_OFF,
                    "只拦 APPWIDGET_UPDATE，添加 / 删除组件仍正常。")
            addBmapSwitch(power.card, "关闭第三方埋点",
                    io.github.ldxm666.bmapclean.Cfg.K_TRACK_OFF,
                    "统计 / 广告 SDK 不初始化，不上报。")
        } finally {
            list = old
        }
        addVersionFooter(root, "首页开关：强停百度地图后重开即可生效；" +
                "「我的」页开关：关掉后重开该页即生效（数据层/JS 层在页面加载时读配置）")
        val reset = skin!!.text("全部恢复显示（清空百度侧配置）", 12f, Skin.REGULAR, cDotBad())
        reset.gravity = Gravity.CENTER
        reset.setPadding(0, dp(10), 0, dp(6))
        reset.background = UiKit.rippleBare(cRipple(), dp(12f).toFloat())
        reset.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                var ok = false
                try {
                    ok = io.github.ldxm666.bmapclean.App.resetAll()
                } catch (ignored: Throwable) {
                }
                Toast.makeText(this@MainActivity,
                        if (ok) "已恢复默认，重开百度地图生效" else "LSPosed 服务未连接",
                        Toast.LENGTH_SHORT).show()
                if (ok) syncSwitches()
            }
        })
        root.addView(reset, skin!!.matchWrap())
        return sv
    }

    /** 页 1：高德地图 —— 沿用 MapAdKiller 的抽屉 */
    private fun buildAmapPage(): ScrollView {
        val sv = newPage()
        val root = pageRoot(sv)
        val mount = newMount()
        root.addView(mount)
        val old = list
        val pageSecs: MutableList<Sec> = ArrayList()
        secs = pageSecs
        list = mount
        registerPageTarget(PAGE_AMAP, pageSecs)
        try {
            buildDrawers()
        } finally {
            list = old
        }
        addVersionFooter(root, "改动后请强停对应地图 App 并重新打开以生效；开关可随时单独开/关")
        return sv
    }

    /**
     * 给一页装上搜索 Target。
     *
     * <p>在页面构建<b>之前</b>调用：{@link SwitchFinder#register} 会重建该页索引，
     * 而索引是由开关工厂在随后的建行过程中一条条填进去的。
     *
     * <p>{@code pageSecs} 必须显式传进来：四页共用同一个 Activity，搜索真正发生时
     * {@link #secs} 早已指向最后构建的那一页。捕获当页自己的那份列表，
     * 搜索才碰不到别的页的抽屉。
     */
    private fun registerPageTarget(page: Int, pageSecs: MutableList<Sec>) {
        if (finder == null) return
        finder!!.register(page, object : SwitchFinder.Target {
            override fun applyQuery(query: String): Boolean {
                val saved = secs
                secs = pageSecs
                try {
                    val any = applyQueryOnPage(page, query)
                    applySearch(true)
                    return any
                } finally {
                    secs = saved
                }
            }

            override fun resetQuery() {
                val saved = secs
                secs = pageSecs
                try {
                    applySearch(false)
                    // 还原之后把每张卡片的行全部放出来，只显示命中项交由下次进入搜索时重算。
                    for (i in 0 until secs.size) {
                        val s = secs[i]
                        // 原先这里判 s == null || s.card == null：Sec 只在 newDrawer() 里构造、
                        // 三个字段必然赋值，是死判；Kotlin 侧用 lateinit 表达非空，故去掉。
                        s.group.visibility = View.VISIBLE
                        s.head.visibility = View.VISIBLE
                        s.label.visibility = View.GONE
                        filterRows(s.card, "")
                    }
                } finally {
                    secs = saved
                }
            }
        })
    }

    /** 页 2：腾讯地图 —— 分类来自 io.github.ldxm666.tmapclean.Spec（开关写 tmapclean_config） */
    private fun buildTmapPage(): ScrollView {
        val sv = newPage()
        val root = pageRoot(sv)
        val mount = newMount()
        root.addView(mount)
        val old = list
        val pageSecs: MutableList<Sec> = ArrayList()
        secs = pageSecs
        list = mount
        registerPageTarget(PAGE_TMAP, pageSecs)
        try {
            // 工具目录由 hook 侧从腾讯地图进程里学回来；进本页时先同步一次。
            TmapPanel.syncLearnedTools()
            val cats = io.github.ldxm666.tmapclean.Spec.cats()
            for (i in cats.indices) {
                val cat = cats[i]
                val sec = newDrawer(cat.title ?: "")
                val catSub = cat.subtitle
                if (!catSub.isNullOrEmpty()) addNoteRow(sec.card, catSub)
                for (j in cat.rows.indices) {
                    val row = cat.rows[j]
                    val rowKey = row.key
                    if (rowKey == null) addNoteRow(sec.card, row.title ?: "")
                    else addTmapSwitch(sec.card, row.title ?: "", rowKey, row.note)
                }
                // 全部默认收起：与高德页统一（v2.2.5，用户要求「不要固定展开」）
            }
        } finally {
            list = old
        }
        addVersionFooter(root, "适配腾讯地图 11.7.0。底栏 / 工具宫格 / 设置家在重新进入首页时生效；" +
                "「我的」页与「大家都在看」在重新进入对应页面时生效。" +
                "工具逐项开关需要先打开一次腾讯地图首页（模块会在真实进程里学到工具目录）。")
        return sv
    }

    /**
     * 页 3：其他 / 调试
     *
     * ⚠ 死代码（docs/current-ui-inventory.md §4 P1）：本方法**全类零调用**，唯一出现是
     * buildDrawers() 里的一句注释。模块版用户完全进不去这一页，它承载的
     * 「接收服务器公告 / 最新公告 / 隐藏桌面图标 / 抓取前 10 分钟 / 抓取后 10 分钟 /
     * 去广告说明」在模块版全部不可达。本轮只做转译 + 修 bug，**故意保留不删**
     * （删除动作单独提交）。保留时唯一的代价是未使用告警，故在此标注。
     */
    private fun buildOtherPage(): ScrollView {
        val sv = newPage()
        val root = pageRoot(sv)
        val mount = newMount()
        root.addView(mount)
        val old = list
        list = mount
        try {
            val sec = newDrawer("入口与调试")
            // 服务器公告（模块版）：「其他」页的兜底入口。开关走 Config/App.writeBoolean
            // 的同一条路（模块版 remote / 内置版 local 已分流），不另写 SharedPreferences。
            addSwitch(sec.card, "接收服务器公告", Announce.K_ON)
            addActionButton(sec.card, "最新公告", Runnable {
                Announce.showLatest(this@MainActivity)
            })
            if (embedded()) {
                addNoteRow(sec.card, "当前整合版设置仅控制本地图，配置保存在本地图的嵌入式模块服务中。修改后彻底退出并重开地图生效。")
                addNoteRow(sec.card, "返回可继续使用地图。更新整合版时，请下载对应地图的新版安装包。")
                if (embeddedAmap()) addSwitch(sec.card, "调试日志", Config.K_DEBUG_LOG)
                else addNoteRow(sec.card, "调试日志开关位于百度地图页的「诊断」分组。")
            } else {
                addLocalSwitch(sec.card, "隐藏桌面图标", App.K_HIDE_ICON)
                addNoteRow(sec.card, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响（v1.1.0 起），" +
                        "快捷设置磁贴与 adb 命令也始终可用。")
                addNoteRow(sec.card, "高德工具列表隐藏后自动补位；旧版实验性坐标重排已移除。")
                addSwitch(sec.card, "调试日志（框架输出协议适配错误）", Config.K_DEBUG_LOG)
            }
            addActionButton(sec.card, "抓取前 10 分钟（立即生成 txt）", Runnable {
                toast("正在导出最近十分钟的数据…")
                var path: String? = null
                try {
                    path = CaptureLog.dumpRecent(this@MainActivity)
                } catch (t: Throwable) {
                    android.util.Log.w("MapCleanEmbedded", "event=capture_dump_fail err=" +
                            t.javaClass.name)
                }
                toast(if (path != null) "已保存：" + path else "导出失败：系统拒绝写文件，见 logcat")
            })
            addActionButton(sec.card, if (CaptureLog.running())
                    "正在抓取最近十分钟的数据…（点此结束）" else "抓取后 10 分钟（从现在开始录）",
                    Runnable {
                        val started = CaptureLog.toggleLive(this@MainActivity)
                        toast(if (started) "正在抓取最近十分钟的数据…" else "已结束抓包，txt 已生成")
                    })
            addNoteRow(sec.card, "「前 10 分钟」立刻导出已经发生的事（只含这次打开高德以后的），" +
                    "「后 10 分钟」从这一刻开始录。" +
                    "两份 txt 都存到「内部存储/下载/MapClean/」，文件管理器点「下载」就能看到；" +
                    "系统不给写时自动落到 Android/data/包名/files/MapClean/。")
            toggle(sec)
            val sec2 = newDrawer("去广告说明")
            addNoteRow(sec2.card, "高德使用版本专用协议适配；推荐内容由高德页的开关控制。百度、腾讯保留原有广告兼容规则和 SDK 清单。")
        } finally {
            list = old
        }
        addVersionFooter(root, if (embedded()) "MapAdKiller v" + Version.LABEL + " · 免 root 整合版"
        else "MapAdKiller v" + Version.LABEL + " · 高德 / 百度地图精简")
        return sv
    }

    /**
     * ⚠ 死代码（§4 P1）：零调用 —— tabBar 字段在本类里从未赋值，本方法即使被调也只会在
     * 第 1 行 return。保留不删，仅标注。
     */
    private fun addTab(label: String) {
        if (tabBar == null) return
        val chip = text(label, 13f, Typeface.BOLD, cTxS())
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(10), dp(10), dp(10), dp(10))
        chip.background = UiKit.rippleBare(cRipple(), dp(14f).toFloat())
        chip.isClickable = true
        UiKit.pressable(chip, 0.95f)
        chip.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val i = tabChips.indexOf(v)
                if (i >= 0) {
                    pager!!.setPage(i, true)
                    selectTab(i)
                }
            }
        })
        val lp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(6)
        tabChips.add(chip)
        tabBar!!.addView(chip, lp)
    }

    private fun selectTab(index: Int) {
        if (tabBar == null) return
        // 每页可搜的范围不同（“其他”页几乎没有），切页时同步决定搜索条去留。
        if (finder != null) {
            finder!!.dismissKeyboard()
            finder!!.attach(index)
        }
        for (i in 0 until tabChips.size) {
            val c = tabChips[i]
            val on = (i == index)
            // 段控样式：选中=染色胶囊 + 强调色文字；未选中=主文字色 + 0.72 不透明。
            // 之前未选中用 0.62 + 灰色文字，小屏上糊成一片“全是灰按钮”。
            c.setTextColor(if (on) cAccent() else cTxP())
            c.setTypeface(Typeface.DEFAULT_BOLD, if (on) Typeface.BOLD else Typeface.NORMAL)
            c.animate().cancel()
            c.animate().alpha(if (on) 1f else 0.72f)
                    .setDuration(UiKit.DUR_BASE)
                    .setInterpolator(UiKit.SPRING).start()
            c.background = if (on)
                UiKit.ripple(cRipple(), UiKit.plate(tabOnBg(), dp(14f).toFloat()), dp(14f).toFloat())
            else
                UiKit.rippleBare(cRipple(), dp(14f).toFloat())
        }
    }

    private fun tabOnBg(): Int = if (dark) 0x2E7AB1FF else 0x1F0A6CF5

    // ── 搜索：每页的查询落地 ──────────────────────────────────────────────

    /**
     * 进入/退出搜索时统一展开或还原抽屉，并把不相关的开关行收起来。
     *
     * <p>为什么必须展开：开关都放在默认折叠的抽屉里，用户搜“步行”却看到
     * “高德 · 首页推荐内容”的折叠头，等于什么都没发生。
     * 退出搜索时按 {@link #expandSnapshot} 还原，不让一次搜索永久改变用户的展开偏好。
     *
     * @param searching true = 展开全部 + 只留命中；false = 还原
     */
    private fun applySearch(searching: Boolean) {
        for (i in 0 until secs.size) {
            val s = secs[i]
            if (searching) {
                if (!expandSnapshot.containsKey(s)) expandSnapshot[s] = s.expanded
                if (!s.expanded) expand(s)
            } else {
                val was = expandSnapshot[s]
                if (was != null && was != s.expanded) {
                    if (was) expand(s)
                    else collapse(s)
                }
            }
            if (!searching) expandSnapshot.remove(s)
        }
    }

    private fun expand(s: Sec) {
        if (s.expanded) return
        s.expanded = true
        s.card.visibility = View.VISIBLE
        s.card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        s.card.alpha = 0f
        s.card.requestLayout()
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start()
        s.chev.animate().cancel()
        s.chev.animate().rotation(90f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start()
    }

    private fun collapse(s: Sec) {
        if (!s.expanded) return
        s.expanded = false
        s.chev.animate().cancel()
        s.chev.animate().rotation(0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start()
        s.card.animate().alpha(0f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.ACCELERATE)
                .withEndAction(Runnable {
                    s.card.visibility = View.GONE
                }).start()
    }

    /**
     * 一页的查询实现：把该页所有抽屉里的开关行按命中收起/显示。
     *
     * <p>行的可见性靠“标题 + 备注 + 配置键”三段匹配（{@link SwitchFinder.Entry#haystack}），
     * 所以「步行」「walk」「tool_walk」敲法都命中同一个开关。
     */
    private fun applyQueryOnPage(page: Int, query: String?): Boolean {
        var any = false
        for (i in 0 until secs.size) {
            val s = secs[i]
            val shown = filterRows(s.card, query ?: "")
            // 抽屉一个命中都没有时整组收起，避免留一堆空壳标题。
            if (shown == 0) {
                s.group.visibility = View.GONE
                s.label.visibility = View.GONE
            } else {
                s.group.visibility = View.VISIBLE
                // 搜索态：分组标题走卡片外的纯文字 section header，卡内不再重复一条头行。
                s.head.visibility = View.GONE
                s.label.visibility = View.VISIBLE
                any = true
            }
        }
        return any
    }

    /**
     * @return 该卡片里被显示出来的开关行数
     *
     * <p>分割线必须跟着行一起藏。旧实现只藏行、不藏线：一个 20 行的工具抽屉搜出 2 行，
     * 中间就留下十几条 1 px 的 divider 叠成一条 40+ px 的灰色色带 —— 这正是用户
     * 反馈的「行与行之间有诡异的阴影/色带」。
     */
    private fun filterRows(host: ViewGroup, query: String): Int {
        val n = host.childCount
        val searching = query.length > 0
        val vis = BooleanArray(n)
        var shown = 0

        // 1) 开关行按 haystack 命中显隐；说明行 / 按钮没有 haystack，搜索态一并收起。
        for (i in 0 until n) {
            val child = host.getChildAt(i)
            val tag: Any? = if (child is ViewGroup) child.getTag(TAG_HAYSTACK) else null
            if (tag is String) {
                val hit = !searching || tag.lowercase(java.util.Locale.ROOT).contains(query)
                child.visibility = if (hit) View.VISIBLE else View.GONE
                vis[i] = hit
                if (hit) shown++
            } else {
                child.visibility = if (searching) View.GONE else View.VISIBLE
                vis[i] = !searching
            }
        }

        // 2) 1 px 分割线只在「前后两行都可见」时出现。
        if (searching) {
            for (i in 1 until n - 1) {
                val child = host.getChildAt(i)
                if (child is ViewGroup) continue
                if (child is TextView) continue
                if (vis[i - 1] && vis[i + 1]) child.visibility = View.VISIBLE
            }
        }
        return shown
    }

    /**
     * 百度那一页的信息卡：模块状态 + 最近一次规则执行 + 一键恢复
     *
     * ⚠ 死代码（§4 P1）：零调用，保留不删。
     */
    private fun addBmapInfoCard(root: LinearLayout) {
        val card = newCard()
        val clp = cardLp()
        root.addView(card, clp)

        val head = text("百度地图 · 界面精简", 15f, Typeface.BOLD, cTxP())
        head.setPadding(0, dp(2), 0, dp(8))
        card.addView(head)

        // 「最近一次规则执行 / 累计回报」那一大片诊断文字已按 LO 要求移除（2026-10-14）：
        // 用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=BMapClean）。
        val reset = text("全部恢复显示（清空百度侧配置）", 13f, Typeface.BOLD, cDotBad())
        reset.setPadding(0, dp(14), 0, dp(4))
        reset.background = ripple(capsule(0x12FF6B70), HEAD_R.toFloat())
        reset.gravity = Gravity.CENTER
        val rlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(12)
        reset.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                var ok = false
                try {
                    ok = io.github.ldxm666.bmapclean.App.resetAll()
                } catch (ignored: Throwable) {
                }
                Toast.makeText(this@MainActivity,
                        if (ok) "已恢复默认，重开百度地图生效" else "LSPosed 服务未连接",
                        Toast.LENGTH_SHORT).show()
                if (ok) syncSwitches()
            }
        })
        card.addView(reset, rlp)
    }

    /** 百度组的开关行（配置 group 与高德不同，写 bmapclean_config） */
    private fun addBmapSwitch(card: LinearLayout, title: String, key: String, note: String?) {
        val row = newSwitchRow(card, key, title, note)
        val label = text(title, 14.5f, Typeface.NORMAL, cTxP())
        label.setPadding(0, dp(11), dp(10), dp(11))
        label.setLineSpacing(0f, 1.15f)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = makeSwitch()
        sw.setLabel(title)
        val def = io.github.ldxm666.bmapclean.Spec.defaultVisible(key)
        try {
            sw.setChecked(io.github.ldxm666.bmapclean.App.read(key, def), false)
        } catch (t: Throwable) {
            sw.setChecked(def, false)
        }
        bmapSwitches[key] = sw
        index(finder, key, title, note, PAGE_BAIDU)
        row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sw.setOnCheckedChange(object : GlassSwitch.OnCheckedChange {
            override fun onCheckedChanged(view: GlassSwitch, next: Boolean) {
                var ok = false
                try {
                    ok = io.github.ldxm666.bmapclean.App.write(key, next)
                } catch (ignored: Throwable) {
                }
                if (!ok) {
                    view.setChecked(!next, true)
                    Toast.makeText(this@MainActivity, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show()
                }
            }
        })
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                sw.performClick()
            }
        })
        if (!note.isNullOrEmpty()) addNoteRow(card, note)
    }

    /**
     * 腾讯那一页的信息卡：模块状态 + 最近一次回报 + 学到的工具目录条数
     *
     * ⚠ 死代码（§4 P1）：零调用，保留不删。
     */
    private fun addTmapInfoCard(root: LinearLayout) {
        val card = newCard()
        val clp = cardLp()
        root.addView(card, clp)

        val head = text("腾讯地图 · 界面精简", 15f, Typeface.BOLD, cTxP())
        head.setPadding(0, dp(2), 0, dp(8))
        card.addView(head)

        // 「界面规则执行 / 累计回报 / 已学到 N 个首页工具」那一大片诊断文字已按 LO 要求移除
        // （2026-10-14）：用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=TMapClean）。
    }

    /** 腾讯组的开关行（第三个配置 group：tmapclean_config） */
    private fun addTmapSwitch(card: LinearLayout, title: String, key: String, note: String?) {
        val row = newSwitchRow(card, key, title, note)
        val label = text(title, 14.5f, Typeface.NORMAL, cTxP())
        label.setPadding(0, dp(11), dp(10), dp(11))
        label.setLineSpacing(0f, 1.15f)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = makeSwitch()
        sw.setLabel(title)
        val def = io.github.ldxm666.tmapclean.Spec.defaultChecked(
                key, io.github.ldxm666.tmapclean.Spec.APP_READ)
        try {
            sw.setChecked(io.github.ldxm666.tmapclean.App.read(key, def), false)
        } catch (t: Throwable) {
            sw.setChecked(def, false)
        }
        tmapSwitches[key] = sw
        row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sw.setOnCheckedChange(object : GlassSwitch.OnCheckedChange {
            override fun onCheckedChanged(view: GlassSwitch, next: Boolean) {
                var ok = false
                try {
                    ok = io.github.ldxm666.tmapclean.App.write(key, next)
                } catch (ignored: Throwable) {
                }
                if (!ok) {
                    view.setChecked(!next, true)
                    Toast.makeText(this@MainActivity, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show()
                }
            }
        })
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                sw.performClick()
            }
        })
        if (!note.isNullOrEmpty()) addNoteRow(card, note)
    }

    private fun addVersionFooter(root: LinearLayout, tip: String) {
        val t = text(tip, 12f, Typeface.NORMAL, cTxS())
        t.setLineSpacing(0f, 1.2f)
        t.setPadding(dp(4), dp(16), 0, dp(2))
        root.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * 底部悬浮玻璃操作栏：作为 root 的覆盖层浮在 pager 之上，不占布局流
     *
     * ⚠ 死代码（§4 P1）：零调用。它是 showConfigManager() / showContact() 唯一的入口，
     * 所以整条「配置管理 → 导入导出 → 配置文件」弹窗链和「联系作者」也一并不可达。
     * 保留不删，仅标注。
     */
    private fun buildBottomBar() {
        bottomBar = LinearLayout(this)
        bottomBar!!.orientation = LinearLayout.HORIZONTAL
        bottomBar!!.gravity = Gravity.CENTER
        bottomBar!!.setPadding(dp(7), dp(7), dp(7), dp(7))
        bottomBar!!.background = glass(26f)
        bottomBar!!.elevation = 0f
        UiKit.clipRounded(bottomBar!!, dp(26f).toFloat())

        bottomBar!!.addView(barChip("SDK 清单", Runnable { showLearnedSdks() }))
        bottomBar!!.addView(spacer(dp(6)))
        bottomBar!!.addView(barChip("配置", Runnable { showConfigManager() }))
        bottomBar!!.addView(spacer(dp(6)))
        bottomBar!!.addView(barChip("联系作者", Runnable { showContact() }))

        // Root is a FrameLayout wrapper only for the bar overlay; the page column is the
        // first child. Using a dedicated overlay host keeps the bar out of the vertical
        // flow so the pager genuinely fills the remaining space.
        barLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barLp!!.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        barLp!!.setMargins(dp(14), 0, dp(14), dp(14))
    }

    private fun spacer(w: Int): View {
        val v = View(this)
        val lp = LinearLayout.LayoutParams(w, 1)
        v.layoutParams = lp
        return v
    }

    private fun barChip(label: String, action: Runnable): TextView {
        val c = text(label, 13f, Typeface.BOLD, cAccent())
        c.gravity = Gravity.CENTER
        c.setPadding(dp(16), dp(10), dp(16), dp(10))
        c.background = UiKit.ripple(cRipple(),
                UiKit.plate(if (dark) 0x2E7AB1FF else 0x330A6CF5, dp(20f).toFloat()), dp(20f).toFloat())
        c.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action.run()
            }
        })
        UiKit.pressable(c, 0.93f)
        return c
    }

    // ---------------------------------------------------------------- 状态区

    /** Render a cached public-service snapshot; framework IPC stays off the UI thread. */
    private fun renderStatus() {
        if (statusView == null) return
        val state = io.github.ldxm666.mapclean.App.frameworkStatus()
        val connected = state.connected

        var targets = arrayOf(MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP)
        var labels = arrayOf("高德", "百度", "腾讯")
        if (embedded()) {
            targets = arrayOf(embeddedHost!!)
            labels = arrayOf(if (embeddedAmap()) "高德" else "百度")
        }
        var scoped = 0
        val picked = StringBuilder()
        if (state.scopeKnown) {
            for (i in targets.indices) {
                if (state.scope.contains(targets[i])) {
                    scoped++
                    if (picked.length > 0) picked.append(" / ")
                    picked.append(labels[i])
                }
            }
        }
        val scopeOk = scoped > 0

        var l1 = if (connected)
            (if (state.loaded) "已连接 · " + state.frameworkLabel() else "已连接 · 正在读取框架信息…")
        else
            "正在连接框架服务 · 请确认已启用模块"
        if (connected && state.apiVersion > 0 && state.apiVersion < 102) {
            l1 += "（需要 API 102+）"
        }
        val l2: String
        if (!connected) l2 = "连接后读取地图作用域"
        else if (!state.loaded) l2 = "正在读取地图作用域…"
        else if (!state.scopeKnown) l2 = "作用域读取失败 · 请在框架管理器确认，稍后重试"
        else if (scopeOk) l2 = "作用域已勾选 · " + picked + "（" + scoped + "/" + targets.size + "）"
        else l2 = "作用域未包含地图 · 请在框架管理器勾选地图应用"

        val scopeColor = if (!state.scopeKnown) cTxS() else (if (scopeOk) cDotOk() else cDotBad())

        // The hero shows a one-line verdict plus a coloured status pill; the full
        // scope / compatibility dump now lives behind a tap so it cannot wrap and
        // stretch the header the way the old five-row block did.
        val pillColor = if (connected && scopeOk) cDotOk() else (if (connected) cDotBad() else cTxS())
        scopeMetric = if (state.scopeKnown) (scoped.toString() + "/" + targets.size) else "未知"
        scopeMetricColor = if (!state.scopeKnown) cTxS() else (if (scopeOk) cDotOk() else cDotBad())

        // 心跳每几秒跑一次；文案与配色没变就不要再 setText / 重新分配 drawable。
        val sig = connected.toString() + "|" + state.loaded + "|" + state.scopeKnown + "|" + scoped +
                "|" + targets.size + "|" + state.frameworkLabel() + "|" + l1 + "|" + l2
        if (sig != lastStatusSig) {
            lastStatusSig = sig
            if (statusPillText != null) {
                statusPillText!!.text = if (connected) (if (scopeOk) "已就绪" else "待配置") else "连接中"
                statusPillText!!.setTextColor(pillColor)
            }
            if (navPill != null && navPill!!.childCount > 0) {
                navPill!!.getChildAt(0).background = pillDot(pillColor)
            }
            statusView!!.text = l1 + "　·　" + l2
        }
        refreshMetrics()
        refreshDiagRows()
    }

    private var lastStatusSig: String? = null
    private var pillDotColor = 0
    private var pillDotCache: GradientDrawable? = null

    /** 缓存 7dp 状态圆点：renderStatus 跑在心跳上，不该每拍都分配一个 drawable。 */
    private fun pillDot(color: Int): GradientDrawable {
        if (pillDotCache == null || pillDotColor != color) {
            pillDotColor = color
            pillDotCache = dotDrawable(color, 7)
        }
        return pillDotCache!!
    }

    /** 圆形色点 drawable（用于刷新状态药丸里的圆点） */
    private fun dotDrawable(color: Int, sizeDp: Int): GradientDrawable {
        val s = dp(sizeDp)
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(color)
        g.setSize(s, s)
        return g
    }

    /** 状态详情弹层：把 hero 折叠掉的适配 / 作用域信息集中展示 */
    private fun showStatusDetail() {
        val state = io.github.ldxm666.mapclean.App.frameworkStatus()
        val sb = StringBuilder()
        sb.append("框架：").append(if (state.connected)
            (if (state.loaded) state.frameworkLabel() else "已连接（读取中）") else "未连接")
        if (state.apiVersion > 0) sb.append("　API ").append(state.apiVersion)
        sb.append("\n")
        sb.append("作用域：").append(if (state.scopeKnown) joinScope(state) else "读取失败")
        sb.append("\n")
        sb.append("本机版本：v").append(Version.LABEL)
        val latest = UpdateChecker.latest(this)
        if (!latest.isNullOrEmpty()) sb.append("　最新：v").append(latest)
        sb.append("\n")
        var sdk = 0
        try {
            sdk = SdkAutoBlock.learnedListForApp().size
        } catch (ignored: Throwable) {
        }
        sb.append("已捕获广告 SDK：").append(sdk).append(" 个")
        val ready = if (adLine == null) "" else adLine!!.text.toString()
        if (ready.length > 0) sb.append("\n").append(ready)

        glassDialog("运行状态", sb.toString(),
                "查看 SDK 清单", Runnable { showLearnedSdks() },
                null, null,
                "关闭", null)
    }

    private fun joinScope(state: FrameworkStatus.Snapshot): String {
        val targets = arrayOf(MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP)
        val labels = arrayOf("高德", "百度", "腾讯")
        val sb = StringBuilder()
        for (i in targets.indices) {
            if (!state.scope.contains(targets[i])) continue
            if (sb.length > 0) sb.append(" / ")
            sb.append(labels[i])
        }
        return if (sb.length == 0) "未包含地图" else sb.toString()
    }

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 绑定前快轮询（等 LSPosed 服务），绑定后降频，避免每 800ms 白跑一遍 UI 刷新。 */
    // POLL_FAST / POLL_SLOW 见文件末尾的 companion object

    private val statusRefresher: Runnable = object : Runnable {
        override fun run() {
            io.github.ldxm666.mapclean.App.refreshFrameworkStatus()
            renderStatus()
            // 服务是异步绑定的：绑定前 readState() 只能回退默认 true，
            // 若此时就把开关画成"开"，用户重开设置页会以为配置全丢了。
            // 所以绑定成功后立刻按真实存储重刷一遍开关。
            val bound = App.svc() != null
            if (bound && !synced) {
                synced = true
                syncSwitches()
            } else if (!bound && synced) {
                synced = false // 服务掉了：下次绑定要重新对齐一遍开关
            }
            statusHandler.postDelayed(this, if (synced) POLL_SLOW else POLL_FAST)
        }
    }

    private val switches: MutableMap<String, GlassSwitch> = LinkedHashMap()

    @Volatile
    private var synced = false

    private fun syncSwitches() {
        try {
            val s = App.svc()
            if (s == null) return
            val p = s.getRemotePreferences(Config.PREF_GROUP)
            // 一次 getAll() 拿整张表：readVisible(prefs,key) 每个键都会复制一份 TreeMap。
            val values = p.all
            for (e in switches.entries) {
                val sw = e.value
                sw.setChecked(Config.readVisible(values, e.key), false)
            }
            // 百度那一页用的是另一个 group（bmapclean_config）—— 单独再刷一遍
            try {
                val bp = s.getRemotePreferences(io.github.ldxm666.bmapclean.Cfg.GROUP)
                for (e in bmapSwitches.entries) {
                    val sw = e.value
                    sw.setChecked(bp.getBoolean(e.key,
                            io.github.ldxm666.bmapclean.Spec.defaultVisible(e.key)), false)
                }
            } catch (ignored: Throwable) {
            }
            // 腾讯那一页同理（tmapclean_config）
            try {
                val tp = s.getRemotePreferences(io.github.ldxm666.tmapclean.Cfg.GROUP)
                for (e in tmapSwitches.entries) {
                    val sw = e.value
                    sw.setChecked(tp.getBoolean(e.key,
                            io.github.ldxm666.tmapclean.Spec.defaultChecked(
                                    e.key, io.github.ldxm666.tmapclean.Spec.APP_READ)), false)
                }
            } catch (ignored: Throwable) {
            }
            refreshSdkRow()
        } catch (ignored: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 对话框

    // ------------------------------------------------------------ 玻璃对话框

    /** 框架 AlertDialog 套玻璃皮：透明窗口 + 自绘玻璃圆角容器，标题/正文/三键齐全 */
    private fun glassDialog(title: String, message: String?,
                            posText: String?, posAction: Runnable?,
                            neuText: String?, neuAction: Runnable?,
                            negText: String?, negAction: Runnable?): AlertDialog {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = skin!!.glassFor(box, GLASS_R.toFloat())
        val pad = dp(20)
        box.setPadding(pad, dp(18), pad, dp(10))
        val tv = text(title, 18f, Typeface.BOLD, cTxP())
        box.addView(tv)
        if (!message.isNullOrEmpty()) {
            val mv = text(message, 14f, Typeface.NORMAL, cTxS())
            mv.setLineSpacing(0f, 1.2f)
            val mlp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            mlp.topMargin = dp(10)
            box.addView(mv, mlp)
        }
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.RIGHT
        val blp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(16)
        box.addView(btns, blp)
        val dlg = AlertDialog.Builder(this).create()
        val defs: MutableList<Array<String>> = ArrayList()
        if (negText != null) defs.add(arrayOf(negText, "neg"))
        if (neuText != null) defs.add(arrayOf(neuText, "neu"))
        if (posText != null) defs.add(arrayOf(posText, "pos"))
        for (def in defs) {
            val b = text(def[0], 14f, Typeface.BOLD, cAccent())
            b.setPadding(dp(14), dp(10), dp(14), dp(10))
            b.background = ripple(if (dark) tintBg(0x2E7AB1FF) else tintBg(0x1E0A6CF5), HEAD_R.toFloat())
            val run = if ("pos" == def[1]) posAction else if ("neu" == def[1]) neuAction else negAction
            val act: Runnable = run ?: Runnable { }
            b.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    act.run()
                    dlg.dismiss()
                }
            })
            val blp2 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp2.leftMargin = dp(8)
            btns.addView(b, blp2)
        }
        dlg.setView(box)
        dlg.show()
        val w = dlg.getWindow()
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            val lp = w.getAttributes()
            lp.dimAmount = 0.45f
            lp.width = Math.min(resources.displayMetrics.widthPixels - dp(48), dp(360))
            w.setAttributes(lp)
        }
        return dlg
    }

    private fun tintBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(HEAD_R.toFloat()).toFloat()
        return g
    }

    // ------------------------------------------------------------ 配置管理

    /**
     * ⚠ 死代码（§4 P1）：只从零调用的 buildBottomBar() 可达，模块版用户永远打不开。
     * 保留不删，仅标注。CFG_SNAPSHOT / CFG_FILE 见文件末尾 companion object。
     */
    private fun showConfigManager() {
        val hasSnap = readSnapshot() != null
        glassDialog("配置管理",
                "保存配置：把高德、百度及桌面图标设置存到本地\n" +
                "恢复配置：回到上次保存的快照\n" +
                "导出 / 导入：通过分享或粘贴迁移到其它设备" + (if (hasSnap) "\n\n已有本地快照" else "\n\n尚未保存过快照"),
                "保存配置", Runnable { saveSnapshot() },
                "恢复配置", if (hasSnap) Runnable {
                    glassDialog("恢复配置？", "将覆盖当前全部开关，回到上次保存的快照。",
                            "恢复", Runnable { restoreSnapshot() }, null, null, "取消", null)
                } else null,
                "更多", Runnable { showConfigMore() })
    }

    /** ⚠ 死代码（§4 P1）：只在死掉的 showConfigManager() 链里被调。保留不删，仅标注。 */
    private fun showConfigMore() {
        glassDialog("导入 / 导出",
                "导出：生成配置文本并分享（可发给自己 / 存文件）\n" +
                "导入：粘贴配置文本恢复\n" +
                "文件：直接读写 .json 文件（推荐，跨设备迁移用这个）",
                "导出", Runnable { exportConfig() },
                "导入", Runnable { importConfig() },
                "文件…", Runnable { showConfigFile() })
    }

    /** 「文件…」二级菜单：真正的 .json 文件读写（系统文件选择器）。 */
    /** ⚠ 死代码（§4 P1）：只在 showConfigMore() → showConfigManager() 链里被调。保留不删，仅标注。 */
    private fun showConfigFile() {
        glassDialog("配置文件（.json）",
                "导出到文件：把" + settingsTargetLabel() + "的开关写成一个 .json，存到手机任意位置\n" +
                "从文件导入：选一个 .json，先预览将覆盖多少项，确认后才写入",
                "导出到文件", Runnable {
                    ConfigBackup.bind(configStore(), configSchema(), packageName)
                    ConfigBackup.startExport(this@MainActivity, ConfigBackup.REQ_EXPORT)
                    val err = ConfigBackup.takeError()
                    if (err != null) Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                },
                "从文件导入", Runnable {
                    ConfigBackup.bind(configStore(), configSchema(), packageName)
                    ConfigBackup.startImport(this@MainActivity, ConfigBackup.REQ_IMPORT)
                    val err = ConfigBackup.takeError()
                    if (err != null) Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                },
                "恢复默认", Runnable {
                    glassDialog("恢复默认？", "恢复" + settingsTargetLabel() + "的默认开关，强停地图 App 后生效。",
                            "恢复默认", Runnable { resetConfig() }, null, null, "取消", null)
                })
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ConfigBackup.REQ_EXPORT) {
            val message = ConfigBackup.handleExportResult(this, requestCode, resultCode, data)
            toast(message)
            setBackupStatus(message, !message.startsWith("已导出"))
        } else if (requestCode == ConfigBackup.REQ_IMPORT) {
            val message = ConfigBackup.handleImportResult(this, requestCode, resultCode, data)
            if (ConfigBackup.hasPendingImport()) {
                val detail = ConfigBackup.pendingSummary()
                glassDialog("确认导入？",
                        message + (if (detail.length == 0) "" else "\n\n" + detail) +
                                "\n\n确认后会覆盖同名配置，地图 App 需强停重开才生效。",
                        "导入", Runnable {
                            val result = ConfigBackup.confirmImport()
                            toast(result)
                            setBackupStatus(result, !result.startsWith("已导入"))
                            syncAllSwitches()
                        }, null, null, "取消", null)
            } else {
                toast(message)
                setBackupStatus(message, !message.startsWith("已取消"))
            }
        }
    }

    /** 空文案不弹（ConfigBackup 在用户取消时返回空串）。 */
    private fun toast(s: String?) {
        if (s == null || s.length == 0) return
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    /** 导入后把界面上的开关状态拉回与配置一致。 */
    private fun syncAllSwitches() {
        try {
            syncSwitches()
        } catch (ignored: Throwable) {
        }
    }

    private fun configSchema(): MutableMap<String, MutableSet<String>> {
        val schema: MutableMap<String, MutableSet<String>> = LinkedHashMap()
        val amap: MutableSet<String> = LinkedHashSet(switches.keys)
        amap.add(ConfigBackup.LEARNED)
        if (!embedded() || embeddedAmap()) schema[ConfigBackup.AMAP] = amap
        if (!embedded() || !embeddedAmap()) schema[ConfigBackup.BAIDU] = LinkedHashSet(bmapSwitches.keys)
        if (!embedded()) schema[ConfigBackup.UI] = LinkedHashSet(java.util.Arrays.asList(App.K_HIDE_ICON))
        return schema
    }

    private fun configStore(): ConfigBackup.Store {
        val service = App.svc()
        if (service == null) throw IllegalStateException("框架服务未连接，请稍后重试")
        return object : ConfigBackup.Store {
            private fun prefs(group: String): android.content.SharedPreferences {
                return if (ConfigBackup.UI == group) uiPrefs() else service.getRemotePreferences(group)
            }

            override fun read(group: String): Map<String, *> {
                return prefs(group).all
            }

            override fun write(group: String, values: Map<String, Any?>): Boolean {
                val editor = prefs(group).edit()
                for (entry in values.entries) {
                    val value = entry.value
                    if (value == null) editor.remove(entry.key)
                    else if (value is Boolean) editor.putBoolean(entry.key, value)
                    else if (value is Set<*>) editor.putStringSet(entry.key, LinkedHashSet<String>(value as Set<String>))
                    else throw IllegalArgumentException("配置类型错误")
                }
                return editor.commit()
            }
        }
    }

    private fun collectConfig(): JSONObject {
        return ConfigBackup.encode(configStore(), configSchema(), packageName)
    }

    private fun saveSnapshot() {
        try {
            val json = collectConfig().toString()
            if (!uiPrefs().edit()
                    .putString(CFG_SNAPSHOT, json).commit()) throw IllegalStateException("本地快照写入失败")
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "保存失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun readSnapshot(): JSONObject? {
        try {
            val j = uiPrefs()
                    .getString(CFG_SNAPSHOT, null)
            if (j != null) {
                val o = JSONObject(j)
                ConfigBackup.decode(o, configSchema())
                return o
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun applyConfig(backup: JSONObject) {
        try {
            val groups = ConfigBackup.decode(backup, configSchema())
            val count = ConfigBackup.apply(configStore(), groups)
            if (!embedded() && groups.containsKey(ConfigBackup.UI) && !App.setHideIcon(this, App.hideIcon(this)))
                throw IllegalStateException("开关已恢复，桌面图标更新失败，请重新切换图标开关")
            val scope = if (embedded()) settingsTargetLabel()
            else (if (groups.containsKey(ConfigBackup.BAIDU)) "高德和百度" else "高德")
            Toast.makeText(this, "已恢复" + scope + " " + count + " 项，重开地图后生效", Toast.LENGTH_LONG).show()
            recreate()
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetConfig() {
        try {
            val groups: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()
            for (entry in configSchema().entries) {
                if (ConfigBackup.UI == entry.key) continue
                val values: MutableMap<String, Any?> = LinkedHashMap()
                for (key in entry.value) values[key] = null
                groups[entry.key] = values
            }
            ConfigBackup.apply(configStore(), groups)
            Toast.makeText(this, settingsTargetLabel() + "已恢复默认，重开地图后生效", Toast.LENGTH_LONG).show()
            recreate()
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSnapshot() {
        val backup = readSnapshot()
        if (backup == null) {
            Toast.makeText(this, "没有可用快照", Toast.LENGTH_SHORT).show()
            return
        }
        applyConfig(backup)
    }

    private fun exportConfig() {
        try {
            val json = collectConfig().toString(2)
            val send = Intent(Intent.ACTION_SEND)
            send.setType("application/json")
            send.putExtra(Intent.EXTRA_SUBJECT, CFG_FILE)
            send.putExtra(Intent.EXTRA_TEXT, json)
            send.putExtra(Intent.EXTRA_TITLE, CFG_FILE)
            startActivity(Intent.createChooser(send, "导出配置"))
        } catch (t: Throwable) {
            Toast.makeText(this, "导出失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun importConfig() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val input = EditText(this)
        input.setHint("粘贴导出的 JSON 配置")
        input.setTextSize(13f)
        input.setTextColor(cTxP())
        input.setHintTextColor(cTxS())
        input.background = tintBg(if (dark) 0x33000000 else 0x14000000)
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (cm != null) {
            val cd = cm.primaryClip
            if (cd != null && cd.itemCount > 0) {
                val t = cd.getItemAt(0).text
                if (!t.isNullOrEmpty()) input.setText(t)
            }
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = skin!!.glassFor(box, GLASS_R.toFloat())
        val pad = dp(20)
        box.setPadding(pad, dp(18), pad, dp(10))
        box.addView(text("导入配置", 18f, Typeface.BOLD, cTxP()))
        // 内置默认配置的入口与导入同源（都是「把一份 JSON 应用过来」），放在这里而不是
        // 再挤一个按钮进「更多」—— 那个对话框的三键布局是固定的。
        box.addView(restoreDefaultsRow())
        val ilp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        ilp.topMargin = dp(12)
        box.addView(input, ilp)
        val tip = text("粘贴后点导入；键名不匹配的项会被忽略", 12f, Typeface.NORMAL, cTxS())
        val tlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(6)
        box.addView(tip, tlp)
        val btns = LinearLayout(this)
        btns.gravity = Gravity.RIGHT
        val blp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(14)
        box.addView(btns, blp)
        val cancel = text("取消", 14f, Typeface.BOLD, cTxS())
        cancel.setPadding(dp(14), dp(10), dp(14), dp(10))
        val go = text("导入", 14f, Typeface.BOLD, cAccent())
        go.setPadding(dp(14), dp(10), dp(14), dp(10))
        go.background = ripple(if (dark) tintBg(0x2E7AB1FF) else tintBg(0x1E0A6CF5), HEAD_R.toFloat())
        go.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                doImport(input.text.toString())
            }
        })
        val clp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.leftMargin = dp(8)
        btns.addView(cancel, clp)
        btns.addView(go, clp)
        val d2 = AlertDialog.Builder(this).create()
        d2.setView(box)
        d2.show()
        val w = d2.getWindow()
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            val lp = w.getAttributes()
            lp.dimAmount = 0.45f
            lp.width = Math.min(resources.displayMetrics.widthPixels - dp(48), dp(360))
            w.setAttributes(lp)
        }
        cancel.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                d2.dismiss()
            }
        })
    }

    private fun doImport(text: String) {
        try {
            applyConfig(JSONObject(text))
        } catch (t: Throwable) {
            Toast.makeText(this, "JSON 解析失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 「恢复默认配置」一行：重新应用模块内置的那份 JSON。
     *
     * <p>与 {@link #importConfig()} 的写入逻辑同源（都走 {@code DefaultConfig}），不重复造。
     * 与应用首启的区别只有一处：这里是无条件覆盖，所以先确认。
     */
    private fun restoreDefaultsRow(): View {
        val action = text("恢复默认配置", 13f, Typeface.BOLD, cAccent())
        action.setPadding(dp(2), dp(6), dp(2), dp(6))
        action.background = ripple(if (dark) tintBg(0x2E7AB1FF) else tintBg(0x1E0A6CF5), HEAD_R.toFloat())
        action.gravity = Gravity.CENTER
        action.contentDescription = "重新应用模块内置的默认配置，会覆盖当前开关"
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        action.layoutParams = lp
        action.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                glassDialog("恢复默认配置？",
                        "将把模块内置的默认配置重新写入，会覆盖你已经改过的开关。\n\n" +
                        "没有值的项（null）保持原样；写入后重开地图生效。",
                        "恢复", Runnable { applyBuiltInDefaults() }, null, null, "取消", null)
            }
        })
        return action
    }

    /** 应用内置默认配置并刷新界面；失败只提示，不中断。 */
    private fun applyBuiltInDefaults() {
        try {
            val result = DefaultConfig.apply(this)
            if (result.groups.isEmpty()) {
                Toast.makeText(this, "没有可写入的默认配置（服务未连接或资源缺失）",
                        Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "已恢复 " + result.applied + " 项，重开地图后生效",
                    Toast.LENGTH_LONG).show()
            recreate()
        } catch (t: Throwable) {
            Toast.makeText(this, "恢复失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 联系作者：Telegram 群组 + GitHub Issues
     *
     * ⚠ 死代码（§4 P1）：只从零调用的 buildBottomBar() 可达。保留不删，仅标注。
     */
    private fun showContact() {
        glassDialog("联系作者",
                "TG 通知频道：发版公告 / 重要通知\n" +
                "TG 聊天群：反馈 / 交流 / 提问\n" +
                "GitHub Issues：bug 报告 / 功能需求",
                "TG 通知频道", Runnable { openUrl(Version.TG) },
                "TG 聊天群", Runnable { openUrl(Version.TG_CHAT) },
                "GitHub Issues", Runnable { openUrl("https://github.com/ldxm666/MapAdKiller/issues") })
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 已捕获清单；还能一键清空重新学习 */
    private fun showLearnedSdks() {
        var list: List<String>? = null
        try {
            list = SdkAutoBlock.learnedListForApp()
        } catch (t: Throwable) {
            list = null
        }
        if (list.isNullOrEmpty()) {
            glassDialog("已捕获广告 SDK",
                    "还没有捕获记录。\n\n模块会扫描三家地图 App 自身 dex 里带广告特征的类，" +
                    "把厂商包名记下来并拦截；记录跨进程保存，下次启动直接生效。",
                    "知道了", null, null, null, null, null)
            return
        }
        val sb = StringBuilder()
        for (s in list!!) sb.append(s).append('\n')
        glassDialog("已捕获广告 SDK（" + list!!.size + "）",
                sb.toString().trim(),
                "关闭", null,
                "清空重新学习", Runnable {
                    try {
                        SdkAutoBlock.clearLearned()
                    } catch (ignored: Throwable) {
                    }
                    Toast.makeText(this@MainActivity, "已清空，强停对应地图后重新打开即重新学习",
                            Toast.LENGTH_LONG).show()
                    recreate()
                }, null, null)
    }

    // ---------------------------------------------------------------- 行工厂

    private fun newCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = skin!!.glassFor(card, 20f)
        card.setPadding(dp(8), dp(6), dp(8), dp(6))
        UiKit.clipRounded(card, dp(20f).toFloat())
        skin!!.shadow(card, 3f)
        return card
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(4)
        return lp
    }

    /**
     * 行间分割线 —— 左缩进 16 dp，与文字左边缘对齐。
     *
     * <p>HyperOS 的分割线不从卡片边缘起笔。全宽分割线会把卡片切成"一格一格的表格"，
     * 缩进之后才读得出"这是一个分组内的多行"。缩进量等于行的左右 padding。
     */
    /** miuix `DividerDefaults.Thickness` —— 0.75 dp。 */
    private val DIVIDER_THICKNESS_DP = 0.75f

    private fun divider(): View {
        val d = View(this)
        d.setBackgroundColor(if (skin == null) 0x0F000000 else skin!!.divider)
        // 0.75 dp —— miuix 的 DividerDefaults.Thickness。1 dp 在 3x 屏上是 3 px，
        // 比文字笔画还粗，读起来像表格线；0.75 dp 落到 2 px，正好是一条"分隔"而不是"框"。
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(DIVIDER_THICKNESS_DP)))
        lp.leftMargin = dp(HyperDesign.DIVIDER_INSET)
        d.layoutParams = lp
        return d
    }

    /** 行容器：60dp 最小高、行级涟漪、可点回弹 */
    private fun newRow(card: LinearLayout): LinearLayout {
        if (card.childCount > 0) card.addView(divider())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56)
        row.setPadding(dp(8), dp(2), dp(8), dp(2))
        row.background = UiKit.rippleBare(cRipple(), dp(12f).toFloat())
        return row
    }

    /**
     * 开关行 + 搜索索引。
     *
     * <p>haystack（标题 + 备注 + 配置键）直接挂在行的 tag 上，搜索时
     * {@link #filterRows} 顺着视图树读出来即可 —— 不必另外维护一份
     * “行 → key”的映射，也就不可能和界面显示的文字脱节。
     */
    private fun newSwitchRow(card: LinearLayout, key: String?, title: String?, note: String?): LinearLayout {
        val row = newRow(card)
        val sb = StringBuilder()
        sb.append(title ?: "")
        if (!note.isNullOrEmpty()) sb.append('\n').append(note)
        sb.append('\n').append(key ?: "")
        row.setTag(TAG_HAYSTACK, sb.toString())
        return row
    }

    /** 行上缓存的 haystack key。用框架不会占用的负数 id，避免再建 ids.xml。 */
    // TAG_HAYSTACK 常量见 companion object
    /** 统一规格开关行：60dp 最小高、左标题右侧胶囊开关、行间分隔线（写 RemotePreferences） */

    /**
     * 一行滑块（值 = min + position * 1）。高德「自动巡航」的两个触发值用它。
     *
     * <p>读写走 {@code Config.getInt/put}，和地图内悬浮球面板完全同一组 key，
     * 两边改哪边都同步。
     */
    private fun addCruiseSlider(card: LinearLayout, title: String, key: String,
                                min: Int, max: Int, def: Int, unit: String) {
        val row = skin!!.row()
        row.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(skin!!.text(title, Skin.T_ROW, Skin.MEDIUM, skin!!.text2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = skin!!.text("", Skin.T_ROW, Skin.BOLD, skin!!.accent)
        head.addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(head, skin!!.matchWrap())

        val stops = (max - min) + 1
        // 先问服务（模块版：地图进程的 remote prefs），拿不到再退回本地（内置版同进程）。
        val init = Math.max(min, Math.min(max, App.readInt(key, Config.getInt(key, def))))
        val slider = GlassSlider(this)
        slider.setStops(stops)
        slider.setTrackColors(0x1F8A94A4, skin!!.accent, skin!!.accent)
        slider.setTrackBandDp(40f)
        slider.setPosition(init - min)
        value.text = init.toString() + unit
        slider.contentDescription = title + "，" + init + unit
        slider.setOnPositionChange(object : GlassSlider.OnPositionChange {
            override fun onPositionChanged(view: GlassSlider, position: Int, settled: Boolean) {
                val v = min + position
                value.text = v.toString() + unit
                view.contentDescription = title + "，" + v + unit
                if (settled) {
                    // 只能走 App.writeInt：模块 App 和地图不是一个进程，写本地 prefs 地图侧永远读不到。
                    // 写失败（服务没绑上 = 高德没运行）就退回原值并说明原因 —— 静默丢弃会让用户
                    // 以为调好了，然后去怪巡航不触发。
                    if (!App.writeInt(key, v)) {
                        val back = Math.max(min, Math.min(max, App.readInt(key, def)))
                        view.setPosition(back - min)
                        value.text = back.toString() + unit
                        view.contentDescription = title + "，" + back + unit
                        Toast.makeText(this@MainActivity,
                                "未连接地图：先打开高德，再回来调", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, skin!!.dp(56f))
        row.addView(slider, slp)
        card.addView(row, skin!!.matchWrap())
    }

    /**
     * 一行「离散值」选择器：60 / 90 / 120 这种只允许几档的整数配置。
     *
     * <p>为什么不复用 {@link #addCruiseSlider}：那个是「min + position，步长 1」的连续滑块，
     * 帧率上限用它会长出 61 个无意义档位（77 帧、83 帧……）。帧率上限只有在屏幕刷新率的
     * 整数分频点上才有确定含义，所以这里给三个按钮，点哪个就是哪个。
     *
     * <p>写盘走 {@code App.writeInt}：模块 App 和地图不是一个进程，写本地 prefs 地图侧永远
     * 读不到。写失败（服务没绑上 = 高德没运行）要当场说清楚并保持原高亮，否则用户会以为调好了，
     * 然后去怪帧率没变。
     */
    private fun addChoiceRow(card: LinearLayout, title: String, key: String,
                             values: IntArray, def: Int, unit: String) {
        val row = skin!!.row()
        row.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(skin!!.text(title, Skin.T_ROW, Skin.MEDIUM, skin!!.text2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(head, skin!!.matchWrap())

        val init = App.readInt(key, Config.getInt(key, def))
        val choices = LinearLayout(this)
        choices.orientation = LinearLayout.HORIZONTAL
        val buttons = arrayOfNulls<TextView>(values.size)
        for (i in values.indices) {
            val value = values[i]
            val accent = skin!!.accent
            val dim = skin!!.text2
            val button = skin!!.text(value.toString() + unit, Skin.T_ROW, Skin.BOLD,
                    if (value == init) accent else dim)
            button.gravity = Gravity.CENTER
            button.setPadding(0, dp(10), 0, dp(10))
            button.contentDescription = title + "，" + value + unit
            button.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    if (!App.writeInt(key, value)) {
                        Toast.makeText(this@MainActivity, "未连接地图：先打开高德，再回来调",
                                Toast.LENGTH_SHORT).show()
                        return
                    }
                    for (k in values.indices) {
                        buttons[k]!!.setTextColor(if (values[k] == value) accent else dim)
                    }
                }
            })
            buttons[i] = button
            choices.addView(button, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(choices, skin!!.matchWrap())
        card.addView(row, skin!!.matchWrap())
    }

    private fun addSwitch(card: LinearLayout, title: String, key: String) {
        val row = newSwitchRow(card, key, title, null)

        val label = text(title, 14.5f, Typeface.NORMAL, cTxP())
        label.setPadding(0, dp(11), dp(10), dp(11))
        label.setLineSpacing(0f, 1.15f)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = makeSwitch()
        sw.setLabel(title)
        sw.setChecked(readState(key), false)
        switches[key] = sw
        index(finder, key, title, null, PAGE_AMAP)
        row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 单一真相路径：开关自己吃掉点击并翻转 → 回调里落盘；落盘失败就回滚。
        // 行点击只是把同一个 performClick() 代理一遍，所以胶囊与行永远不会打架。
        sw.setOnCheckedChange(object : GlassSwitch.OnCheckedChange {
            override fun onCheckedChanged(view: GlassSwitch, next: Boolean) {
                if (App.writeBoolean(key, next)) {
                    if (Config.K_TOOLS_ALLOWLIST == key) syncSwitches()
                } else {
                    view.setChecked(!next, true)
                    Toast.makeText(this@MainActivity, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show()
                }
            }
        })
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                sw.performClick()
            }
        })
    }

    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    private fun addLocalSwitch(card: LinearLayout, title: String, key: String) {
        val row = newRow(card)

        val label = text(title, 14.5f, Typeface.NORMAL, cTxP())
        label.setPadding(0, dp(11), dp(10), dp(11))
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = makeSwitch()
        sw.setLabel(title)
        var on = false
        try {
            on = getSharedPreferences(App.UI_PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
        } catch (ignored: Throwable) {
        }
        sw.setChecked(on, false)
        row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 本地开关不进 RemotePreferences：翻转后先弹确认，确认了才落盘，取消就回滚。
        sw.setOnCheckedChange(object : GlassSwitch.OnCheckedChange {
            override fun onCheckedChanged(view: GlassSwitch, next: Boolean) {
                if (!next) {
                    applyHide(false, view)
                    return
                }
                // 桌面入口（LAUNCHER alias）禁掉后桌面看不到，但 LSPosed 管理器
                // 走 CATEGORY_INFO 通道（见 AndroidManifest 注释），入口不受影响。
                glassDialog("隐藏桌面图标？",
                        "· 桌面/抽屉里的 MapAdKiller 图标消失\n" +
                        "· LSPosed 管理器里的「打开」入口不受影响，随时可回到本页\n" +
                        "· 快捷设置磁贴与 adb 命令同样始终可用",
                        "仍然隐藏", Runnable { applyHide(true, view) }, null, null, "取消", Runnable { view.setChecked(false, true) })
            }
        })
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                sw.performClick()
            }
        })
    }

    private fun applyHide(hide: Boolean, sw: GlassSwitch) {
        if (embedded()) return
        val ok = App.setHideIcon(this, hide)
        sw.setChecked(hide)
        Toast.makeText(this,
                if (ok) (if (hide) "已隐藏桌面图标（LSPosed 管理器可随时再打开）" else "已恢复桌面图标")
                else "组件状态变更被系统拒绝",
                Toast.LENGTH_SHORT).show()
    }

    /** 当前状态：优先读 RemotePreferences（Hook 侧同一数据源），读不到按各键默认值 */
    private fun readState(key: String): Boolean {
        val def = Config.defaultVisible(key)
        try {
            val s = App.svc()
            if (s != null) return Config.readVisible(s.getRemotePreferences(Config.PREF_GROUP), key)
        } catch (ignored: Throwable) {
        }
        return def
    }

    /** 把 SDK 计数刷新成实时值 */
    private fun refreshSdkRow() {
        try {
            sdkRow?.setText(sdkSummary())
        } catch (ignored: Throwable) {
        }
    }

    /** 设置页上那一行：已自动捕获多少个广告 SDK */
    private fun sdkSummary(): String {
        var n = 0
        try {
            n = SdkAutoBlock.learnedListForApp().size
        } catch (ignored: Throwable) {
        }
        return "已捕获广告 SDK：" + n + " 个 · 点按查看清单"
    }

    private fun addActionButton(card: LinearLayout, title: String, action: Runnable): TextView {
        if (card.childCount > 0) card.addView(divider())
        val label = text(title, 13.5f, Typeface.BOLD, cAccent())
        label.setPadding(dp(14), dp(13), dp(14), dp(13))
        label.gravity = Gravity.CENTER
        label.background = UiKit.ripple(cRipple(), UiKit.plate(tintBg(), dp(14).toFloat()), dp(14).toFloat())
        label.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action.run()
            }
        })
        UiKit.pressable(label, 0.97f)
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        lp.bottomMargin = dp(10)
        card.addView(label, lp)
        return label
    }

    private fun tintBg(): Int = if (dark) 0x2E7AB1FF else 0x1A0A6CF5

    /** 说明文字：左侧细色条 + 淡色正文，与开关行区分开 */
    private fun addNoteRow(card: LinearLayout, s: String) {
        // A hint belongs to the row above, so it is a quiet 12 sp line, not its own entry.
        val t = skin!!.text(s, 12f, Skin.REGULAR, cTxS())
        t.setLineSpacing(dp(2).toFloat(), 1.25f)
        t.setPadding(dp(16), dp(2), dp(16), dp(10))
        card.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 版式：底部标签栏 + 首页 / 配置 / 备份 / 关于 四页
    // ══════════════════════════════════════════════════════════════════════
    //
    // 设计基线（见 Skin.java）：
    //   · 4/8/12/16/20/24/32 间距阶梯，卡片 20 dp 圆角 + 1 px 描边；
    //   · 每屏一个强调色（蓝），状态色只用于状态点；
    //   · 页面标题 26 sp / 卡片标题 15 sp / 正文 13.5 sp / 注释 12 sp / 标签 11 sp。
    // 信息架构：顶部原先那整块（标题 / 状态 / 三格指标 / 更新行）整体搬进首页；
    // 三个地图的开关页收进「配置」；配置文件的读写独立成「备份」；链接与通用设置进「关于」。

    // TAB_HOME / TAB_CONFIG / TAB_BACKUP / TAB_ABOUT 见 companion object

    // skin / contentHost / configScreen / homePage / backupPage / aboutPage 等字段
    // 在 Java 版里就声明在这个位置（类中部），Kotlin 允许属性先使用后声明，故保持原位。
    private var skin: Skin? = null
    private lateinit var contentHost: FrameLayout
    private lateinit var configScreen: View
    private var homePage: ScrollView? = null
    private lateinit var backupPage: ScrollView
    private lateinit var aboutPage: ScrollView
    private lateinit var configHost: FrameLayout
    private val appPages: MutableList<ScrollView> = ArrayList()
    private val appPageIds: MutableList<Int> = ArrayList()
    private val appChips: MutableList<TextView> = ArrayList()
    private lateinit var navBar: LinearLayout
    private val navItems: MutableList<LinearLayout> = ArrayList()
    private val navIcons: MutableList<Icon> = ArrayList()
    private val navLabels: MutableList<TextView> = ArrayList()
    private var tabIndex = TAB_HOME
    private var homeAnimated = false
    private var appIndex = 0
    private var backupStatus: TextView? = null
    private var configSummary: TextView? = null

    /* ────────────────────────────────────────────────────────────── 首页 */

    // ══════════════════════════════════════════════════════════════ 首页 · 服务器公告

    /**
     * 首页顶部的公告卡 —— 模块版的**主入口**（内置版的主入口在悬浮面板首页的横幅上）。
     *
     * <p>两种状态：有未读公告时整卡走 accent 着色 + 右侧红点，已读后退回普通玻璃卡，
     * 仍可点开重看。视觉原子全部来自 {@link Skin}（{@code card/glassFor/tintedGlass/rowTitle/
     * text/iconTile}），不新造圆角与字号。
     *
     * <p>卡片只建一次，内容用 {@link #renderAnnounceCard} 重建：这样点击事件不会随着公告
     * 状态变化丢失，也避免每次刷新往 root 里再塞一张卡。
     */
    private var announceCard: LinearLayout? = null

    private fun buildAnnounceCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(skin!!.dp(Skin.S4.toFloat()), skin!!.dp(Skin.S4.toFloat()), skin!!.dp(Skin.S4.toFloat()), skin!!.dp(Skin.S4.toFloat()))
        UiKit.clipRounded(card, skin!!.dp(Skin.R_CARD.toFloat()).toFloat())
        skin!!.shadow(card, 7f)
        card.isClickable = true
        UiKit.pressable(card, 0.995f)
        card.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                Announce.showLatest(this@MainActivity)
            }
        })
        renderAnnounceCard(card)

        // 读完公告后把红点去掉。Weak 引用，Activity 没了回调自动失效。
        val self = java.lang.ref.WeakReference<MainActivity>(this)
        Announce.setOnChanged(Runnable {
            val activity = self.get()
            if (activity != null && activity.announceCard != null) {
                activity.renderAnnounceCard(activity.announceCard!!)
            }
        })
        return card
    }

    /** 重画卡片内容（不改卡片本身）。未读 ⇒ 着色 + 红点；已读 ⇒ 普通卡 + 标题即最近一条。 */
    private fun renderAnnounceCard(card: LinearLayout) {
        var unread = false
        var latest: String? = null
        try {
            unread = Announce.hasUnread(this)
            latest = Announce.latestTitle(this)
        } catch (ignored: Throwable) {
        }
        card.background = if (unread)
            skin!!.tintedGlass(skin!!.accent, Skin.R_CARD.toFloat())
        else
            skin!!.glassFor(card, Skin.R_CARD.toFloat())
        card.removeAllViews()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(iconTile(Icon.INFO, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 38, 38))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(skin!!.rowTitle(if (unread) "服务器公告" else "最新公告"))
        val line = skin!!.text(if (latest != null) latest else "暂无公告",
                Skin.T_CAPTION, Skin.REGULAR, skin!!.text2)
        line.isSingleLine = true
        line.ellipsize = android.text.TextUtils.TruncateAt.END
        val lineLp = skin!!.matchWrap()
        lineLp.topMargin = skin!!.dp(2f)
        texts.addView(line, lineLp)
        val textsLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textsLp.leftMargin = skin!!.dp(12f)
        row.addView(texts, textsLp)

        if (unread) {
            val dotLp = fixed(skin!!, 8, 8)
            dotLp.rightMargin = skin!!.dp(6f)
            row.addView(dot(skin!!.danger, 8), dotLp)
        }
        row.addView(Icon(this, Icon.CHEVRON, if (unread) skin!!.accent else skin!!.text3),
                fixed(skin!!, 18, 18))
        card.addView(row, skin!!.matchWrap())
    }

    private fun buildHomePage(): ScrollView {
        val sv = ScrollView(this)
        sv.setFillViewport(true)
        sv.clipToPadding = false
        sv.isVerticalScrollBarEnabled = false

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S3.toFloat()), skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S7.toFloat()))
        sv.addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 标题行：品牌 + 版本胶囊
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.VERTICAL
        brand.addView(skin!!.display("MapAdKiller"))
        val tagline = skin!!.caption(if (embedded())
                "地图广告净化 · 免 root 整合版"
                else "地图广告净化 · LSPosed 模块")
        val tagLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tagLp.topMargin = skin!!.dp(3f)
        brand.addView(tagline, tagLp)
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(skin!!.chip("v" + Version.LABEL, skin!!.accentSoft, skin!!.accent))
        top.setPadding(skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S1.toFloat()), skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S2.toFloat()))
        top.background = skin!!.headerScrim()
        root.addView(top, skin!!.matchWrap())

        announceCard = buildAnnounceCard()
        root.addView(announceCard, withTop(skin!!, Skin.S4))

        // 状态卡：这一块就是原来屏幕顶部的那一整块，只是挪到了首页
        val status = LinearLayout(this)
        status.orientation = LinearLayout.VERTICAL
        status.setPadding(skin!!.dp(18f), skin!!.dp(18f), skin!!.dp(18f), skin!!.dp(14f))
        status.background = skin!!.glassFor(status, Skin.R_CARD.toFloat(),
                UiKit.blend(skin!!.glassTint(), skin!!.accent, 0.08f))
        UiKit.clipRounded(status, skin!!.dp(Skin.R_CARD.toFloat()).toFloat())
        skin!!.shadow(status, 6f)
        status.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showStatusDetail()
            }
        })
        UiKit.pressable(status, 0.995f)

        val statusTop = LinearLayout(this)
        statusTop.orientation = LinearLayout.HORIZONTAL
        statusTop.gravity = Gravity.CENTER_VERTICAL

        navPill = LinearLayout(this)
        navPill!!.orientation = LinearLayout.HORIZONTAL
        navPill!!.gravity = Gravity.CENTER_VERTICAL
        val dotLp = LinearLayout.LayoutParams(skin!!.dp(8f), skin!!.dp(8f))
        dotLp.rightMargin = skin!!.dp(7f)
        dotLp.gravity = Gravity.CENTER_VERTICAL
        navPill!!.addView(dot(skin!!.success, 8), dotLp)
        statusPillText = skin!!.text("连接中", 20f, Skin.BOLD, skin!!.text)
        navPill!!.addView(statusPillText)
        statusTop.addView(navPill, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val detail = LinearLayout(this)
        detail.orientation = LinearLayout.HORIZONTAL
        detail.gravity = Gravity.CENTER_VERTICAL
        detail.addView(skin!!.text("运行状态", Skin.T_CAPTION, Skin.MEDIUM, skin!!.accent))
        val chev = Icon(this, Icon.CHEVRON, skin!!.accent)
        val chevLp = LinearLayout.LayoutParams(skin!!.dp(15f), skin!!.dp(15f))
        chevLp.leftMargin = skin!!.dp(2f)
        detail.addView(chev, chevLp)
        statusTop.addView(detail)
        status.addView(statusTop, skin!!.matchWrap())

        statusView = skin!!.text("", Skin.T_CAPTION + 0.5f, Skin.REGULAR, skin!!.text2)
        statusView!!.setLineSpacing(skin!!.dp(2f).toFloat(), 1.15f)
        val stLp = skin!!.matchWrap()
        stLp.topMargin = skin!!.dp(7f)
        status.addView(statusView, stLp)

        val hair = View(this)
        hair.setBackgroundColor(skin!!.stroke)
        val hairLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, skin!!.dp(1f)))
        hairLp.topMargin = skin!!.dp(14f)
        status.addView(hair, hairLp)

        metricRow = LinearLayout(this)
        metricRow!!.orientation = LinearLayout.HORIZONTAL
        val mrlp = skin!!.matchWrap()
        mrlp.topMargin = skin!!.dp(12f)
        status.addView(metricRow, mrlp)
        buildHomeMetrics()
        root.addView(status, withTop(skin!!, Skin.S4))

        // 更新卡
        val update = skin!!.card()
        val updateRow = LinearLayout(this)
        updateRow.orientation = LinearLayout.HORIZONTAL
        updateRow.gravity = Gravity.CENTER_VERTICAL
        updateRow.addView(iconTile(Icon.REFRESH, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 38, 38))
        val updateText = LinearLayout(this)
        updateText.orientation = LinearLayout.VERTICAL
        updateText.addView(skin!!.rowTitle("版本更新"))
        verLine = skin!!.text("当前版本 v" + Version.LABEL, Skin.T_CAPTION, Skin.REGULAR, skin!!.text2)
        verLine!!.isSingleLine = true
        verLine!!.ellipsize = android.text.TextUtils.TruncateAt.END
        val vlp = skin!!.matchWrap()
        vlp.topMargin = skin!!.dp(2f)
        updateText.addView(verLine, vlp)
        val utlp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        utlp.leftMargin = skin!!.dp(12f)
        updateRow.addView(updateText, utlp)
        updateBtn = skin!!.textButton("检查")
        updateBtn!!.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                if (UpdateChecker.isOutdated(this@MainActivity)) {
                    showUpdateDialog(UpdateChecker.latest(this@MainActivity)!!)
                    return
                }
                Toast.makeText(this@MainActivity, "正在检查更新…", Toast.LENGTH_SHORT).show()
                UpdateChecker.check(this@MainActivity, true, updateCb)
            }
        })
        updateRow.addView(updateBtn)
        update.addView(updateRow, skin!!.matchWrap())
        root.addView(update, withTop(skin!!, Skin.S3))

        // 数据与日志（v2.2.6）：抓取云数据 / 运行日志，首页直达
        skin!!.addSection(root, "数据与日志")
        val diag = skin!!.listCard()
        diag.addView(cloudDataRow())
        diag.addView(skin!!.hairline())
        diag.addView(logRow())
        root.addView(diag, skin!!.matchWrap())
        loadCloudState()

        // 应用入口
        skin!!.addSection(root, "应用")
        val apps = skin!!.listCard()
        // 已安装的排前面，未安装的沉底；同类之间保持 百度 → 高德 → 腾讯 的固定顺序。
        val appPkgs = arrayOf("com.baidu.BaiduMap", "com.autonavi.minimap", "com.tencent.map")
        val appNames = arrayOf("百度地图", "高德地图", "腾讯地图")
        val appOrder = arrayOf(0, 1, 2)
        java.util.Arrays.sort(appOrder, java.util.Comparator<Int> { a, b ->
            val ia = installedVersion(appPkgs[a]) != null
            val ib = installedVersion(appPkgs[b]) != null
            if (ia != ib) return@Comparator if (ia) -1 else 1
            a - b
        })
        for (k in appOrder.indices) {
            val idx = appOrder[k]
            if (k > 0) apps.addView(skin!!.hairline())
            apps.addView(appRow(appPkgs[idx], appNames[idx], idx))
        }
        root.addView(apps, skin!!.matchWrap())

        // 作者与链接
        skin!!.addSection(root, "作者")
        root.addView(buildAuthorCard(), skin!!.matchWrap())

        // 赞赏：主页最后一个区块，点开收款码
        skin!!.addSection(root, "赞赏")
        val donate = skin!!.listCard()
        donate.addView(donateRow())
        root.addView(donate, skin!!.matchWrap())

        val foot = skin!!.footnote("免费开源 · 禁止倒卖 · MapAdKiller v" + Version.LABEL)
        foot.gravity = Gravity.CENTER
        val flp = skin!!.matchWrap()
        flp.topMargin = skin!!.dp(Skin.S6.toFloat())
        root.addView(foot, flp)

        adLine = skin!!.footnote("")
        adLine!!.visibility = View.GONE
        renderVersionLines()
        return sv
    }

    // ══════════════════════════════════════════════════ 首页 · 数据与日志（v2.2.6）

    /**
     * 云数据状态缓存。模块版（LSPosed）读不到宿主 UID 的 files/baseline、也没有 root 兜底，
     * 状态由宿主进程发广播回来，落在本 App 的 {@code capture_report} prefs 里
     * （见 {@code mapadkiller.CaptureReport}）。心跳里只读这份内存缓存，不碰磁盘。
     */
    private var cloudDone = false
    private var cloudFileCount = 0
    private var cloudStateKnown = false
    /** 面板已请求抓取、还没等到宿主回报结果。 */
    private var cloudPending = false
    /** 上次抓取失败原因（宿主的 baseline_capture_retry 原因码）。 */
    private var cloudError: String? = null
    /** 上次抓取完成时间。 */
    private var cloudAt = 0L
    private var cloudSub: TextView? = null
    private var logSub: TextView? = null

    /** 已经提示过的抓取回执时间戳，**持久化**在 {@code capture_report} prefs 的 {@code ack_at}。 */
    // K_ACK_AT 常量见 companion object
    private var ackAtCache = -1L
    /** 用户点「开始抓取 / 重新抓取」的时刻，用来算 {@code event=capture_done} 里的 ms=。 */
    private var captureAskedAt = 0L

    /** 「抓取云数据」一行：官方云端下发的首页卡片 / 金刚位 / 工具宫格 / 云控开关，按版本抓一份基线。 */
    private fun cloudDataRow(): LinearLayout {
        val row = skin!!.row()
        row.addView(iconTile(Icon.DOWNLOAD, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 38, 38))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(skin!!.rowTitle("抓取云数据"))
        cloudSub = skin!!.text("读取状态…", Skin.T_CAPTION, Skin.REGULAR, skin!!.text2)
        cloudSub!!.isSingleLine = true
        cloudSub!!.ellipsize = android.text.TextUtils.TruncateAt.END
        val slp = skin!!.matchWrap()
        slp.topMargin = skin!!.dp(2f)
        box.addView(cloudSub, slp)

        val blp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.leftMargin = skin!!.dp(12f)
        row.addView(box, blp)
        row.addView(Icon(this, Icon.CHEVRON, skin!!.text3), fixed(skin!!, 18, 18))

        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                startCloudCapture()
            }
        })
        UiKit.pressable(row, 0.99f)
        return row
    }

    /** 「运行日志」一行：进程内环形缓冲（最近 10 分钟）查看 / 复制 / 导出 txt。 */
    private fun logRow(): LinearLayout {
        val row = skin!!.row()
        row.addView(iconTile(Icon.ARCHIVE, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 38, 38))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(skin!!.rowTitle("运行日志"))
        logSub = skin!!.text("点此查看 / 导出运行日志", Skin.T_CAPTION, Skin.REGULAR, skin!!.text2)
        logSub!!.isSingleLine = true
        logSub!!.ellipsize = android.text.TextUtils.TruncateAt.END
        val slp = skin!!.matchWrap()
        slp.topMargin = skin!!.dp(2f)
        box.addView(logSub, slp)

        val blp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.leftMargin = skin!!.dp(12f)
        row.addView(box, blp)
        row.addView(Icon(this, Icon.CHEVRON, skin!!.text3), fixed(skin!!, 18, 18))

        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showLogDialog()
            }
        })
        UiKit.pressable(row, 0.99f)
        return row
    }

    /** 心跳里只改文案，不碰磁盘 / 不起进程。 */
    private fun refreshDiagRows() {
        try {
            if (cloudSub != null) {
                if (!embedded()) readCaptureReport()
                val msg: String
                val err = cloudError
                if (embedded() && CaptureGate.capturing(this)) {
                    msg = "正在抓取官方云端数据…"
                } else if (!cloudStateKnown) {
                    msg = "读取状态…"
                } else if (embedded() && cloudDone) {
                    msg = "已抓取" + (if (cloudFileCount > 0) " " + cloudFileCount + " 个配置文件" else "本版本基线") + " · 点此重新抓取"
                } else if (!embedded() && cloudPending) {
                    msg = "已请求抓取 · 打开高德后自动开始"
                } else if (cloudDone) {
                    msg = "已抓取" + (if (cloudFileCount > 0) " " + cloudFileCount + " 个配置文件" else "本版本基线") +
                            (if (cloudAt > 0L) " · " + timeText(cloudAt) else "") +
                            " · 点此重新抓取"
                } else if (err != null && err.length > 0) {
                    msg = "上次没抓全（" + reasonText(err) + "）· 点此重试"
                } else {
                    msg = "未抓取 · 点此抓取官方云端数据"
                }
                if (!msg.contentEquals(cloudSub!!.text)) cloudSub!!.text = msg
            }
            if (logSub != null) {
                var n = 0
                try {
                    n = CaptureLog.recentCount()
                } catch (ignored: Throwable) {
                }
                val msg = if (n > 0)
                    "最近 " + n + " 条事件 · 点此查看"
                else
                    "点此查看 / 导出运行日志"
                if (!msg.contentEquals(logSub!!.text)) logSub!!.text = msg
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 读一次云数据状态。
     *
     * <p>内置版：与宿主同进程同 UID，直接读 files/baseline。
     * <p>模块版：跨 UID，宿主的 state 文件读不到（模块 App 也不在 KernelSU 白名单，su 静默失败），
     * 只能读宿主抓完/失败时广播回来的 capture_report。旧实现只调
     * Baseline.isCaptured()，永远返回 false —— 首页就一直显示「未抓取」，
     * 用户当然认为「抓取一直失败」。
     */
    private fun loadCloudState() {
        if (embedded()) {
            try {
                cloudDone = CaptureGate.captured(this)
                cloudFileCount = CaptureGate.capturedFiles(this)
            } catch (ignored: Throwable) {
            }
            cloudStateKnown = true
            refreshDiagRows()
            return
        }
        readCaptureReport()
        cloudStateKnown = true
        refreshDiagRows()
        if (cloudDone) return
        // 本机还没收到过任何回报（比如模块刚更新）：再用权威接口确认一次，和广播结果取并集。
        Thread(Runnable {
            var ok = false
            var files = 0
            try {
                ok = io.github.ldxm666.mapadkiller.Baseline.isCaptured()
                if (ok) files = io.github.ldxm666.mapadkiller.Baseline.capturedFiles()
            } catch (ignored: Throwable) {
            }
            val done = ok
            val n = files
            runOnUiThread(Runnable {
                if (done && !cloudDone) {
                    cloudDone = true
                    if (n > 0) cloudFileCount = n
                }
                cloudStateKnown = true
                refreshDiagRows()
            })
        }, "mapclean-cloud-state").start()
    }

    /** 把宿主广播回来的抓取状态读进内存字段（SharedPreferences 有内存缓存，心跳里读也便宜）。 */
    private fun readCaptureReport() {
        try {
            val sp = getSharedPreferences(
                    io.github.ldxm666.mapadkiller.CaptureReport.P, Context.MODE_PRIVATE)
            val known = sp.getBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_KNOWN, false)
            if (!known) return
            val stage = sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_STAGE, null)
            val captured = sp.getBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_CAPTURED, false)
            if (io.github.ldxm666.mapadkiller.CaptureReport.S_DONE == stage
                    || io.github.ldxm666.mapadkiller.CaptureReport.S_STATE == stage
                    || io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED == stage) {
                if (captured) {
                    cloudDone = true
                    cloudFileCount = sp.getInt(io.github.ldxm666.mapadkiller.CaptureReport.K_FILES, cloudFileCount)
                    val at = sp.getLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, 0L)
                    if (at > 0L) cloudAt = at
                    cloudError = null
                    cloudPending = false
                } else if (io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED == stage
                        || io.github.ldxm666.mapadkiller.CaptureReport.S_STATE == stage) {
                    cloudError = sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_ERROR, null)
                    cloudPending = false
                    if (io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED == stage) cloudDone = false
                }
            } else if (io.github.ldxm666.mapadkiller.CaptureReport.S_PENDING == stage) {
                // 「已请求、等宿主回报」：即便手里还有上一次的成功结果，也要先显示等待中。
                cloudPending = true
            }
            // v2.3.0：状态读完顺手做一次「结果反馈」判定。放在这里（而不是广播接收器里）
            // 有个硬理由：接收器只负责落盘，它没有 Activity，判定不了「用户到底看没看屏幕」，
            // 也就分不出该弹 Toast（看完自动退出）还是该发通知栏（用户在高德里）。
            checkCaptureResult(stage, captured,
                    sp.getInt(io.github.ldxm666.mapadkiller.CaptureReport.K_FILES, 0),
                    sp.getLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, 0L),
                    sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_ERROR, null))
        } catch (t: Throwable) {
            // 不许静默：读回执失败 = 首页那行永远停在旧文案，没有日志就只能靠猜。
            android.util.Log.w("MapCleanCapture", "event=capture_report_read_fail err=" + t)
        }
    }

    // ══════════════════════════════════════════════ 抓取结果反馈 + 自动退出（v2.3.0）

    /** 已提示过的回执时间戳（读一次就缓存，心跳不反复碰磁盘）。 */
    private fun ackAt(): Long {
        if (ackAtCache >= 0L) return ackAtCache
        try {
            ackAtCache = getSharedPreferences(
                    io.github.ldxm666.mapadkiller.CaptureReport.P, Context.MODE_PRIVATE)
                    .getLong(K_ACK_AT, 0L)
        } catch (t: Throwable) {
            android.util.Log.w("MapCleanCapture", "event=capture_ack_read_fail err=" + t)
            ackAtCache = 0L
        }
        return ackAtCache
    }

    /** 记下「这条回执已经提示过」，落盘，避免重进页面/重启进程重复弹。 */
    private fun markAck(at: Long) {
        ackAtCache = at
        try {
            getSharedPreferences(io.github.ldxm666.mapadkiller.CaptureReport.P, Context.MODE_PRIVATE)
                    .edit().putLong(K_ACK_AT, at).commit()
        } catch (t: Throwable) {
            // 落盘失败只影响「下次还会提示一次」，不影响本次反馈，所以只记日志不中断。
            android.util.Log.w("MapCleanCapture", "event=capture_ack_write_fail err=" + t)
        }
    }

    /**
     * 抓取结果的唯一出口：成功/失败各一条明确提示，成功且用户正看着本页时自动退出。
     *
     * <p><b>成功</b>：Toast（前台）或通知栏（后台），文案带抓到多少项；1.2 秒后 finish()，
     * 把界面还给地图。摘要留在首页那行（「已抓取 N 个配置文件 · 今天 03:47」），
     * 所以退出后用户回来仍然找得到结果，不会「一关就什么都不知道了」。
     *
     * <p><b>失败</b>：只提示、**绝不自动退出**。用户要停在这一页看到原因并重试；
     * 自动退出会把「失败」变成「什么都没发生」，那正是这次要修的老毛病。
     */
    private fun checkCaptureResult(stage: String?, captured: Boolean, files: Int, at: Long, error: String?) {
        val done = io.github.ldxm666.mapadkiller.CaptureReport.S_DONE == stage && captured
        val failed = io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED == stage
        if (!done && !failed) return
        val stamp = if (at > 0L) at else System.currentTimeMillis()
        if (stamp <= ackAt()) return // 这条回执已经提示过（含冷启动时的历史结果）
        val age = System.currentTimeMillis() - stamp
        if (age > 10L * 60L * 1000L) {
            // 隔了太久的旧结果（比如进程被杀、第二天才打开）只更新列表，不再打扰。
            android.util.Log.i("MapCleanCapture", "event=capture_feedback_skip reason=stale age_ms=" + age)
            markAck(stamp)
            return
        }
        var fg = false
        try {
            fg = !isFinishing && hasWindowFocus()
        } catch (t: Throwable) {
            android.util.Log.w("MapCleanCapture", "event=capture_fg_probe_fail err=" + t)
        }
        var toasted = false
        try {
            toasted = io.github.ldxm666.mapadkiller.CaptureLog.notifyResult(
                    this, fg, done, files, if (done) ("amap:" + files) else "",
                    if (captureAskedAt > 0L) stamp - captureAskedAt else 0L,
                    stamp, if (done) null else reasonText(error))
        } catch (t: Throwable) {
            // 提示这条路整个挂了：记下来并**记账**，否则心跳每 1.5 秒重试一次，
            // logcat 会被同一条异常刷满。抓取结果本身已经落盘，首页那行仍然是对的。
            android.util.Log.w("MapCleanCapture", "event=capture_feedback_fail err=" + t)
            markAck(stamp)
            return
        }
        // 提示真的发出去了才记账：先记账再提示的话，提示一失败就永久丢了，
        // 用户永远等不到那条「抓取完成」。
        markAck(stamp)
        if (!done || !toasted) return // 失败 / 走的是通知栏（用户不在本页）⇒ 不退出
        // 成功且用户正看着设置页：留 1.2 秒让 Toast 被看见，然后自动退出。
        statusHandler.postDelayed(Runnable {
            try {
                if (!isFinishing) finish()
                android.util.Log.i("MapCleanCapture", "event=capture_finish ok=true")
            } catch (t: Throwable) {
                android.util.Log.w("MapCleanCapture", "event=capture_finish_fail err=" + t)
            }
        }, 1200L)
        android.util.Log.i("MapCleanCapture", "event=capture_finish scheduled_ms=1200")
    }

    /** 抓取时间显示：「今天 14:32」/「10-27 14:32」。 */
    private fun timeText(at: Long): String {
        try {
            val c = java.util.Calendar.getInstance()
            val t = java.util.Calendar.getInstance()
            t.timeInMillis = at
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
            val today = c.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                    && c.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
            if (today) return "今天 " + hm
            return java.text.SimpleDateFormat("MM-dd ", java.util.Locale.getDefault()).format(java.util.Date(at)) + hm
        } catch (ignored: Throwable) {
            return ""
        }
    }

    /** 抓取失败原因 → 人话。 */
    private fun reasonText(code: String?): String {
        if (code == null) return "未知原因"
        if (code.contains("prefs_incomplete")) return "高德启动太慢，配置没写完"
        if (code.contains("fallback_thin")) return "没等到首页，抓到的配置太少"
        if (code.contains("no_host_context")) return "高德进程还没起来"
        if (code.contains("timeout")) return "等待超时"
        return code
    }

    /** 本地记一条「已请求抓取」，等宿主的广播回来后覆盖成真正的结果。 */
    private fun markCapturePending() {
        try {
            getSharedPreferences(io.github.ldxm666.mapadkiller.CaptureReport.P, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_KNOWN, true)
                    .putString(io.github.ldxm666.mapadkiller.CaptureReport.K_STAGE,
                            io.github.ldxm666.mapadkiller.CaptureReport.S_PENDING)
                    .putBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_CAPTURED, cloudDone)
                    .putLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, System.currentTimeMillis())
                    .commit()
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 把高德拉到前台：进程没起来就是干净的一次启动（冷启动会直接进入采集），
     * 进程还活着则由 CaptureGate.applyRecaptureRequest 在 onResume 写下采集标记
     * 并提示用户「关掉高德再打开一次」。
     *
     * <p>直接 startActivity 在部分 ROM（HyperOS 等）会被「链式启动 / 后台弹出界面」
     * 的 ConfirmStartActivity 确认框拦一道，所以同时排一个几秒后的系统闹钟拉起启动页，
     * 和内置版 CaptureGate.restartHost 用的是同一招。
     */
    /**
     * root 直启：su -c am start 是 shell 身份，不受 HyperOS「链式启动 /
     * 后台弹出界面」那个 ConfirmStartActivity 确认框拦截。真机上之前的
     * startActivity 全被那个框吃掉，用户看到的就是「点了抓取，什么都没发生」。
     */
    // sRootLaunch（-1 未知 / 0 不可用 / 1 可用）见 companion object

    private fun launchHostByRoot(): Boolean {
        if (sRootLaunch == 0) return false
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity"))
            val done = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
            val rc = if (done) p.exitValue() else -1
            if (!done) try {
                p.destroy()
            } catch (ignored: Throwable) {
            }
            if (rc == 0) {
                sRootLaunch = 1
                io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG,
                        "event=cloud_launch root ok")
                return true
            }
            if (rc == 1) sRootLaunch = 0
            io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG,
                    "event=cloud_launch root failed rc=" + rc)
        } catch (t: Throwable) {
            sRootLaunch = 0
            io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG,
                    "event=cloud_launch root failed " + t)
        }
        return false
    }

    private fun launchHost() {
        try {
            if (launchHostByRoot()) return
            val i = packageManager.getLaunchIntentForPackage("com.autonavi.minimap")
            if (i == null) {
                toast("没找到高德地图，请手动打开一次")
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch no_launch_intent")
                return
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                startActivity(i)
                io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch direct ok")
            } catch (t: Throwable) {
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch direct failed " + t)
            }
            try {
                var flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT
                if (android.os.Build.VERSION.SDK_INT >= 23) flags = flags or android.app.PendingIntent.FLAG_IMMUTABLE
                val pi = android.app.PendingIntent.getActivity(this, 0x4D43, i, flags)
                val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (am != null) {
                    val at = System.currentTimeMillis() + 2500L
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                        } else {
                            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                        }
                    } catch (tt: Throwable) {
                        am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                    }
                    io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch alarm_at=+2500ms")
                }
            } catch (t: Throwable) {
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch alarm failed " + t)
            }
        } catch (t: Throwable) {
            toast("打开高德失败：" + t.message)
        }
    }

    private fun startCloudCapture() {
        try {
            val isEmbedded = embedded()
            val done = cloudDone
            val message: String
            if (done) {
                message = "已经抓过本版本的官方云端数据" +
                        (if (cloudFileCount > 0) "（" + cloudFileCount + " 个配置文件）" else "") +
                        "。\n\n" +
                        (if (isEmbedded)
                        "重新抓取会先关闭模块并重启高德：正常用一遍（首页 / 搜索 / 我的），抓完再重启一次恢复你的设置。"
                        else "点「重新抓取」会自动打开高德：先以纯官方模式重跑一遍抓数据，抓完自动再重启一次恢复模块功能。")
            } else {
                message = "高德的首页卡片、金刚位、工具宫格、云控开关都是服务端下发的。" +
                        "首次必须先让高德跑一遍纯官方，把这份云端数据抓下来，之后才按你的设置改；" +
                        "否则会出现图标不显示、宫格缺格。\n\n" +
                        (if (isEmbedded)
                        "点「开始抓取」后高德会重启一次，期间模块功能全部关闭（悬浮窗保留）。"
                        else "点「开始抓取」会自动打开高德并以纯官方模式跑一遍（期间模块功能关闭），抓完自动重启恢复；若高德没有自动回来，手动打开一次即可。\n\n" +
                        "抓取时请不要退出高德，停留 20 秒左右。")
            }
            glassDialog(if (done) "重新抓取云数据？" else "抓取云数据", message,
                    if (done) "重新抓取" else "开始抓取",
                    Runnable { doStartCloudCapture(isEmbedded) },
                    null, null, "取消", null)
        } catch (t: Throwable) {
            toast("操作失败：" + t.message)
        }
    }

    private fun doStartCloudCapture(isEmbedded: Boolean) {
        try {
            // 记下起点：成功回执要打 event=capture_done ms=…，没有起点这个字段永远是 0。
            captureAskedAt = System.currentTimeMillis()
            // 抓取期间用户基本都在高德里，结果只能靠通知栏送达 —— 顺手把权限要了。
            // 不申请的话 Android 13+ 会静默丢掉 notify()，用户又变成「抓完了也没提示」。
            ensureNotifyPermission()
            if (isEmbedded) {
                if (!CaptureGate.setCaptureMode(this, true)) {
                    toast("写入采集标记失败，请检查存储权限")
                    return
                }
                toast("正在重启高德，请稍候…")
                CaptureGate.restartHost(this)
                return
            }
            toast("正在请求抓取云数据…")
            Thread(Runnable {
                var result = false
                var error: String? = null
                try {
                    result = io.github.ldxm666.mapadkiller.Baseline.requestRecapture()
                } catch (t: Throwable) {
                    error = t.message.toString()
                }
                val ok = result
                val err = error
                if (err != null) {
                    runOnUiThread(Runnable { toast("请求失败：" + err) })
                    return@Runnable
                }
                runOnUiThread(Runnable {
                    if (ok) {
                        // 本地先记成「已请求」，宿主真正抓完会用广播把这一行改成「已抓取 N 项」。
                        markCapturePending()
                        cloudPending = true
                        cloudError = null
                        cloudStateKnown = true
                        refreshDiagRows()
                        toast("已请求抓取 · 正在打开高德，若弹出“允许启动”请点允许")
                        launchHost()
                    } else {
                        val why = io.github.ldxm666.mapadkiller.Baseline.lastRequestError()
                        toast("请求失败：" + (if (why == null) "框架服务未连接" else why))
                    }
                })
            }, "mapclean-cloud-request").start()
        } catch (t: Throwable) {
            toast("操作失败：" + t.message)
        }
    }

    /**
     * Android 13+ 的通知权限（POST_NOTIFICATIONS）。
     *
     * <p>为什么在「开始抓取」这一刻要：后台抓取完成时设置页一定不在前台，通知栏是唯一
     * 还能看见「抓完了」的通道；没授权时 notify() 会被系统静默丢弃 —— 用户看到
     * 的仍然是「抓完没反应」。在用户主动点抓取的瞬间申请，上下文最自然，也不会一启动
     * 就弹权限框。已授权 / 低版本直接返回。
     */
    private fun ensureNotifyPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0x4D43)
            android.util.Log.i("MapCleanCapture", "event=capture_notify_permission_requested")
        } catch (t: Throwable) {
            // 申请失败不算错误：提示会退回 Toast，抓取本身一点不受影响。
            android.util.Log.w("MapCleanCapture", "event=capture_notify_permission_fail err=" + t)
        }
    }

    /** 运行日志入口：内置版直接读环形缓冲；模块版事件在宿主进程，走 root 从 logcat 捞一份。 */
    private fun showLogDialog() {
        var count = 0
        var body: String? = ""
        try {
            count = CaptureLog.recentCount()
            body = CaptureLog.recentText(400)
        } catch (ignored: Throwable) {
        }
        if (body == null) body = ""
        if (body.length > 0 || embedded()) {
            showLogSheet(body, count)
            return
        }
        toast("正在读取宿主日志…")
        Thread(Runnable {
            var text = ""
            try {
                val pr = Runtime.getRuntime().exec(arrayOf("su", "-c",
                        "logcat -d -t 400 -s MapAdKiller:V MapCleanCfg:V MapCleanFloat:V MapCleanCapture:V"))
                val r = java.io.BufferedReader(
                        java.io.InputStreamReader(pr.inputStream))
                val b = StringBuilder(32 * 1024)
                var line: String? = r.readLine()
                while (line != null) {
                    b.append(line).append('\n')
                    line = r.readLine()
                }
                pr.waitFor()
                text = b.toString()
            } catch (ignored: Throwable) {
            }
            val result = text
            runOnUiThread(Runnable { showLogSheet(result, 0) })
        }, "mapclean-host-log").start()
    }

    /** 日志弹层：等宽只读文本 + 复制 / 导出 txt。 */
    private fun showLogSheet(body: String?, count: Int) {
        try {
            var content = body ?: ""
            val shown = content
            if (content.length == 0) {
                content = if (embedded())
                    "还没有日志事件。\n\n模块加载、hook 安装、巡航判定、抓取流程都会实时写进这里。"
                else
                    "还没有日志事件。\n\n没能读到宿主日志（需要 root）。\n点下方「导出 txt」可让宿主侧导出一份最近 10 分钟的运行日志。"
            }

            val dlg = AlertDialog.Builder(this).create()

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.background = skin!!.glassFor(box, GLASS_R.toFloat())
            box.setPadding(dp(18), dp(16), dp(18), dp(8))

            box.addView(text("运行日志", 17f, Typeface.BOLD, cTxP()))
            val sub = text((if (count > 0) "最近 " + count + " 条事件" else "暂无事件") +
                    " · 环形缓冲保留 10 分钟", 11.5f, Typeface.NORMAL, cTxS())
            val subLp = skin!!.matchWrap()
            subLp.topMargin = dp(3)
            box.addView(sub, subLp)

            val tv = TextView(this)
            tv.text = content
            tv.setTextSize(10.5f)
            tv.setTypeface(Typeface.MONOSPACE)
            tv.setTextColor(cTxS())
            tv.setTextIsSelectable(true)
            tv.setLineSpacing(0f, 1.15f)
            tv.setPadding(dp(10), dp(10), dp(10), dp(10))
            tv.background = tintBg(if (dark) 0x1FFFFFFF else 0x0A000000)

            val sc = ScrollView(this)
            sc.isVerticalScrollBarEnabled = true
            sc.addView(tv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val scLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(dp(420), (resources.displayMetrics.heightPixels * 0.52f).toInt()))
            scLp.topMargin = dp(12)
            box.addView(sc, scLp)

            val btns = LinearLayout(this)
            btns.orientation = LinearLayout.HORIZONTAL
            btns.gravity = Gravity.RIGHT
            val btnLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            btnLp.topMargin = dp(10)
            box.addView(btns, btnLp)

            btns.addView(sheetButton("复制", dlg, Runnable {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val text = if (shown.length > 0) shown else CaptureLog.recentText(400)
                    cm.setPrimaryClip(ClipData.newPlainText("MapCleanLog", text))
                    toast("已复制到剪贴板")
                } catch (t: Throwable) {
                    toast("复制失败：" + t.message)
                }
            }), btnMargin())

            btns.addView(sheetButton("导出 txt", dlg, Runnable {
                var path: String? = null
                try {
                    path = CaptureLog.dumpRecent(this@MainActivity)
                } catch (ignored: Throwable) {
                }
                toast(if (path == null) "导出失败：请稍后重试" else "已导出：" + path)
            }), btnMargin())

            btns.addView(sheetButton("关闭", dlg, null), btnMargin())

            dlg.setView(box)
            dlg.show()
            val w = dlg.getWindow()
            if (w != null) {
                w.setBackgroundDrawable(ColorDrawable(0x00000000))
                val lp = w.getAttributes()
                lp.dimAmount = 0.45f
                lp.width = Math.min(resources.displayMetrics.widthPixels - dp(40), dp(460))
                w.setAttributes(lp)
            }
        } catch (t: Throwable) {
            toast("打开日志失败：" + t.message)
        }
    }

    private fun btnMargin(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(8)
        return lp
    }

    private fun sheetButton(label: String, dlg: AlertDialog?, action: Runnable?): TextView {
        val b = text(label, 14f, Typeface.BOLD, cAccent())
        b.setPadding(dp(14), dp(10), dp(14), dp(10))
        b.background = ripple(if (dark) tintBg(0x2E7AB1FF) else tintBg(0x1E0A6CF5), HEAD_R.toFloat())
        b.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action?.run()
                dlg?.dismiss()
            }
        })
        return b
    }

    /** 三格指标（版本 / 作用域 / 广告 SDK）：继续复用 refreshMetrics() 的刷新契约。 */
    private fun buildHomeMetrics() {
        if (metricRow == null) return
        metricRow!!.removeAllViews()
        metricRow!!.addView(homeMetric("版本"), weightLp(0))
        metricRow!!.addView(homeMetric("作用域"), weightLp(1))
        metricRow!!.addView(homeMetric("广告 SDK"), weightLp(2))
    }

    private fun homeMetric(label: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val value = skin!!.text("—", 19f, Skin.BOLD, skin!!.text)
        value.isSingleLine = true
        value.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(value)
        val name = skin!!.text(label, Skin.T_LABEL, Skin.REGULAR, skin!!.text2)
        val nlp = skin!!.matchWrap()
        nlp.topMargin = skin!!.dp(2f)
        box.addView(name, nlp)
        box.tag = value
        box.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showStatusDetail()
            }
        })
        return box
    }

    private fun weightLp(index: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (index > 0) lp.leftMargin = skin!!.dp(Skin.S4.toFloat())
        return lp
    }

    private fun appRow(pkg: String, label: String, vendor: Int): LinearLayout {
        val version = installedVersion(pkg)
        // 未安装：收成一行紧凑条目，且不画灰底单字图标（v2.2.5）
        if (version == null) return appRowUninstalled(pkg, label, vendor)

        val row = skin!!.row()
        row.addView(appIcon(pkg, label), fixedMargin(skin!!, 42, 42, 12))

        val count = if (vendor == 0) bmapSwitches.size else if (vendor == 1) switches.size else tmapSwitches.size
        val recommended = recommendedVersion(pkg)
        var caption = "推荐 " + (if (recommended == null) "—" else recommended)
        if (version == null) caption += if (embedded()) " · 未安装" else " · 本机未安装"
        else caption += " · 本机 " + version + (if (count > 0) " · " + count + " 项" else "")
        row.addView(skin!!.rowText(label, caption), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val chev = Icon(this, Icon.CHEVRON, skin!!.text3)
        row.addView(chev, fixed(skin!!, 18, 18))
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showTab(TAB_CONFIG, true)
                selectApp(vendor)
            }
        })
        UiKit.pressable(row, 0.99f)
        return row
    }

    /**
     * 未安装的地图：一行紧凑条目（44dp），不占 42dp 图标位、不画灰底单字。
     * 仍然可点 —— 进对应功能页能看到有哪些开关、推荐哪个版本，只是不再占一整坨。
     */
    private fun appRowUninstalled(pkg: String, label: String, vendor: Int): LinearLayout {
        val row = skin!!.row()
        row.minimumHeight = skin!!.dp(44f)

        // 图标常态化：不再用 20dp 下载箭头（像「待下载的文件」），改成品牌色圆角方形 App 图标，
        // 与已安装项的 42dp 图标同一视觉语言。装了对应 App 会自动换成它自己的真图标。
        val ilp = LinearLayout.LayoutParams(skin!!.dp(32f), skin!!.dp(32f))
        ilp.rightMargin = skin!!.dp(12f)
        // 优先用**该 App 的原生启动图标**（从它的 APK 里提出来内嵌的）；取不到才退回品牌色圆角图标。
        // 三家的原生图标都内嵌了一份，未安装时也能显示自己的图标（不再只有腾讯有）。
        val real: Drawable? =
                if ("com.tencent.map" == pkg) TencentIconAsset.drawable(this)
                else if ("com.baidu.BaiduMap" == pkg) BaiduIconAsset.drawable(this)
                else if ("com.autonavi.minimap" == pkg) AmapIconAsset.drawable(this) else null
        if (real != null) {
            val iv = ImageView(this)
            iv.setImageDrawable(real)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            row.addView(iv, ilp)
        } else {
            row.addView(AppIcon(this, vendor), ilp)
        }

        // 名称正常色 +「未安装」红色。两个 TextView 比 SpannableString 更简单，也不会多引类。
        val nameWrap = LinearLayout(this)
        nameWrap.orientation = LinearLayout.HORIZONTAL
        nameWrap.gravity = Gravity.CENTER_VERTICAL
        nameWrap.addView(skin!!.text(label, Skin.T_ROW, Skin.MEDIUM, skin!!.text2))
        nameWrap.addView(skin!!.text(if (embedded()) " · 未安装" else " · 本机未安装",
                Skin.T_ROW, Skin.MEDIUM, 0xFFE53935.toInt()))
        row.addView(nameWrap, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 未安装时**不再**显示「推荐 x」：那是模块验证过的固定版本号，跟用户手机装什么无关，
        // 摆出来只会让人以为要装这个版本（用户反馈「推荐版本是测试机的固定版本」）。
        // 已安装的行里「推荐」直接等于本机版本，见 recommendedVersion()。
        row.addView(Icon(this, Icon.CHEVRON, skin!!.text3), fixed(skin!!, 18, 18))

        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showTab(TAB_CONFIG, true)
                selectApp(vendor)
            }
        })
        UiKit.pressable(row, 0.99f)
        return row
    }

    /** 模块当前验证过的目标版本（Version.TARGETS 是唯一事实源）。 */
    private fun recommendedVersion(pkg: String): String? {
        // Recommendation follows the build actually installed on this device: the module
        // is verified against that build, so showing a hard-coded version that differs
        // from the phone just makes the row look wrong.
        val installed = installedVersion(pkg)
        if (installed != null && installed.length > 0) return installed
        for (i in Version.TARGETS.indices) {
            if (Version.TARGETS[i][0] == pkg) return Version.TARGETS[i][2]
        }
        return null
    }

    /** 装了的应用用真实图标；取不到图标时用 accent 字标兜底（不再用灰底灰字）。未安装的行不走这里。 */
    private fun appIcon(pkg: String, label: String): View {
        val box = FrameLayout(this)
        var icon: Drawable? = null
        try {
            icon = packageManager.getApplicationIcon(pkg)
        } catch (ignored: Throwable) {
        }
        if (icon != null) {
            val image = ImageView(this)
            image.setImageDrawable(icon)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            box.addView(image, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            // 字标兜底：accent 底 + 白字，看起来像有意设计，而不是灰底灰字的半成品
            val letter = skin!!.text(label.substring(0, 1), 16f, Skin.BOLD, skin!!.onAccent)
            letter.gravity = Gravity.CENTER
            box.background = skin!!.solid(skin!!.accent, 11f)
            box.addView(letter, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        UiKit.clipRounded(box, skin!!.dp(11f).toFloat())
        return box
    }

    private fun buildAuthorCard(): LinearLayout {
        val card = skin!!.listCard()
        val who = LinearLayout(this)
        who.orientation = LinearLayout.HORIZONTAL
        who.gravity = Gravity.CENTER_VERTICAL
        who.setPadding(skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S2.toFloat()))

        val avatar = ImageView(this)
        val face = AvatarAsset.drawable(this)
        if (face != null) avatar.setImageDrawable(face)
        val avLp = LinearLayout.LayoutParams(skin!!.dp(44f), skin!!.dp(44f))
        avLp.rightMargin = skin!!.dp(Skin.S3.toFloat())
        who.addView(avatar, avLp)
        UiKit.clipRounded(avatar, skin!!.dp(22f).toFloat())

        who.addView(skin!!.rowText("ldxm666", "开源作者 · 地图广告净化"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(who)
        card.addView(skin!!.hairline())

        val mark = GithubMark(this)
        mark.setColor(skin!!.text)
        card.addView(linkRow(mark, "GitHub 主页", "github.com/ldxm666", Version.REPO))
        card.addView(skin!!.hairline())
        val send = Icon(this, Icon.SEND, skin!!.accent)
        card.addView(linkRow(send, "Telegram 群组", "t.me/+2rqisPe5tJxjNTk1", Version.TG))
        return card
    }

    private fun linkRow(icon: View, title: String, subtitle: String?, url: String): LinearLayout {
        val row = skin!!.row()
        val badge = LinearLayout(this)
        badge.orientation = LinearLayout.HORIZONTAL
        badge.gravity = Gravity.CENTER
        badge.background = skin!!.solid(skin!!.chipBg, 10f)
        badge.addView(icon, fixed(skin!!, 18, 18))
        row.addView(badge, fixedMargin(skin!!, 38, 38, 12))
        row.addView(skin!!.rowText(title, subtitle), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chev = Icon(this, Icon.EXTERNAL, skin!!.text3)
        row.addView(chev, fixed(skin!!, 16, 16))
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                openUrl(url)
            }
        })
        return row
    }

    private fun iconTile(kind: Int, color: Int, fill: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.background = skin!!.solid(fill, 11f)
        box.addView(Icon(this, kind, color), fixed(skin!!, 20, 20))
        return box
    }

    // fixed / fixedMargin / withTop 三个布局工厂见 companion object

    /* ──────────────────────────────────────────────────────────── 赞赏 */

    /** 「赞赏支持」行：骨架照抄 linkRow，只是点开的是收款码对话框而不是外链。 */
    private fun donateRow(): View {
        val row = skin!!.row()
        val badge = LinearLayout(this)
        badge.orientation = LinearLayout.HORIZONTAL
        badge.gravity = Gravity.CENTER
        badge.background = skin!!.solid(skin!!.chipBg, 10f)
        badge.addView(Icon(this, Icon.SEND, skin!!.accent), fixed(skin!!, 18, 18))
        row.addView(badge, fixedMargin(skin!!, 38, 38, 12))
        row.addView(skin!!.rowText("赞赏支持", "感谢投喂 · 扫码请作者喝杯咖啡"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Icon(this, Icon.CHEVRON, skin!!.text3), fixed(skin!!, 16, 16))
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showDonateDialog()
            }
        })
        return row
    }

    /** 收款码来自 DonateAsset（base64 内嵌，无 R 类）；解码失败退化成一行文字，不崩。 */
    private fun showDonateDialog() {
        // 统一到 DonateUi，模块版与内置版同一套观感
        DonateUi.show(this)
    }

    /* ──────────────────────────────────────────────────────────── 配置页 */

    private fun buildConfigPage(): View {
        val screen = LinearLayout(this)
        screen.orientation = LinearLayout.VERTICAL
        screen.setBackgroundColor(0x00000000)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S3.toFloat()), skin!!.dp(Skin.S5.toFloat()), 0)
        head.addView(skin!!.display("配置"))
        val sub = skin!!.caption("按应用调整模块开关")
        val subLp = skin!!.matchWrap()
        subLp.topMargin = skin!!.dp(3f)
        head.addView(sub, subLp)
        head.background = skin!!.headerScrim()
        screen.addView(head, skin!!.matchWrap())

        val segment = LinearLayout(this)
        segment.orientation = LinearLayout.HORIZONTAL
        segment.setPadding(skin!!.dp(4f), skin!!.dp(4f), skin!!.dp(4f), skin!!.dp(4f))
        segment.background = skin!!.solid(skin!!.surface2, Skin.R_INNER.toFloat())
        if (embedded()) {
            addAppChip(segment, if (embeddedAmap()) "高德地图" else "百度地图")
        } else {
            addAppChip(segment, "百度地图")
            addAppChip(segment, "高德地图")
            addAppChip(segment, "腾讯地图")
        }
        val segLp = skin!!.matchWrap()
        segLp.topMargin = skin!!.dp(Skin.S4.toFloat())
        segLp.leftMargin = skin!!.dp(Skin.S5.toFloat())
        segLp.rightMargin = skin!!.dp(Skin.S5.toFloat())
        screen.addView(segment, segLp)

        configSummary = skin!!.footnote("")
        val cslp = skin!!.matchWrap()
        cslp.topMargin = skin!!.dp(Skin.S3.toFloat())
        cslp.leftMargin = skin!!.dp((Skin.S5 + 2).toFloat())
        cslp.rightMargin = skin!!.dp(Skin.S5.toFloat())
        screen.addView(configSummary, cslp)

        // 内置版只读说明条：不是脚注 —— 整页置灰之后，这一条是用户唯一的出口提示，
        // 必须首屏可见、且「长按悬浮球」几个字要能一眼抓住（见 applyEmbeddedNotice 的 Spannable）。
        embeddedNotice = skin!!.text("", Skin.T_BODY, Skin.MEDIUM, skin!!.text)
        embeddedNotice!!.visibility = View.GONE
        embeddedNotice!!.setLineSpacing(skin!!.dp(3f).toFloat(), 1f)
        val noticePad = skin!!.dp(Skin.S3.toFloat())
        embeddedNotice!!.setPadding(noticePad, noticePad, noticePad, noticePad)
        embeddedNotice!!.background = skin!!.solid(skin!!.surface2, Skin.R_INNER.toFloat())
        val noticeLp = skin!!.matchWrap()
        noticeLp.topMargin = skin!!.dp(Skin.S3.toFloat())
        noticeLp.leftMargin = skin!!.dp(Skin.S5.toFloat())
        noticeLp.rightMargin = skin!!.dp(Skin.S5.toFloat())
        screen.addView(embeddedNotice, noticeLp)

        val searchBar = finder!!.bar()
        val searchLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, skin!!.dp(44f))
        searchLp.topMargin = skin!!.dp(Skin.S3.toFloat())
        searchLp.leftMargin = skin!!.dp(Skin.S5.toFloat())
        searchLp.rightMargin = skin!!.dp(Skin.S5.toFloat())
        screen.addView(searchBar, searchLp)
        val resultsHost = finder!!.resultsHost()
        try {
            if (resultsHost is android.widget.HorizontalScrollView) {
                val hs = resultsHost
                hs.clipToPadding = false
                hs.setPadding(skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S2.toFloat()), skin!!.dp(Skin.S5.toFloat()), 0)
                // 结果统计胶囊：SwitchFinder 里 results 是 VERTICAL 的，塞进横向滚动容器后
                // 会一个个竖着排（截图里「百度 · 2」「高德 · 2」各占一行）。这里掰回横排。
                if (hs.childCount > 0 && hs.getChildAt(0) is LinearLayout) {
                    (hs.getChildAt(0) as LinearLayout).orientation = LinearLayout.HORIZONTAL
                }
            }
        } catch (ignored: Throwable) {
        }
        screen.addView(resultsHost, skin!!.matchWrap())


        configHost = FrameLayout(this)
        val hostLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        hostLp.topMargin = skin!!.dp(Skin.S2.toFloat())
        screen.addView(configHost, hostLp)

        if (embedded()) {
            addAppPage(if (embeddedAmap()) buildAmapPage() else buildBaiduPage(),
                    if (embeddedAmap()) PAGE_AMAP else PAGE_BAIDU)
        } else {
            addAppPage(buildBaiduPage(), PAGE_BAIDU)
            addAppPage(buildAmapPage(), PAGE_AMAP)
            addAppPage(buildTmapPage(), PAGE_TMAP)
        }
        selectApp(0)
        probeEmbeddedBuilds()
        return screen
    }

    private fun addAppChip(segment: LinearLayout, label: String) {
        val index = appChips.size
        val chip = skin!!.text(label, Skin.T_CAPTION + 0.5f, Skin.MEDIUM, skin!!.text2)
        chip.gravity = Gravity.CENTER
        chip.isClickable = true
        chip.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                selectApp(index)
            }
        })
        val lp = LinearLayout.LayoutParams(
                0, skin!!.dp(38f), 1f)
        if (index > 0) lp.leftMargin = skin!!.dp(2f)
        segment.addView(chip, lp)
        appChips.add(chip)
    }

    private fun addAppPage(page: ScrollView, pageId: Int) {
        page.visibility = View.GONE
        appPages.add(page)
        appPageIds.add(pageId)
        configHost.addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 切换地图应用（0 百度 / 1 高德 / 2 腾讯；内置版只有一个，下标恒为 0）。 */
    private fun selectApp(index: Int) {
        if (index < 0 || index >= appPages.size) return
        appIndex = index
        for (i in 0 until appPages.size) {
            appPages[i].visibility = if (i == index) View.VISIBLE else View.GONE
        }
        for (i in 0 until appChips.size) {
            val chip = appChips[i]
            val on = i == index
            chip.setTextColor(if (on) skin!!.accent else skin!!.text2)
            chip.setTypeface(if (on) Skin.MEDIUM else Skin.REGULAR)
            chip.background = if (on) skin!!.border(skin!!.surface, skin!!.stroke, 11f) else null
            if (on) {
                chip.scaleX = 0.96f
                chip.scaleY = 0.96f
                chip.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220L).setInterpolator(UiKit.SPRING).start()
            }
        }
        if (configSummary != null && index < appChips.size && index < appPageIds.size) {
            val pageId = appPageIds[index]
            val count = if (pageId == PAGE_BAIDU) bmapSwitches.size
            else if (pageId == PAGE_AMAP) switches.size else tmapSwitches.size
            configSummary!!.text = appChips[index].text.toString() + " · " + count + " 项开关"
        }
        if (tabIndex == TAB_CONFIG && finder != null) {
            finder!!.attach(appPageIds[index])
        }
        applyEmbeddedNotice()
    }

    // ------------------------------------------------ 内置版只读闸（独立 App 侧）

    /**
     * 后台判定三个宿主包是不是 LSPatch 内置版。
     *
     * <p>只在<b>独立 App</b> 里跑：进程内面板（embedded()==true）本身就活在地图进程里，
     * 写的就是宿主 prefs，不需要也没必要判。
     */
    private fun probeEmbeddedBuilds() {
        if (embedded()) return
        for (i in 0 until appPageIds.size) {
            val pageId = appPageIds[i]
            val pkg = hostPackageOf(pageId)
            if (pkg == null) continue
            EmbeddedBuild.detect(this, pkg, object : EmbeddedBuild.Callback {
                override fun onResult(isEmbeddedBuild: Boolean) {
                    runOnUiThread(Runnable {
                        embeddedPages[pageId] = isEmbeddedBuild
                        applyEmbeddedNotice()
                    })
                }
            })
        }
    }

    /**
     * 当前页若是内置版宿主：顶部出说明条 + 整页置灰不可点。
     *
     * <p>为什么必须锁而不能只提示：UI 是「先写先翻」—— 开关拨过去、Toast 也可能报成功，
     * 但写的是<b>本 App 沙箱</b>，宿主进程读的是它自己的沙箱，永远读不到。
     * 只提示不锁，用户还是会以为改好了。
     */
    private fun applyEmbeddedNotice() {
        val notice = embeddedNotice
        if (notice == null) return
        val pageId = if (appIndex >= 0 && appIndex < appPageIds.size) appPageIds[appIndex] else -1
        val locked = embeddedPages[pageId] == true
        notice.visibility = if (locked) View.VISIBLE else View.GONE
        if (!locked) return
        notice.text = buildEmbeddedNoticeText()
        if (embeddedLocked.add(pageId)) {
            val page = if (appIndex >= 0 && appIndex < appPages.size) appPages[appIndex] else null
            lockPage(page)
            if (page != null) page.alpha = 0.6f
        }
    }

    /**
     * 说明条文案：**「长按悬浮球」几个字单独加粗 + 主色**。
     *
     * <p>整页置灰之后用户能读到的只有这一条，所以它不能和旁边的说明文字一个灰度 ——
     * 用 {@link StyleSpan} 加粗 + {@link ForegroundColorSpan} 上主色，扫一眼就能落到
     * "该去哪儿改"这个动作上。
     */
    private fun buildEmbeddedNoticeText(): CharSequence {
        val head = "当前是免 root 内置版：这里的开关写不进地图，改了也不生效。"
        val action = "请在地图里轻点悬浮球"
        val tail = "，用悬浮球面板修改 —— 那一套才是真正生效的配置。"
        val all = head + "\n" + action + tail
        val span = SpannableString(all)
        val start = head.length + 1
        val end = start + action.length
        try {
            span.setSpan(StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(cAccent()), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (ignored: Throwable) {
        }
        return span
    }

    /**
     * 把一页里所有可交互控件关掉，<b>但保留滚动</b>（长页面还得能看）。
     *
     * <p>两道保险：setEnabled/setClickable 走框架的分发拦截；
     * 对自绘控件（{@link GlassSwitch} / {@link GlassSlider}）再加一个吞掉手势的
     * OnTouchListener，因为它们各自重写了 onTouchEvent，
     * 只靠 enabled 状态不一定拦得住。
     */
    // lockPage / hostPackageOf 见 companion object

    private fun addNavItem(parent: LinearLayout, kind: Int, label: String) {
        val index = navItems.size
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER
        item.setPadding(0, skin!!.dp(4f), 0, skin!!.dp(4f))
        item.background = UiKit.rippleBare(if (dark) 0x22FFFFFF else 0x0F000000, skin!!.dp(14f).toFloat())

        val pill = FrameLayout(this)
        val icon = Icon(this, kind, skin!!.text2)
        val iconLp = FrameLayout.LayoutParams(
                skin!!.dp(22f), skin!!.dp(22f), Gravity.CENTER)
        pill.addView(icon, iconLp)
        item.addView(pill, LinearLayout.LayoutParams(skin!!.dp(46f), skin!!.dp(28f)))

        val text = skin!!.text(label, 10.5f, Skin.MEDIUM, skin!!.text2)
        val tlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = skin!!.dp(2f)
        item.addView(text, tlp)

        item.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showTab(index, true)
            }
        })
        parent.addView(item, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navItems.add(item)
        navIcons.add(icon)
        navLabels.add(text)
        item.tag = pill
    }

    private fun applyNav() {
        for (i in 0 until navItems.size) {
            val on = i == tabIndex
            navIcons[i].setColor(if (on) skin!!.accent else skin!!.text2)
            navLabels[i].setTextColor(if (on) skin!!.accent else skin!!.text2)
            navLabels[i].setTypeface(if (on) Skin.MEDIUM else Skin.REGULAR)
            navItems[i].background = if (on)
                skin!!.solid(skin!!.accentSoft, 16f) else null
            if (on) {
                val item = navItems[i]
                item.scaleX = 0.94f
                item.scaleY = 0.94f
                item.animate().scaleX(1f).scaleY(1f)
                        .setDuration(260L).setInterpolator(UiKit.SPRING).start()
                navIcons[i].scaleX = 0.86f
                navIcons[i].scaleY = 0.86f
                navIcons[i].animate().scaleX(1f).scaleY(1f)
                        .setDuration(260L).setInterpolator(UiKit.SPRING).start()
            }
        }
    }

    private fun showTab(index: Int, animate: Boolean) {
        if (index < TAB_HOME || index > TAB_ABOUT || pager == null) return
        pager!!.setPage(index, animate)
        if (!animate) onTabShown(index)
    }

    /** Tab is on screen: refresh the bar, wire the search index, restore scroll state. */
    private fun onTabShown(index: Int) {
        tabIndex = index
        if (index == TAB_CONFIG) {
            if (finder != null && appIndex < appPageIds.size) {
                finder!!.attach(appPageIds[appIndex])
            }
        } else if (finder != null) {
            finder!!.dismissKeyboard()
        }
        applyNav()
        if (index == TAB_HOME && !homeAnimated && homePage != null && homePage!!.childCount > 0) {
            homeAnimated = true
            UiKit.enterStaggered(homePage!!.getChildAt(0) as ViewGroup, skin!!.dp(12f).toFloat(), 32L, 30L)
        }
    }

    /* ────────────────────────────────────────────────────────────── 备份页 */

    private fun buildBackupPage(): ScrollView {
        val sv = ScrollView(this)
        sv.setFillViewport(true)
        sv.clipToPadding = false
        sv.isVerticalScrollBarEnabled = false
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S3.toFloat()), skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S7.toFloat()))
        sv.addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.addView(skin!!.display("备份"))
        val sub = skin!!.caption("配置文件 · 快照 · 跨设备迁移")
        val subLp = skin!!.matchWrap()
        subLp.topMargin = skin!!.dp(3f)
        head.addView(sub, subLp)
        head.background = skin!!.headerScrim()
        root.addView(head, skin!!.matchWrap())

        backupStatus = skin!!.text("", Skin.T_CAPTION, Skin.REGULAR, skin!!.text3)
        val bslp = skin!!.matchWrap()
        bslp.topMargin = skin!!.dp(Skin.S3.toFloat())
        root.addView(backupStatus, bslp)

        // 导出
        val export = skin!!.card()
        val exportHead = LinearLayout(this)
        exportHead.orientation = LinearLayout.HORIZONTAL
        exportHead.gravity = Gravity.CENTER_VERTICAL
        exportHead.addView(iconTile(Icon.DOWNLOAD, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 40, 40))
        val exportText = LinearLayout(this)
        exportText.orientation = LinearLayout.VERTICAL
        exportText.addView(skin!!.rowTitle("导出配置文件"))
        exportText.addView(skin!!.caption("保存为 .json 文件，可用于备份或迁移到其它设备"))
        val etLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        etLp.leftMargin = skin!!.dp(Skin.S3.toFloat())
        exportHead.addView(exportText, etLp)
        export.addView(exportHead, skin!!.matchWrap())

        val exportNote = skin!!.footnote("将包含全部开关：" + configCounts())
        val enLp = skin!!.matchWrap()
        enLp.topMargin = skin!!.dp(Skin.S3.toFloat())
        export.addView(exportNote, enLp)

        val exportBtn = skin!!.primaryButton("导出 JSON 文件")
        val ebLp = skin!!.matchWrap()
        ebLp.topMargin = skin!!.dp(Skin.S4.toFloat())
        export.addView(exportBtn, ebLp)
        exportBtn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                ConfigBackup.bind(configStore(), configSchema(), packageName)
                ConfigBackup.startExport(this@MainActivity, ConfigBackup.REQ_EXPORT)
                val error = ConfigBackup.takeError()
                if (error != null) {
                    setBackupStatus(error, true)
                } else {
                    setBackupStatus("已打开系统保存对话框，选择位置即可导出", false)
                }
            }
        })
        root.addView(export, withTop(skin!!, Skin.S4))

        // 导入
        val imp = skin!!.card()
        val impHead = LinearLayout(this)
        impHead.orientation = LinearLayout.HORIZONTAL
        impHead.gravity = Gravity.CENTER_VERTICAL
        impHead.addView(iconTile(Icon.UPLOAD, skin!!.accent, skin!!.accentSoft), fixed(skin!!, 40, 40))
        val impText = LinearLayout(this)
        impText.orientation = LinearLayout.VERTICAL
        impText.addView(skin!!.rowTitle("导入配置文件"))
        impText.addView(skin!!.caption("选择 .json 文件，先预览再写入，不会静默覆盖"))
        val itLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        itLp.leftMargin = skin!!.dp(Skin.S3.toFloat())
        impHead.addView(impText, itLp)
        imp.addView(impHead, skin!!.matchWrap())

        val importBtn = skin!!.secondaryButton("选择 JSON 文件")
        val ibLp = skin!!.matchWrap()
        ibLp.topMargin = skin!!.dp(Skin.S4.toFloat())
        imp.addView(importBtn, ibLp)
        importBtn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                ConfigBackup.bind(configStore(), configSchema(), packageName)
                ConfigBackup.startImport(this@MainActivity, ConfigBackup.REQ_IMPORT)
                val error = ConfigBackup.takeError()
                if (error != null) setBackupStatus(error, true)
            }
        })
        val importHint = skin!!.footnote("兼容旧版「复制粘贴」的文本：存成 .json 后同样可以导入。")
        val ihLp = skin!!.matchWrap()
        ihLp.topMargin = skin!!.dp(Skin.S3.toFloat())
        imp.addView(importHint, ihLp)
        root.addView(imp, withTop(skin!!, Skin.S3))

        // 本机快照
        val snap = skin!!.card()
        snap.addView(skin!!.rowTitle("本机快照"))
        val snapNote = skin!!.caption("保存在本机；重装应用或清除数据后失效")
        val snLp = skin!!.matchWrap()
        snLp.topMargin = skin!!.dp(3f)
        snap.addView(snapNote, snLp)

        val snapRow = LinearLayout(this)
        snapRow.orientation = LinearLayout.HORIZONTAL
        val saveBtn = skin!!.secondaryButton("保存快照")
        val restoreBtn = skin!!.secondaryButton("恢复快照")
        val sbLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sbLp.rightMargin = skin!!.dp(Skin.S2.toFloat())
        snapRow.addView(saveBtn, sbLp)
        snapRow.addView(restoreBtn, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val srLp = skin!!.matchWrap()
        srLp.topMargin = skin!!.dp(Skin.S4.toFloat())
        snap.addView(snapRow, srLp)
        saveBtn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                saveSnapshot()
                setBackupStatus("已保存本机快照", false)
            }
        })
        restoreBtn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                if (readSnapshot() == null) {
                    setBackupStatus("还没有保存过快照", true)
                    return
                }
                glassDialog("恢复快照？", "将覆盖当前全部开关，回到上次保存的快照。",
                        "恢复", Runnable { restoreSnapshot() }, null, null, "取消", null)
            }
        })
        root.addView(snap, withTop(skin!!, Skin.S3))

        // 高级：文本方式
        skin!!.addSection(root, "高级")
        val advanced = skin!!.listCard()
        advanced.addView(advancedRow(Icon.COPY, "复制配置文本", "发给其它设备粘贴导入", Runnable { exportConfig() }))
        advanced.addView(skin!!.hairline())
        advanced.addView(advancedRow(Icon.PASTE, "从剪贴板导入", "粘贴旧版文本并预览", Runnable { importConfig() }))
        root.addView(advanced, skin!!.matchWrap())

        val danger = skin!!.card()
        danger.background = skin!!.glassFor(danger, Skin.R_CARD.toFloat())
        danger.addView(advancedRow(Icon.TRASH, "恢复默认设置", "清空本模块写入的开关，回到出厂值",
                Runnable {
                    glassDialog("恢复默认？", "恢复" + settingsTargetLabel() +
                            "的默认开关，强停地图 App 后生效。",
                            "恢复默认", Runnable { resetConfig() }, null, null, "取消", null)
                }))
        root.addView(danger, withTop(skin!!, Skin.S3))
        return sv
    }

    private fun advancedRow(kind: Int, title: String, caption: String, action: Runnable): View {
        val row = skin!!.row()
        val icon = Icon(this, kind, skin!!.text2)
        row.addView(icon, fixedMargin(skin!!, 20, 20, 14))
        row.addView(skin!!.rowText(title, caption), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                action.run()
            }
        })
        return row
    }

    private fun setBackupStatus(message: String?, error: Boolean) {
        if (backupStatus == null) return
        backupStatus!!.text = message
        backupStatus!!.setTextColor(if (error) skin!!.danger else skin!!.text3)
    }

    private fun configCounts(): String {
        val sb = StringBuilder()
        if (!embedded() || !embeddedAmap()) {
            if (sb.length > 0) sb.append(" · ")
            sb.append("百度 ").append(bmapSwitches.size).append(" 项")
        }
        if (!embedded() || embeddedAmap()) {
            if (sb.length > 0) sb.append(" · ")
            sb.append("高德 ").append(switches.size).append(" 项")
        }
        if (!embedded()) {
            sb.append(" · 腾讯 ").append(tmapSwitches.size).append(" 项")
        }
        return sb.toString()
    }

    /* ────────────────────────────────────────────────────────────── 关于页 */

    private fun buildAboutPage(): ScrollView {
        val sv = ScrollView(this)
        sv.setFillViewport(true)
        sv.clipToPadding = false
        sv.isVerticalScrollBarEnabled = false
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S3.toFloat()), skin!!.dp(Skin.S5.toFloat()), skin!!.dp(Skin.S7.toFloat()))
        sv.addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.addView(skin!!.display("关于"))
        val sub = skin!!.caption("版本信息 · 作者链接 · 通用设置")
        val subLp = skin!!.matchWrap()
        subLp.topMargin = skin!!.dp(3f)
        head.addView(sub, subLp)
        head.background = skin!!.headerScrim()
        root.addView(head, skin!!.matchWrap())

        // 应用身份卡
        val identity = skin!!.card()
        val identityRow = LinearLayout(this)
        identityRow.orientation = LinearLayout.HORIZONTAL
        identityRow.gravity = Gravity.CENTER_VERTICAL
        val logo = FrameLayout(this)
        var launcher: Drawable? = null
        try {
            launcher = ModuleResources.drawable(this, "ic_launcher")
        } catch (ignored: Throwable) {
        }
        if (launcher != null) {
            val image = ImageView(this)
            image.setImageDrawable(launcher)
            logo.addView(image, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        UiKit.clipRounded(logo, skin!!.dp(14f).toFloat())
        identityRow.addView(logo, fixedMargin(skin!!, 56, 56, 14))
        val nameCol = LinearLayout(this)
        nameCol.orientation = LinearLayout.VERTICAL
        nameCol.addView(skin!!.title("MapAdKiller"))
        nameCol.addView(skin!!.caption("版本 " + Version.LABEL + " · 构建 " + Version.CODE))
        identityRow.addView(nameCol, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        identityRow.addView(skin!!.chip(if (embedded()) "整合版" else "模块版",
                skin!!.accentSoft, skin!!.accent))
        identity.addView(identityRow, skin!!.matchWrap())

        root.addView(identity, withTop(skin!!, Skin.S4))

        // 支持版本：模块真正验证过的地图版本，和本机安装版本并排显示
        skin!!.addSection(root, "支持版本")
        val versions = skin!!.listCard()
        var firstVersion = true
        for (i in Version.TARGETS.indices) {
            val pkg = Version.TARGETS[i][0]
            if (embedded() && pkg != embeddedHost) continue
            if (!firstVersion) versions.addView(skin!!.hairline())
            firstVersion = false
            val row = skin!!.row()
            row.isClickable = false
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(skin!!.rowTitle(Version.TARGETS[i][1]))
            val rec = skin!!.caption("推荐版本 " + recommendedVersion(pkg))
            val recLp = skin!!.matchWrap()
            recLp.topMargin = skin!!.dp(2f)
            col.addView(rec, recLp)
            row.addView(col, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val installed = installedVersion(pkg)
            val match = installed != null && installed.startsWith(Version.TARGETS[i][2])
            row.addView(skin!!.chip(if (installed == null) "未安装" else (if (match) "本机 " + installed else "本机 " + installed),
                    if (installed == null) skin!!.chipBg else (if (match) (if (dark) 0x1F34D399 else 0x1416A34A)
                    else (if (dark) 0x2AFBBF24 else 0x1AF79009)),
                    if (installed == null) skin!!.text3 else (if (match) skin!!.success else skin!!.warn)))
            versions.addView(row)
        }
        root.addView(versions, skin!!.matchWrap())

        // 链接卡
        skin!!.addSection(root, "链接")
        val links = skin!!.listCard()
        val mark = GithubMark(this)
        mark.setColor(skin!!.text)
        links.addView(linkRow(mark, "GitHub 仓库", "github.com/ldxm666/MapAdKiller", Version.REPO))
        links.addView(skin!!.hairline())
        links.addView(linkRow(Icon(this, Icon.DOWNLOAD, skin!!.accent),
                "版本发布", "最新安装包与更新日志", Version.RELEASES))
        links.addView(skin!!.hairline())
        links.addView(linkRow(Icon(this, Icon.SEND, skin!!.accent),
                "Telegram 群组", "反馈 / 交流 / 第一时间更新", Version.TG))
        links.addView(skin!!.hairline())
        links.addView(linkRow(Icon(this, Icon.INFO, skin!!.text2),
                "问题反馈", "GitHub Issues", Version.ISSUES))
        root.addView(links, skin!!.matchWrap())

        // 通用设置
        skin!!.addSection(root, "通用")
        val general = newCard()
        if (embedded()) {
            addNoteRow(general, "当前为免 root 整合版：设置只作用于本地图，配置保存在本地图的嵌入式模块服务中。")
            if (embeddedAmap()) addSwitch(general, "调试日志", Config.K_DEBUG_LOG)
        } else {
            addLocalSwitch(general, "隐藏桌面图标", App.K_HIDE_ICON)
            addNoteRow(general, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响，" +
                    "快捷设置磁贴与 adb 命令也始终可用。")
            addSwitch(general, "调试日志（框架输出协议适配错误）", Config.K_DEBUG_LOG)
        }
        root.addView(general, skin!!.matchWrap())

        // 说明
        skin!!.addSection(root, "说明")
        val note = skin!!.card()
        val body = skin!!.body("本模块免费开源，禁止任何形式的倒卖。\n" +
                "广告与推荐内容的拦截规则随地图版本变化，升级地图后请回来看一眼开关状态。\n" +
                "遇到问题请带上机型、地图版本与日志到 GitHub Issues 或 Telegram 群反馈。")
        body.setLineSpacing(skin!!.dp(3f).toFloat(), 1.25f)
        note.addView(body, skin!!.matchWrap())
        root.addView(note, skin!!.matchWrap())

        val foot = skin!!.footnote("MapAdKiller v" + Version.LABEL + " · 免费开源 · 禁止倒卖")
        foot.gravity = Gravity.CENTER
        val flp = skin!!.matchWrap()
        flp.topMargin = skin!!.dp(Skin.S6.toFloat())
        root.addView(foot, flp)
        return sv
    }




    // ---------------------------------------------------------------- 基础工厂

    private fun text(s: String, sp: Float, style: Int, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(sp)
        t.setTypeface(if (style == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT, style)
        t.setTextColor(color)
        return t
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    private fun dp(v: Float): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    /* ──────────────────────────────────────────────────── 底部标签栏 */

    private fun buildBottomNav(): LinearLayout {
        navBar = LinearLayout(this)
        navBar.orientation = LinearLayout.VERTICAL
        navBar.setBackgroundColor(0x00000000)
        navBar.setPadding(skin!!.dp(16f), skin!!.dp(6f), skin!!.dp(16f), skin!!.dp(10f))

        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.setPadding(skin!!.dp(8f), skin!!.dp(6f), skin!!.dp(8f), skin!!.dp(6f))
        pill.background = skin!!.glassFor(pill, 26f)
        UiKit.clipRounded(pill, skin!!.dp(26f).toFloat())
        skin!!.shadow(pill, 10f)
        navBar.addView(pill, skin!!.matchWrap())

        addNavItem(pill, Icon.HOME, "首页")
        addNavItem(pill, Icon.TUNE, "配置")
        addNavItem(pill, Icon.ARCHIVE, "备份")
        addNavItem(pill, Icon.INFO, "关于")
        return navBar
    }
}