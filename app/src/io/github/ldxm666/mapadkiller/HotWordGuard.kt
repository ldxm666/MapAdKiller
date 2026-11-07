// language: Kotlin, file: HotWordGuard.kt, module: MapAdKiller, target: 高德地图 17.00.0.2005 首页搜索框
package io.github.ldxm666.mapadkiller

import android.content.Context
import android.util.Log
import android.view.View

import java.lang.reflect.Field
import java.util.WeakHashMap

import io.github.libxposed.api.XposedInterface

/**
 * HotWordGuard — 高德首页搜索框「热词轮播」关闭器（v1.1.0）。
 *
 * ════════════════════════════════════════════════════════════════════════
 * 为什么挂这里（jadx 静态实证，不是猜的）
 * ════════════════════════════════════════════════════════════════════════
 * 目标类：com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar
 * （运行时子类是 com.autonavi.bundle.amaphome.components.SearchBarV2，
 *   真机 DiagPage 树实证：SearchBarV2 → DtLinearLayout → … → DtTextView(id=txt_hotword)）
 *
 * 反编译确认：**所有**会写进 mHotWordTxtView(txt_hotword) 的路径只有两个出口 ——
 *   ① private void setHotWordTxt(String word, String color, n55 data, boolean anim [, PlayAnimViewListener])
 *      ← setPreSetWord(...) 的 6 个分支（新词 / 频控 / 空词 / 轮播）全部汇到这里；
 *        方法尾部还有 PreSetWordManager.b().c = word（当前词被存进全局管理器）。
 *      （17.00.0.2005 该参数类型是 c95，17.01.0.2012 变成 n55 —— 只改注释不改 hook：
 *        本类按**方法名**挂全部重载，不认参数类型，所以这个变更对 hook 无影响。）
 *   ② private void setPreWordTextView(String word, boolean z, boolean z2 [, PlayAnimViewListener])
 *      ← 上面 ① 的两个重载都调它，refreshHotWordTxt()（resume 时把上次的词贴回来）也调它；
 *        它再往下才走 PreSetWordAnimManager.a(view, word, …) —— 「文字流动」的动画就在那一层。
 *
 * 因此把 ① / ② 的第一个 String 参数换成系统默认提示词，
 * 就能一次性掐掉「文字流动」+「运营/广告词」，
 * 而且 PreSetWordManager 里存的也是默认词，onResume 贴回来的仍是静态文本。
 *
 * 默认提示词取的是 App 自己的资源
 *   com.autonavi.minimap:string/title_search_hint = "查找地点、公交、地铁"
 * （用 getIdentifier 运行时解析，不硬编码资源 id；解析失败回退同串字面量）。
 *
 * 附赠：热词品牌图标（mHotWordIconImg / mRightHotWordIconImg，运营词才带）一并 GONE，
 * 否则文字没了图标还挂着，视觉上仍是运营位。
 *
 * 开关：Config.K_HOTWORD_OFF（默认 true = 关闭热词），读取失败也回退「关」。
 */
object HotWordGuard {

    private const val CLS_BAR =
            "com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar"

    /** 目标 App 包名（解析自身字符串资源用） */
    private const val TARGET_PKG = "com.autonavi.minimap"

    /** title_search_hint 的兜底字面量（与资源同串，仅在 getIdentifier 失败时使用） */
    private const val HINT_FALLBACK = "查找地点、公交、地铁"

    @Volatile
    private var hintCache: String? = null
    @Volatile
    private var lastRewritten: String? = null
    private val iconDone: WeakHashMap<View, Boolean> = WeakHashMap()
    @Volatile
    private var logged = false
    @Volatile
    private var requestLogged = false

