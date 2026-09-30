package io.github.ldxm666.bmapclean;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 百度首页两个「广告位」的清除（v2.0.1 新增）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ① 左上角运营浮层（真机 uiautomator 实证）
 *    node class=android.widget.RelativeLayout content-desc="活动" clickable=true
 *         bounds=[18,297][1555,434]   ← 就是「一键穿越 / 古今地图」那块
 *    这是**地图上的绝对定位浮层**（不是流式布局里的兄弟节点），所以直接 GONE 不会
 *    影响右侧「图层 / 反馈 / 加行程」那几个按钮的位置（它们在 x≈975）。
 *    判据：可点击 + contentDescription 命中 {@link #AD_DESCS} + 位于屏幕左上区域。
 *
 * ② 搜索框里的**热词轮播**（"青城山门票 / 成都海昌极地海洋公园好…"换着播）
 *    真机实证：文字落在 `com.baidu.BaiduMap:id/tv_searchbar_title`（0x7f0252b5）这个 TextView 上。
 *    做法与 MapAdKiller 对高德 `HotWordGuard` 同款语义：**不摘控件，只把文字钉死**成
 *    系统默认提示词 —— 挂 `TextView#setText(CharSequence[, BufferType])`，
 *    命中那个 id 就把文案改回默认、热词品牌图标不再有意义（文字不再变）。
 *    用 after-hook + ThreadLocal 防重入，**不做参数替换**（那条路按熔断规则停用）。
 *
 * 失效安全：id 解析不到 / 开关关 / 任何异常 → 原样放行；绝不影响别的 TextView。
 */
public final class HomeAds {

    private HomeAds() {}

    /** 热词钉死后显示的文案（与百度默认提示词一致，用户不会觉得突兀） */
    private static final String HINT = "查找地点、公交、地铁";

    /** 左上角浮层的 contentDescription 白名单（实测「活动」；另两个是同类运营位的常见叫法） */
    private static final String[] AD_DESCS = {"活动", "新鲜事", "一键穿越", "古今地图"};

    /**
     * 左上角运营气泡的**资源 id**（真机诊断原文：
     *   `{RelativeLayout id=event_entry_container @18,297 137x137}`   ← 新鲜事 / 一键穿越
     *   `{FrameLayout id=left_container @26,135 131x131 click}`       ← 动态图标 / 答题红包
     * 这类气泡没有 a11y 节点（uiautomator 看不到）、文案服务端下发（dex 搜不到），
     * 按 id 命中是唯一稳定抓手；名字在 App 更新后重排数值也不怕（运行时按名解析）。
     */
    private static final String[] BUBBLE_IDS = {"event_entry_container", "left_container"};

    private static volatile boolean installed;
    private static volatile int sHidden;
    private static volatile int sFrozen;
    private static volatile int sIdCache;
    /** 左上角浮层扫描只诊断一次（未命中时把候选节点打出来） */
    private static volatile int sScanned;
    private static final ThreadLocal<Boolean> IN = new ThreadLocal<Boolean>();

    // ══════════════════════════════════════════════ 安装（钩子）

    public static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        try {
            Class<?> tv = H.cls(cl, "android.widget.TextView");
            if (tv == null) return;
            Method[] ms = tv.getDeclaredMethods();
            int n = 0;
            for (int i = 0; i < ms.length; i++) {
                final Method m = ms[i];
                if (!"setText".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean str = p.length >= 1 && p.length <= 2
                        && (p[0] == CharSequence.class
                            || (p.length == 2 && p[0] == CharSequence.class));
                if (!str || p[0] != CharSequence.class) continue;
                H.hook(m, "home_hotword_" + p.length, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try {
                            freezeIfHotWord(chain);
                        } catch (Throwable ignored) {}
                        return r;
                    }
                });
                n++;
            }
            Cfg.log("HomeAds: hotword setText hooks=" + n);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "HomeAds install failed: " + t);
        }
    }

    /** 命中搜索框标题 TextView → 把文字钉死成默认提示词 */
    private static void freezeIfHotWord(XposedInterface.Chain chain) {
        if (!Spec.hotwordFreeze()) return;
        if (Boolean.TRUE.equals(IN.get())) return;              // 防重入（下面自己调 setText）
        Object self = chain.getThisObject();
        if (!(self instanceof TextView)) return;
        TextView v = (TextView) self;
        int id = v.getId();
        if (id == 0) return;
        if (!inSearchBox(v, id)) return;
        CharSequence cur = v.getText();
        if (cur == null || cur.length() == 0 || HINT.contentEquals(cur)) return;
        IN.set(Boolean.TRUE);
        try {
            v.setText(HINT);
            sFrozen++;
            if (Cfg.debug()) Cfg.log("hotword freeze: '" + cur + "' -> '" + HINT + "'");
        } finally {
            IN.remove();
        }
    }

    /** 这个 TextView 是否属于首页搜索框（标题本身，或切换器的两个子容器之内） */
    private static boolean inSearchBox(TextView v, int id) {
        if (id == idOf(v, Anchors.ID_SEARCHBAR_TITLE, Anchors.NUM_SEARCHBAR_TITLE)) return true;
        try {
            android.view.ViewParent p = v.getParent();
            if (p instanceof View) {
                int pid = ((View) p).getId();
                int cont = idOf(v, Anchors.ID_SEARCHBOX_CONTAINER, Anchors.NUM_SEARCHBOX_CONTAINER);
                int swit = idOf(v, Anchors.ID_SEARCHBOX_SWITCHER, Anchors.NUM_SEARCHBOX_SWITCHER);
                if (pid != 0 && (pid == cont || pid == swit)) return true;
                android.view.ViewParent pp = p.getParent();
                if (pp instanceof View && ((View) pp).getId() == swit) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 按资源名解析 id（带缓存；解析不到用 num 兜底） */
    private static int idOf(View v, String name, int num) {
        try {
            android.content.res.Resources r = v.getResources();
            if (r != null) {
                int id = r.getIdentifier(name, "id", Anchors.PKG);
                if (id == 0) id = r.getIdentifier(name, "id", null);
                if (id != 0) return id;
            }
        } catch (Throwable ignored) {}
        return num;
    }
    /** 解析 `tv_searchbar_title` 的数值 id（名字优先，失败用真机实测数值兜底），带缓存 */
    private static int titleId(TextView v) {
        int c = sIdCache;
        if (c != 0) return c;
        int id = 0;
        try {
            Resources r = v.getResources();
            if (r != null) {
                id = r.getIdentifier(Anchors.ID_SEARCHBAR_TITLE, "id", Anchors.PKG);
                if (id == 0) id = r.getIdentifier(Anchors.ID_SEARCHBAR_TITLE, "id", null);
            }
        } catch (Throwable ignored) {}
        if (id == 0) id = Anchors.NUM_SEARCHBAR_TITLE;
        sIdCache = id;
        if (Cfg.debug()) Cfg.log("hotword id " + Anchors.ID_SEARCHBAR_TITLE + " = 0x" + Integer.toHexString(id));
        return id;
    }

    // ══════════════════════════════════════════════ 首页左上角运营浮层

    /**
     * 左上角运营气泡的几何兜底。
     *
     * 背景：这类气泡（新鲜事 / 一键穿越 / 古今地图）在 uiautomator 树里**完全看不到**
     * （浮层自绘，没有 a11y 节点），文案是服务端下发的所以 dex 里也搜不到 ——
     * 只能按位置与尺寸在浮层容器里认：
     *   可点击 + 完全落在屏幕左上角（右边界 < 45% 宽、上边界 > 7% 高、下边界 < 32% 高）
     *   + 宽高都 ≤ 150dp（气泡是个小方块）+ 不在 ufo_root / home_ai_container / 搜索框子树里
     * 命中即 GONE，并把它的 class/尺寸打进日志（便于下轮收紧或放宽）。
     */
    private static View findBubbleByGeometry(View decor) {
        try {
            View frame = Anchors.find(decor, "front_frame", 0);
            if (frame == null || !(frame instanceof ViewGroup)) return null;
            return scanBubble((ViewGroup) frame, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static View scanBubble(ViewGroup g, int depth) {
        if (depth > 6) return null;
        try {
            android.util.DisplayMetrics dm = g.getResources().getDisplayMetrics();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                if (c.isClickable() && isCornerBubble(c, dm) && !inKnownUi(c)) return c;
                if (c instanceof ViewGroup) {
                    View r = scanBubble((ViewGroup) c, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isCornerBubble(View v, android.util.DisplayMetrics dm) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int x = loc[0], y = loc[1];
            int w = v.getWidth(), h = v.getHeight();
            int maxSide = (int) (150 * dm.density);
            return w > 0 && h > 0 && w <= maxSide && h <= maxSide
                    && x + w < dm.widthPixels * 0.45f
                    && y > dm.heightPixels * 0.07f
                    && y + h < dm.heightPixels * 0.32f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已知 UI（首页面板 / 工具行 / 搜索框 / 悬浮按钮）一律不碰 */
    private static boolean inKnownUi(View v) {
        int[] ids = {
                Anchors.NUM_UFO_ROOT, Anchors.NUM_HOME_PANEL,
                Anchors.NUM_SEARCHBAR_TITLE, Anchors.NUM_SEARCHBOX_SWITCHER,
                Anchors.NUM_SEARCHBOX_CONTAINER,
        };
        View p = v;
        int guard = 0;
        while (p != null && guard++ < 25) {
            int id = p.getId();
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != 0 && id == ids[i]) return true;
            }
            android.view.ViewParent q = p.getParent();
            p = (q instanceof View) ? (View) q : null;
        }
        return false;
    }

    /** 在首页 decor 上清一次左上角浮层；返回清掉的个数（0/1） */
    public static int applyHomeActivity(View decor) {
        // ⚠ 键语义是「可见」：true=显示 → 用户要显示时**不要动**；false/缺省=隐藏 → 执行
        //   （v2.0.1 第一次写反了，结果规则永不执行、浮层照旧 —— 真机实测）
        if (Cfg.visible(Spec.K_HOME_ACT, Spec.defaultVisible(Spec.K_HOME_ACT))) return 0;
        try {
            View v = findAdOverlay(decor, 0);
            if (v == null) v = findBubbleByGeometry(decor);
            if (v == null) {
                if (sScanned < 6) {
                    sScanned++;
                    Cfg.log("homeAd scan: 未命中；左上角可点击节点 = " + allTopLeft(decor, 0, new StringBuilder()));
                }
                return 0;
            }
            v.setVisibility(View.GONE);
            sHidden++;
            Cfg.log("homeAd hidden: " + v.getClass().getSimpleName()
                    + " desc=" + v.getContentDescription()
                    + " @" + v.getLeft() + "," + v.getTop() + " (" + sHidden + ")");
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 一次性诊断：把左上角区域**所有**视图打出来（含没有 a11y 属性的）—— 运营气泡就是那种 */
    private static String allTopLeft(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 60 || sb.length() > 1300) return sb.toString();
        try {
            if (v.getVisibility() == View.GONE) return sb.toString();
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
            int w = v.getWidth(), h = v.getHeight();
            if (loc[1] < dm.heightPixels * 0.36f && loc[0] < dm.widthPixels * 0.5f
                    && w > 0 && h > 0 && w <= dm.widthPixels * 0.5f) {
                String id = "";
                try { id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
                String desc = v.getContentDescription() == null ? "" : v.getContentDescription().toString();
                String txt = (v instanceof TextView && ((TextView) v).getText() != null)
                        ? ((TextView) v).getText().toString() : "";
                sb.append('{').append(v.getClass().getSimpleName())
                  .append(" id=").append(id)
                  .append(" @").append(loc[0]).append(',').append(loc[1])
                  .append(' ').append(w).append('x').append(h)
                  .append(v.isClickable() ? " click" : "")
                  .append(desc.length() > 0 ? (" desc=" + desc) : "")
                  .append(txt.length() > 0 ? (" text=" + txt) : "")
                  .append("} ");
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    allTopLeft(g.getChildAt(i), depth + 1, sb);
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    /**
     * 找左上角运营浮层。
     *
     * 判据（两道，命中任一即认）：
     *   a) 可点击 + contentDescription 命中白名单（实测「活动」）；
     *   b) 可点击 + **子树里有白名单文案的 TextView**（浮层本体常常只是个图片，
     *      文案在里面的小 TextView 上，例如「新鲜事 / 一键穿越 / 古今地图」）。
     * 位置限制：屏幕上半部分靠左（避免误伤别处的同名控件）。
     * 深度上限放到 200：百度首页的视图树很深，40 层根本走不到浮层那一层（实测漏命中）。
     */
    private static View findAdOverlay(View v, int depth) {
        if (v == null || depth > 200) return null;
        try {
            if (v.getVisibility() == View.GONE) return null;
            if (inTopLeft(v) && hitBubbleId(v)) return v;                  // ① id 最准
            if (v.isClickable() && inTopLeft(v) && hitWhitelist(v, 0)) return v;   // ② desc/文案
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findAdOverlay(g.getChildAt(i), depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 资源 id 命中运营气泡白名单（按名解析，不依赖 0x7f 数值） */
    private static boolean hitBubbleId(View v) {
        try {
            int id = v.getId();
            if (id == 0) return false;
            for (int i = 0; i < BUBBLE_IDS.length; i++) {
                if (id == idOf(v, BUBBLE_IDS[i], 0)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** contentDescription 或子树文案命中白名单（子树只往下探 3 层，够用且便宜） */
    private static boolean hitWhitelist(View v, int depth) {
        try {
            CharSequence d = v.getContentDescription();
            if (d != null && matches(d.toString())) return true;
            if (depth >= 3) return false;
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && matches(t.toString())) return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (hitWhitelist(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean inTopLeft(View v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
            return loc[1] < dm.heightPixels * 0.4f && loc[0] < dm.widthPixels * 0.6f;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean matches(String s) {
        for (int i = 0; i < AD_DESCS.length; i++) {
            if (AD_DESCS[i].equals(s)) return true;
        }
        return false;
    }

    public static String stats() {
        return "adOverlay=" + sHidden + " hotwordFrozen=" + sFrozen;
    }
}
