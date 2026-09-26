package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.regex.Pattern;

/**
 * ViewKiller — 通用视图层兜底：Activity onResume 后遍历 DecorView，
 * 命中「类名正则 / 资源名正则 / 无障碍"广告"标签」即 GONE + 移除子树。
 * 广告角标（小"广告"标签）命中时上溯到列表项容器一并移除。
 */
public final class ViewKiller {

    private final Pattern classPat;
    private final Pattern resPat;
    private final String tag;

    /**
     * 资源名按 id 缓存。
     *
     * Resources#getResourceName() 是一次真正的资源表查询，而 ViewKiller 每次 resume 要
     * 走三遍整棵视图树、每个节点都调一次 —— 这是纯浪费（同一个 id 的结果永远一样）。
     * 缓存后每棵树的成本从「节点数 × 资源查询」降到「不同 id 数 × 一次」。
     */
    private final java.util.HashMap<Integer, String> resNames = new java.util.HashMap<>();

    public ViewKiller(String tag, String classRegex, String resRegex) {
        this.tag = tag;
        this.classPat = classRegex == null ? null : Pattern.compile(classRegex);
        this.resPat = resRegex == null ? null : Pattern.compile(resRegex);
    }

    public void sweep(Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            View decor = act.getWindow().getDecorView();
            sweep(decor, act.getResources());
        } catch (Throwable t) {
            H.log(android.util.Log.WARN, MainHook.TAG, tag + " sweep err " + t);
        }
    }

    private void sweep(View v, Resources res) {
        boolean gone = false;
        try {
            if (classPat != null && classPat.matcher(v.getClass().getName()).find()) gone = true;
            if (!gone && resPat != null && v.getId() != View.NO_ID && v.getId() != 0) {
                String name = resName(res, v.getId());
                if (name != null && name.length() > 0 && resPat.matcher(name).find()) gone = true;
            }
            // 无障碍标签识别：广告卡通常自带 "广告"/"Ad" contentDescription
            if (!gone) {
                CharSequence cd = v.getContentDescription();
                if (cd == null && v instanceof android.widget.TextView) {
                    cd = ((android.widget.TextView) v).getText();
                }
                if (cd != null) {
                    String s = cd.toString();
                    if (s.length() <= 6 && (s.contains("广告")
                            || s.equalsIgnoreCase("Ad") || s.equalsIgnoreCase("AD"))) {
                        v = bubbleToAdContainer(v);
                        gone = true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 非法 id / 资源不存在时跳过
        }
        if (gone && v.getVisibility() != View.GONE) {
            v.setVisibility(View.GONE);
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).removeAllViews();
            }
            H.log(android.util.Log.INFO, MainHook.TAG,
                    tag + " KILLED view: " + v.getClass().getName() + " id=" + safeName(v, res));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sweep(g.getChildAt(i), res);
            }
        }
    }

    /** "广告"角标命中后上溯到列表项容器：第一个 高度>=96px 且 ≥3倍角标 的祖先，最多 5 层 */
    private static View bubbleToAdContainer(View label) {
        int lh = Math.max(label.getHeight(), 1);
        View cur = label;
        for (int i = 0; i < 5; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            if (cur.getHeight() >= 96 && cur.getHeight() >= 3 * lh) return cur;
        }
        return label;
    }

    /** 带缓存的资源名查询（查不到缓存空串，避免重复抛异常） */
    private String resName(Resources res, int id) {
        String c = resNames.get(id);
        if (c != null) return c;
        try {
            c = res.getResourceName(id);
        } catch (Throwable t) {
            c = "";
        }
        if (c == null) c = "";
        resNames.put(id, c);
        return c;
    }

    private String safeName(View v, Resources res) {
        if (v.getId() <= 0) return "-";
        String n = resName(res, v.getId());
        return n.length() == 0 ? "?" : n;
    }
}
