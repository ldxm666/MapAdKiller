package io.github.ldxm666.mapadkiller

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import java.util.HashSet

/**
 * SplashProbe — 三方广告 SDK 视图识别器（高德/百度/腾讯共用）。
 *
 * 只做识别，不做任何动作；由调用方决定隐藏 / 摘除 / 放行。
 * 判据两路，任一命中即算广告：
 *   ① 类名包含已知 ADN / 聚合 / 广告位词根（大小写无关）；
 *   ② 视图树上出现「跳过 / 跳转 / 广告 / Ad」这类小角标文本。
 *
 * 关键约束（踩过的坑）：
 *   · 词根用**长词根**，不用 "ad"/"ads" 这种 2~3 字母的短串 ——
 *     否则会命中 android.widget.**Ad**apterView、xxx.**Ad**dressPicker 之类的正常控件；
 *   · 角标文本必须整串相等（不查子串），长度 ≤ 8；
 *   · 遍历深度与节点数设上限，避免在开屏热路径上爆栈/卡顿。
 */
object SplashProbe {

    /** 已知广告 SDK / 聚合 / 广告位类名词根（真机实测校准，keep long） */
    @JvmField
    val TOKENS: Array<String> = arrayOf(
            // ---- 国内 ADN ----
            "qq.e", "gdtad", "tangramsplash",
            "bytedance.sdk", "pangle", "ttadview", "csj", "gromore", "bytedance.msdk",
            "kwad", "ksad", "kuaishou.ad",
            "baidu.mobads", "qumeng", "advlib", "splashcountdown",
            "sigmob", "mintegral", "mbridge",
            "meishu.sdk", "beizi.fusion", "alimm.tanx", "tradplus", "klevin",
            "windmill", "anythink", "topon", "jd.ad.sdk", "youxiao.ssp",
            "octopus.ad", "mimo.sdk", "smartdigimkt", "vlion.ad", "ubix",
            "taku.ad", "wangmai", "ruishi", "huawei.hms.ads", "openalliance.ad",
            // ---- 海外 ----
            "applovin", "ironsource", "vungle", "unityads", "facebook.ads",
            "fyber", "smaato", "yandex", "google.android.gms.ads",
            // ---- 通用广告位命名（长词根） ----
            "splashadview", "adloader", "iadloader", "adcontainer", "adcardview",
            "banneradview", "operationbanner", "splashview"
    )

    /** 角标文本：整串相等才算（避免"广告过滤"这类正常文案误杀） */
    private val BADGES = arrayOf(
            "跳过", "跳转", "广告", "关闭广告", "skip", "ad"
    )

    class Result internal constructor(
            @JvmField val hit: String?,
            @JvmField val tree: String,
            @JvmField val node: View?
    ) {
        fun isAd(): Boolean {
            return hit != null
        }
    }

    @JvmStatic
    fun scan(root: View?): Result {
        val tree = StringBuilder(256)
        val hit = arrayOfNulls<String>(1)
        val node = arrayOfNulls<View>(1)
        walk(root, tree, hit, node, 0, intArrayOf(0))
        return Result(hit[0], tree.toString(), node[0])
    }

