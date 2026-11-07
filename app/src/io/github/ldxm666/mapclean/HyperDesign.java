package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.res.Configuration;

/**
 * HyperOS 设计 token —— 色板、字号、间距、圆角。
 *
 * <p>数值全部取自 Miuix 0.9.4（见 {@code docs/miuix-design-spec.md}）。这个类是
 * <b>唯一的样式来源</b>：{@link Skin}（模块 App）与悬浮面板都从它取色，不再各自维护
 * 一套。此前面板里散着 54 个硬编码 ARGB，改一次配色要动十几个文件，就是没有这个类
 * 的后果。
 *
 * <p>深色模式的关键特征：<b>{@code surface}（纯黑）比 {@code background}（深灰）更暗</b>
 * —— 卡片是"压暗"而不是"提亮"。这和 Material 相反，是 HyperOS 最容易被做错的一条。
 */
final class HyperDesign {

    /* ------------------------------------------------------------------ 间距阶梯 */

    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;
    static final int S5 = 20;
    static final int S6 = 24;
    static final int S7 = 32;

    /** 页面左右边距。 */
    static final int PAGE_PAD = S4;
    /** 卡片之间的间距。 */
    static final int CARD_GAP = S3;
    /** 卡片内单行的左右 padding。 */
    static final int ROW_PAD = S4;
    /** 分割线左侧缩进 —— 从文字起始处开始，不从卡片边缘。 */
    static final int DIVIDER_INSET = S4;

    /* ------------------------------------------------------------------ 圆角 */

    static final float R_CARD = 16f;
    static final float R_CARD_LARGE = 24f;
    static final float R_DIALOG = 28f;
    static final float R_CONTROL = 12f;
    static final float R_PILL = 999f;

    /* ------------------------------------------------------------------ 字号 */

    static final float T_DISPLAY = 32f;
    static final float T_TITLE = 24f;
    static final float T_SUBTITLE = 20f;
    static final float T_SECTION = 18f;
    /** 主力正文。HyperOS 比 Material 大一号 —— 不要用 14 当正文。 */
    static final float T_BODY1 = 17f;
    static final float T_BODY2 = 16f;
    static final float T_BODY3 = 14f;
    static final float T_CAPTION = 13f;
    static final float T_LABEL = 11f;

    /* ------------------------------------------------------------------ 行高 */

    /** 单行设置项最小高度。 */
    static final int ROW_H = 56;
    /** 带副标题的行。 */
    static final int ROW_H_TALL = 64;
    /** 顶部栏。 */
    static final int TOPBAR_H = 56;
    /** 弹层最大宽度（源码里 600.dp 出现 49 次）。 */
    static final int DIALOG_MAX_W = 600;

    /* ------------------------------------------------------------------ 实例色板 */

    final boolean dark;

    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int error;
    final int errorContainer;
    final int onErrorContainer;
    final int disabledPrimary;

    final int secondary;
    final int secondaryVariant;
    final int onSecondaryVariant;
    final int secondaryContainer;
    final int onSecondaryContainer;
    final int tertiaryContainer;
    final int onTertiaryContainer;

    /** 页面底色。 */
    final int background;
    final int onBackground;
    final int onBackgroundVariant;

    /** 卡片底。深色模式下比 background 更暗。 */
    final int surface;
    final int onSurface;
    final int surfaceVariant;
    final int onSurfaceSecondary;
    final int onSurfaceVariantSummary;
    final int onSurfaceVariantActions;
    final int disabledOnSurface;

    /** 一级容器。 */
    final int surfaceContainer;
    final int onSurfaceContainer;
    final int onSurfaceContainerVariant;
    /** 二级容器。 */
    final int surfaceContainerHigh;
    final int onSurfaceContainerHigh;
    /** 三级容器。 */
    final int surfaceContainerHighest;
    final int onSurfaceContainerHighest;

    /** 描边 —— 浅色下卡片靠它区分层级，不靠阴影。 */
    final int outline;
    final int dividerLine;
    final int windowDimming;

    final int sliderKeyPoint;
    final int sliderKeyPointForeground;
    final int sliderBackground;

