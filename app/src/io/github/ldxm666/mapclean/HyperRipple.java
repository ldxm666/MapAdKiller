package io.github.ldxm666.mapclean;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * HyperOS 按压反馈 —— 整块矩形淡色叠加，不是圆形涟漪。
 *
 * <p>miuix 的 {@code MiuixIndication} 用 {@code drawRect} 铺满整个组件，按下时把前景色
 * 以 alpha 0.10 叠上去（hover 0.06 / focus 0.08），配合 0.94 缩放。圆形涟漪一出现就
 * 立刻"不是 HyperOS"—— 这是整套视觉里最容易露馅的一处，也是改造成本最低的一处。
 *
 * <p>alpha 用弹簧驱动而不是补间：快速连点时补间会从当前值重新起步，看起来一卡一卡；
 * 弹簧把当前速度带进去，连续按下是平滑的。
 *
 * <p>用法：{@code view.setBackground(new HyperRipple(color, radiusPx))}。View 的
 * pressed/enabled 状态会自动通过 {@link #onStateChange} 传进来。
 */
final class HyperRipple extends Drawable {

    /** 按下叠加量（miuix MiuixIndication）。 */
    static final float ALPHA_PRESS = 0.10f;
    /** 悬停叠加量 —— 触屏设备基本用不到，留给鼠标/手写笔。 */
    static final float ALPHA_HOVER = 0.06f;
    /** 焦点叠加量 —— 键盘导航。 */
    static final float ALPHA_FOCUS = 0.08f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private final float radius;
    private final boolean circular;
    private final Rect bounds = new Rect();

    private float level;
    private HyperMotion.Driver driver;

    /** @param color 叠加色 —— 浅色主题传黑，深色主题传白 */
    HyperRipple(int color, float radiusPx) {
        this.color = color;
        this.radius = radiusPx;
        this.circular = false;
        paint.setStyle(Paint.Style.FILL);
    }

    private HyperRipple(int color) {
        this.color = color;
        this.radius = 0f;
        this.circular = true;
        paint.setStyle(Paint.Style.FILL);
    }

    /** 圆形叠加 —— 用于悬浮球这类本身就是圆的控件。 */
    static HyperRipple circular(int color) {
        return new HyperRipple(color);
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        float target = 0f;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) { target = ALPHA_PRESS; break; }
            if (s == android.R.attr.state_focused) target = ALPHA_FOCUS;
            else if (s == android.R.attr.state_hovered && target == 0f) target = ALPHA_HOVER;
        }
        animateTo(target);
        return true;
    }

    private void animateTo(float target) {
        if (driver == null) {
            driver = new HyperMotion.Driver(
                    HyperMotion.Spring.ofStiffness(0.8f, 600f, level),
                    new HyperMotion.Driver.Sink() {
                        @Override public void onValue(float value) {
                            level = value;
                            invalidateSelf();
                        }
                    });
        }
        driver.to(target);
    }

    /** 立刻设为按下态，不播动画 —— 用于列表复用时重置。 */
    void snap(boolean pressed) {
        if (driver != null) driver.cancel();
        level = pressed ? ALPHA_PRESS : 0f;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        if (level <= 0.001f) return;
        int a = (int) (Math.min(1f, level) * 255f + 0.5f);
        paint.setColor(color);
        paint.setAlpha(a);
        bounds.set(getBounds());
        if (bounds.isEmpty()) return;
        if (circular) {
            canvas.drawCircle(bounds.centerX(), bounds.centerY(),
                    Math.min(bounds.width(), bounds.height()) / 2f, paint);
        } else if (radius > 0f) {
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    radius, radius, paint);
        } else {
            canvas.drawRect(bounds, paint);
        }
    }

    @Override public void setAlpha(int alpha) {
        // 叠加量由 level 控制，外部 alpha 不参与 —— 否则会和状态机打架
    }

    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
