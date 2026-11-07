package io.github.ldxm666.mapclean

import io.github.ldxm666.tmapclean.App as TmapApp
import io.github.ldxm666.tmapclean.ReportReceiver
import io.github.ldxm666.tmapclean.Spec

/**
 * 腾讯地图 side of the floating panel: the switch catalogue and the config plumbing.
 *
 * <h3>Why this file exists separately</h3>
 * Same reason as [BaiduPanel]: the orb renders one vendor's switches, never several.
 * {@code tools/build_variant.py} replaces this file with a no-op stub when it builds a variant
 * that does not carry 腾讯, so a shipped APK contains no code for a map it never runs inside.
 *
 * <h3>Catalogue source</h3>
 * Categories and rows come straight from [Spec.cats] -- the same table the standalone
 * settings page renders and the same keys the hooks read. There is deliberately no second copy.
 *
 * <h3>Learned tool catalogue</h3>
 * 腾讯's home tool grid is server-driven: the APK ships no static tool list (only four fallback
 * {@code tools_ic_*} drawables). The hook side therefore reports every {@code name → title} pair
 * it sees in the real process through {@code tmapclean.Report}, and
 * [syncLearnedTools] feeds that back into [Spec] before the rows are built.
 * Without it the per-tool switches would have nothing to list.
 */
class TmapPanel private constructor() {

    companion object {
        /** 腾讯's catalogue, mapped onto the panel's view model. */
        @JvmStatic
        fun groups(): Array<PanelTypes.Group> {
            TmapApp.ensureBound()
            syncLearnedTools()
            val cats = Spec.cats()
            // Leading group for the 悬浮窗 master switch; see BaiduPanel#groups for why it is not
            // part of Spec's catalogue (that table is 腾讯's own switches).
            val out = arrayOfNulls<PanelTypes.Group>(cats.size + 1)
            // ★ 2.3.0 修白页：这里原来是 `new PanelTypes.Item[]{}` —— **长度为 0 的 Item 数组**。
            // 面板渲染到空组时整页直接变白（导航 chip 有、内容区全空），百度侧同一个坑在
            // BaiduPanel#floatGroup 的注释里已经记过一次「实测踩过」。腾讯这一份漏了。
            // 这一组还必须至少留一行：腾讯内置版既没有独立设置页、也没有长按找回入口，
            // 悬浮球一关就再也打不开面板 —— 把开关和说明放在同一行，用户至少知道自己关了什么。
            out[0] = PanelTypes.Group(
                "悬浮窗", arrayOf(
                    PanelTypes.Item(
                        FloatingConfig.K_FLOAT_ENABLED, "显示悬浮球",
                        "关掉后悬浮球和这个面板都会消失，且腾讯内置版没有其它入口可以再打开 ——" +
                            "需要重装内置版才会恢复。正常使用请保持开启。"
                    )
                ), "只影响悬浮球本身，不动其它任何开关。"
            )
            for (i in cats.indices) {
                val cat = cats[i]
                val items = arrayOfNulls<PanelTypes.Item>(cat.rows.size)
                for (j in cat.rows.indices) {
                    val row = cat.rows[j]
                    items[j] = PanelTypes.Item(row.key, row.title, row.note)
                }
                out[i + 1] = PanelTypes.Group(cat.title, items.requireNoNulls(), cat.subtitle)
            }
            return out.requireNoNulls()
        }

        /**
         * True when a writable path is available inside the host process.
         *
         * <p>{@code App.ensureBound()} is what makes this answer true inside 腾讯地图.
         * The shared {@code mapclean.App.attachEmbedded} forwarding never reaches
         * {@code tmapclean.App.onBind} in this host (it is gated on a vendor list that only
         * names 高德 and 百度), so the 腾讯 holder has to bind the LSPatch service itself.
         * Registration is synchronous once the framework has delivered the service, so the
         * first panel render already sees it. See {@code tmapclean.App#ensureBound}.
         */
        @JvmStatic
        fun writable(): Boolean {
            return try {
                TmapApp.ensureBound()
                TmapApp.svc() != null
            } catch (ignored: Throwable) {
                false
            }
        }

        /** 见 [BaiduPanel.hasStored] —— 同一个「关掉重启又回来」的根因。 */
        @JvmStatic
        fun hasStored(key: String): Boolean {
            return try {
                val p = io.github.ldxm666.tmapclean.Cfg.prefs()
                p != null && p.contains(key)
            } catch (t: Throwable) {
                false
            }
        }

        @JvmStatic
        fun read(key: String): Boolean {
            return try {
                TmapApp.read(key, Spec.defaultChecked(key, Spec.APP_READ))
            } catch (t: Throwable) {
                Spec.defaultChecked(key, Spec.APP_READ)
            }
        }

        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            return try {
                TmapApp.write(key, value)
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun invalidate() {
            io.github.ldxm666.tmapclean.Cfg.invalidate()
        }

        /**
         * Nothing to push: 腾讯 applies these switches when the affected view is built
         * (底栏 / 工具宫格 / 我的页都在重新进入页面时重建)，改动后重开腾讯地图生效。
         */
        @JvmStatic
        fun apply(key: String) {
            io.github.ldxm666.tmapclean.MainHook.onConfigChanged(key)
        }

        /**
         * 把 hook 侧学到的工具目录灌进 [Spec]。
         *
         * <p>每次构建面板都调一次：目录只在真正打开过腾讯首页之后才有，
         * 用户在设置页里下拉刷新即可看到逐项开关出现。
         */
        @JvmStatic
        fun syncLearnedTools() {
            try {
                val app = TmapApp.app()
                if (app == null) return
                val catalog = ReportReceiver.catalog(app)
                if (catalog != null) Spec.learnTools(catalog[0], catalog[1])
            } catch (ignored: Throwable) {
            }
        }
    }
}