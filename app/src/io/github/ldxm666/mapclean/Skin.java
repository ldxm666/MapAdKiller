package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens + component factory for the module settings screen.
 *
 * <p>Visual language: soft "liquid glass" — a tinted aurora backdrop, frosted translucent
 * plates with a bright specular rim, one accent colour, and a strict spacing/type scale.
 * Implemented with plain framework API because this module compiles with {@code javac}
 * against {@code android.jar} — no AndroidX, no Compose, no XML.
 */
final class Skin {

    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;
    static final int S5 = 20;
    static final int S6 = 24;
    static final int S7 = 32;

    static final int R_CARD = (int) HyperDesign.R_CARD;        // 16
    static final int R_INNER = (int) HyperDesign.R_CONTROL;     // 12
    static final int R_CONTROL = (int) HyperDesign.R_CONTROL;   // 12
    static final int R_PILL = 999;

    static final float T_DISPLAY = HyperDesign.T_DISPLAY;   // 32
    static final float T_TITLE = HyperDesign.T_SUBTITLE;    // 20
    static final float T_ROW = HyperDesign.T_BODY1;         // 17
    static final float T_BODY = HyperDesign.T_BODY3;        // 14
    static final float T_CAPTION = HyperDesign.T_CAPTION;   // 13
    static final float T_LABEL = HyperDesign.T_LABEL;       // 11

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    final boolean dark;
    final int bg;
    final int surface;
    final int surface2;
    final int rimHi;
    final int rimLo;
    final int stroke;
    final int divider;
    final int text;
    final int text2;
    final int text3;
    final int accent;
    final int accentSoft;
    final int onAccent;
    final int success;
    final int warn;
    final int danger;
    final int chipBg;
    final int navBg;

    private final Context context;
    private Bitmap frostTile;

    Skin(Context context, boolean dark) {
        this.context = context;
        this.dark = dark;
        // 色板唯一来源：HyperDesign。此前这里是一份手抄的 Liquid Glass 色板，
        // 悬浮面板里还有第二份（cBg/cGlass/…），改配色要动两个地方。
        HyperDesign h = HyperDesign.of(dark);
        bg = h.background;
        surface = h.surfaceContainer;          // 卡片底：浅色白 / 深色 #242424
        surface2 = h.surface;                  // 次级面：浅色 #F7F7F7 / 深色纯黑
        stroke = h.outline;
        divider = h.dividerLine;
        text = h.onSurface;
        text2 = h.onSurfaceVariantSummary;
        text3 = h.onSurfaceContainerVariant;
        accent = h.primary;
        accentSoft = h.tertiaryContainer;
        onAccent = h.onPrimary;
        success = dark ? 0xFF34D399 : 0xFF12A150;
        warn = dark ? 0xFFFBBF24 : 0xFFC77700;
        danger = h.error;
        chipBg = h.surfaceContainerHigh;
        navBg = h.surfaceContainer;
        // 描边渐变的两端：HyperOS 不用高光边，两端都取 outline，
        // 只让左上略亮一点点，保留一点方向感而不抢眼。
        rimHi = HyperDesign.mix(h.outline, 0xFFFFFFFF, dark ? 0.10f : 0.55f);
        rimLo = h.outline;
    }

    /** Base tint painted over the blurred backdrop inside every glass pane. */
    int glassTint() {
        return surface;
    }

