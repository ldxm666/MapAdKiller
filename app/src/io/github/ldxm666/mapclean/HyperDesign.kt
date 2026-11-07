package io.github.ldxm666.mapclean

import android.content.Context
import android.content.res.Configuration

/**
 * HyperOS 设计 token —— 色板、字号、间距、圆角。
 *
 * <p>数值全部取自 Miuix 0.9.4（见 {@code docs/miuix-design-spec.md}）。这个类是
 * <b>唯一的样式来源</b>：[Skin]（模块 App）与悬浮面板都从它取色，不再各自维护
 * 一套。此前面板里散着 54 个硬编码 ARGB，改一次配色要动十几个文件，就是没有这个类
 * 的后果。
 *
 * <p>深色模式的关键特征：<b>{@code surface}（纯黑）比 {@code background}（深灰）更暗</b>
 * —— 卡片是"压暗"而不是"提亮"。这和 Material 相反，是 HyperOS 最容易被做错的一条。
 */
class HyperDesign private constructor(dark: Boolean) {

    /* ------------------------------------------------------------------ 实例色板 */

    @JvmField
    val dark: Boolean = dark

    @JvmField
    val primary: Int = if (dark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
    @JvmField
    val onPrimary: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
    @JvmField
    val primaryContainer: Int = if (dark) 0xFF338FE4.toInt() else 0xFF5D9BFF.toInt()
    @JvmField
    val onPrimaryContainer: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
    @JvmField
    val error: Int = if (dark) 0xFFF12522.toInt() else 0xFFE94634.toInt()
    @JvmField
    val errorContainer: Int = if (dark) 0xFF2E0603.toInt() else 0xFFFDF6F4.toInt()
    @JvmField
    val onErrorContainer: Int = if (dark) 0xFFFFDAD6.toInt() else 0xFF410002.toInt()
    @JvmField
    val disabledPrimary: Int = if (dark) 0xFF253E64.toInt() else 0xFFC2D9FF.toInt()

    @JvmField
    val secondary: Int = if (dark) 0xFF505050.toInt() else 0xFFE6E6E6.toInt()
    @JvmField
    val secondaryVariant: Int = if (dark) 0xFF434343.toInt() else 0xFFF0F0F0.toInt()
    @JvmField
    val onSecondaryVariant: Int = if (dark) 0xFFD9D9D9.toInt() else 0xFF303030.toInt()
    @JvmField
    val secondaryContainer: Int = if (dark) 0xFF434343.toInt() else 0xFFF0F0F0.toInt()
    @JvmField
    val onSecondaryContainer: Int = if (dark) 0xFF7C7C7C.toInt() else 0xFFA9A9A9.toInt()
    @JvmField
    val tertiaryContainer: Int = if (dark) 0xFF2B3B54.toInt() else 0xFFEAF2FF.toInt()
    @JvmField
    val onTertiaryContainer: Int = if (dark) 0xFF4788FF.toInt() else 0xFF3482FF.toInt()

    /** 页面底色。 */
    @JvmField
    val background: Int = if (dark) 0xFF242424.toInt() else 0xFFFFFFFF.toInt()
    @JvmField
    val onBackground: Int = if (dark) 0xE6FFFFFF.toInt() else 0xFF000000.toInt()
    @JvmField
    val onBackgroundVariant: Int = if (dark) 0xFF787E96.toInt() else 0xFF8C93B0.toInt()

    /** 卡片底。深色模式下比 background 更暗。 */
    @JvmField
    val surface: Int = if (dark) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
    @JvmField
    val onSurface: Int = if (dark) 0xFFF2F2F2.toInt() else 0xFF000000.toInt()
    @JvmField
    val surfaceVariant: Int = if (dark) 0xFF242424.toInt() else 0xFFFFFFFF.toInt()
    @JvmField
    val onSurfaceSecondary: Int = if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
    @JvmField
    val onSurfaceVariantSummary: Int = if (dark) 0x80FFFFFF.toInt() else 0x99000000.toInt()
    @JvmField
    val onSurfaceVariantActions: Int = if (dark) 0x66FFFFFF else 0x66000000
    @JvmField
    val disabledOnSurface: Int = if (dark) 0xFF666666.toInt() else 0xFFB2B2B2.toInt()

    /** 一级容器。 */
    @JvmField
    val surfaceContainer: Int = if (dark) 0xFF242424.toInt() else 0xFFFFFFFF.toInt()
    @JvmField
    val onSurfaceContainer: Int = if (dark) 0xE6FFFFFF.toInt() else 0xFF000000.toInt()
    @JvmField
    val onSurfaceContainerVariant: Int = if (dark) 0xFF737373.toInt() else 0xFF959595.toInt()
    /** 二级容器。 */
    @JvmField
    val surfaceContainerHigh: Int = if (dark) 0xFF242424.toInt() else 0xFFE8E8E8.toInt()
    @JvmField
    val onSurfaceContainerHigh: Int = if (dark) 0xFF666666.toInt() else 0xFFA2A2A2.toInt()
    /** 三级容器。 */
    @JvmField
    val surfaceContainerHighest: Int = if (dark) 0xFF2D2D2D.toInt() else 0xFFE8E8E8.toInt()
    @JvmField
    val onSurfaceContainerHighest: Int = if (dark) 0xFFE9E9E9.toInt() else 0xFF000000.toInt()

    /** 描边 —— 浅色下卡片靠它区分层级，不靠阴影。 */
    @JvmField
    val outline: Int = if (dark) 0xFF404040.toInt() else 0xFFD9D9D9.toInt()
    @JvmField
    val dividerLine: Int = if (dark) 0xFF393939.toInt() else 0xFFE0E0E0.toInt()
    @JvmField
    val windowDimming: Int = if (dark) 0x4D000000 else 0x4D000000

    @JvmField
    val sliderKeyPoint: Int = if (dark) 0x4D7A8AA6 else 0x4DA3B3CD
    @JvmField
    val sliderKeyPointForeground: Int = if (dark) 0xFF5DAAFF.toInt() else 0xFF6EB5FF.toInt()
    @JvmField
    val sliderBackground: Int = if (dark) 0x26FFFFFF else 0x0F000000

    companion object {
        /* -------------------------------------------------------------- 间距阶梯 */

        const val S1 = 4
        const val S2 = 8
        const val S3 = 12
        const val S4 = 16
        const val S5 = 20
        const val S6 = 24
        const val S7 = 32

        /**
         * 页面左右边距 —— **12 dp**。
         *
         * <p>miuix 示例全站统一 `Modifier.padding(horizontal = 12.dp)`（38 处）。这个值不是
         * 随手取的：浅色下卡片底和页面底**同为纯白**，卡片的分层完全靠页边距 + 圆角切出来，
         * 边距给大了（16）卡片像浮在纸上的贴片，给小了（8）就分不出「这是一组」。
         */
        const val PAGE_PAD = S3
        /** 卡片之间的间距。 */
        const val CARD_GAP = S3
        /** 卡片内单行的左右 padding。 */
        const val ROW_PAD = S4
        /** 分割线左侧缩进 —— 从文字起始处开始，不从卡片边缘。 */
        const val DIVIDER_INSET = S4

        /* -------------------------------------------------------------- 圆角 */

        const val R_CARD = 16f
        const val R_CARD_LARGE = 24f
        const val R_DIALOG = 28f
        const val R_CONTROL = 12f
        const val R_PILL = 999f

        /* -------------------------------------------------------------- 字号 */

        const val T_DISPLAY = 32f
        const val T_TITLE = 24f
        const val T_SUBTITLE = 20f
        const val T_SECTION = 18f
        /** 主力正文。HyperOS 比 Material 大一号 —— 不要用 14 当正文。 */
        const val T_BODY1 = 17f
        const val T_BODY2 = 16f
        const val T_BODY3 = 14f
        const val T_CAPTION = 13f
        const val T_LABEL = 11f

        /* -------------------------------------------------------------- 行高 */

        /** 单行设置项最小高度。 */
        const val ROW_H = 56
        /** 带副标题的行。 */
        const val ROW_H_TALL = 64
        /** 顶部栏。 */
        const val TOPBAR_H = 56
        /** 弹层最大宽度（源码里 600.dp 出现 49 次）。 */
        const val DIALOG_MAX_W = 600

        /** 按压缩放量 —— 开关 0.94，按钮 0.92，行 0.98。 */
        const val PRESS_SWITCH = 0.94f
        const val PRESS_BUTTON = 0.92f
        const val PRESS_ROW = 0.98f

        @Volatile
        private var lightCache: HyperDesign? = null

        @Volatile
        private var darkCache: HyperDesign? = null

        @JvmStatic
        fun of(dark: Boolean): HyperDesign {
            if (dark) {
                val d = darkCache ?: HyperDesign(true).also { darkCache = it }
                return d
            }
            val d = lightCache ?: HyperDesign(false).also { lightCache = it }
            return d
        }

        @JvmStatic
        fun of(c: Context?): HyperDesign {
            var dark = false
            if (c != null) {
                try {
                    val mode = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    dark = mode == Configuration.UI_MODE_NIGHT_YES
                } catch (ignored: Throwable) {
                }
            }
            return of(dark)
        }

        /* -------------------------------------------------------------- 派生色 */

        /**
         * 按 alpha 混合两层颜色。{@code ratio} 是 {@code top} 的占比。
         *
         * <p>用于在 token 上做微调（按下态、选中态），避免再定义一堆派生常量。
         */
        @JvmStatic
        fun mix(bottom: Int, top: Int, ratio: Float): Int {
            val r = Math.max(0f, Math.min(1f, ratio))
            val br = (bottom ushr 16) and 0xFF
            val bg = (bottom ushr 8) and 0xFF
            val bb = bottom and 0xFF
            val ba = (bottom ushr 24) and 0xFF
            val tr = (top ushr 16) and 0xFF
            val tg = (top ushr 8) and 0xFF
            val tb = top and 0xFF
            val ta = (top ushr 24) and 0xFF
            val a = (ba + (ta - ba) * r).toInt()
            val rr = (br + (tr - br) * r).toInt()
            val gg = (bg + (tg - bg) * r).toInt()
            val bbb = (bb + (tb - bb) * r).toInt()
            return (a shl 24) or (rr shl 16) or (gg shl 8) or bbb
        }

        /** 给颜色套一个透明度，保留 RGB。 */
        @JvmStatic
        fun alpha(color: Int, a: Float): Int {
            val v = (Math.max(0f, Math.min(1f, a)) * 255f + 0.5f).toInt()
            return (v shl 24) or (color and 0x00FFFFFF)
        }
    }
}