    private HyperDesign(boolean dark) {
        this.dark = dark;
        if (dark) {
            primary = 0xFF277AF7;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFF338FE4;
            onPrimaryContainer = 0xFFFFFFFF;
            error = 0xFFF12522;
            errorContainer = 0xFF2E0603;
            onErrorContainer = 0xFFFFDAD6;
            disabledPrimary = 0xFF253E64;

            secondary = 0xFF505050;
            secondaryVariant = 0xFF434343;
            onSecondaryVariant = 0xFFD9D9D9;
            secondaryContainer = 0xFF434343;
            onSecondaryContainer = 0xFF7C7C7C;
            tertiaryContainer = 0xFF2B3B54;
            onTertiaryContainer = 0xFF4788FF;

            background = 0xFF242424;
            onBackground = 0xE6FFFFFF;
            onBackgroundVariant = 0xFF787E96;

            surface = 0xFF000000;          // 比背景更黑
            onSurface = 0xFFF2F2F2;
            surfaceVariant = 0xFF242424;
            onSurfaceSecondary = 0xCCFFFFFF;
            onSurfaceVariantSummary = 0x80FFFFFF;
            onSurfaceVariantActions = 0x66FFFFFF;
            disabledOnSurface = 0xFF666666;

            surfaceContainer = 0xFF242424;
            onSurfaceContainer = 0xE6FFFFFF;
            onSurfaceContainerVariant = 0xFF737373;
            surfaceContainerHigh = 0xFF242424;
            onSurfaceContainerHigh = 0xFF666666;
            surfaceContainerHighest = 0xFF2D2D2D;
            onSurfaceContainerHighest = 0xFFE9E9E9;

            outline = 0xFF404040;
            dividerLine = 0xFF393939;
            windowDimming = 0x4D000000;

            sliderKeyPoint = 0x4D7A8AA6;
            sliderKeyPointForeground = 0xFF5DAAFF;
            sliderBackground = 0x26FFFFFF;
        } else {
            primary = 0xFF3482FF;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFF5D9BFF;
            onPrimaryContainer = 0xFFFFFFFF;
            error = 0xFFE94634;
            errorContainer = 0xFFFDF6F4;
            onErrorContainer = 0xFF410002;
            disabledPrimary = 0xFFC2D9FF;

            secondary = 0xFFE6E6E6;
            secondaryVariant = 0xFFF0F0F0;
            onSecondaryVariant = 0xFF303030;
            secondaryContainer = 0xFFF0F0F0;
            onSecondaryContainer = 0xFFA9A9A9;
            tertiaryContainer = 0xFFEAF2FF;
            onTertiaryContainer = 0xFF3482FF;

            background = 0xFFFFFFFF;
            onBackground = 0xFF000000;
            onBackgroundVariant = 0xFF8C93B0;

            surface = 0xFFF7F7F7;          // 灰卡片
            onSurface = 0xFF000000;
            surfaceVariant = 0xFFFFFFFF;
            onSurfaceSecondary = 0xCC000000;
            onSurfaceVariantSummary = 0x99000000;
            onSurfaceVariantActions = 0x66000000;
            disabledOnSurface = 0xFFB2B2B2;

            surfaceContainer = 0xFFFFFFFF; // 白容器
            onSurfaceContainer = 0xFF000000;
            onSurfaceContainerVariant = 0xFF959595;
            surfaceContainerHigh = 0xFFE8E8E8;
            onSurfaceContainerHigh = 0xFFA2A2A2;
            surfaceContainerHighest = 0xFFE8E8E8;
            onSurfaceContainerHighest = 0xFF000000;

            outline = 0xFFD9D9D9;
            dividerLine = 0xFFE0E0E0;
            windowDimming = 0x4D000000;

            sliderKeyPoint = 0x4DA3B3CD;
            sliderKeyPointForeground = 0xFF6EB5FF;
            sliderBackground = 0x0F000000;
        }
    }

    private static volatile HyperDesign lightCache;
    private static volatile HyperDesign darkCache;

    static HyperDesign of(boolean dark) {
        if (dark) {
            HyperDesign d = darkCache;
            if (d == null) darkCache = d = new HyperDesign(true);
            return d;
        }
        HyperDesign d = lightCache;
        if (d == null) lightCache = d = new HyperDesign(false);
        return d;
    }

    static HyperDesign of(Context c) {
        boolean dark = false;
        if (c != null) {
            try {
                int mode = c.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                dark = mode == Configuration.UI_MODE_NIGHT_YES;
            } catch (Throwable ignored) {
            }
        }
        return of(dark);
    }

    /* ------------------------------------------------------------------ 派生色 */

    /**
     * 按 alpha 混合两层颜色。{@code ratio} 是 {@code top} 的占比。
     *
     * <p>用于在 token 上做微调（按下态、选中态），避免再定义一堆派生常量。
     */
    static int mix(int bottom, int top, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int br = (bottom >>> 16) & 0xFF, bg = (bottom >>> 8) & 0xFF, bb = bottom & 0xFF;
        int ba = (bottom >>> 24) & 0xFF;
        int tr = (top >>> 16) & 0xFF, tg = (top >>> 8) & 0xFF, tb = top & 0xFF;
        int ta = (top >>> 24) & 0xFF;
        int a = (int) (ba + (ta - ba) * r);
        int rr = (int) (br + (tr - br) * r);
        int gg = (int) (bg + (tg - bg) * r);
        int bbb = (int) (bb + (tb - bb) * r);
        return (a << 24) | (rr << 16) | (gg << 8) | bbb;
    }

    /** 给颜色套一个透明度，保留 RGB。 */
    static int alpha(int color, float a) {
        int v = (int) (Math.max(0f, Math.min(1f, a)) * 255f + 0.5f);
        return (v << 24) | (color & 0x00FFFFFF);
    }

    /** 按压缩放量 —— 开关 0.94，按钮 0.92，行 0.98。 */
    static final float PRESS_SWITCH = 0.94f;
    static final float PRESS_BUTTON = 0.92f;
    static final float PRESS_ROW = 0.98f;
}
