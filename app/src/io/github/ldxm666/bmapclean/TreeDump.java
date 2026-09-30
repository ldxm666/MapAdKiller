package io.github.ldxm666.bmapclean;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 取证用视图树 dump（只在 debug 打开时输出到 logcat）。
 *
 * 为什么必须有它：百度首页展开态的关键区域（频道栏「推荐/看世界/…」、
 * 优质内容精选、推荐卡片）都是 **Talos（类 React）渲染**的，没有资源 id，
 * 且它们的文案只有 `TextView#getText()` 拿得到 ——
 *   · `dumpsys activity top` 不给文本，还会在别的 App 上超时；
 *   · `uiautomator dump` 在百度首页 "could not get idle state"（一直动画）；
 *   · Frida 被 baiduprotect 拦。
 * 所以只能从模块内部把树（含文本）打进 logcat。
 *
 * 输出格式（每行一个节点，便于 grep / 切片）：
 *   TREE <缩进><简单类名> <可见性> <bounds> #<十六进制id> <资源名> txt=<文本>
 */
public final class TreeDump {

    private static final int MAX_NODES = 2600;
    private static final int MAX_DEPTH = 60;

    private TreeDump() {}

    public static void dump(View root, String label) {
        if (root == null) return;
        final int[] n = new int[]{0};
        H.log(Log.INFO, MainHook.TAG, "TREE ---------- begin " + label
                + " cls=" + root.getClass().getName() + " ----------");
        walk(root, 0, n, label);
        H.log(Log.INFO, MainHook.TAG, "TREE ---------- end " + label + " nodes=" + n[0] + " ----------");
    }

    private static void walk(View v, int depth, int[] n, String label) {
        if (v == null || depth > MAX_DEPTH || n[0] > MAX_NODES) return;
        n[0]++;
        try {
            final String cls = v.getClass().getName();
            final String simple = cls.substring(cls.lastIndexOf('.') + 1);
            final int id = v.getId();
            String idName = "";
            if (id != 0 && id != -1) {   // -1 = NO_ID（Talos 渲染的视图全是 -1，别去查名字刷错误日志）
                String name = null;
                try { name = v.getResources().getResourceEntryName(id); } catch (Throwable ignored) {}
                idName = "#" + Integer.toHexString(id) + (name == null ? "" : " " + name);
            }
            String txt = "";
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) txt = cs.toString().replace('\n', ' ');
            }
            final String vis = v.getVisibility() == View.VISIBLE ? "V"
                    : (v.getVisibility() == View.INVISIBLE ? "I" : "G");
            final StringBuilder ind = new StringBuilder();
            for (int i = 0; i < depth && i < 30; i++) ind.append("  ");
            H.log(Log.INFO, MainHook.TAG, "TREE " + label + " " + ind + simple + " " + vis
                    + " " + v.getLeft() + "," + v.getTop() + "-" + v.getRight() + "," + v.getBottom()
                    + " " + idName + (txt.length() == 0 ? "" : " txt=" + txt));
            if (txt.length() > 0) return;   // 文本节点不再下钻（没有意义，省日志）
        } catch (Throwable ignored) {}

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, n, label);
            }
        }
    }
}
