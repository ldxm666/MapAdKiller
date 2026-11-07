package io.github.ldxm666.mapclean;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Capsule switch drawn from scratch.
 *
 * Replaces the framework {@code Switch}: the stock thumb/track scaling looks foreign next
 * to the glass surfaces and cannot animate its colours. This one animates a single 0..1
 * progress value, driving track tint, thumb travel and a subtle thumb squash so the throw
 * feels physical.
 *
 * <h3>Contract (v2.2.0 rewrite)</h3>
 * <ul>
 *   <li>The view owns its own gesture: a tap anywhere on the capsule toggles it and fires
 *       {@link OnCheckedChange}. Callers must <b>not</b> also set an {@code OnClickListener}
 *       that flips the state -- that double-toggles.</li>
 *   <li>{@link #setChecked} is the <i>programmatic</i> path and never notifies, so hydrating
 *       from storage cannot write back to storage.</li>
 *   <li>The drawn state always tracks {@link #progress}. An earlier build forced the track
 *       to OFF whenever the view was disabled, so a switch that was genuinely ON still
 *       painted grey -- the whole pill looked dead and never changed colour on toggle.</li>
 *   <li>The touch target is a full 48dp tall even though the capsule is only 30dp, and the
 *       reported size is {@code width x 48dp} so the parent row gets a comfortable hit box.</li>
 * </ul>
 */
public final class GlassSwitch extends View {

    /** Notified only for user-initiated toggles (tap), never for {@link #setChecked}. */
    public interface OnCheckedChange {
        void onCheckedChanged(GlassSwitch view, boolean checked);
    }

    // miuix Switch.kt：轨道 49x28，滑块 20，上下各留 4，行程 21 dp。
    private static final float TRACK_W_DP = 49f;
    private static final float TRACK_H_DP = 28f;
    private static final float THUMB_DP = 20f;
    private static final float PAD_DP = 4f;
    /** 按下 / 拖动时滑块放大倍数（miuix Switch.kt:105）。 */
    private static final float THUMB_PRESS_SCALE = 1.127f;
    /** Vertical footprint: keeps the capsule centred inside a finger-sized hit box. */
    private static final float TOUCH_H_DP = 48f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float trackW, trackH, thumbSize, pad, radius, viewH;

    private float progress;          // 0 = off, 1 = on (may overshoot mid-spring)
    private boolean checked;
    private boolean enabled = true;
    private boolean pressed;

    private ValueAnimator anim;
    private HyperMotion.Driver driver;
    private OnCheckedChange listener;
    private String label;

    private int trackOff = 0xFFE6E6E6;   // secondary
    private int trackOn = 0xFF3482FF;    // primary
    private int thumbOff = 0xFFFFFFFF;
    private int thumbOn = 0xFFFFFFFF;

    public GlassSwitch(Context c) { this(c, null); }

    public GlassSwitch(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        float d = c.getResources().getDisplayMetrics().density;
        trackW = TRACK_W_DP * d;
        trackH = TRACK_H_DP * d;
        thumbSize = THUMB_DP * d;
        pad = PAD_DP * d;
        radius = trackH / 2f;
        viewH = TOUCH_H_DP * d;
        progress = 0f;
        refreshDescription();
    }

    public void setTrackColors(int off, int on) { trackOff = off; trackOn = on; invalidate(); }

    public void setThumbColors(int off, int on) { thumbOff = off; thumbOn = on; invalidate(); }

    /** Row title, used for the accessibility description ("…，已开启"). */
    public void setLabel(String value) {
        label = value;
        refreshDescription();
    }

    public void setOnCheckedChange(OnCheckedChange l) { listener = l; }

    public boolean isChecked() { return checked; }

    public void setChecked(boolean value) { setChecked(value, true); }

    /** {@code animate=false} snaps without motion -- used while hydrating from storage. */
    public void setChecked(boolean value, boolean animate) {
        boolean was = checked;
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate) {
            cancelAnim();
            progress = target;
            if (was != value) refreshDescription();
            invalidate();
            return;
        }
        if (was == value && progress == target) return;
        if (was != value) refreshDescription();
        animateTo(target);
    }

    /**
     * 滑块位移走物理弹簧 {@code spring(0.7, 987)}（miuix Switch.kt:87）。
     *
     * <p>用弹簧而不是补间，是因为连续点击时补间会从当前值重新起步、视觉上"顿一下"；
     * 弹簧把当前速度带进下一段，中途打断是连续的。
     */
    private void animateTo(float target) {
        cancelAnim();
        if (driver == null) {
            driver = new HyperMotion.Driver(
                    HyperMotion.Spring.ofStiffness(
                            HyperMotion.D_SWITCH_MOVE, HyperMotion.K_SWITCH_MOVE, progress),
                    new HyperMotion.Driver.Sink() {
                        @Override public void onValue(float value) {
                            progress = value;
                            invalidate();
                        }
                    });
        }
        driver.to(target);
    }

    private void cancelAnim() {
        if (driver != null) {
            driver.cancel();
        }
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    /**
     * Cosmetic only. The switch stays touchable either way: the surrounding row is the
     * alternate hit target, and a disabled view that still claims {@code clickable}
     * silently swallows every tap (that is the "grey pill that does nothing" report).
     */
    @Override public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        invalidate();
    }

    @Override public boolean isEnabled() { return enabled; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) Math.ceil(trackW), (int) Math.ceil(viewH));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;     // let the parent row take the tap
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                HyperMotion.press(this, true, HyperDesign.PRESS_SWITCH);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight();
                if (pressed != inside) {
                    pressed = inside;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                releasePress();
                if (pressed) {
                    pressed = false;
                    invalidate();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                releasePress();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void releasePress() {
        HyperMotion.press(this, false, HyperDesign.PRESS_SWITCH);
    }

    /** A user tap: flip, animate, notify. Programmatic {@link #setChecked} stays silent. */
    @Override public boolean performClick() {
        super.performClick();
        if (!enabled) return true;
        boolean next = !checked;
        setChecked(next, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onCheckedChanged(this, next);
        return true;
    }

    private void refreshDescription() {
        setContentDescription((label == null ? "开关" : label) + (checked ? "，已开启" : "，已关闭"));
    }

    /**
     * HyperOS 开关绘制 —— 平实轨道 + 纯白滑块，没有任何高光或玻璃质感。
     *
     * <p>改自 Liquid Glass：那一版有"内圈 1 px 高光 + 滑块顶部月牙高光 + 按下时轨道外
     * 发光"，读起来是玻璃胶囊。HyperOS 是纯色填充，厚度感只靠滑块下方一层极淡的投影。
     *
     * <p>位移用 clamp 后的 progress，弹簧过冲时滑块不会冲出轨道；缩放用原始值，
     * 让放大动作带一点过冲的弹性。
     */
    @Override protected void onDraw(Canvas canvas) {
        float p = Math.max(0f, Math.min(1f, progress));
        float raw = progress;
        int track = UiKit.blend(trackOff, trackOn, p);
        int thumb = UiKit.blend(thumbOff, thumbOn, p);
        int dim = enabled ? 255 : 120;

        float left = (getWidth() - trackW) / 2f;
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(dim);

        // 轨道：纯色全圆矩形，无描边无内高光。
        rect.set(left, top, left + trackW, top + trackH);
        paint.setColor(track);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 滑块：按下时放大到 1.127，用弹簧过冲值驱动，松手弹回。
        float scale = 1f;
        if (pressed) {
            scale = THUMB_PRESS_SCALE;
        } else if (Math.abs(raw - p) > 0.001f) {
            // 弹簧过冲中：把超出的部分转成一点点放大，读作惯性
            scale = 1f + Math.min(0.08f, Math.abs(raw - p) * 0.5f);
        }
        float r = thumbSize * 0.5f * scale;

        // 行程：未选中距左 4，选中距右 4，行程 = 49 - 20 - 8 = 21 dp
        float travel = trackW - pad * 2f - thumbSize;
        float cx = left + pad + thumbSize * 0.5f + travel * p;

        // 滑块投影：极淡，只制造一点离面感（HyperOS 不用阴影做层级）
        paint.setColor(0x1F000000);
        canvas.drawCircle(cx, cy + r * 0.10f, r, paint);

        paint.setColor(thumb);
        canvas.drawCircle(cx, cy, r, paint);

        paint.setAlpha(255);
    }
}
