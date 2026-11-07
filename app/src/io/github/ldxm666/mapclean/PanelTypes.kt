package io.github.ldxm666.mapclean

/**
 * 面板的**纯数据类型**（原 {@code FloatingPanel} 的嵌套类型）。
 *
 * <h3>为什么单独抽出来</h3>
 * 用户要求「模块版必须彻底移除免 root 内置部分」。{@code FloatingPanel}（79 KB，悬浮面板 UI）
 * 必须从模块产物里消失，但**它的数据类型仍被保留代码使用**：
 * {@code AmapPanel} / {@code BaiduPanel} / {@code TmapPanel} 用 [Group] / [Item] /
 * [Action] 描述各自的开关清单，{@code MainActivity} 渲染这些清单。
 *
 * <p>所以把类型搬到这里，UI 留在 {@code FloatingPanel}。{@code FloatingPanel} 改成
 * {@code implements PanelTypes} —— **接口的嵌套类型会被继承到实现类的作用域**，
 * 于是那 79 KB 文件里的 {@code Item}/{@code Group}/{@code Metric} 一行都不用改。
 *
 * <p>模块构建由 {@code tools/module_prune.py} 直接删掉 {@code FloatingPanel.java}，本文件保留。
 */
interface PanelTypes {

    class Item @JvmOverloads constructor(
        /**
         * 配置键。**可以为 null**：腾讯 `Spec.Row` 的「尚未学到工具目录」占位行、
         * 以及所有纯说明行都传 null（Java 时期是平台类型 `String!`，所以没暴露）。
         * 读 key 的地方必须处理 null —— 直接当非空用会在这些行上 NPE。
         */
        @JvmField val key: String?,
        @JvmField val title: String,
        @JvmField val note: String? = null,
        /**
         * True when "on" means "the stored value is literally true".
         *
         * <p>Almost every key in this sheet is read through {@code Config.visible()}, which
         * resolves a missing key through {@code defaultVisible} -- that is exactly what makes
         * the 「关闭…」 rows default to ON (true = 该行为已被拦截). {@code labels_on} is the one
         * exception: its consumer ({@code AmapPoiLayer.install}) reads {@code Config.selected()}
         * (strict {@code Boolean.TRUE}), so it defaults to OFF and a {@code visible()}-based
         * hydrate would paint the switch ON while the filter is actually off.
         */
        @JvmField val strict: Boolean = false
    )

    /**
     * One optional "do it for me" action rendered above a group's body (e.g. 「一键省电」).
     *
     * <p>An interface rather than a key list, so the vendor panel owns the behaviour and this
     * file stays free of map-specific keys.
     */
    interface Action {
        fun label(): String

        /** Runs it; returns how many settings were written (0 = nothing was writable). */
        fun run(): Int
    }

