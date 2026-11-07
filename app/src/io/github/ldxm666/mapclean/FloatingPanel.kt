package io.github.ldxm666.mapclean

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import io.github.ldxm666.mapadkiller.CaptureLog


/**
 * The settings sheet the floating orb opens: a glass bottom sheet with a category rail
 * and animated switch rows.
 *
 * <h3>Structure</h3>
 * <pre>
 *   scrim            tap to dismiss, fades with the sheet
 *   └ card           rounded glass plate, slides up from the bottom
 *     ├ header       title + live "服务已连接 / 只读" pill + close
 *     ├ rail         horizontal category chips (首页 / 工具 / 内容 / 我的 / 地图)
 *     └ body         scrollable switch rows for the selected category
 * </pre>
 *
 * <h3>Why a hand-built sheet</h3>
 * The module compiles against {@code android.jar} only -- no AndroidX, no Material
 * Components -- so there is no {@code BottomSheetDialogFragment} to lean on. Everything
 * here is plain framework views plus the shared [UiKit] motion kit, which keeps the
 * look identical to the main settings page.
 */
class FloatingPanel(c: Context, darkTheme: Boolean) : FrameLayout(c), PanelTypes {

    /*
     * 转译说明（Kotlin 化，2026）：原 Java 的 `context` 字段改名 `ctx` —— Kotlin 会为
     * `val context` 生成 getContext()，与 View.getContext() 的 JVM 签名撞车（accidental
     * override）。私有字段，无契约影响。`open` 字段改名 `panelOpen`（`open` 是修饰符）。
     * `import` 只剩真正用到的；AlarmManager/PendingIntent/Intent 在原文件里就是死 import。
     */

    private val ctx: Context
    private val dark: Boolean
    /** Rebuilt whenever the sheet opens, so the "enabled first" tool ordering stays true. */
    private var groups: Array<PanelTypes.Group> = emptyArray()
    private val switches = java.util.LinkedHashMap<String, GlassSwitch>()
    /** Live slider rows of the 外观 page, keyed by config key, so [sync] can refresh. */
    private val metricRows = java.util.LinkedHashMap<String, MetricRow>()

    /**
     * Keys whose "on" is the literal stored value (see [PanelTypes.Item.strict]).
     *
     * [sync] only has the key, not the `Item`, so the mode is remembered here
     * when the row is built.
     */
    private val strictKeys = java.util.LinkedHashSet<String>()

    /* ------------------------------------------------------- 版本 / 更新（单一状态机） */

    /** Not checked yet (or the last answer was lost) -- grey. */
    private var updateState = U_UNKNOWN
    private var updateLatest: String? = null
    /** The process-wide automatic check runs once, on the first open of the sheet. */
    private var updateAutoChecked = false
    /** A check is in flight; guards against a second round that would never get a callback. */
    private var updateInFlight = false

    private var versionChip: TextView? = null
    private var versionBadge: TextView? = null
    /** The 「其他」 page's copy of the same state; null while another page is showing. */
    private var aboutUpdateRow: TextView? = null
    /** 「镜像下载」 link on the same page, visible only when a newer version is known. */
    private var aboutMirrorRow: TextView? = null

    /* ------------------------------------------------------- 写入失败提示（持久行） */

    /** 「⚠ 上次写入失败：<原因>（点此重试）」, always in the footer, hidden while writes work. */
    private var writeWarnRow: TextView? = null
    private var lastFailedKey: String? = null
    private var lastFailedValue = false
    /** Uptime of the last [open], for the scrim's grace window. */
    private var openedAt = 0L

    /**
     * The orb of this layer, wired by [FloatingController.attach].
     *
     * The sheet previews orb changes on the very orb the user is about to touch, which is
     * the whole point of "拖动时球立即变". Null until the controller has built the ball; every
     * use is null-guarded so a sheet opened in a half-built layer cannot throw.
     */
    private var ball: FloatingBall? = null

    /** Persisted plate opacity, 0.6..1. Applied to the card only -- never to the scrim. */
    private var panelAlpha = FloatingConfig.PANEL_ALPHA_DEFAULT / 100f
    /** Last width handed to [applyPanelWidth]; guards redundant layout passes. */
    private var appliedPanelWidth = Int.MIN_VALUE
    /**
     * Window width (px) the last [applyPanelWidth] was computed against.
     *
     * 原版只缓存 widthDp 一个键：旋屏 / 分屏改变窗口宽度后，同一个 dp 值会命中缓存提前
     * return，固定像素宽度就停在旧屏幕的尺寸上（宽面板在窄窗口里溢出）。
     */
    private var appliedPanelAvailable = Int.MIN_VALUE

    private val scrim: View
    private val card: LinearLayout
    private val body: LinearLayout

    /** Built in [buildRail] / [buildHeader], both called from the constructor. */
    private var rail: LinearLayout? = null
    /** The horizontal scroller around [rail]; used to bring the selected chip into view. */
    private var railScroll: HorizontalScrollView? = null
    private var statusPill: TextView? = null

    /** Search box; typing filters every category at once. */
    private var searchField: EditText? = null
    private var searchClear: TextView? = null
    private var searchCount: TextView? = null
    private var query = ""

    /* ---- horizontal swipe between categories ---- */
    /** Where the finger went down, so a swipe can be told from a scroll or a tap. */
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    /** True once the gesture is unambiguously horizontal and we have taken it over. */
    private var swipeActive = false
    /** A swipe that starts on the category rail must scroll the rail, not change category. */
    private var swipeFromRail = false

    private var selected = 0
    private var panelOpen = false
    private var onClose: Runnable? = null

    private var captureFinished = false

    /** 面板重建时会被置空；倒计时线程只在还挂着窗口时才继续刷。 */
    private var captureLogButton: TextView? = null

    private var hdCache: HyperDesign? = null