    private fun walk(v: View?, tree: StringBuilder, hit: Array<String?>, node: Array<View?>,
                     depth: Int, budget: IntArray): Boolean {
        if (v == null || depth > 26 || budget[0] > 900) return hit[0] != null
        budget[0]++
        val cn = v.javaClass.name
        if (tree.length < 3500) tree.append(cn).append(' ')
        val low = cn.lowercase()
        for (t in TOKENS) {
            if (low.contains(t)) {
                hit[0] = t + ":" + cn
                node[0] = v
                return true
            }
        }
        val badge = badgeOf(v)
        if (badge != null) {
            hit[0] = "badge:" + badge + ":" + cn
            node[0] = v
            return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (walk(v.getChildAt(i), tree, hit, node, depth + 1, budget)) return true
            }
        }
        return false
    }

    /** 短文本角标（跳过 / 广告），整串匹配 */
    private fun badgeOf(v: View): String? {
        try {
            val cd = v.contentDescription
            var s: String? = cd?.toString()
            if (s == null && v is TextView) {
                val tx = v.text
                s = tx?.toString()
            }
            if (s == null) return null
            s = s.trim()
            if (s.isEmpty() || s.length > 8) return null
            for (b in BADGES) {
                if (s.equals(b, ignoreCase = true)) return s
            }
        } catch (ignored: Throwable) {}
        return null
    }

    // ══════════════════════════════════════════ 开屏窗口自动清扫

    /** 是否处于开屏窗口期（各 App 在自己的入口 Hook 里置位） */
    interface SplashPhase {
        fun active(): Boolean
    }

    @Volatile
    private var sweepInstalled = false

    /**
     * 在 Activity.onResume 后自动清扫开屏窗口里的广告子树。
     *
     * 为什么必须有这一层：不同 App 的开屏容器类名/挂载方式差别很大，
     * 有的容器不覆写 addView（高德 SplashContainerView 就是继承 FrameLayout 的），
     * 单靠挂容器方法会"hooks=0"。改成**在开屏窗口期扫整个 decor**，
     * 只要开屏容器和广告子树出现在窗口上就会被抓到。
     *
     * v2.1.7：只保留 600ms 一轮（原为 600/1600/3000 三轮）。
     * 开屏广告已在**数据层**被掐断（见 TmapHooks：GDTADManager.initWith 与 isInitialized
     * 返回 false、TGSplashAD.fetchAdOnly/preLoad 空转），视图树上不会再长出开屏广告子树；
     * 每次 onResume 走三遍全树递归是纯固定开销，正是用户反馈"hook 卡顿"的来源之一。
     */
    @JvmStatic
    @Synchronized
    fun installActivitySweep(phase: SplashPhase, tag: String) {
        if (sweepInstalled) return
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            val mod = H.module ?: return
            mod.hook(onResume).setId("splash_sweep_" + tag)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            try {
                                if (!phase.active()) return r
                                val act = chain.getThisObject() as? Activity ?: return r
                                val h = android.os.Handler(android.os.Looper.getMainLooper())
                                h.postDelayed(object : Runnable {
                                    override fun run() {
                                        sweepActivity(act, tag, 600)
                                    }
                                }, 600)
                            } catch (ignored: Throwable) {}
                            return r
                        }
                    })
            sweepInstalled = true
            H.log(Log.INFO, MainHook.TAG, tag + " splash activity sweep installed")
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, tag + " splash sweep fail " + t)
        }
    }

    /** 扫一遍 decor：找到开屏容器 → 摘广告子树 */
    @JvmStatic
    fun sweepActivity(act: Activity?, tag: String, at: Long) {
        try {
            if (act == null || act.isFinishing) return
            val decor = act.window.decorView
            val root = findSplashRoot(decor) ?: return
            val res = scan(root)
            if (!res.isAd()) return
            if (root is ViewGroup) stripAdChildren(root)
            if (first(tag + ":sweep:" + res.hit)) {
                H.log(Log.INFO, MainHook.TAG, tag + " splash ad swept at=" + at
                        + "ms hit=" + res.hit + " act=" + act.javaClass.name)
            }
        } catch (ignored: Throwable) {}
    }

    // ══════════════════════════════════════════ 视图树工具

    /** 在 decor 里找第一个可见的开屏容器（类名含 SplashView / SplashContainer / SplashAdView） */
    @JvmStatic
    fun findSplashRoot(v: View?): View? {
        if (v == null) return null
        try {
            if (v.visibility == View.VISIBLE && v is ViewGroup) {
                val n = v.javaClass.name
                if (n.contains("SplashViewContainer") || n.contains("SplashContainerView")
                        || n.contains("SplashAdView") || n.contains("SplashRootView")) {
                    return v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val r = findSplashRoot(v.getChildAt(i))
                    if (r != null) return r
                }
            }
        } catch (ignored: Throwable) {}
        return null
    }

    /**
     * 把开屏容器里**只**由广告 SDK 组成的子树摘掉；容器本身保留（宿主还要用它做收尾动画）。
     *
     * 判定很保守：一个直接子节点，如果它自己或其子树的**根**命中广告特征，就摘掉它。
     * 品牌层（Logo/标语）与倒计时控件不会被误伤 —— 它们的类名不含广告词根。
     */
    @JvmStatic
    fun stripAdChildren(container: ViewGroup?) {
        if (container == null) return
        var i = container.childCount - 1
        while (i >= 0) {
            val c = container.getChildAt(i)
            if (c == null) {
                i--
                continue
            }
            val r = scan(c)
            if (r.isAd() && nearRoot(r.node, c)) {
                try {
                    container.removeViewAt(i)
                    H.log(Log.INFO, MainHook.TAG, "SPLASH strip ad child hit=" + r.hit
                            + " cls=" + c.javaClass.name)
                } catch (ignored: Throwable) {}
            }
            i--
        }
    }

    /** 命中节点必须是这棵子树的前若干层，避免"容器里某处有个小广告角标"就把整块内容摘掉 */
    private fun nearRoot(node: View?, root: View): Boolean {
        var cur: View? = node
        for (i in 0 until 4) {
            if (cur == null) break
            if (cur === root) return true
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return false
    }

    /** 开屏容器是否还挂在窗口上（用于卡开屏判定） */
    @JvmStatic
    fun hasSplashOnScreen(act: Activity): Boolean {
        try {
            val decor = act.window.decorView
            return findSplashRoot(decor) != null
        } catch (t: Throwable) {
            return false
        }
    }

    /** 屏幕尺寸（角标尺寸判据用） */
    @JvmStatic
    fun screenWidth(v: View): Int {
        try {
            return v.rootView.width
        } catch (t: Throwable) {
            return 1080
        }
    }

    /** 视图是否在屏幕可见区域内（避免把预加载的不可见视图当成广告） */
    @JvmStatic
    fun onScreen(v: View): Boolean {
        try {
            val r = Rect()
            if (!v.getGlobalVisibleRect(r)) return false
            return r.width() > 0 && r.height() > 0
        } catch (t: Throwable) {
            return false
        }
    }

    private val seen: MutableSet<String> = HashSet()

    /** 诊断日志去重（同一类名只报一次） */
    @JvmStatic
    fun first(key: String): Boolean {
        synchronized(seen) {
            return seen.add(key)
        }
    }
}