    /** A category shown as a chip on the rail. */
    class Group private constructor(
        @JvmField val label: String,
        @JvmField val items: Array<Item>?,
        /** Optional sub-sections; when present the body renders one header per sub-section. */
        @JvmField val sections: Array<Array<Any?>>?,
        /** Slider rows; mutually exclusive with [items] and [sections]. */
        @JvmField val metrics: Array<Metric>?,
        /** Optional one-line explanation rendered above the body. */
        @JvmField val note: String?,
        /** Optional one-tap action rendered above the body (「一键省电」). */
        @JvmField val action: Action?,
        /**
         * Optional label of another group to link to, rendered as a banner at the top of this
         * page (e.g. 首页 → 省电).
         *
         * <p>Why a banner and not just a chip: the user reported twice that 「模块里并没有省电
         * 开关入口」. A page reachable only by scrolling the chip rail reads as absent, so the
         * entry point has to exist on the screen the sheet opens on.
         */
        @JvmField val link: String?,
        /**
         * Render the module's self-info rows (当前版本 / 检查更新) at the bottom of this page.
         *
         * <p>Lives here rather than in {@code MainActivity} because the 免 root 内置版 ships
         * <b>without</b> a MainActivity -- the sheet is its only settings surface.
         */
        @JvmField val about: Boolean
    ) {
        constructor(label: String, items: Array<Item>) :
            this(label, items, null, null, null, null, null, false)

        constructor(label: String, items: Array<Item>, note: String?) :
            this(label, items, null, null, note, null, null, false)

        /** Items plus the module self-info rows: the 其他 page. */
        constructor(label: String, items: Array<Item>, note: String?, about: Boolean) :
            this(label, items, null, null, note, null, null, about)

        /** Items plus a banner linking to another group: the 首页 entry to 省电. */
        constructor(label: String, items: Array<Item>, note: String?, link: String?) :
            this(label, items, null, null, note, null, link, false)

        /** Flat item list plus a one-tap action: the 省电 page of vendors without sub-sections. */
        constructor(label: String, items: Array<Item>, note: String?, action: Action?) :
            this(label, items, null, null, note, action, null, false)

        /** Sections are {title, Item[]} pairs, used by the tool catalogue. */
        constructor(label: String, sections: Array<Array<Any?>>, note: String?) :
            this(label, null, sections, null, note, null, null, false)

        /** Sections plus an optional one-tap action: the 省电 / 后台 page. */
        constructor(label: String, sections: Array<Array<Any?>>, note: String?, action: Action?) :
            this(label, null, sections, null, note, action, null, false)

        /**
         * 开关行 + 滑块行共存：**巡航**页。
         *
         * <p>为什么需要这个重载：巡航的「总开关」和「触发速度 / 持续时间」必须出现在同一页。
         * 分成两个 chip 是上一版的真实问题 —— 用户在「巡航」chip 里只看到两个滑块，
         * 找不到开关，以为功能没开；而开关藏在「首页」里，更没人会去那儿找。
         *
         * <p>渲染顺序固定：先 items，后 metrics（见 {@code FloatingPanel}），
         * 于是「开 → 调阈值」的阅读顺序天然成立。
         */
        constructor(label: String, items: Array<Item>, metrics: Array<Metric>, note: String?) :
            this(label, items, null, metrics, note, null, null, false)

        /**
         * 带 {@code about} 的版本 —— 「其他」页要同时挂实验开关、目标帧率滑块和版本行。
         *
         * <p>此前 about 只能和纯 Item 页搭配，所以「实验」被迫单列一页：它有两个开关和
         * 一个滑块，而版本行只认 about 页。chip 行是横滚的，多一页就多一次「看不到入口」。
         */
        constructor(label: String, items: Array<Item>, metrics: Array<Metric>, note: String?, about: Boolean) :
            this(label, items, null, metrics, note, null, null, about)

        /** Numeric rows: the 外观 page. */
        constructor(label: String, metrics: Array<Metric>, note: String?) :
            this(label, null, null, metrics, note, null, null, false)
    }

    /**
     * One adjustable numeric row: orb size / orb opacity / sheet width / sheet opacity.
     *
     * <h3>Why a stop table instead of min/max/step</h3>
     * The sheet width needs a "自动" stop that is not part of its numeric range (it means
     * "span the screen", which is what shipped before this page existed). Modelling every
     * metric as an explicit list of legal stored values lets one stop be a sentinel without
     * special-casing arithmetic in the slider, and guarantees that whatever the user lands on
     * is exactly a value [FloatingConfig] can clamp and hand back unchanged.
     *
     * <p>[labels] is optional: a {@code null} entry falls back to {@code value + unit},
     * so only the sentinel stop needs a word of its own.
     */
    class Metric(
        @JvmField val key: String,
        @JvmField val title: String,
        @JvmField val note: String?,
        @JvmField val unit: String,
        /** Legal stored values, ascending. */
        @JvmField val values: IntArray,
        /** Optional per-stop display override (e.g. "自动"); null entries use value + unit. */
        @JvmField val labels: Array<String?>?,
        /** Default stored value -- must equal the behaviour that shipped before this page. */
        @JvmField val def: Int
    ) {
        /** Stop index for a stored value; snaps to the nearest stop for anything off-table. */
        fun positionOf(value: Int): Int {
            var best = 0
            var bestDelta = Int.MAX_VALUE
            for (i in values.indices) {
                val delta = Math.abs(values[i] - value)
                if (delta == 0) return i
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = i
                }
            }
            return best
        }

        fun labelAt(position: Int): String {
            val table = labels
            if (table != null && position >= 0 && position < table.size && table[position] != null) {
                return table[position]!!
            }
            return values[position].toString() + unit
        }
    }
}