    init {
        this.ctx = c
        this.dark = darkTheme
        Vendor.from(c)          // the orb is drawn inside the host, so this is authoritative
        this.groups = buildGroups()

        this.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.isClickable = true   // swallow taps so they never reach the map behind

        // ---- scrim -------------------------------------------------------
        scrim = View(c)
        scrim.setBackgroundColor(0x00000000)
        scrim.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                // Grace window: the long press that opens this sheet fires at ~750ms and its
                // UP can land on the freshly attached scrim, which used to dismiss the sheet
                // ~1.9s after it appeared (the 「点了没反应」 reports). A tap inside the window
                // is treated as part of the opening gesture and ignored.
                val age = android.os.SystemClock.uptimeMillis() - openedAt
                if (age < SCRIM_GRACE_MS) {
                    Log.i(TAG, "event=sheet_close_ignored cause=scrim age=" + age)
                    return
                }
                closeFrom("scrim")
            }
        })
        addView(scrim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- card --------------------------------------------------------
        card = LinearLayout(c)
        card.orientation = LinearLayout.VERTICAL
        card.background = UiKit.glass(cGlass(), cRimHi(), cRimLo(),
                UiKit.dpf(c, 26f), UiKit.dp(c, 1f))
        card.elevation = 0f
        val cardLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        cardLp.gravity = Gravity.BOTTOM
        cardLp.leftMargin = UiKit.dp(c, 10f)
        cardLp.rightMargin = UiKit.dp(c, 10f)
        // Clear the gesture / navigation bar, otherwise the footer sits under it.
        cardLp.bottomMargin = UiKit.dp(c, 10f) + navigationBarInset(c)
        card.layoutParams = cardLp
        addView(card)

        card.addView(buildHeader())
        card.addView(buildSearch())
        card.addView(buildRail())

        body = LinearLayout(c)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(UiKit.dp(c, 6f), UiKit.dp(c, 2f), UiKit.dp(c, 6f), UiKit.dp(c, 8f))
        val scroll = ScrollView(c)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // Fixed sheet height: a content-sized sheet would jump every time a shorter category
        // is selected, which reads as a glitch rather than as a transition.
        val bodyHeight = Math.min(UiKit.dp(c, 396f),
                (c.resources.displayMetrics.heightPixels * 0.46f).toInt())
        card.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, bodyHeight))

        val foot = TextView(c)
        foot.text = "修改即时写入；地图内页面可能需要重新进入才会刷新。"
        foot.setTextSize(11.5f)
        foot.setTextColor(cTxS())
        foot.setPadding(UiKit.dp(c, 20f), 0, UiKit.dp(c, 20f), UiKit.dp(c, 14f))
        card.addView(foot)

        // Persistent write-failure line. Hidden while writes work; a toast is gone in two
        // seconds and leaves the user staring at a switch that flipped back.
        val warn = TextView(c)
        warn.setTextSize(11.5f)
        warn.setTextColor(cWarn())
        warn.setPadding(UiKit.dp(c, 20f), 0, UiKit.dp(c, 20f), UiKit.dp(c, 10f))
        warn.visibility = View.GONE
        warn.background = UiKit.rippleBare(cRipple(), UiKit.dpf(c, 10f))
        warn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val key = lastFailedKey ?: return
                val ok = FloatingConfig.write(key, lastFailedValue)
                if (ok) {
                    FloatingConfig.invalidate()
                    sync()
                    toast("已重新写入")
                } else {
                    toast(writeFailedHint())
                }
                refreshStatus()
            }
        })
        writeWarnRow = warn
        card.addView(warn)

        card.addView(buildDonateRow())
        card.addView(buildDefaultsRow())

        // Restore the persisted appearance before the first frame: the card is GONE at this
        // point, so this only sets the layout params and the plate alpha -- no visible jump.
        applyPanelWidth(FloatingConfig.panelWidthDp())
        applyPanelAlpha(FloatingConfig.panelAlphaPercent())

        select(0, false)
    }

    /** Hand the sheet the orb of its own layer so 外观 rows can preview on the real thing. */
    fun setBall(target: FloatingBall?) {
        ball = target
    }

    /**
     * 页脚动作行：「恢复默认配置」。
     *
     * 内置的那份 JSON 只在全新安装后自动应用一次；用户后来把开关改乱了，需要一条能
     * 手动再来一次的入口。它和应用首启用的是同一份资源、同一段写入逻辑
     * ([DefaultConfig.apply]），会覆盖当前配置，所以先确认。
     *
     * 刻意做成一行低调的文字链接而不是按钮：这一页的主角是开关，页脚只是兜底。
     */
    /**
     * 内置版没有 MainActivity：2.2.5 的「赞赏支持」入口补在面板底部。
     * 只做 UI，不动任何已有逻辑；DonateAsset 解码失败时退化为一行文字，绝不崩。
     */
    private fun buildDonateRow(): View {
        // ★ 2.3.0：**点击区放大**。
        // 原来只有「赞赏支持」那一行 13sp 文字可点：高度约 21dp、
        // 宽度也只有文字那么宽，远低于 Android 无障碍的 48dp 最小触控尺寸，
        // 用户反馈「点不到」。现在**整块可点**：
        //   · 最小高度 48dp；上下各 12dp、左右各 20dp 内边距（点击热区随之扩大）；
        //   · 浪潮背景铺满整行（原来只铺在文字上，看不出哪里能点）；
        //   · 副标题也要能点（原来它是死的，点它没反应）。
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.gravity = Gravity.CENTER_VERTICAL
        wrap.minimumHeight = UiKit.dp(ctx, 48f)
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 12f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 12f))
        wrap.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 10f))
        wrap.contentDescription = "赞赏支持，感谢投喂"
        wrap.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                showDonate()
            }
        })
        UiKit.pressable(wrap, 0.96f)

        val title = TextView(ctx)
        title.text = "赞赏支持"
        title.setTextSize(13.5f)
        title.setTextColor(cTxP())
        wrap.addView(title)

        val sub = TextView(ctx)
        sub.text = "点这里扫码 · 请作者喝杯咖啡"
        sub.setTextSize(11f)
        sub.setTextColor(cTxS())
        sub.setPadding(0, UiKit.dp(ctx, 2f), 0, 0)
        wrap.addView(sub)
        return wrap
    }

    /** 收款码对话框（复用 DonateAsset 的 base64 图，不依赖 R 资源）。 */
    private fun showDonate() {
        // 统一到 DonateUi，模块版与内置版同一套观感
        DonateUi.show(ctx)
    }

    private fun buildDefaultsRow(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), 0, UiKit.dp(ctx, 20f), UiKit.dp(ctx, 14f))

        val action = TextView(ctx)
        action.text = "恢复默认配置"
        action.setTextSize(12f)
        action.setTextColor(cAccent())
        action.paintFlags = action.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        action.setPadding(UiKit.dp(ctx, 4f), UiKit.dp(ctx, 2f),
                UiKit.dp(ctx, 4f), UiKit.dp(ctx, 2f))
        action.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 8f))
        action.contentDescription = "重新应用模块内置的默认配置，会覆盖当前开关"
        UiKit.pressable(action, 0.96f)
        action.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                confirmDefaults(action)
            }
        })
        wrap.addView(action)

        val hint = TextView(ctx)
        hint.text = "重新套用模块内置的那套配置；会覆盖当前开关，" + "未设置的项（null）保持原样。"
        hint.setTextSize(10.5f)
        hint.setTextColor(cTxS())
        hint.setLineSpacing(0f, 1.16f)
        val hlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = UiKit.dp(ctx, 2f)
        wrap.addView(hint, hlp)
        return wrap
    }

    /** 覆盖前给一次确认；确认后在后台线程写入，避免 commit() 卡住界面。 */
    private fun confirmDefaults(anchor: TextView) {
        // NONE 态下这个按钮只会静默失败，用户会以为"恢复了其实没有" —— 直接说清楚原因，
        // 连确认框都不弹（弹了也没意义：确认完同样写不进去）。
        if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
            toast("配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块")
            return
        }
        try {
            android.app.AlertDialog.Builder(ctx)
                    .setTitle("恢复默认配置？")
                    .setMessage("将把模块内置的默认配置重新写入当前地图，"
                            + "会覆盖你已经改过的开关。\n\n"
                            + "没有值的项（null）保持原样；写入后需要重新进入地图页面才会刷新。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复") { d, which -> runDefaults(anchor) }
                    .show()
        } catch (t: Throwable) {
            // 宿主主题异常时退化成直接执行，至少不让入口变成死键。
            runDefaults(anchor)
        }
    }

    private fun runDefaults(anchor: TextView) {
        anchor.isEnabled = false
        anchor.text = "正在恢复…"
        val app = ctx
        val worker = Thread(Runnable {
            var result: DefaultConfig.Result? = null
            var failure: Throwable? = null
            try {
                result = DefaultConfig.apply(app)
            } catch (t: Throwable) {
                failure = t
            }
            val outcome = result
            val error = failure
            post(Runnable {
                anchor.isEnabled = true
                anchor.text = "恢复默认配置"
                if (error != null) {
                    toast("恢复失败：" + error.message)
                    return@Runnable
                }
                if (outcome == null || outcome.groups.isEmpty()) {
                    toast("没有可写入的默认配置（服务未连接或资源缺失）")
                    return@Runnable
                }
                sync()
                toast("已恢复 " + outcome.applied + " 项，重进地图页面后生效")
            })
        }, "MapClean-defaults-manual")
        worker.isDaemon = true
        worker.start()
    }

    private fun toast(message: String) {
        try {
            android.widget.Toast.makeText(ctx, message,
                    android.widget.Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {
            // 宿主 ROM 上 Toast 也可能抛（没有窗口 / 通知被禁）。原来就是静默吞掉。
        }
    }

    /* ------------------------------------------------------------------ 头部 */

    private fun buildHeader(): View {
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 14f),
                UiKit.dp(ctx, 12f), UiKit.dp(ctx, 10f))

        // Author avatar: the picture shipped in res/drawable, cropped to a disc. Falls
        // back to the module icon, then to a monogram, so the header never shows a hole.
        val avatar = AvatarBadge(ctx, "M", 0)
        avatar.setRingColor(if (dark) 0x33FFFFFF else 0x1A000000)
        val avatarSize = UiKit.dp(ctx, 38f)
        val avatarLp = LinearLayout.LayoutParams(avatarSize, avatarSize)
        avatarLp.setMarginEnd(UiKit.dp(ctx, 12f))
        head.addView(avatar, avatarLp)

        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL

        // Line 1: product name + version chip + (optional) 「新版本」 badge.
        //
        // Width budget at the default full-width card on a 1080px/3x screen:
        //   titles ≈ 226dp  (360 - 10 - 10 card margins - 18 - 12 head padding - 44 - 12 avatar - 28 close)
        //   「MapAdKiller」 19sp ≈ 110dp, 「v2.2.2」 chip ≈ 54dp, 「新版本」 badge ≈ 42dp, gaps 8dp
        //   ⇒ 214dp: fits, and the title ellipsizes if the badge pushes it over.
        // Three-in-one-row (pill + chip + GitHub) would need 262-329dp and CANNOT fit at any
        // panel width, which is why the credit moved to line 2 and the pill owns line 2 alone.
        val titleRow = LinearLayout(ctx)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(ctx)
        title.text = "MapAdKiller"
        title.setTextSize(17f)
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        title.setTextColor(cTxP())
        title.setLetterSpacing(0.01f)
        title.isSingleLine = true
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        // The title absorbs any shortfall: the version chip and its 「新版本」 badge must never
        // be clipped, so they keep WRAP_CONTENT while this line gives up width first.
        titleRow.addView(title, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(versionChip(), chipLp(0))
        titleRow.addView(versionBadge(), chipLp(UiKit.dp(ctx, 4f)))
        titles.addView(titleRow)

        // Row 2: live connection state, then the author credit as a quiet text link.
        //
        // The author entry is deliberately understated -- a filled button here competed
        // with the switches, which are what the sheet is actually for. A muted label that
        // only picks up the accent colour under the finger reads as a link without
        // shouting, and the avatar above already identifies the author visually.
        val meta = LinearLayout(ctx)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = Gravity.CENTER_VERTICAL
        val metaLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        metaLp.topMargin = UiKit.dp(ctx, 5f)
        titles.addView(meta, metaLp)

        val pill = TextView(ctx)
        pill.setTextSize(11.5f)
        pill.setPadding(UiKit.dp(ctx, 8f), UiKit.dp(ctx, 3f),
                UiKit.dp(ctx, 8f), UiKit.dp(ctx, 3f))
        // The pill can only hold a short phrase; the actionable explanation lives on long-press
        // (a NONE pill that just says 「只读」 leaves the user with nothing to do).
        pill.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
                    toast("配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块")
                } else if (FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED) {
                    toast("配置无法保存：请检查存储空间或重装模块")
                } else {
                    toast("配置后端：" + (if (FloatingConfig.backend()
                                    == FloatingConfig.BACKEND_REMOTE) "远程（框架服务）" else "本地")
                            + "；当前版本 v" + Version.NAME)
                }
                return true
            }
        })
        statusPill = pill
        meta.addView(pill)
        refreshStatus()

        meta.addView(authorLink(), LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(titles, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(ctx)
        close.text = "✕"
        close.setTextSize(17f)
        close.setTextColor(cTxS())
        close.gravity = Gravity.CENTER
        close.contentDescription = "关闭"
        close.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 20f))
        close.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                closeFrom("close_button")
            }
        })
        val cs = UiKit.dp(ctx, 40f)
        head.addView(close, LinearLayout.LayoutParams(cs, cs))
        UiKit.pressable(close, 0.9f)
        return head
    }

    /**
     * The GitHub entry point, styled as a quiet text link next to the status pill.
     *
     * No filled background and no accent colour at rest: it sits at the same visual
     * weight as the status pill beside it, and only the mark keeps a hint of colour so the
     * row still reads as something tappable. Pressing it ripples and brightens.
     *
     * Opened through {@code ACTION_VIEW} rather than an in-app WebView. A WebView would
     * drag a browser engine into a module that otherwise never opens a network page, and the
     * user's own browser already has their GitHub session.
     */
    private fun authorLink(): View {
        val url = "https://github.com/ldxm666/MapAdKiller"

        val link = LinearLayout(ctx)
        link.orientation = LinearLayout.HORIZONTAL
        link.gravity = Gravity.CENTER_VERTICAL
        link.setPadding(UiKit.dp(ctx, 6f), UiKit.dp(ctx, 3f),
                UiKit.dp(ctx, 8f), UiKit.dp(ctx, 3f))
        link.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 10f))
        link.contentDescription = "打开 GitHub 项目主页：MapAdKiller"

        val linkLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        linkLp.setMarginStart(UiKit.dp(ctx, 6f))
        link.layoutParams = linkLp

        val mark = GithubMark(ctx)
        mark.setColor(cTxS())
        val ms = UiKit.dp(ctx, 12f)
        val markLp = LinearLayout.LayoutParams(ms, ms)
        markLp.setMarginEnd(UiKit.dp(ctx, 5f))
        link.addView(mark, markLp)

        val label = TextView(ctx)
        label.text = "GitHub"
        label.setTextSize(11f)
        label.setTextColor(cTxS())
        link.addView(label)

        // Underline the label so it is unmistakably a link, and brighten the pair on press.
        label.isSingleLine = true
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        UiKit.pressable(link, 0.96f)
        link.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                openUrl(url)
            }
        })
        return link
    }

    /** Fire an external VIEW intent, falling back to the raw string if nothing handles it. */
    private fun openUrl(url: String) {
        try {
            val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            try {
                android.widget.Toast.makeText(ctx, url,
                        android.widget.Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Actionable text for a refused write, plus one self-heal attempt.
     *
     * 「服务未连接，写入失败」 told the user nothing they could act on -- and it was often
     * simply wrong. Now the text follows the recorded reason: a refused commit is a
     * permissions/IO problem the user can actually fix, while a missing path is a "run the map
     * first" problem.
     */
    private fun writeFailedHint(): String {
        FloatingConfig.ensureServiceFrom(ctx)
        if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
            FloatingConfig.reportNoWritePath("write")
            return "配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块"
        }
        // A backend exists, so the refusal is a real storage/permission problem
        // (commit_false / io / prefs_null / no_backend all end up here).
        return "配置无法保存：请检查存储空间或重装模块"
    }

    /**
     * One refused write: say the right thing, and give the service one more chance.
     *
     * The first tap after a cold start often lands before {@code onServiceBind}, so a
     * no-path result is not a failure -- it is "not yet". That case retries itself once after
     * 300ms and says 「正在连接」 instead of 「失败」, which is what the user actually needs.
     */
    private fun onWriteFailed(key: String, value: Boolean, sw: GlassSwitch) {
        lastFailedKey = key
        lastFailedValue = value
        if (FloatingConfig.writePath() == FloatingConfig.WRITE_NONE) {
            FloatingConfig.ensureServiceFrom(ctx)
            toast("正在连接，请稍候…")
            refreshStatus()
            postDelayed(Runnable {
                if (!panelOpen) return@Runnable
                if (FloatingConfig.write(key, value)) {
                    FloatingConfig.invalidate()
                    sw.setChecked(value, true)
                    Log.i(TAG, "event=float_toggle_retry_ok key=" + key)
                } else {
                    toast(writeFailedHint())
                }
                refreshStatus()
            }, 300L)
            return
        }
        toast(writeFailedHint())
        refreshStatus()
    }

    private fun refreshStatus() {
        val pill = statusPill ?: return
        val backend = FloatingConfig.backend()
        val refused = FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED
        if (backend == FloatingConfig.BACKEND_NONE) {
            // 「需要目标 App 运行中」 was self-contradictory here: the sheet IS inside the target
            // app. Say what is actually wrong, not where the user supposedly is.
            pill.text = "○ 只读"
            pill.setTextColor(cDotBad())
            pill.background = UiKit.plate(cWarnFill(),
                    UiKit.dpf(ctx, 20f))
            FloatingConfig.reportNoWritePath("status")
        } else if (refused) {
            // A backend exists but the last write was refused (permissions / IO / disk full).
            // Saying 「可写入」 here is what sent the user chasing the wrong cause.
            pill.text = if (backend == FloatingConfig.BACKEND_REMOTE)
                "● 已连接 · 写入被拒" else "● 本地 · 写入被拒"
            pill.setTextColor(cWarn())
            pill.background = UiKit.plate(if (dark) 0x26FFB020 else 0x1AF79009,
                    UiKit.dpf(ctx, 20f))
        } else if (backend == FloatingConfig.BACKEND_REMOTE) {
            pill.text = "● 已连接"
            pill.setTextColor(cDotOk())
            pill.background = UiKit.plate(if (dark) 0x262BD88A else 0x1A12B76A,
                    UiKit.dpf(ctx, 20f))
        } else {
            // Local SharedPreferences backend: writable in-process, no framework channel
            // needed. This is the state the 免 root build falls back to on devices where the
            // remote channel never arrives.
            pill.text = "● 本地"
            pill.setTextColor(cAccent())
            pill.background = UiKit.plate(cRipple(),
                    UiKit.dpf(ctx, 20f))
        }
        paintWriteWarn()
    }

    /**
     * Footer line that survives the toast: 「⚠ 上次写入失败：<原因>（点此重试）」.
     *
     * A toast is gone in two seconds, and the user is left staring at a switch that flipped
     * back with no explanation. This row stays until a write succeeds.
     */
    private fun paintWriteWarn() {
        val row = writeWarnRow ?: return
        val refused = FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED
        if (!refused) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.text = "⚠ 上次写入失败：" + reasonText(FloatingConfig.lastWriteReason()) +
                "（点此重试）"
    }

    /* ------------------------------------------------------- 版本胶囊 / 更新状态机 */

    /** Margin helper for the two chips in the title row. */
    private fun chipLp(startMargin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMarginStart(startMargin)
        return lp
    }

    /** The version capsule: one chip, four states, never clipped (WRAP_CONTENT). */
    private fun versionChip(): TextView {
        val chip = TextView(ctx)
        chip.setTextSize(10.5f)
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        chip.isSingleLine = true
        chip.gravity = Gravity.CENTER
        chip.setPadding(UiKit.dp(ctx, 7f), UiKit.dp(ctx, 2f),
                UiKit.dp(ctx, 7f), UiKit.dp(ctx, 2f))
        chip.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 10f))
        chip.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                // A known-newer version: the useful action is opening it, not re-checking.
                if (updateState == U_NEW) {
                    openUrl(updateDownloadUrl())
                    return
                }
                if (updateState == U_FAILED) {
                    Log.i(TAG, "event=update_check_retry")
                }
                startUpdateCheck(true)
            }
        })
        versionChip = chip
        return chip
    }

    /** The 「新版本」 badge, shown only in [U_NEW]. */
    private fun versionBadge(): TextView {
        val badge = TextView(ctx)
        badge.text = "新版本"
        badge.setTextSize(9.5f)
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.isSingleLine = true
        badge.gravity = Gravity.CENTER
        badge.setPadding(UiKit.dp(ctx, 5f), UiKit.dp(ctx, 1f),
                UiKit.dp(ctx, 5f), UiKit.dp(ctx, 1f))
        badge.background = UiKit.plate(hd().error, UiKit.dpf(ctx, 6f))
        badge.visibility = View.GONE
        versionBadge = badge
        return badge
    }

    /**
     * Repaint every surface that shows the update state: the header chip, its badge, and the
     * 「其他」 page row when that page is the one on screen.
     *
     * One state machine, several surfaces -- they must never disagree, and they must never
     * each fire their own request.
     */
    private fun paintUpdate() {
        var color = cTxS()
        var badge = false
        var text = "v" + Version.NAME
        when (updateState) {
            U_CHECKING -> {
                text = "检查中…"
                color = cTxS()
            }
            U_LATEST -> {
                text = "v" + Version.NAME
                color = if (dark) 0xFF6BD68A.toInt() else 0xFF2E7D32.toInt()     // green
            }
            U_NEW -> {
                text = "v" + Version.NAME + " → v" + updateLatest
                color = hd().error
                badge = true
            }
            else -> {
                text = "v" + Version.NAME
                color = cTxS()
            }
        }
        val chip = versionChip
        if (chip != null) {
            chip.text = text
            chip.setTextColor(color)
            chip.background = UiKit.plate(tint(color), UiKit.dpf(ctx, 10f))
            chip.contentDescription = if (badge)
                "当前版本 v" + Version.NAME + "，线上有 v" + updateLatest + "，点此查看"
            else
                "当前版本 v" + Version.NAME + "，点此检查更新"
        }
        versionBadge?.visibility = if (badge) View.VISIBLE else View.GONE
        aboutUpdateRow?.let { row ->
            row.text = aboutUpdateText()
            row.setTextColor(color)
        }
        aboutMirrorRow?.let { row ->
            // 「镜像下载」 only makes sense when there is something to download, and it names
            // the mirror it will open so the user knows what is about to happen.
            row.visibility = if (badge) View.VISIBLE else View.GONE
            if (badge) row.text = "从服务器下载（" + mirrorHost() + "）"
        }
    }

    /** 下载地址的 host，用于「从服务器下载（47.109.49.93）」这类标签。 */
    private fun mirrorHost(): String {
        try {
            val url = UpdateChecker.downloadUrl(ctx)
            if (url == null) return "服务器"
            val u = java.net.URL(url)
            val host = u.host
            return host ?: "服务器"
        } catch (t: Throwable) {
            return "服务器"
        }
    }

    /**
     * 新版本的下载地址：优先服务端清单（内置版会自动选宿主对应的那个包），
     * 拿不到才退回项目发布页 —— 后者需要能访问 GitHub，只作兜底。
     */
    private fun updateDownloadUrl(): String {
        try {
            val url = UpdateChecker.downloadUrl(ctx)
            if (url != null) return url
        } catch (ignored: Throwable) {
        }
        return Version.RELEASES
    }

    /** 12% tint of a state colour, used as the chip's plate. */
    private fun tint(color: Int): Int {
        return (color and 0x00FFFFFF) or (if (dark) 0x26 shl 24 else 0x1A shl 24)
    }

    /** The 「其他」 page line, worded for the same state. */
    private fun aboutUpdateText(): String {
        return when (updateState) {
            U_CHECKING -> "检查中…"
            // 带上命中的源（host），便于诊断「为什么这台机器查不到/查到的是旧版」。
            U_NEW -> "有新版本 v" + updateLatest + "（点此查看）" + sourceSuffix()
            U_LATEST -> "✓ 已是最新 v" + Version.NAME
            U_FAILED -> "线上版本未获取（点此重试）"
            else -> "检查更新"
        }
    }

    /** 「 · via gh-proxy.com」, empty when the source is unknown or it was a cache replay. */
    private fun sourceSuffix(): String {
        try {
            val src = UpdateChecker.source(ctx)
            if (src == null || src.length == 0 || "cache" == src) return ""
            return " · via " + src
        } catch (t: Throwable) {
            return ""
        }
    }

    /**
     * Read the cached answer -- no network. Called on every open, which is why the sheet can be
     * opened as often as the user likes without a single extra request.
     */
    private fun refreshUpdateFromCache() {
        try {
            val latest = UpdateChecker.latest(ctx)
            val outdated = UpdateChecker.isOutdated(ctx)
            updateLatest = latest
            updateState = if (latest == null) U_UNKNOWN else (if (outdated) U_NEW else U_LATEST)
        } catch (t: Throwable) {
            updateState = U_UNKNOWN
        }
        paintUpdate()
    }

    /**
     * The only caller of {@code UpdateChecker.check}.
     *
     * {@code force} is true only for a tap; the automatic run on the first open passes
     * false, so the 6-hour throttle inside {@code UpdateChecker} still applies.
     */
    private fun startUpdateCheck(userInitiated: Boolean) {
        // 已经有一轮在跑（可能是 App 侧 maybeAutoCheck 起的）：不要再把自己置成 U_CHECKING ——
        // UpdateChecker 的 sRunning 会直接 return，回调永远不会来，界面就永远停在「检查中」。
        if (updateInFlight || UpdateChecker.isChecking()) {
            if (userInitiated) {
                toast("正在检查…")
                Log.i(TAG, "event=update_check_busy")
            }
            return
        }
        updateInFlight = true
        updateState = U_CHECKING
        paintUpdate()
        // 看门狗：即使 UpdateChecker 内部异常导致回调没来，也必须强制落到终态。
        // 「U_CHECKING 绝不能成为最终可见状态」是这条的硬约束。
        postDelayed(Runnable {
            if (!updateInFlight || updateState != U_CHECKING) return@Runnable
            updateInFlight = false
            updateState = resolveFromCache()
            paintUpdate()
            Log.w(TAG, "event=update_check_watchdog state=" + updateState)
        }, WATCHDOG_MS)
        try {
            UpdateChecker.check(ctx, userInitiated, object : UpdateChecker.Callback {
                override fun onResult(latest: String?, outdated: Boolean, source: String?) {
                    // Already on the main thread (UpdateChecker posts through its Handler).
                    updateInFlight = false
                    updateLatest = latest
                    updateState = if (latest == null) U_FAILED else (if (outdated) U_NEW else U_LATEST)
                    paintUpdate()
                    Log.i(TAG, "event=update_check latest=" + latest
                            + " outdated=" + outdated + " source=" + source
                            + " user=" + userInitiated)
                }
            })
        } catch (t: Throwable) {
            updateInFlight = false
            updateState = U_FAILED
            paintUpdate()
        }
    }

    /** Terminal state derived from the cache alone -- used by the watchdog. */
    private fun resolveFromCache(): Int {
        try {
            val latest = UpdateChecker.latest(ctx)
            if (latest == null) return U_FAILED
            return if (UpdateChecker.isOutdated(ctx)) U_NEW else U_LATEST
        } catch (t: Throwable) {
            return U_FAILED
        }
    }

    /* ------------------------------------------------------------------ 搜索 */

    /**
     * Search field. 高德's tool catalogue runs past a hundred entries, so a category rail
     * alone forces a lot of scrolling; typing filters across *every* category at once
     * and shows the group each hit came from.
     */
    private fun buildSearch(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = Gravity.CENTER_VERTICAL
        wrap.background = UiKit.plate(if (dark) 0x1AFFFFFF else 0x0F000000,
                UiKit.dpf(ctx, 12f))
        wrap.setPadding(UiKit.dp(ctx, 12f), 0, UiKit.dp(ctx, 8f), 0)

        val wrapLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 40f))
        wrapLp.leftMargin = UiKit.dp(ctx, 16f)
        wrapLp.rightMargin = UiKit.dp(ctx, 16f)
        wrapLp.bottomMargin = UiKit.dp(ctx, 10f)
        wrap.layoutParams = wrapLp

        val glyph = TextView(ctx)
        glyph.text = "⌕"
        glyph.setTextSize(16f)
        glyph.setTextColor(cTxS())
        wrap.addView(glyph)

        val field = EditText(ctx)
        field.hint = "搜索设置项 / 工具名"
        field.setHintTextColor(cTxS())
        field.setTextColor(cTxP())
        field.setTextSize(13.5f)
        field.isSingleLine = true
        field.background = null
        field.setPadding(UiKit.dp(ctx, 8f), 0, UiKit.dp(ctx, 4f), 0)
        // The map is behind the sheet; a soft keyboard opening would resize it oddly.
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                setQuery(if (s == null) "" else s.toString())
            }
        })
        searchField = field
        wrap.addView(field, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val count = TextView(ctx)
        count.setTextSize(11.5f)
        count.setTextColor(cTxS())
        count.visibility = View.GONE
        searchCount = count
        wrap.addView(count)

        val clear = TextView(ctx)
        clear.text = "✕"
        clear.setTextSize(13f)
        clear.setTextColor(cTxS())
        clear.gravity = Gravity.CENTER
        clear.contentDescription = "清除搜索"
        clear.visibility = View.GONE
        clear.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                searchField?.setText("")
            }
        })
        searchClear = clear
        wrap.addView(clear, LinearLayout.LayoutParams(
                UiKit.dp(ctx, 30f), LinearLayout.LayoutParams.MATCH_PARENT))
        return wrap
    }

    /** Apply a new query: filter the body and, while searching, hide the category rail. */
    private fun setQuery(value: String?) {
        val next = if (value == null) "" else value.trim().lowercase(java.util.Locale.ROOT)
        if (next == query) return
        query = next
        val searching = query.isNotEmpty()
        searchClear?.visibility = if (searching) View.VISIBLE else View.GONE
        // The hit counter only means anything while a query is active; leaving it up with a
        // stale number next to an unfiltered list would be actively misleading.
        if (!searching) searchCount?.visibility = View.GONE
        val railParent = rail?.parent
        if (railParent is View) {
            railParent.visibility = if (searching) View.GONE else View.VISIBLE
        }
        select(if (searching) -1 else Math.max(0, selected), true)
    }

    /** Every item of every category, flattened -- the haystack for search. */
    private fun flatten(): List<Array<Any?>> {
        val out = java.util.ArrayList<Array<Any?>>()
        for (g in groups) {
            val items = g.items
            val sections = g.sections
            if (items != null) {
                for (item in items) out.add(arrayOf<Any?>(g.label, item))
            } else if (sections != null) {
                for (section in sections) {
                    val sectionItems = section[1] as Array<PanelTypes.Item>
                    for (item in sectionItems) out.add(arrayOf<Any?>(section[0], item))
                }
            }
        }
        return out
    }

    /* ------------------------------------------------------------------ 分类栏 */

    private fun buildRail(): View {
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.setClipToPadding(false)
        hs.setPadding(UiKit.dp(ctx, 14f), 0, UiKit.dp(ctx, 14f), UiKit.dp(ctx, 8f))
        railScroll = hs      // kept so select() can scroll the chosen chip into view
        // No OnTouchListener here (r12 regression). A listener on the scroller -- or any
        // requestDisallowInterceptTouchEvent() issued from a view *inside* it -- propagates
        // upwards and clears the HorizontalScrollView's own interception flag, so the scroller
        // can never take the gesture over and scrollX stays 0. The panel now simply declines to
        // intercept while the DOWN was in the rail, and the scroller does the rest natively.
        val railView = LinearLayout(ctx)
        railView.orientation = LinearLayout.HORIZONTAL
        hs.addView(railView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        rail = railView
        rebuildRail()
        return hs
    }

    /** (Re)populate the category chips from the current [groups]. */
    private fun rebuildRail() {
        val railView = rail ?: return
        railView.removeAllViews()
        for (i in groups.indices) {
            val index = i
            val chip = TextView(ctx)
            chip.text = groups[i].label
            chip.setTextSize(13f)
            chip.gravity = Gravity.CENTER
            chip.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 7f),
                    UiKit.dp(ctx, 14f), UiKit.dp(ctx, 7f))
            val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.setMarginStart(UiKit.dp(ctx, 7f))
            chip.layoutParams = lp
            chip.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    // Tapping a category leaves search mode; otherwise the rail would be
                    // hidden while the chip that was just tapped stays selected.
                    val field = searchField
                    if (field != null && field.text.length > 0) {
                        field.setText("")
                        return
                    }
                    select(index, true)
                }
            })
            // Press feedback only -- deliberately an OnClickListener, not an OnTouchListener.
            //
            // r12 installed an OnTouchListener here that called requestDisallowInterceptTouchEvent
            // on the ancestors. That call propagates UPWARDS, and since the walk starts at the
            // rail's LinearLayout (a child of the scroller) it cleared the
            // HorizontalScrollView's own interception flag: the scroller could no longer take
            // the gesture over, scrollX stayed 0 and the last chips became unreachable.
            // Gesture ownership is now decided in onInterceptTouchEvent (isInRail ⇒ return
            // false), which needs no flag at all.
            UiKit.pressable(chip, 0.95f)
            railView.addView(chip)
        }
    }

    /**
     * Bring the selected chip fully into view.
     *
     * 「外观」 is the last chip and starts off-screen, so the page the user just opened was
     * unreachable without first swiping the chip row. Scrolling the rail on every selection
     * also keeps a category reachable when the list grows, and it is one short animation per
     * tap -- no loop, no idle work.
     */
    private fun scrollRailToSelected(index: Int) {
        val scroller = railScroll ?: return
        val railView = rail ?: return
        if (index < 0 || index >= railView.childCount) return
        val chip = railView.getChildAt(index) ?: return
        scroller.post(Runnable {
            val viewport = scroller.width
            if (viewport <= 0) return@Runnable
            val target = chip.left - (viewport - chip.width) / 2
            val max = Math.max(0, railView.width - viewport)
            scroller.smoothScrollTo(Math.max(0, Math.min(max, target)), 0)
        })
    }

    private fun select(index: Int, animate: Boolean) {
        selected = index
        val railView = rail
        if (railView != null) {
            for (i in 0 until railView.childCount) {
                val chip = railView.getChildAt(i) as TextView
                val on = i == index
                chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else cTxS())
                chip.setTypeface(if (on) android.graphics.Typeface.DEFAULT_BOLD
                        else android.graphics.Typeface.DEFAULT)
                chip.background = if (on)
                    UiKit.gradient(cAccentTop(), cAccentBottom(), UiKit.dpf(ctx, 18f))
                else
                    UiKit.plate(if (dark) 0x1AFFFFFF else 0x0F000000, UiKit.dpf(ctx, 18f))
            }
        }
        scrollRailToSelected(index)
        // The about row is rebuilt with every page switch; drop the stale reference so a late
        // update result cannot paint into a detached view.
        aboutUpdateRow = null
        aboutMirrorRow = null
        captureLogButton = null
        body.removeAllViews()
        // The old rows (and their sliders / switches) are gone with the body; drop the refs so
        // sync() can never poke a detached view.
        //
        // ★ 泄漏修复：原版只清 metricRows，switches / strictKeys 是**只增不减**的 —— 每切一次
        // 页就往 Map 里塞一整套 GlassSwitch（连同已 detach 的整棵行视图树），面板活多久就
        // 留多久。sync() 还会对着一堆不可见的旧开关 setChecked。这里一起清掉。
        metricRows.clear()
        switches.clear()
        strictKeys.clear()

        // index -1 means "showing search results", which span every category.
        if (index < 0) {
            renderSearchResults()
        } else {
            val g = groups[index]
            // 服务器公告：**第一个 chip 的顶部**细横幅（未读时高亮 + 红点，已读后退成一行常驻）。
            // ★ 不能用 label=="首页" 判断：高德第一页叫「首页」，百度叫「界面」，腾讯叫「悬浮窗」
            //   —— 按名字匹配会让百度/腾讯永远看不到横幅（百度真机验证时踩到）。
            // 「其他」页那两行是兜底入口，主入口在这里 —— 用户不会为了看公告翻进其他页。
            if (index == 0) {
                val announce = buildAnnounceBanner()
                if (announce != null) body.addView(announce)
            }
            // 首次使用：抓取官方下发数据（只在「首页」分组顶部）
            if ("首页" == g.label) {
                val capture = buildCaptureBanner()
                if (capture != null) body.addView(capture)
            }
            if (g.link != null) {
                val banner = buildLinkBanner(g.link!!)
                if (banner != null) body.addView(banner)
            }
            if (g.action != null) body.addView(buildActionRow(g.action!!))
            if (g.note != null) body.addView(groupNote(g.note!!))
            val items = g.items
            val sections = g.sections
            if (items != null) {
                for (item in items) body.addView(buildRow(item))
            } else if (sections != null) {
                for (section in sections) {
                    val sectionItems = section[1] as Array<PanelTypes.Item>
                    if (sectionItems.isEmpty()) continue
                    body.addView(sectionHeader(section[0] as String, sectionItems.size))
                    for (item in sectionItems) body.addView(buildRow(item))
                }
            }
            // 滑块行**独立于 items/sections** 渲染：巡航页要「先开关、后阈值」。
            // 「恢复默认」和「关闭悬浮窗」只属于纯外观页 —— 巡航页挂上去就是错的，
            // 所以用 items == null 做判据，别让它跑到别的页面。
            val metrics = g.metrics
            if (metrics != null) {
                for (m in metrics) body.addView(buildMetricRow(m))
                if (g.items == null) {
                    body.addView(buildAppearanceResetRow())
                    val offRow = buildFloatOffRow()
                    if (offRow != null) body.addView(offRow)
                }
            }
            // 当前版本 / 检查更新：挂在「其他」页底部。免 root 内置版没有 MainActivity，
            // 这两行是它唯一的版本信息与更新入口。
            if (g.about) {
                // 服务器公告：一行开关 + 一行入口。排在版本行上面 —— 公告是作者要用户
                // 立刻看到的东西，版本号是查资料时才看的。
                body.addView(buildAnnounceRows())
                body.addView(buildCaptureLogRow())
                body.addView(buildAboutRows())
            }
        }
        if (animate) {
            body.alpha = 0f
            body.translationY = UiKit.dp(ctx, 10f).toFloat()
            UiKit.enter(body, UiKit.dp(ctx, 10f).toFloat(), 0L)
        }
    }

    private fun sectionHeader(title: String, count: Int): TextView {
        val header = TextView(ctx)
        header.text = title + "　·　" + count + " 项"
        header.setTextSize(11.5f)
        header.setTextColor(cTxS())
        header.setPadding(UiKit.dp(ctx, 16f), UiKit.dp(ctx, 14f),
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 4f))
        return header
    }

    /**
     * Filter every category by the current query and render the matches, grouped by their
     * original section so a hit's context is never lost.
     *
     * Matching is case-insensitive and covers both the title and the note (which carries the
     * tool id), so "544" and "超划算" both find the same switch.
     */
    private fun renderSearchResults() {
        val hits = java.util.LinkedHashMap<String, MutableList<PanelTypes.Item>>()
        var total = 0
        for (pair in flatten()) {
            val section = pair[0] as String
            val item = pair[1] as PanelTypes.Item
            var haystack = item.title.lowercase(java.util.Locale.ROOT)
            val note = item.note
            if (note != null) haystack += "\n" + note.lowercase(java.util.Locale.ROOT)
            if (!haystack.contains(query)) continue
            var bucket = hits[section]
            if (bucket == null) {
                bucket = java.util.ArrayList<PanelTypes.Item>()
                hits[section] = bucket
            }
            bucket.add(item)
            total++
        }

        // 外观 rows are not switches, so flatten() cannot see them; match them separately or
        // searching "大小" would answer "没有匹配" while the row is one chip away.
        val metricHits = java.util.ArrayList<PanelTypes.Metric>()
        for (m in APPEARANCE) {
            var haystack = m.title.lowercase(java.util.Locale.ROOT)
            val note = m.note
            if (note != null) haystack += "\n" + note.lowercase(java.util.Locale.ROOT)
            if (!haystack.contains(query)) continue
            metricHits.add(m)
            total++
        }

        searchCount?.visibility = View.VISIBLE
        searchCount?.text = total.toString() + " 项"

        if (total == 0) {
            val empty = TextView(ctx)
            empty.text = "没有匹配「" + query + "」的设置项"
            empty.setTextSize(13.5f)
            empty.setTextColor(cTxS())
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, UiKit.dp(ctx, 40f), 0, UiKit.dp(ctx, 40f))
            body.addView(empty)
            return
        }
        for (e in hits.entries) {
            body.addView(sectionHeader(e.key, e.value.size))
            for (item in e.value) body.addView(buildRow(item))
        }
        if (metricHits.isNotEmpty()) {
            body.addView(sectionHeader("外观", metricHits.size))
            for (m in metricHits) body.addView(buildMetricRow(m))
        }
    }

    /* ------------------------------------------------------------------ 开关行 */

    private fun buildRow(item: PanelTypes.Item): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f),
                UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f))
        row.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 14f))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL

        val title = TextView(ctx)
        title.text = item.title
        title.setTextSize(14.5f)
        title.setTextColor(cTxP())
        title.setLineSpacing(0f, 1.14f)
        texts.addView(title)

        if (!item.note.isNullOrEmpty()) {
            val note = TextView(ctx)
            note.text = item.note
            note.setTextSize(11.5f)
            note.setTextColor(cTxS())
            note.setLineSpacing(0f, 1.16f)
            val nlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            nlp.topMargin = UiKit.dp(ctx, 3f)
            texts.addView(note, nlp)
        }
        row.addView(texts, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // key == null 的行是**占位 / 纯说明行** —— 腾讯「尚未学到工具目录」是最典型的一条。
        // 这类行没有可写的配置键，画一个拨不动的开关只会骗人：用户拨了、界面回弹、日志里
        // 连个失败原因都没有。所以这里直接不放开关，整行也不可点，只当说明文字渲染。
        // （Java 时期 Item.key 是平台类型 String!，null 会被塞进 switches 表并静默写失败。）
        val key = item.key ?: return row

        val sw = GlassSwitch(ctx)
        sw.setLabel(item.title)
        sw.setTrackColors(hd().secondary, cAccent())
        // Hydrate without animating: the sheet must not fire a wave of toggles on open.
        // `strict` items are read literally (labels_on defaults to false); everything else goes
        // through the visibility table, which is what makes the 「关闭…」 rows default to ON.
        if (item.strict) strictKeys.add(key)
        sw.setChecked(readItem(item), false)
        switches[key] = sw
        sw.setOnCheckedChange(object : GlassSwitch.OnCheckedChange {
            override fun onCheckedChanged(view: GlassSwitch, next: Boolean) {
                val ok = FloatingConfig.write(key, next)
                if (ok) {
                    // Same-process readers cache for 2s; drop it so the next read agrees.
                    FloatingConfig.invalidate()
                } else {
                    view.setChecked(!next, true)
                    Log.w(TAG, "event=float_toggle_fail key=" + key
                            + " value=" + next + " reason=" + FloatingConfig.lastWriteReason())
                    onWriteFailed(key, next, view)
                }
            }
        })

        // One log line per toggle, always on (a switch tap is a low-frequency user action and
        // this is the only way a verification run can tell "the row was never touched" from
        // "the write was refused"). GlassSwitch.performClick() calls super.performClick()
        // BEFORE it flips, so sw.isChecked() is still the old value here.
        val viaRow = booleanArrayOf(false)
        sw.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                Log.i(TAG, "event=float_toggle_row key=" + key
                        + " value=" + (!sw.isChecked())
                        + " via=" + (if (viaRow[0]) "row" else "switch"))
            }
        })

        row.addView(sw, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The whole row is the tap target, not just the pill at its right edge: on a 1080px
        // screen the pill is ~50dp wide, and a user aiming at the title line missed it (r9
        // report: four taps across the row, none flipped the switch). setClickable is explicit
        // -- View#setOnClickListener would do it, but stating it keeps the row's touchability
        // from depending on a side effect. The title/note TextViews stay non-clickable on
        // purpose, so a tap on them falls through to this row (and keeps the row ripple).
        row.isClickable = true
        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                viaRow[0] = true
                try {
                    sw.performClick()
                } finally {
                    viaRow[0] = false
                }
            }
        })
        return row
    }

    /* ------------------------------------------------------------------ 服务器公告 */

    /**
     * 「首页」顶部那条细横幅 —— 公告的**主入口**。
     *
     * 两种状态，同一处渲染：
     *
     *  * **有未读**：accent 色调底 + 红点 + 「查看 ›」，一眼能看见；
     *  * **已读**：退成一行常驻的「最新公告 ›」，只提供重看。
     *
     * 用的全是这个文件已有的原子：[UiKit.plate] 做底色、[UiKit.ripple] 做水波、
     * [UiKit.pressable] 做按压反馈，配色取 `cAccent/cTxS/cDotBad`，
     * 和首页「首次使用」卡片是同一套观感。
     */
    private fun buildAnnounceBanner(): View? {
        var unread = false
        var latest: String? = null
        try {
            unread = Announce.hasUnread(ctx)
            latest = Announce.latestTitle(ctx)
        } catch (ignored: Throwable) {
        }

        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 6f), UiKit.dp(ctx, 8f),
                UiKit.dp(ctx, 6f), 0)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UiKit.dp(ctx, 12f), UiKit.dp(ctx, 10f),
                UiKit.dp(ctx, 12f), UiKit.dp(ctx, 10f))
        val tone = if (unread) cAccent() else cTxS()
        row.background = UiKit.ripple(cRipple(),
                UiKit.plate((tone and 0x00FFFFFF) or (if (unread) 0x1F000000 else 0x0D000000),
                        UiKit.dpf(ctx, 14f)), UiKit.dpf(ctx, 14f))
        row.isClickable = true
        UiKit.pressable(row, 0.99f)

        if (unread) {
            val dot = View(ctx)
            dot.background = UiKit.plate(cDotBad(), UiKit.dpf(ctx, 4f))
            val dotLp = LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 8f), UiKit.dp(ctx, 8f))
            dotLp.rightMargin = UiKit.dp(ctx, 8f)
            row.addView(dot, dotLp)
        }

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val title = TextView(ctx)
        title.text = if (unread) "服务器公告" else "最新公告"
        title.setTextSize(13f)
        title.setTextColor(if (unread) cAccent() else cTxP())
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        texts.addView(title)
        if (latest != null) {
            val note = TextView(ctx)
            note.text = latest
            note.setTextSize(11f)
            note.setTextColor(cTxS())
            note.isSingleLine = true
            note.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(note)
        }
        row.addView(texts, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val action = TextView(ctx)
        action.text = if (unread) "查看 ›" else "›"
        action.setTextSize(12f)
        action.setTextColor(if (unread) cAccent() else cTxS())
        row.addView(action, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                try {
                    Announce.showLatest(ctx)
                } catch (t: Throwable) {
                    Log.w(TAG, "event=announce_show_fail err=" + t.javaClass.simpleName)
                }
            }
        })
        wrap.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /**
     * 公告读完后的刷新：面板还开着就重建当前页，横幅的红点立刻消失。
     *
     * ★ 必须是**字段**，不能每次现造一个临时 Runnable：[Announce.setOnChanged] 内部只存
     * `WeakReference<Runnable>`，`Announce.setOnChanged(announceRefresher())` 这种写法把回调
     * 交给了一个没有任何强引用的临时对象 —— GC 一跑就被回收，`sOnChanged.get()` 从此返回
     * null，红点再也不会自己消失（偶发、取决于 GC 时机，所以一直没被当成 bug）。挂在面板
     * 字段上，只要面板还活着弱引用就有效，面板没了回调本来就该失效。
     */
    private val announceRefresherRunnable = Runnable {
        if (!panelOpen) return@Runnable
        try {
            select(Math.max(0, selected), false)
        } catch (ignored: Throwable) {
        }
    }

    private fun announceRefresher(): Runnable = announceRefresherRunnable

    /**
     * 「其他」页顶部的两行：**接收服务器公告**（开关）+ **最新公告**（入口）。
     *
     * 开关行直接走 [buildRow] —— 它已经处理了「读 → 拨 → 写 → 写失败回滚 →
     * 写盘日志」整条链，且注册进 `switches`，面板每次 [sync] 都会重读。
     * 自己再画一个 GlassSwitch 等于把那条链复制一遍，迟早会和别处不一致。
     *
     * 入口行是纯动作（没有开关），所以单独画：标题 + 一行「最近一条公告是什么」+ 右侧箭头。
     * 点它重看缓存的那条；缓存为空时 [Announce.showLatest] 会强制查一次。
     */
    private fun buildAnnounceRows(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 6f), UiKit.dp(ctx, 4f),
                UiKit.dp(ctx, 6f), 0)
        wrap.addView(buildRow(PanelTypes.Item(Announce.K_ON, "接收服务器公告",
                "只在启动后和打开面板时查一次，6 小时内不重复联网；已经是同一条不会再弹。")))
        wrap.addView(buildAnnounceEntry())
        return wrap
    }

    /** 「最新公告」入口行：动作行，样式对齐 [buildRow] 的左右内边距与字阶。 */
    private fun buildAnnounceEntry(): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f),
                UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f))
        row.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 14f))
        row.isClickable = true

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL

        val title = TextView(ctx)
        title.text = "最新公告"
        title.setTextSize(14.5f)
        title.setTextColor(cTxP())
        texts.addView(title)

        val note = TextView(ctx)
        var latest: String? = null
        try {
            latest = Announce.latestTitle(ctx)
        } catch (ignored: Throwable) {
        }
        note.text = latest ?: "点开查看作者最近发布的一条公告"
        note.setTextSize(11.5f)
        note.setTextColor(cTxS())
        note.setLineSpacing(0f, 1.16f)
        val nlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = UiKit.dp(ctx, 3f)
        texts.addView(note, nlp)

        row.addView(texts, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val arrow = TextView(ctx)
        arrow.text = "›"
        arrow.setTextSize(20f)
        arrow.setTextColor(cTxS())
        row.addView(arrow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        row.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                try {
                    Announce.showLatest(ctx)
                } catch (t: Throwable) {
                    Log.w(TAG, "event=announce_show_fail err=" + t.javaClass.simpleName)
                }
            }
        })
        return row
    }

    /* ------------------------------------------------------------------ 关于 / 更新 */

    /**
     * 当前版本 + 检查更新。渲染在带 [PanelTypes.Group.about] 的那一页（「其他」）底部。
     *
     * 必须在面板里而不是 `MainActivity` 里：免 root 内置版**没有** MainActivity，
     * 悬浮面板是它唯一的设置界面。
     *
     * 用 [UpdateChecker] 的现有入口，不另造 API：
     * `latest(Context)` / `isOutdated(Context)` / `check(Context, boolean, Callback)`。
     * `check` 的回调**已经**在主线程（内部有 Handler），所以这里直接改控件。
     *
     * 四种状态与文案：检查中「检查中…」；有新版「有新版本 vX（点此查看）」→ 点击打开
     * [Version.RELEASES]；已最新「✓ 已是最新 v当前」；失败「线上版本未获取（点此重试）」。
     * 打开发链接沿用面板既有的 [openUrl]（ACTION_VIEW + NEW_TASK）。
     */
    private fun buildAboutRows(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 8f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 14f))

        val version = TextView(ctx)
        version.text = "当前版本 v" + Version.NAME
        version.setTextSize(12f)
        version.setTextColor(cTxS())
        wrap.addView(version)

        val update = TextView(ctx)
        update.setTextSize(12f)
        update.setTextColor(cAccent())
        update.paintFlags = update.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        update.setPadding(UiKit.dp(ctx, 4f), UiKit.dp(ctx, 6f),
                UiKit.dp(ctx, 4f), UiKit.dp(ctx, 6f))
        update.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 8f))
        UiKit.pressable(update, 0.96f)
        // Same state machine as the header chip: this row never queries on its own.
        aboutUpdateRow = update
        update.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                if (updateState == U_NEW) {
                    openUrl(updateDownloadUrl())
                    return
                }
                startUpdateCheck(true)
            }
        })
        val ulp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ulp.topMargin = UiKit.dp(ctx, 2f)
        wrap.addView(update, ulp)

        // 「镜像下载」：GitHub 下载页在国内常常打不开，给一个反代入口。
        // 只在有新版时出现，避免平时占位置。
        val mirror = TextView(ctx)
        mirror.setTextSize(12f)
        mirror.setTextColor(cAccent())
        mirror.paintFlags = mirror.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        mirror.setPadding(UiKit.dp(ctx, 4f), UiKit.dp(ctx, 2f),
                UiKit.dp(ctx, 4f), UiKit.dp(ctx, 6f))
        mirror.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 8f))
        UiKit.pressable(mirror, 0.96f)
        mirror.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val url = UpdateChecker.downloadUrl(ctx)
                if (url == null) {
                    toast("尚未获取下载地址，请先检查更新")
                    return
                }
                openUrl(url)
            }
        })
        aboutMirrorRow = mirror
        val mlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        wrap.addView(mirror, mlp)

        paintUpdate()       // paint from the shared state, not from a fresh request
        return wrap
    }

    /* ------------------------------------------------------------------ 一键动作 */

    /** Index of the group with this chip label, or -1. */
    private fun indexOfGroup(label: String?): Int {
        if (label == null) return -1
        for (i in groups.indices) {
            if (label == groups[i].label) return i
        }
        return -1
    }

    /**
     * True when every **suppression** toggle in the group is on (= every behaviour blocked).
     *
     * `strict` rows are skipped on purpose: they are the "enable a feature" kind
     * (`labels_on`, default OFF), not one of the 拦截 gates. Counting them would leave
     * the 首页 banner permanently in its warning state even though every 省电 gate is on.
     */
    private fun allOn(g: PanelTypes.Group): Boolean {
        val items = g.items
        if (items != null) {
            for (item in items) {
                if (item.strict) continue
                if (!readItem(item)) return false
            }
            return true
        }
        val sections = g.sections
        if (sections != null) {
            for (section in sections) {
                val sectionItems = section[1] as Array<PanelTypes.Item>
                for (item in sectionItems) {
                    if (item.strict) continue
                    if (!readItem(item)) return false
                }
            }
            return true
        }
        return true
    }

    /**
     * The banner at the top of 首页 that jumps to the 省电 page.
     *
     * Two states, both one line: everything suppressed (accent gradient) or something
     * switched off (warning fill) -- so the sheet's first screen always tells the user where
     * the power controls are and whether they are still fully on. Pure navigation: it owns no
     * setting of its own.
     */
    // ─────────────────────────────────── 首次使用：抓取官方下发数据（v2.2.6）

    /**
     * 首页「首次使用」卡片：一行状态 + 一个显式按钮 + 一行说明。
     *
     * 状态只反映事实（没抓 / 抓取中 / 已就绪），按钮才是入口——之前的整条通栏可点，
     * 用户根本看不出那是个按钮。抓取成功后按钮变成「重新抓取」，随时能手动补抓。
     */
    private fun buildCaptureBanner(): View? {
        try {
            val mode = CaptureGate.captureMode(ctx)
            val done = CaptureGate.captured(ctx)
            val running = CaptureGate.capturing(ctx)
            val status: String
            val tone: Int
            if (mode && (running || !done)) {
                status = "正在抓取官方数据…"
                tone = 0xFFB26A00.toInt()
            } else if (done) {
                status = "官方数据已就绪"
                tone = 0xFF1E8E3E.toInt()
            } else {
                status = "还没抓取官方数据"
                tone = 0xFFC62828.toInt()
            }
            val label: String
            val caption: String
            if (mode) {
                label = if (done) "重启并启用模块" else "查看抓取进度"
                caption = if (done)
                    "数据已抓完，重启一次即可按你的设置运行"
                else
                    "抓取期间模块全部暂停，请正常用一次地图（首页 / 搜索 / 我的）"
            } else if (done) {
                label = "重新抓取官方数据"
                caption = "图标缺失、工具宫格不全、家/单位不生效时点这里重抓"
            } else {
                label = "立即抓取官方数据"
                caption = "首次安装必须先抓一次，否则图标不显示、宫格缺格"
            }

            val wrap = LinearLayout(ctx)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 10f),
                    UiKit.dp(ctx, 20f), UiKit.dp(ctx, 4f))

            val pill = TextView(ctx)
            pill.text = status
            pill.setTextSize(12f)
            pill.setTextColor(tone)
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            pill.gravity = Gravity.CENTER
            pill.setPadding(UiKit.dp(ctx, 10f), UiKit.dp(ctx, 5f),
                    UiKit.dp(ctx, 10f), UiKit.dp(ctx, 5f))
            pill.background = UiKit.plate((tone and 0x00FFFFFF) or 0x22000000,
                    UiKit.dpf(ctx, 8f))
            val pillLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            pillLp.bottomMargin = UiKit.dp(ctx, 8f)
            wrap.addView(pill, pillLp)

            val button = TextView(ctx)
            button.text = label
            button.setTextSize(13.5f)
            button.setTextColor(0xFFFFFFFF.toInt())
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            button.gravity = Gravity.CENTER
            button.setPadding(0, UiKit.dp(ctx, 11f), 0, UiKit.dp(ctx, 11f))
            button.background = UiKit.gradient(cAccentTop(), cAccentBottom(),
                    UiKit.dpf(ctx, 14f))
            button.contentDescription = label
            UiKit.pressable(button, 0.97f)
            button.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    onCaptureBanner(mode, done)
                }
            })
            wrap.addView(button, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val hint = TextView(ctx)
            hint.text = caption
            hint.setTextSize(11f)
            hint.setTextColor(cTxS())
            hint.setLineSpacing(0f, 1.2f)
            hint.setPadding(0, UiKit.dp(ctx, 6f), 0, 0)
            wrap.addView(hint, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return wrap
        } catch (t: Throwable) {
            return null
        }
    }

    private fun onCaptureBanner(mode: Boolean, done: Boolean) {
        try {
            if (mode && done) {
                confirmEnableModule()
                return
            }
            if (!mode && done) {
                confirmRecapture()
                return
            }
            if (mode) {
                CaptureDialog.show(ctx, "正在抓取官方数据",
                        "现在请像平时那样用一遍地图：打开首页、搜一次、进一次「我的」。\n\n"
                                + "抓取在后台自动完成，成功后这里会变成「重启并启用模块」。",
                        "知道了", null, null, null)
                return
            }
            CaptureDialog.show(ctx, "先抓一次官方数据",
                    "高德的首页卡片、金刚位、工具宫格都是服务端下发的。"
                            + "第一次必须让高德跑一遍纯官方，把这份数据抓下来，"
                            + "之后才按你的设置改；否则会出现图标不显示、宫格缺格。\n\n"
                            + "点「开始抓取」后高德会重启一次，期间模块功能全部关闭（悬浮窗保留）。",
                    "开始抓取", Runnable {
                if (!CaptureGate.setCaptureMode(ctx, true)) {
                    toast("写入采集标记失败，请检查存储权限")
                    return@Runnable
                }
                toast("正在重启高德，请稍候…")
                restartHost()
            },
                    "取消", null)
        } catch (t: Throwable) {
            toast("操作失败：" + t.message)
        }
    }

    /** 已抓过之后的重抓入口：先关模块（采集模式），抓完再重启恢复。 */
    private fun confirmRecapture() {
        try {
            CaptureDialog.show(ctx, "重新抓取官方数据？",
                    "会先关闭模块功能并重启高德，正常用一次（首页 / 搜索 / 我的）后自动抓取，"
                            + "抓完再重启一次就恢复你的设置。\n\n期间悬浮窗保留，面板顶部状态会从黄色变绿色。"
                            + "\n\n什么时候需要重抓：高德大版本更新、首页图标/宫格明显不对、或者你换了常驻城市。",
                    "开始抓取", Runnable {
                if (!CaptureGate.setCaptureMode(ctx, true)) {
                    toast("写入采集标记失败，请检查存储权限")
                    return@Runnable
                }
                toast("正在重启高德，请稍候…")
                restartHost()
            },
                    "取消", null)
        } catch (t: Throwable) {
            toast("操作失败：" + t.message)
        }
    }

    private fun confirmEnableModule() {
        try {
            CaptureDialog.show(ctx, "启用模块功能？",
                    "官方数据已经抓到了（共 " + CaptureGate.capturedFiles(ctx) + " 个配置文件）。"
                            + "点「立即重启」后高德会重启一次，模块功能正式生效。",
                    "立即重启", Runnable { finishCapture() },
                    "稍后", null)
        } catch (t: Throwable) {
            finishCapture()
        }
    }

    /** 关掉采集模式 + 重启宿主：下一次启动就是正常的模块模式。 */
    private fun finishCapture() {
        if (captureFinished) return
        captureFinished = true
        CaptureGate.setCaptureMode(ctx, false)
        toast("正在重启高德…")
        CaptureGate.restartHost(ctx)
    }

    /**
     * 重启宿主（内置版 = 高德自己）。
     *
     * 没有 root 就不能 `am force-stop`，所以走「先排一个 1.2 秒后的系统闹钟拉起启动页，
     * 再杀掉自己」：进程死了闹钟还在系统里，起来就是干净的一次启动。
     */
    private fun restartHost() {
        // 采集模式标记由调用方自己清（「开始抓取」要带着标记重启）。
        CaptureGate.restartHost(ctx)
    }

    private fun buildLinkBanner(targetLabel: String): View? {
        val target = indexOfGroup(targetLabel)
        if (target < 0) return null
        val all = allOn(groups[target])

        val banner = TextView(ctx)
        banner.text = if (all) "净化拦截已开启 · 查看/调整 →" else "净化拦截有项已关闭 · 查看 →"
        banner.setTextSize(13f)
        banner.setTextColor(0xFFFFFFFF.toInt())
        banner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f),
                UiKit.dp(ctx, 14f), UiKit.dp(ctx, 11f))
        banner.background = if (all)
            UiKit.gradient(cAccentTop(), cAccentBottom(), UiKit.dpf(ctx, 14f))
        else
            UiKit.plate(cWarn(), UiKit.dpf(ctx, 14f))
        banner.contentDescription = if (all)
            "净化拦截已开启，点这里查看或调整"
        else
            "净化拦截有项已关闭，点这里查看"
        UiKit.pressable(banner, 0.97f)
        banner.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val at = indexOfGroup(targetLabel)
                if (at >= 0) select(at, true)
            }
        })

        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 8f),
                UiKit.dp(ctx, 14f), UiKit.dp(ctx, 2f))
        wrap.addView(banner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /**
     * 「一键省电」and friends: a full-width accent button above the group's body.
     *
     * Uses the same accent gradient as the selected chip and the same press feel as the
     * footer link, so it reads as part of the sheet rather than as a system button. After the
     * action runs, [sync] re-reads every switch so the rows below show the new state
     * instead of the stale one.
     */
    private fun buildActionRow(action: PanelTypes.Action): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 10f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 4f))

        val button = TextView(ctx)
        button.text = action.label()
        button.setTextSize(13.5f)
        button.setTextColor(0xFFFFFFFF.toInt())
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        button.gravity = Gravity.CENTER
        button.setPadding(0, UiKit.dp(ctx, 11f), 0, UiKit.dp(ctx, 11f))
        button.background = UiKit.gradient(cAccentTop(), cAccentBottom(),
                UiKit.dpf(ctx, 14f))
        button.contentDescription = action.label() + "：一次把所有净化拦截打开"
        UiKit.pressable(button, 0.97f)
        button.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                var written = 0
                try {
                    written = action.run()
                } catch (t: Throwable) {
                    Log.w(TAG, "event=action_fail err=" + t.javaClass.name)
                }
                sync()                      // rows below must show the new state at once
                toast(if (written > 0) "已开启全部净化拦截" else writeFailedHint())
                refreshStatus()
                Log.i(TAG, "event=power_all written=" + written)
            }
        })
        wrap.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /* ------------------------------------------------------------ 主动抓包（诊断） */

    /**
     * 「其他」页底部的主动抓包：两行按钮。
     * 上：抓「前 10 分钟」——内存环形缓冲里已经有的，点了立刻出 txt；
     * 下：抓「后 10 分钟」——从现在开始录，再点一下提前结束。
     */
    private fun buildCaptureLogRow(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 10f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 6f))

        val before = captureButton("抓取前 10 分钟（立即生成 txt）")
        before.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                toast("正在导出最近十分钟的数据…")
                var path: String? = null
                try {
                    path = CaptureLog.dumpRecent(ctx)
                } catch (t: Throwable) {
                    Log.w(TAG, "event=capture_dump_fail err=" + t.javaClass.name)
                }
                toast(if (path != null) "已保存：" + path else "导出失败：系统拒绝写文件，见 logcat")
            }
        })
        wrap.addView(before, matchWrap())

        val after = captureButton(captureLogLabel())
        after.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                var started = false
                try {
                    started = CaptureLog.toggleLive(ctx)
                } catch (t: Throwable) {
                    Log.w(TAG, "event=capture_toggle_fail err=" + t.javaClass.name)
                }
                after.text = captureLogLabel()
                toast(if (started) "正在抓取最近十分钟的数据…" else "已结束抓包，txt 已生成")
                if (started) tickCaptureLog(after)
                Log.i(TAG, "event=capture_toggle running=" + started)
            }
        })
        captureLogButton = after
        val lp = matchWrap()
        lp.topMargin = UiKit.dp(ctx, 8f)
        wrap.addView(after, lp)

        val hint = TextView(ctx)
        hint.text = "「前 10 分钟」立刻导出已经发生的事（只含这次打开高德以后的），" +
                "「后 10 分钟」从这一刻开始录。" +
                "两份 txt 都存到「内部存储/下载/MapClean/」，打开文件管理器点「下载」就能看到；" +
                "万一系统不给写，会自动落到 Android/data/包名/files/MapClean/。"
        hint.setTextSize(11f)
        hint.setTextColor(cTxS())
        hint.setLineSpacing(UiKit.dp(ctx, 2f).toFloat(), 1.2f)
        hint.setPadding(UiKit.dp(ctx, 2f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 2f), 0)
        wrap.addView(hint, matchWrap())
        if (CaptureLog.running()) tickCaptureLog(after)
        return wrap
    }

    private fun captureButton(text: String): TextView {
        val button = TextView(ctx)
        button.text = text
        button.setTextSize(13.5f)
        button.setTextColor(0xFFFFFFFF.toInt())
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        button.gravity = Gravity.CENTER
        button.setPadding(0, UiKit.dp(ctx, 11f), 0, UiKit.dp(ctx, 11f))
        button.background = UiKit.gradient(cAccentTop(), cAccentBottom(),
                UiKit.dpf(ctx, 14f))
        UiKit.pressable(button, 0.97f)
        return button
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun captureLogLabel(): String {
        if (!CaptureLog.running()) return "抓取后 10 分钟（从现在开始录）"
        val left = CaptureLog.remainSeconds()
        return String.format(java.util.Locale.US,
                "正在抓取最近十分钟的数据… 剩余 %d:%02d（点此提前结束）", left / 60, left % 60)
    }

    /** 面板开着时每秒刷一次倒计时；面板关了（没有 windowToken）就自动停。 */
    private fun tickCaptureLog(button: TextView) {
        button.postDelayed(object : Runnable {
            override fun run() {
                if (button.windowToken == null) return
                button.text = captureLogLabel()
                if (CaptureLog.running()) button.postDelayed(this, 1000L)
            }
        }, 1000L)
    }

    /* ------------------------------------------------------------------ 外观行 */

    /** One live slider row, kept so [sync] can re-read storage into it. */
    private class MetricRow(
            val metric: PanelTypes.Metric,
            val slider: GlassSlider,
            val value: TextView)

    /**
     * One 外观 row: title, current value, slider, optional hint.
     *
     * Same 14dp/11dp insets and the same type ramp as [buildRow]: the title is the
     * 14.5sp primary text every switch row uses, the trailing value takes the accent colour
     * the selected chip uses, and the hint is the 11.5sp secondary line. Nothing here is a
     * stock SeekBar -- [GlassSlider] draws the channel, gradient fill and thumb from the
     * same primitives [GlassSwitch] uses.
     *
     * Live preview vs. persist: the slider reports every stop with `settled=false`
     * (apply now, no disk write) and exactly one `settled=true` when the finger lifts.
     * That is what keeps a drag from firing dozens of `commit()` calls.
     */
    private fun buildMetricRow(m: PanelTypes.Metric): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.VERTICAL
        // No vertical padding here: it used to be 4dp top/bottom OUTSIDE the slider, which left
        // two 4dp strips per row that a horizontal drag could not use -- swiping there flipped
        // the category instead of changing the value. The spacing now lives inside the zone, so
        // the whole 84dp row belongs to the slider.
        row.setPadding(UiKit.dp(ctx, 14f), 0, UiKit.dp(ctx, 14f), 0)

        // ---- one touch surface for the whole row --------------------------------------
        // Device report r6: the label line and the track were two separate blocks, the user
        // dragged on the label, the sheet's horizontal category swipe claimed the gesture and
        // the page flipped to another category instead of changing the value. The row is now
        // a single slider surface: the slider fills the zone and draws its channel in the
        // bottom 48dp band, while the title/value sit on top as non-touchable text.
        val zone = FrameLayout(ctx)

        val slider = GlassSlider(ctx)
        slider.setStops(m.values.size)
        slider.setTrackColors(hd().sliderBackground, cAccent(), cAccentTop())
        slider.setTrackBandDp(48f)
        // Hydrate without notifying: opening the sheet must not write to storage.
        val position = m.positionOf(FloatingConfig.readInt(m.key, m.def))
        slider.setPosition(position)
        slider.contentDescription = m.title + "，" + m.labelAt(position)
        zone.addView(slider, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        // Text is never a touch target here: a non-clickable container returns false from
        // dispatch, so the press falls through to the slider underneath.
        head.isClickable = false
        head.isFocusable = false

        val title = TextView(ctx)
        title.text = m.title
        title.setTextSize(14.5f)
        title.setTextColor(cTxP())
        title.setLineSpacing(0f, 1.14f)
        head.addView(title, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val value = TextView(ctx)
        value.setTextSize(13f)
        value.setTextColor(cAccent())
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        value.gravity = Gravity.END
        value.text = m.labelAt(position)
        head.addView(value, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val headLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        headLp.gravity = Gravity.TOP
        headLp.topMargin = UiKit.dp(ctx, 10f)      // 4dp former row padding + 6dp gap
        zone.addView(head, headLp)

        // 84dp = the old 76dp content box plus the 4dp of vertical padding that used to sit
        // outside it: 10dp gap + one 14.5sp line + the 48dp interactive band the channel is
        // drawn in. The slider fills all of it, so there is no dead strip left to swipe on.
        row.addView(zone, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 84f)))

        if (!m.note.isNullOrEmpty()) {
            val note = TextView(ctx)
            note.text = m.note
            note.setTextSize(11.5f)
            note.setTextColor(cTxS())
            note.setLineSpacing(0f, 1.16f)
            val nlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            nlp.topMargin = UiKit.dp(ctx, 2f)
            nlp.bottomMargin = UiKit.dp(ctx, 4f)   // the row itself has no vertical padding
            row.addView(note, nlp)
        }

        slider.setOnPositionChange(object : GlassSlider.OnPositionChange {
            override fun onPositionChanged(view: GlassSlider, next: Int, settled: Boolean) {
                val stored = m.values[next]
                value.text = m.labelAt(next)
                view.contentDescription = m.title + "，" + m.labelAt(next)
                applyMetric(m, stored)
                if (!settled) return               // preview only until the finger lifts
                val ok = FloatingConfig.writeInt(m.key, stored)
                if (ok) {
                    FloatingConfig.invalidate()
                } else {
                    toast(writeFailedHint())
                    refreshStatus()
                }
            }
        })

        metricRows[m.key] = MetricRow(m, slider, value)
        return row
    }

    /**
     * 外观页脚：「恢复默认外观」。
     *
     * Deliberately the same quiet underlined text link as [buildDefaultsRow]: the
     * rows above it are the feature, this is the way back. It resets only the four appearance
     * keys, never the map's switches -- 「恢复默认配置」 in the sheet footer already does that,
     * and one button that silently did both would be a trap.
     */
    private fun buildAppearanceResetRow(): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 6f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 10f))

        val action = TextView(ctx)
        action.text = "恢复默认外观"
        action.setTextSize(12f)
        action.setTextColor(cAccent())
        action.paintFlags = action.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        action.setPadding(UiKit.dp(ctx, 4f), UiKit.dp(ctx, 2f),
                UiKit.dp(ctx, 4f), UiKit.dp(ctx, 2f))
        action.background = UiKit.rippleBare(cRipple(), UiKit.dpf(ctx, 8f))
        action.contentDescription = "把悬浮球大小、不透明度与面板宽度、不透明度恢复为默认"
        UiKit.pressable(action, 0.96f)
        action.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                resetAppearance()
            }
        })
        wrap.addView(action)

        val hint = TextView(ctx)
        hint.text = "悬浮球 " + FloatingConfig.BALL_SIZE_DEFAULT + "dp、不透明度 " +
                FloatingConfig.BALL_ALPHA_DEFAULT + "%；面板跟随屏幕宽度、不透明度 " +
                FloatingConfig.PANEL_ALPHA_DEFAULT + "%。只影响悬浮窗外观。"
        hint.setTextSize(10.5f)
        hint.setTextColor(cTxS())
        hint.setLineSpacing(0f, 1.16f)
        val hlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = UiKit.dp(ctx, 2f)
        wrap.addView(hint, hlp)
        return wrap
    }

    /** Reset the four appearance keys, apply live, and re-seat the sliders. */
    private fun resetAppearance() {
        var ok = true
        for (m in APPEARANCE) {
            ok = FloatingConfig.writeInt(m.key, m.def) and ok
            applyMetric(m, m.def)
            val row = metricRows[m.key] ?: continue
            val position = m.positionOf(m.def)
            row.slider.setPosition(position)
            row.value.text = m.labelAt(position)
            row.slider.contentDescription = m.title + "，" + m.labelAt(position)
        }
        if (ok) FloatingConfig.invalidate()
        toast(if (ok) "已恢复默认外观" else "部分设置未写入：" + writeFailedHint())
        refreshStatus()
    }

    /**
     * 「关闭悬浮窗」——真正把悬浮层摘掉，并持久化。
     *
     * 与右上角 ✕ 的区别写在文案里：✕ 只是收起这个面板（悬浮球还在），这一行才是关掉整个
     * 悬浮窗。两个动作的语义必须分得开，否则用户会以为「✕」把功能关了、下次找不到。
     *
     * 做成描边按钮而不是系统 Button：模块只用框架 API，`UiKit.outlined` 生成的圆角描边
     * 与面板其它玻璃元素同一套语言，同时用警示色把「这是一个会改变入口的动作」讲清楚。
     *
     * **找回路径是这个功能成立的前提**：关掉后 `float_enabled=false`，没有 layer，
     * 长按底部「我的」时 `FloatingController.openPanelNear` 必然失败，于是
     * `AmapMineEntry.showOwnPanel` 会自托管一个同样的面板 —— 那个面板的「其他」组里
     * 就有 `悬浮窗入口（悬浮球）` 开关，翻回去即恢复。所以这里必须把找回方式写在提示里。
     */
    private fun buildFloatOffRow(): View? {
        // 百度/腾讯内置版**没有长按找回**（MainActivity 被裁掉、也没有 tab 入口），
        // 一旦关掉就彻底回不来 —— 实测用户已经踩过一次。所以这两个厂商不提供这一行。
        if (Vendor.baidu() || Vendor.tmap()) return null

        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 10f),
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 14f))

        val action = TextView(ctx)
        action.text = "关闭悬浮窗"
        action.setTextSize(13f)
        action.setTextColor(cWarn())
        action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        action.gravity = Gravity.CENTER
        action.setPadding(0, UiKit.dp(ctx, 10f), 0, UiKit.dp(ctx, 10f))
        action.background = UiKit.outlined(cWarnFill(), cWarn(), UiKit.dp(ctx, 1f),
                UiKit.dpf(ctx, 14f))
        action.contentDescription = "关闭悬浮球和这个面板；之后可以长按底部「我的」重新打开"
        UiKit.pressable(action, 0.97f)
        action.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                closeFloatWindow()
            }
        })
        wrap.addView(action, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(ctx)
        hint.text = "关掉后悬浮球和本面板都会消失（右上角 ✕ 只是收起面板，不会关掉悬浮球）。" +
                "要重新打开：长按底部「我的」标签，在「其他」里打开「悬浮窗入口」。"
        hint.setTextSize(10.5f)
        hint.setTextColor(cTxS())
        hint.setLineSpacing(0f, 1.16f)
        val hlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = UiKit.dp(ctx, 6f)
        wrap.addView(hint, hlp)
        return wrap
    }

    /**
     * Persist `float_enabled=false` and tear the overlay down immediately.
     *
     * [FloatingConfig.write] already applies this key through
     * `FloatingController.setEnabled` (that is why the switch row works without extra
     * plumbing); the explicit call here is a second, cheap guarantee that the host is really
     * detached before the toast appears -- the user must not be left looking at a ball that
     * "closed" but is still on screen.
     *
     * A failed write is not silently ignored: without storage the orb would come back on
     * the next launch, so it is better to keep it and say so than to hide it now.
     */
    private fun closeFloatWindow() {
        val ok: Boolean
        try {
            ok = FloatingConfig.write(FloatingConfig.K_FLOAT_ENABLED, false)
        } catch (t: Throwable) {
            // 原版这里写的是 `ok = false` —— 语义一样（下面走失败分支），保持等价即可。
            toast(writeFailedHint())
            refreshStatus()
            return
        }
        if (!ok) {
            toast(writeFailedHint())
            refreshStatus()
            return
        }
        FloatingConfig.invalidate()
        try {
            FloatingController.setEnabled(false)
        } catch (t: Throwable) {
            android.util.Log.w("MapCleanFloat", "close float failed: " + t.javaClass.name)
        }
        toast("悬浮窗已关闭；长按底部「我的」可重新打开")
    }

    /** Push one appearance value into the live views; no storage access. */
    private fun applyMetric(m: PanelTypes.Metric, stored: Int) {
        if (FloatingConfig.K_FLOAT_BALL_SIZE == m.key) {
            ball?.setSizeDp(stored)
        } else if (FloatingConfig.K_FLOAT_BALL_ALPHA == m.key) {
            ball?.setAlphaPercent(stored)
        } else if (FloatingConfig.K_FLOAT_PANEL_WIDTH == m.key) {
            applyPanelWidth(stored)
        } else if (FloatingConfig.K_FLOAT_PANEL_ALPHA == m.key) {
            applyPanelAlpha(stored)
        }
    }

    /**
     * Resize the sheet plate.
     *
     * `0` (自动) restores exactly the shipped layout: MATCH_PARENT with 10dp side
     * margins and `gravity=bottom`. A numeric width switches to a fixed, centred plate,
     * clamped to the window so a 420dp sheet cannot hang off a 360dp-wide screen.
     */
    private fun applyPanelWidth(widthDp: Int) {
        val lp = card.layoutParams
        if (lp !is FrameLayout.LayoutParams) return
        val margin = UiKit.dp(ctx, 10f)
        val available = resources.displayMetrics.widthPixels - margin * 2
        // Re-seating identical LayoutParams still schedules a layout pass; skip it so the
        // once-per-open re-read in applyAppearance() cannot churn the tree. 窗口宽度也是键的一
        // 部分：旋屏 / 分屏 / 折叠屏换了宽度之后，同一个 dp 值算出的像素宽度已经不同，
        // 只看 dp 会命中缓存，把面板留在旧屏幕的尺寸上。
        if (widthDp == appliedPanelWidth && available == appliedPanelAvailable) return
        appliedPanelWidth = widthDp
        appliedPanelAvailable = available
        if (widthDp <= FloatingConfig.PANEL_WIDTH_AUTO) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            lp.leftMargin = margin
            lp.rightMargin = margin
        } else {
            val avail = Math.max(1, available)
            // 原版是 `max(240dp, px)`：窗口窄于 240dp（分屏 / 小屏 / 折叠内屏）时面板比窗口
            // 还宽，左右被裁。先保证不超窗，再把 240dp 下限收进窗口里。
            val minWidth = Math.min(UiKit.dp(ctx, 240f), avail)
            lp.width = Math.max(minWidth, Math.min(UiKit.dp(ctx, widthDp.toFloat()), avail))
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.leftMargin = 0
            lp.rightMargin = 0
        }
        card.layoutParams = lp
    }

    /** Plate opacity. The scrim keeps its own dimming -- fading it too would dim the map. */
    private fun applyPanelAlpha(percent: Int) {
        val clamped = Math.max(FloatingConfig.PANEL_ALPHA_MIN,
                Math.min(FloatingConfig.PANEL_ALPHA_MAX, percent))
        panelAlpha = clamped / 100f
        if (panelOpen) card.alpha = panelAlpha
    }

    /**
     * Re-read the appearance from storage and apply it to the orb and the sheet.
     *
     * Needed because the layer can be built before the module's preferences are reachable
     * (`attach()` runs early in the Activity's life); without this a user who set a
     * bigger orb would see the default one until the next full attach.
     */
    private fun applyAppearance() {
        ball?.let {
            it.setSizeDp(FloatingConfig.ballSizeDp())
            it.setAlphaPercent(FloatingConfig.ballAlphaPercent())
        }
        applyPanelWidth(FloatingConfig.panelWidthDp())
        applyPanelAlpha(FloatingConfig.panelAlphaPercent())
    }


    /* ------------------------------------------------------------------ 开合 */

    /** Re-read every switch and appearance value from storage; called on every open. */
    fun sync() {
        for (e in switches.entries) {
            val key = e.key
            e.value.setChecked(
                    if (strictKeys.contains(key)) FloatingConfig.readStrict(key)
                    else FloatingConfig.read(key), false)
        }
        for (row in metricRows.values) {
            val position = row.metric.positionOf(
                    FloatingConfig.readInt(row.metric.key, row.metric.def))
            row.slider.setPosition(position)          // programmatic: never writes back
            row.value.text = row.metric.labelAt(position)
            row.slider.contentDescription = row.metric.title + "，" + row.metric.labelAt(position)
        }
        applyAppearance()
        refreshStatus()
    }

    /** Drop the search filter and return to the last category. */
    private fun clearSearch() {
        // Clear the text first: its watcher re-runs select(), so assigning `query` afterwards
        // keeps this method from fighting the watcher over who owns the field.
        val field = searchField
        if (field != null && field.text.length > 0) {
            field.setText("")
        }
        query = ""
        searchCount?.visibility = View.GONE
        searchClear?.visibility = View.GONE
        val railParent = rail?.parent
        if (railParent is View) {
            railParent.visibility = View.VISIBLE
        }
    }

    fun isOpen(): Boolean = panelOpen

    fun setOnClose(r: Runnable?) {
        onClose = r
    }

    fun open() {
        if (panelOpen) return
        // 最后一道闸：模块模式（root/LSPosed）不该出现任何应用内悬浮面板。
        // FloatingHooks 一条 hook 都不装、Controller 也挡了，这里兜住「自托管面板」
        // 那条路（AmapMineEntry.showOwnPanel 会 new 一个本类并 post(open())）——
        // 立刻回调 onClose 让宿主把刚 add 进来的容器拆掉，不留空壳。
        if (!FloatingConfig.isEmbedded()) {
            Log.i(TAG, "event=floating_skipped reason=module_mode via=panel.open")
            onClose?.let {
                try {
                    it.run()
                } catch (ignored: Throwable) {
                }
            }
            return
        }
        panelOpen = true
        openedAt = android.os.SystemClock.uptimeMillis()
        visibility = View.VISIBLE
        // 面板展开时把球收起来：球和面板是 host 里的兄弟，z 序在面板之上，
        // 不收就会压在滑杆数值和右侧内容上（真机上是肉眼可见的“贴纸”感）。
        ball?.visibility = View.GONE
        // 内置版没有 MainActivity：首启「重要提示」原本只在 MainActivity.onCreate 里调用。
        // 悬浮面板是内置版唯一的设置入口，所以在这里补上唯一一处调用（FirstRunNotice 内部
        // 全 try/catch，弹窗失败也绝不会拖垮面板）。
        // 采集模式抓到数据后的「✓ 已获取官方数据」弹窗 + 自动重启由 CaptureGate.tick() 统一负责
        // （它在 onActivityResumed 里轮询 restart.pending），这里不再重复弹一次。
        clearSearch()
        // Rebuild the category data so the tool sections reflect switches flipped since the
        // last time the sheet was open (enabled entries sort to the top of each section).
        groups = buildGroups()
        rebuildRail()
        select(Math.max(0, Math.min(selected, groups.size - 1)), false)
        sync()
        // Version state: always from the cache (no request per open); the automatic check runs
        // once per process, on the first open, in the background.
        refreshUpdateFromCache()
        if (!updateAutoChecked) {
            updateAutoChecked = true
            startUpdateCheck(false)
        }
        // 每天一次的后台检查（24h 节流在 UpdateChecker 内部，重复调用不会多发请求）。
        try {
            UpdateChecker.maybeAutoCheck(ctx)
        } catch (ignored: Throwable) {
        }
        // 服务器公告：面板打开是「用户在前台」的明确信号，也是唯一保证有 Activity 可依附的
        // 时机。节流（6h）在 Announce 内部；拿不到新公告时它只把之前没弹出去的补弹一次。
        try {
            Announce.setOnChanged(announceRefresher())
            Announce.onPanelOpen(ctx)
        } catch (ignored: Throwable) {
        }
        // 每个新版本只提示一次，且只在用户打开面板时提示 —— 不在 App 启动时打扰。
        try {
            if (updateState == U_NEW && UpdateChecker.shouldNotify(ctx)) {
                toast("发现新版本 v" + updateLatest + "，点这里查看")
            }
        } catch (ignored: Throwable) {
        }
        // Dim behind the sheet and slide the card up.
        UiKit.animateFloat(0f, 0.42f, UiKit.DECELERATE, UiKit.DUR_BASE,
                ValueAnimator.AnimatorUpdateListener { a ->
                    val t = a.animatedValue as Float
                    scrim.setBackgroundColor(UiKit.blend(0x00000000,
                            if (dark) 0xB3000000.toInt() else 0x8C000000.toInt(), t / 0.42f))
                })
        card.translationY = UiKit.dp(ctx, 420f).toFloat()
        // Fade in to the *persisted* plate opacity, not to 1: a 70% sheet that flashes fully
        // opaque and then dims reads as a glitch.
        card.alpha = panelAlpha * 0.6f
        card.animate().translationY(0f).alpha(panelAlpha)
                .setDuration(UiKit.DUR_SLOW).setInterpolator(UiKit.DECELERATE).start()
    }

    /**
     * Close with a logged cause.
     *
     * There is **no** timeout anywhere in this class: a sheet only closes because the
     * user tapped the ✕ or the scrim. The log line makes that provable on a device -- a sheet
     * that vanishes on its own will name its own killer.
     */
    private fun closeFrom(cause: String) {
        Log.i(TAG, "event=sheet_close cause=" + cause
                + " age=" + (android.os.SystemClock.uptimeMillis() - openedAt))
        close()
    }

    fun close() {
        if (!panelOpen) return
        panelOpen = false
        clearSearch()
        // Drop the keyboard, otherwise it stays up over the map after the sheet is gone.
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager?
            imm?.hideSoftInputFromWindow(windowToken, 0)
            searchField?.clearFocus()
        } catch (ignored: Throwable) {
        }
        card.animate().translationY(UiKit.dp(ctx, 420f).toFloat()).alpha(0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.ACCELERATE)
                .withEndAction(Runnable {
                    // 打开动画会取消这条收尾动画（endAction 随之作废）；这里再挡一次，
                    // 免得「关 → 立刻开」的竞态把刚打开的面板又设成 GONE。
                    if (!panelOpen) {
                        visibility = View.GONE
                        ball?.visibility = View.VISIBLE
                    }
                }).start()
        UiKit.animateFloat(0.42f, 0f, UiKit.ACCELERATE, UiKit.DUR_BASE,
                ValueAnimator.AnimatorUpdateListener { a ->
                    val t = a.animatedValue as Float
                    scrim.setBackgroundColor(UiKit.blend(0x00000000,
                            if (dark) 0xB3000000.toInt() else 0x8C000000.toInt(), t / 0.42f))
                })
        onClose?.run()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // The scrim already consumes taps outside the card, so events that land on a switch
        // or on the close button reach their child normally -- never intercept those.
        //
        // The one gesture the panel does claim is a deliberate horizontal swipe, which
        // changes category. It is claimed only after the direction is unambiguous, so a
        // vertical drag still scrolls the list and a tap still hits its row.
        if (!panelOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = e.x
                swipeDownY = e.y
                swipeActive = false
                // A DOWN inside the chip row belongs to the HorizontalScrollView: returning
                // false here (and in MOVE) is the whole mechanism -- no disallow flag, no touch
                // listener on the scroller. Both of those used to clear the scroller's own
                // interception and left scrollX at 0 (r12).
                swipeFromRail = isInRail(swipeDownX, swipeDownY)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (swipeActive) return true
                // The rail is itself a horizontal scroller, and search results are a single
                // flat list with no "next category" to move to.
                if (swipeFromRail || query.isNotEmpty()) return false
                val dx = e.x - swipeDownX
                val dy = e.y - swipeDownY
                if (Math.abs(dx) > UiKit.dp(ctx, 20f)
                        && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    swipeActive = true
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeActive = false
                // Evidence line for a verification run: if the rail actually scrolled, this is
                // non-zero right after the gesture. Only logged for gestures that started in
                // the rail, so ordinary taps stay quiet.
                val scroller = railScroll
                if (swipeFromRail && scroller != null) {
                    Log.i(TAG, "event=rail_after scrollX=" + scroller.scrollX)
                }
                swipeFromRail = false
                return false
            }

            else -> return false
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (swipeActive) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val dx = e.x - swipeDownX
                swipeActive = false
                // A short drag is more likely a mis-tap than an intent to change category.
                if (Math.abs(dx) > UiKit.dp(ctx, 52f)) step(if (dx < 0) 1 else -1)
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                swipeActive = false
                return true
            }
            return true
        }
        // Only claim events when the sheet is up; otherwise let the map have them.
        return panelOpen || super.onTouchEvent(e)
    }

    /**
     * Move one category in `direction` (+1 = next, -1 = previous).
     *
     * The body slides out the way the finger went and the next one slides in behind it, so
     * the motion reads as a single continuous pager rather than a hard cut.
     *
     * **The rail wraps around.** The direction maths was never asymmetric -- the caller
     * uses `dx < 0 ? 1 : -1` and `Math.abs(dx)` for the threshold, so both ways
     * intercept and fire identically. What "failed" was the end of the list: 「外观」 is the
     * last category, so a "next" swipe from there hit `target >= count`, played a short
     * resistance nudge and changed nothing -- reported as 「右→左 失效」 (r8). Wrapping is the
     * only way both directions can do something on the page the user is actually looking at,
     * and a closed ring is the natural model for a horizontal chip rail.
     */
    private fun step(direction: Int) {
        val count = groups.size
        if (count < 2) return
        val target = ((selected + direction) % count + count) % count
        val travel = UiKit.dp(ctx, 64f)
        body.animate().translationX((-direction * travel).toFloat()).alpha(0f)
                .setDuration(UiKit.DUR_QUICK).setInterpolator(UiKit.ACCELERATE)
                .withEndAction(Runnable {
                    select(target, false)
                    body.translationX = (direction * travel).toFloat()
                    body.animate().translationX(0f).alpha(1f)
                            .setDuration(UiKit.DUR_BASE)
                            .setInterpolator(UiKit.DECELERATE).start()
                }).start()
    }

    /** True when the point falls inside the category rail (in this panel's coordinates). */
    private fun isInRail(x: Float, y: Float): Boolean {
        val railView = rail ?: return false
        val scroller = railView.parent as? View ?: return false
        val bounds = Rect()
        if (!boundsInPanel(scroller, bounds)) return false
        val hit = bounds.contains(x.toInt(), y.toInt())
        // One line per DOWN while the sheet is open: this is the only way to tell a wrong
        // hit rectangle from a wrong decision further up (the lead asked for exactly these
        // numbers -- r8 and r10 both went wrong on coordinates, so they are printed now).
        Log.i(TAG, "event=rail_probe down=" + x.toInt() + "," + y.toInt()
                + " rect=" + bounds.left + "," + bounds.top
                + "," + bounds.right + "," + bounds.bottom
                + " scroller=(" + scroller.left + "," + scroller.top
                + "," + scroller.width + "x" + scroller.height
                + ",scrollX=" + scroller.scrollX + ")"
                + " hit=" + hit)
        return hit
    }

    /**
     * Translate a descendant's rectangle into this panel's coordinate space.
     *
     * Scroll offsets are subtracted **from the parent**, not from the view itself: a
     * `ScrollView`/`HorizontalScrollView` moves its children on the canvas without
     * changing their layout bounds, so a child's visual position is
     * `child.getLeft() - parent.getScrollX()`. The r8 version subtracted the view's own
     * scroll, which shifted the reported rect of the scrolling container *itself*: once the
     * chip row had been scrolled, [isInRail] answered false and a horizontal drag on the
     * rail was taken as a category swipe instead of a rail scroll.
     */
    private fun boundsInPanel(view: View, out: Rect): Boolean {
        var left = 0
        var top = 0
        var current: View? = view
        // 引用比较：Java 的 `current != this` 是身份比较，Kotlin 的 `!=` 会走 equals —— 这里
        // 必须写 `!==` / `===`（View 现在没重写 equals，但语义上要的是同一个实例）。
        while (current != null && current !== this) {
            val parent = current.parent
            if (parent !is View) break
            left += current.left - parent.scrollX
            top += current.top - parent.scrollY
            current = parent
        }
        if (current !== this) return false
        out.set(left, top, left + view.width, top + view.height)
        return true
    }

    /* ------------------------------------------------------------------ 数据 */

    /**
     * One-line explanation for the tool catalogue.
     *
     * The home toolbar renders exactly five cells (the app's own builder iterates id0..id4),
     * so switching a sixth tool on cannot show up there. Saying so up front is better than
     * letting the limit read as another dead switch.
     */
    private fun groupNote(text: String): TextView {
        val note = TextView(ctx)
        note.text = text
        note.setTextSize(11.5f)
        note.setTextColor(cTxS())
        note.setLineSpacing(0f, 1.18f)
        note.setPadding(UiKit.dp(ctx, 16f), UiKit.dp(ctx, 10f),
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 2f))
        return note
    }

    /* ------------------------------------------------------------------ 配色 */

    /**
     * 色板全部转发到 [HyperDesign] —— 这里此前是一份独立的 Liquid Glass 色板
     * （11 个私有方法、73 处硬编码 ARGB），和模块 App 的 `Skin` 各说各话。
     * 改一次主题要动两个文件，且内置版和模块版的观感对不上。
     *
     * 方法名保持不变：调用点遍布 2844 行，改名没有收益。
     *
     * 转译约定：这 14 个方法**继续转发** HyperDesign 的 token，绝不在 Kotlin 侧把色值写回去。
     * 只有 cDotOk / cWarn 两个保留字面量 —— HyperDesign 里本来就没有这两个语义 token
     * （成功绿、警示橙），它们原本就是硬编码的。
     */
    private fun hd(): HyperDesign {
        var cached = hdCache
        if (cached == null || cached.dark != dark) {
            cached = HyperDesign.of(dark)
            hdCache = cached
        }
        return cached
    }

    /** 页面底 —— 取 surface（浅 #F7F7F7 / 深 #000000），不是 background。见 [Skin.bg] 的说明。 */
    private fun cBg(): Int = hd().surface

    /** 卡片底。HyperOS 浅色下与页面同色，靠页边距和圆角切分。 */
    private fun cGlass(): Int = hd().surfaceContainer

    /** 描边两端：HyperOS 不用高光边，只保留一点方向感。 */
    private fun cRimHi(): Int = HyperDesign.mix(hd().outline, 0xFFFFFFFF.toInt(),
            if (dark) 0.10f else 0.55f)

    private fun cRimLo(): Int = hd().outline

    private fun cTxP(): Int = hd().onSurface

    private fun cTxS(): Int = hd().onSurfaceVariantSummary

    private fun cAccent(): Int = hd().primary

    private fun cRipple(): Int = HyperDesign.alpha(hd().primary, if (dark) 0.16f else 0.10f)

    private fun cDotOk(): Int = if (dark) 0xFF34D399.toInt() else 0xFF12A150.toInt()

    private fun cDotBad(): Int = hd().error

    /** Warning tone for the one action that removes the entry point (关闭悬浮窗). */
    private fun cWarn(): Int = if (dark) 0xFFFFB020.toInt() else 0xFFC77700.toInt()

    private fun cWarnFill(): Int = HyperDesign.alpha(cWarn(), if (dark) 0.16f else 0.10f)

    private fun cAccentTop(): Int = HyperDesign.mix(hd().primary, 0xFFFFFFFF.toInt(),
            if (dark) 0.10f else 0.18f)

    private fun cAccentBottom(): Int = HyperDesign.mix(hd().primary, 0xFF000000.toInt(),
            if (dark) 0.14f else 0.10f)

    companion object {
        /** The four appearance rows, in the order the 外观 page shows them. */
        private val APPEARANCE = arrayOf(
                PanelTypes.Metric(FloatingConfig.K_FLOAT_BALL_SIZE, "悬浮球大小", null, "dp",
                        steps(FloatingConfig.BALL_SIZE_MIN, FloatingConfig.BALL_SIZE_MAX, 2),
                        null, FloatingConfig.BALL_SIZE_DEFAULT),
                PanelTypes.Metric(FloatingConfig.K_FLOAT_BALL_ALPHA, "悬浮球不透明度",
                        "调到 100% 以下时，悬浮球与收起后的把手都会变淡。", "%",
                        steps(FloatingConfig.BALL_ALPHA_MIN, FloatingConfig.BALL_ALPHA_MAX, 5),
                        null, FloatingConfig.BALL_ALPHA_DEFAULT),
                PanelTypes.Metric(FloatingConfig.K_FLOAT_PANEL_WIDTH, "面板宽度",
                        "「自动」= 跟随屏幕宽度，与之前一致。", "dp",
                        widthStops(), arrayOf("自动"), FloatingConfig.PANEL_WIDTH_DEFAULT),
                PanelTypes.Metric(FloatingConfig.K_FLOAT_PANEL_ALPHA, "面板不透明度", null, "%",
                        steps(FloatingConfig.PANEL_ALPHA_MIN, FloatingConfig.PANEL_ALPHA_MAX, 5),
                        null, FloatingConfig.PANEL_ALPHA_DEFAULT),
        )

        /** Inclusive stop list, e.g. 36..72 step 2. */
        private fun steps(from: Int, to: Int, step: Int): IntArray {
            val count = (to - from) / step + 1
            val out = IntArray(Math.max(1, count))
            for (i in out.indices) out[i] = from + i * step
            return out
        }

        /** 自动 first, then the numeric range -- so the leftmost stop is the pre-2.2.2 look. */
        private fun widthStops(): IntArray {
            val range = steps(FloatingConfig.PANEL_WIDTH_MIN, FloatingConfig.PANEL_WIDTH_MAX, 10)
            val out = IntArray(range.size + 1)
            out[0] = FloatingConfig.PANEL_WIDTH_AUTO
            System.arraycopy(range, 0, out, 1, range.size)
            return out
        }

        /** Log tag shared with [FloatingController]/[FloatingHooks]. */
        private const val TAG = "MapCleanFloat"

        /** Not checked yet (or the last answer was lost) -- grey. */
        private const val U_UNKNOWN = 0

        /** A check is in flight -- grey, 「检查中…」. */
        private const val U_CHECKING = 1

        /** Online == local -- green. */
        private const val U_LATEST = 2

        /** Online > local -- red plus the 「新版本」 badge. */
        private const val U_NEW = 3

        /** The check found nothing online -- grey. */
        private const val U_FAILED = 4

        /** Hard cap on the 「检查中…」 state: UpdateChecker's own deadline is 12s, this is the net. */
        private const val WATCHDOG_MS = 13000L

        /** A scrim tap inside this window is the UP of the press that opened the sheet. */
        private const val SCRIM_GRACE_MS = 800L

        /**
         * Read one row's stored state, honouring [PanelTypes.Item.strict].
         *
         * <p>key == null 是**占位 / 纯说明行**（腾讯「尚未学到工具目录」），没有可读的配置值，
         * 直接返回 false。不要写成 `item.key!!`：那行在真机上会立刻崩。
         */
        private fun readItem(item: PanelTypes.Item): Boolean {
            val key = item.key ?: return false
            return if (item.strict) FloatingConfig.readStrict(key)
            else FloatingConfig.read(key)
        }

        /** Human wording for one write-failure reason. */
        private fun reasonText(reason: String?): String {
            if (reason == null) return "未知原因"
            if ("commit_false" == reason) return "配置库不可写"
            if ("io" == reason) return "读写出错"
            if ("prefs_null" == reason) return "配置库不可用"
            if ("svc_null" == reason) return "服务未就绪"
            if ("no_backend" == reason) return "配置通道不可用"
            return reason
        }

        /** Height of the gesture / navigation bar, so the sheet never hides behind it. */
        private fun navigationBarInset(c: Context): Int {
            try {
                val res = c.resources
                val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
                return if (id == 0) 0 else res.getDimensionPixelSize(id)
            } catch (t: Throwable) {
                return 0
            }
        }

        /**
         * The catalogue for whichever map this orb is drawn over, plus the 外观 page.
         *
         * Never both vendors: each vendor's list lives in its own file, and the 免 root 内置
         * build for a given map replaces the other vendor's panel with a no-op stub (see
         * tools/build_variant.py).
         *
         * 外观 is appended **here** rather than inside a vendor panel on purpose: the orb's
         * own size and opacity belong to [FloatingController], not to the map's catalogue,
         * and adding it here gives all three vendors the page without editing a file that the
         * variant builder substitutes (the generated stubs would not carry a new method).
         */
        private fun buildGroups(): Array<PanelTypes.Group> {
            var vendor: Array<PanelTypes.Group> =
                    if (Vendor.tmap()) TmapPanel.groups()
                    else if (Vendor.baidu()) BaiduPanel.groups()
                    else AmapPanel.groups()
            // 服务器公告与厂商无关，落点统一收在「其他」页：高德 / 百度的目录自带这一页
            // （about=true），腾讯的目录里没有，这里补一页。放在厂商目录之外，
            // 三份目录就不必各维护一遍同样的两行。
            vendor = withAnnouncePage(vendor)
            // 模块模式（root/LSPosed）**没有悬浮窗** ⇒ 「外观」整页（球大小/透明度/关闭悬浮窗）
            // 不注册，chip 也不出现。内置版保持原样。
            if (!FloatingConfig.isEmbedded()) return vendor
            // 原版是 new Group[n+1] + System.arraycopy；Kotlin 用 `+` 生成同样内容的新数组。
            return vendor + PanelTypes.Group("外观", APPEARANCE,
                    "悬浮球与面板的外观。拖动滑块即时预览，松手后保存。")
        }

        /**
         * 保证存在一页带 `about` 的「其他」。
         *
         * 这一页承载的东西与厂商无关：**服务器公告**（开关 + 最新公告入口）和版本行。
         * 高德 `othersItems()` 与百度 `groups()` 自己声明了 `about=true`；
         * 腾讯的分类全部来自 `Spec.cats()`，没有这一页 —— 于是它的公告入口曾经无处可放。
         */
        private fun withAnnouncePage(vendorIn: Array<PanelTypes.Group>?): Array<PanelTypes.Group> {
            val vendor = vendorIn ?: emptyArray<PanelTypes.Group>()
            for (g in vendor) if (g.about) return vendor
            return vendor + PanelTypes.Group("其他", emptyArray<PanelTypes.Item>(),
                    "服务器公告与版本信息。", true)
        }
    }
}
