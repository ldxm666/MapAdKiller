package io.github.ldxm666.mapclean;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * 极简横向分页容器 —— **不依赖 AndroidX**。
 *
 * 本工程是 `javac + android.jar` 的离线构建（没有 Gradle、没有依赖仓库），
 * 所以不能用 ViewPager/ViewPager2；这里用最朴素的做法自己实现：
 *   - 子 View 一字排开（第 i 页放在 x = i*宽），翻页靠父容器的 scrollX；
 *   - 手指横向拖拽**跟手**（scrollTo），松手按速度/半页阈值吸附到整页；
 *   - 只在"横向意图明显"时拦截触摸（|dx| > slop 且 > |dy|*1.3），
 *     否则把手势让给页内的 ScrollView，纵向滚动不受影响。
 *
 * 用法：pager.addView(page1); pager.addView(page2); pager.setPage(1, true);
 */
public final class Pager extends ViewGroup {

    public interface OnPageChanged {
        void onPageChanged(int index);
    }

    private final Scroller scroller;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;

    private OnPageChanged listener;
    private int page = 0;

    private float downX, downY, lastX;
    private boolean dragging;
    private VelocityTracker vt;

    public Pager(Context c) {
        super(c);
        scroller = new Scroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setClipToPadding(false);
    }

    public void setOnPageChanged(OnPageChanged l) { listener = l; }

    public int page() { return page; }

    public int pageCount() { return getChildCount(); }

    /** 切到第 p 页；animate=false 用于初始化/布局完成后的归位 */
    public void setPage(int p, boolean animate) {
        int n = getChildCount();
        if (n == 0) return;
        if (p < 0) p = 0;
        if (p > n - 1) p = n - 1;
        int target = p * getWidth();
        boolean changed = page != p;
        page = p;
        if (animate && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 260);
            invalidate();
        } else {
            scroller.abortAnimation();
            scrollTo(target, 0);
        }
        if (changed && listener != null) listener.onPageChanged(p);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = getWidth();
        int h = getHeight();
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
        scrollTo(page * w, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                dragging = false;
                scroller.abortAnimation();
                if (vt == null) vt = VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    // 横向意图明确 → 由本容器接管
                    dragging = true;
                    lastX = e.getX();
                    requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int n = getChildCount();
        if (w <= 0 || n == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                lastX = downX;
                dragging = false;
                scroller.abortAnimation();
                if (vt == null) vt = VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float x = e.getX();
                if (!dragging) {
                    if (Math.abs(x - downX) > touchSlop) {
                        dragging = true;
                        lastX = x;
                    } else {
                        return true;
                    }
                }
                int delta = (int) (lastX - x);
                lastX = x;
                int want = getScrollX() + delta;
                int max = (n - 1) * w;
                if (want < 0) want = 0;
                if (want > max) want = max;
                scrollTo(want, 0);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (vt != null) vt.addMovement(e);
                if (!dragging) {
                    recycleVt();
                    return true;    // 点击交给子 View（本容器不处理）
                }
                float vx = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    vx = vt.getXVelocity();
                }
                recycleVt();
                int cur = getScrollX();
                int nearest = Math.round((float) cur / (float) w);
                int target;
                if (Math.abs(vx) > minFling) {
                    // ⚠ 基准必须是**拖动开始时所在的页**（字段 page，拖动过程中不更新），
                    // 不能拿 cur（松手时的像素偏移）去 round 之后再 ±1 —— 那样"拖了大半页 + 有速度"
                    // 会叠加成跳两页（真机实测：一次左滑直接从第 1 页跳到第 3 页）。
                    target = page + ((vx < 0) ? 1 : -1);   // 左滑（vx<0）→ 下一页
                } else {
                    target = nearest;
                }
                if (target < 0) target = 0;
                if (target > n - 1) target = n - 1;
                setPage(target, true);
                dragging = false;
                return true;
            }
            default:
                return true;
        }
    }

    private void recycleVt() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }
}