    // ═══════════════════════════════════════════════ 安装

    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★ 安装期门控：hotword_off 关掉（= 用户要看热词）⇒ 一条都不装（零成本）。
        if (!Config.installGate(Config.K_HOTWORD_OFF)) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=hotword_off reason=switch_off")
            return
        }
        try {
            val bar = H.cls(cl, CLS_BAR)
            if (bar == null) {
                H.log(Log.INFO, MainHook.TAG, "hotword guard: class NOT FOUND " + CLS_BAR)
                return
            }
            var n = 0
            // 按名挂全部重载（4 参 / 5 参两种签名都在里面）
            n += H.hookAll(bar, "setHotWordTxt", "amap_hotword_set", FREEZE)
            n += H.hookAll(bar, "setPreWordTextView", "amap_hotword_view", FREEZE)
            // ── 永不加载：掐掉预设词/热词的**数据源**，而不是只在最后一刻换掉文字 ──────
            // DEX 证据（17.01.0.2012, classes4.dex）：
            //   · BaseSearchBar.requestPreSetWord(ZLjava/lang/String;)V 方法体只有 3 条调用：
            //       getPreSetWordCallback() → IPreSetWordCallback.setUploadLogFlag(Z)V → new JSONObject()
            //     即它只是把「去拉词」这件事转交给宿主回调；真正的网络请求在
            //     IPreSetWordCallback 的实现里，本方法就是整条链上唯一的 Java 侧咽喉点。
            //   · 调用方 3 处，全部 void、无返回值分支可被破坏：
            //       o55.doBizLogic()V、o55$b.run()V、eb1.run()V
            //   · 同一套 DEX 里 BaseSearchBar 还有 access$1100()->Handler、
            //     access$1200()->Runnable 两个合成访问器，即轮播定时器就在这个类里；
            //     不拉词 ⇒ setPreSetWord 不被调用 ⇒ 轮播 Runnable 根本不会被 post。
            //   · setPresetWordData(...,Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;...) 说明
            //     每个运营词还要下载并解码两张品牌图标 —— 一起省掉。
            // 回滚：Config.K_HOTWORD_OFF 关掉（= 恢复热词）时本 hook 原样 proceed，行为与旧版一致。
            H.hookSig(bar, "requestPreSetWord", "amap_hotword_request", REQUEST_BLOCK,
                    Boolean::class.javaPrimitiveType, String::class.java)
            H.log(Log.INFO, MainHook.TAG, "hotword guard installed hooks=" + n)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "hotword guard fail " + t)
        }
    }

    // ═══════════════════════════════════════════════ Hooker

    private val FREEZE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val self = chain.getThisObject()
            if (self !is View || !blocked()) return chain.proceed()
            val bar = self

            var patched: Array<Any?>? = null
            try {
                val hint = hintOf(bar)
                val cur = chain.getArgs()
                for (i in 0 until cur.size) {
                    val a = cur[i]
                    if (a !is String) continue   // 第一个 String 参数就是「要显示的那句词」
                    if (hint != a) {
                        val copy = cur.toTypedArray()
                        copy[i] = hint
                        patched = copy
                        lastRewritten = a
                    }
                    break
                }
            } catch (ignored: Throwable) {}

            // 关键：proceed 只调一次 —— 放在 try 之外，异常逃逸交给框架的 protective 模式
            val r = if (patched == null) chain.proceed() else chain.proceed(patched)
            if (patched != null) {
                try {
                    hideHotWordIcons(bar)
                    if (!logged) {
                        logged = true
                        H.log(Log.INFO, MainHook.TAG, "HOTWORD-FREEZE '" + lastRewritten
                                + "' -> '" + hintOf(bar) + "' (轮播/运营词已关闭)")
                    }
                } catch (ignored: Throwable) {}
            }
            return r
        }
    }

    /**
     * 预设词数据源的短路器（「永不加载」）。
     *
     * <p>旧实现只挂 [FREEZE]：词照拉、图标照下、轮播定时器照转，只是每帧把
     * 文本换回默认提示词。对功耗来说等于「隐藏了但还在跑」。
     *
     * <p>这里直接在 `requestPreSetWord` 上短路：开关打开（默认）时不 proceed，
     * 于是宿主的 IPreSetWordCallback 永远收不到「去拉词」的指令 —— 请求不发、图标不下载、
     * 轮播不排程。方法本身返回 void，返回 null 是唯一正确的短路方式。
     */
    private val REQUEST_BLOCK: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!blocked()) return chain.proceed()
            if (!requestLogged) {
                requestLogged = true
                H.log(Log.INFO, MainHook.TAG, "HOTWORD-NOLOAD requestPreSetWord 已拦截（不拉词/不下图/不轮播）")
            }
            return null
        }
    }

    /** 开关：true = 关闭热词（默认、且读取失败也回退 true） */
    private fun blocked(): Boolean {
        try {
            return Config.visible(Config.K_HOTWORD_OFF)
        } catch (t: Throwable) {
            return true
        }
    }

    /** 默认提示词：优先读目标 App 自己的 title_search_hint，失败回退同串字面量 */
    private fun hintOf(v: View): String {
        var h = hintCache
        if (h != null) return h
        try {
            val c: Context? = v.context
            if (c != null) {
                val id = c.resources.getIdentifier("title_search_hint", "string", TARGET_PKG)
                if (id != 0) {
                    val s = c.resources.getString(id)
                    if (s != null && s.length > 0) h = s
                }
            }
        } catch (ignored: Throwable) {}
        if (h == null || h.length == 0) h = HINT_FALLBACK
        hintCache = h
        return h
    }

    /**
     * 摘掉热词旁边那对品牌图标（img_hotword_icon / img_hotword_icon_right）。
     * 字段名取自反编译证据（mHotWordIconImg / mRightHotWordIconImg），
     * 每个搜索框实例只做一次，避免热点路径反复反射。
     */
    private fun hideHotWordIcons(bar: View) {
        synchronized(iconDone) {
            if (iconDone.containsKey(bar)) return
            iconDone.put(bar, java.lang.Boolean.TRUE)
        }
        for (name in arrayOf("mHotWordIconImg", "mRightHotWordIconImg")) {
            try {
                var f: Field? = null
                var k: Class<*>? = bar.javaClass
                while (k != null && k != Any::class.java) {
                    try {
                        f = k.getDeclaredField(name)
                        break
                    } catch (ignored: NoSuchFieldException) {
                    }
                    k = k.superclass
                }
                if (f == null) continue
                f.isAccessible = true
                val o = f.get(bar)
                if (o is View && o.visibility != View.GONE) {
                    o.visibility = View.GONE
                }
            } catch (ignored: Throwable) {}
        }
    }
}