    /** Soft veil behind a page title so dark type stays legible over the photo. */
    Drawable headerScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{HyperDesign.alpha(bg, 0.90f), HyperDesign.alpha(bg, 0f)});
    }

    /**
     * 卡片底 —— HyperOS 是实色 + 1 px 描边，不是半透明玻璃。
     *
     * <p>改自 Liquid Glass：那一版是"高光外圈 + 白色内圈 + 半透明body"，靠层叠制造
     * 厚度感。HyperOS 反过来 —— 实色面，用 {@code outline} 一条描边界定边界，浅色下
     * 几乎不用阴影。保留方法名和签名，419 处调用方一行不用改。
     */
    Drawable glassFor(View host, float radiusDp) {
        return border(surface, stroke, radiusDp);
    }

    /** 带色调的卡片 —— 用于状态卡（成功/警告/危险）。色调只染 12% 和描边 30%。 */
    Drawable glassFor(View host, float radiusDp, int tint) {
        int fill = HyperDesign.mix(surface, tint, dark ? 0.16f : 0.10f);
        int line = HyperDesign.mix(stroke, tint, dark ? 0.34f : 0.30f);
        return border(fill, line, radiusDp);
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Pure white canvas — the requested backdrop. */
    Drawable aurora() {
        return solid(bg, 0);
    }

    TextView display(String value) {
        return text(value, T_DISPLAY, BOLD, text);
    }

    TextView title(String value) {
        return text(value, T_TITLE, BOLD, text);
    }

    TextView rowTitle(String value) {
        return text(value, T_ROW, MEDIUM, text);
    }

    TextView body(String value) {
        return text(value, T_BODY, REGULAR, text2);
    }

    TextView caption(String value) {
        return text(value, T_CAPTION, REGULAR, text2);
    }

    TextView footnote(String value) {
        return text(value, T_LABEL, REGULAR, text3);
    }

    TextView text(String value, float sp, Typeface face, int color) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTypeface(face);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(S4), dp(S3), dp(S4), dp(S3));
        card.setBackground(glassFor(card, R_CARD));
        UiKit.clipRounded(card, dp(R_CARD));
        shadow(card, 1f);
        return card;
    }

    LinearLayout listCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(S1), dp(S2), dp(S1), dp(S2));
        card.setBackground(glassFor(card, R_CARD));
        UiKit.clipRounded(card, dp(R_CARD));
        shadow(card, 1f);
        return card;
    }

    Drawable glass(float radiusDp) {
        return border(surface, stroke, radiusDp);
    }

    /** White card tinted with the accent — the one block that leads the eye. */
    Drawable tintedGlass(int tintColor, float radiusDp) {
        int fill = UiKit.blend(surface, tintColor, dark ? 0.16f : 0.06f);
        int line = UiKit.blend(stroke, tintColor, dark ? 0.22f : 0.10f);
        return border(fill, line, radiusDp);
    }

    GradientDrawable border(int fill, int line, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(1)), line);
        return g;
    }

    GradientDrawable solid(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    Drawable pressable(int fill, float radiusDp) {
        return UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000, solid(fill, radiusDp), dp(radiusDp));
    }

    Drawable pressableBorder(int fill, int line, float radiusDp) {
        return UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000, border(fill, line, radiusDp), dp(radiusDp));
    }

    /** 行间分割线 —— 左缩进 16 dp，与文字左边缘对齐（HyperOS 不从卡片边缘起笔）。 */
    View hairline() {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(HyperDesign.DIVIDER_INSET);
        v.setLayoutParams(lp);
        v.setBackgroundColor(divider);
        return v;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(HyperDesign.ROW_H));
        row.setPadding(dp(HyperDesign.ROW_PAD), dp(S2), dp(HyperDesign.ROW_PAD), dp(S2));
        row.setBackground(new HyperRipple(dark ? 0xFFFFFFFF : 0xFF000000, dp(R_INNER)));
        row.setClickable(true);
        return row;
    }

    LinearLayout rowText(String title, String caption) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(rowTitle(title));
        if (caption != null && caption.length() > 0) {
            TextView c = caption(caption);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            col.addView(c, lp);
        }
        return col;
    }

    TextView chip(String value, int fill, int color) {
        TextView t = text(value, T_LABEL + 0.5f, MEDIUM, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(solid(fill, R_PILL));
        return t;
    }

    TextView section(String value) {
        TextView t = text(value, T_LABEL + 1f, MEDIUM, text3);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    void addSection(LinearLayout parent, String label) {
        TextView t = section(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(S6);
        lp.bottomMargin = dp(S2);
        parent.addView(t, lp);
    }

    TextView primaryButton(String label) {
        TextView b = text(label, T_ROW, MEDIUM, onAccent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S4), dp(13), dp(S4), dp(13));
        b.setBackground(UiKit.ripple(0x33FFFFFF, accentPlate(), dp(R_INNER)));
        b.setClickable(true);
        UiKit.pressable(b, 0.98f);
        return b;
    }

    private Drawable accentPlate() {
        int top = UiKit.blend(accent, 0xFFFFFFFF, dark ? 0.06f : 0.18f);
        int bottom = UiKit.blend(accent, 0xFF000000, dark ? 0.10f : 0.06f);
        return UiKit.gradient(top, bottom, dp(R_INNER));
    }

    TextView secondaryButton(String label) {
        TextView b = text(label, T_ROW, MEDIUM, text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S4), dp(13), dp(S4), dp(13));
        b.setBackground(UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000,
                glassFor(b, R_INNER), dp(R_INNER)));
        b.setClickable(true);
        UiKit.pressable(b, 0.98f);
        return b;
    }

    TextView textButton(String label) {
        TextView b = text(label, T_CAPTION + 0.5f, MEDIUM, accent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S3), dp(6), dp(S3), dp(6));
        b.setBackground(UiKit.rippleBare(dark ? 0x22FFFFFF : 0x12000000, dp(R_PILL)));
        b.setClickable(true);
        return b;
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    void addWithTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    /**
     * Soft, colour-tinted elevation.
     *
     * <p>Framework shadows are neutral grey and read as dirt over a coloured backdrop.
     * API 28+ lets us tint spot and ambient shadows, so the plate looks lit rather than
     * dirty; older versions simply get a lower elevation.
     */
    /**
     * 阴影 —— HyperOS 克制使用。
     *
     * <p>浅色下卡片靠 {@code outline} 描边区分层级，阴影只保留 1 dp 的接地感；
     * 深色下卡片本身比背景更黑，再叠阴影会糊成一片，所以进一步压到 0.5 倍。
     */
    void shadow(View v, float elevationDp) {
        float e = dark ? elevationDp * 0.5f : elevationDp;
        v.setElevation(dp(e));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(dark ? 0x66000000 : 0x14000000);
            v.setOutlineAmbientShadowColor(dark ? 0x33000000 : 0x0D000000);
        }
    }

    /** 1 px rim stroked with a corner-to-corner gradient: lit top-left, quiet bottom-right. */
    private static final class Rim extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float stroke;
        private final int hi;
        private final int lo;

        Rim(float radius, float stroke, int hi, int lo) {
            this.radius = radius;
            this.stroke = stroke;
            this.hi = hi;
            this.lo = lo;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    hi, lo, Shader.TileMode.CLAMP));
            float inset = stroke / 2f;
            canvas.drawRoundRect(new android.graphics.RectF(
                    b.left + inset, b.top + inset, b.right - inset, b.bottom - inset),
                    radius - inset, radius - inset, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Aurora extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] base;
        private final int[] colors;
        private final float[][] blobs;

        Aurora(int[] base, int[] colors, float[][] blobs) {
            this.base = base;
            this.colors = colors;
            this.blobs = blobs;
            basePaint.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            basePaint.setShader(new LinearGradient(getBounds().left, getBounds().top,
                    getBounds().right, getBounds().bottom, base, new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(getBounds(), basePaint);
            float max = Math.max(w, h);
            for (int i = 0; i < colors.length && i < blobs.length; i++) {
                float cx = getBounds().left + w * blobs[i][0];
                float cy = getBounds().top + h * blobs[i][1];
                float radius = max * blobs[i][2];
                paint.setShader(new RadialGradient(cx, cy, radius,
                        new int[]{colors[i], colors[i] & 0x00FFFFFF}, null,
                        Shader.TileMode.CLAMP));
                canvas.drawRect(getBounds(), paint);
            }
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { basePaint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            basePaint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
