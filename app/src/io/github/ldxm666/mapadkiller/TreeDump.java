package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * TreeDump — 真机取证用：把 DecorView 全树按 walk() 的判据逐节点落日志。
 * 输出每节点的真实类名、几何、可见性、LayoutParams、getContentDescription()、
 * instanceof TextView 的文本，用于校准锚点与"谁是 item 宿主"。
 * 仅在 Config.debugLog() 打开时工作。
 */
public final class TreeDump {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_LINES = 8000;

    private static volatile boolean installed;

    private TreeDump() {}

    public static void install() {
        if (installed) return;
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume)
                    .setId("amapenhancer_treedump")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (!Config.debugLog()) return r;
                            final Activity act = (Activity) chain.getThisObject();
                            final Handler h = new Handler(Looper.getMainLooper());
                            final Runnable[] self = new Runnable[1];
                            final int[] shot = {0};
                            self[0] = new Runnable() {
                                @Override public void run() {
                                    try {
                                        if (act.isFinishing() || shot[0] > 40) return;
                                        String name = act.getClass().getName();
                                        name = name.substring(name.lastIndexOf('.') + 1);
                                        H.log(Log.INFO, MainHook.TAG,
                                                "### TREE[" + (shot[0]++) + "] act=" + name
                                                        + " decor=" + geom(act.getWindow().getDecorView()));
                                        dump(act.getWindow().getDecorView(), 0, new int[]{0});
                                    } catch (Throwable t) {
                                        H.log(Log.WARN, MainHook.TAG, "treedump err " + t);
                                    }
                                    h.postDelayed(self[0], 2500);
                                }
                            };
                            h.postDelayed(self[0], 3000);
                            return r;
                        }
                    });
            installed = true;
            H.log(Log.INFO, MainHook.TAG, "treedump installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "treedump fail " + t);
        }
    }

    private static void dump(View v, int depth, int[] budget) {
        if (v == null || depth > MAX_DEPTH || budget[0] > MAX_LINES) return;
        budget[0]++;
        StringBuilder sb = new StringBuilder(160);
        for (int i = 0; i < depth; i++) sb.append('.');
        sb.append(' ').append(shortName(v)).append(' ').append(geom(v));
        sb.append(" vis=").append(vis(v));
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) sb.append(" lp=").append(lp.width).append('x').append(lp.height);
        String cd = null;
        try { CharSequence c = v.getContentDescription(); if (c != null) cd = c.toString(); } catch (Throwable ignored) {}
        if (cd != null) sb.append(" CD='").append(trim(cd)).append('\'');
        if (v instanceof TextView) {
            CharSequence t;
            try { t = ((TextView) v).getText(); } catch (Throwable ignored) { t = null; }
            if (t != null) sb.append(" TV='").append(trim(t.toString())).append('\'');
        }
        // v1.1.9 取证：AJX 文本控件（Label/Html…）不是 TextView，反射取 getText
        String cn = v.getClass().getName();
        if (cn.contains("ajx3") && (cn.endsWith("Label") || cn.endsWith("Html")
                || cn.endsWith("Text") || cn.endsWith("TitleView"))) {
            try {
                java.lang.reflect.Method gm = v.getClass().getMethod("getText");
                gm.setAccessible(true);
                Object t = gm.invoke(v);
                if (t instanceof CharSequence && ((CharSequence) t).length() > 0) {
                    sb.append(" AJXTV='").append(trim(((CharSequence) t).toString())).append('\'');
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {}
        }
        String id = null;
        try {
            int rid = v.getId();
            if (rid != View.NO_ID && rid != 0) id = v.getResources().getResourceName(rid);
        } catch (Throwable ignored) {}
        if (id != null) sb.append(" id=").append(id.replace("com.autonavi.minimap:id/", ""));
        sb.append(" rvParent=").append(isRvLike(v.getParent()) ? 'Y' : 'n');
        H.log(Log.INFO, MainHook.TAG, sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, budget);
        }
    }

    private static String shortName(View v) {
        String n = v.getClass().getName();
        if (n.startsWith("com.autonavi.minimap.ajx3.")) return "AJX!" + n.substring(n.lastIndexOf('.') + 1);
        if (n.startsWith("ajx3.")) return "AJX!" + n.substring(n.lastIndexOf('.') + 1);
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static String vis(View v) {
        switch (v.getVisibility()) {
            case View.VISIBLE: return "V";
            case View.INVISIBLE: return "I";
            default: return "G";
        }
    }

    private static String geom(View v) {
        int[] loc = new int[2];
        try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
        return "[" + v.getLeft() + "," + v.getTop() + " " + v.getWidth() + "x" + v.getHeight()
                + " scr" + loc[0] + "," + loc[1] + "]";
    }

    private static String trim(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 30 ? s.substring(0, 30) + "~" : s;
    }

    private static boolean isRvLike(ViewParent p) {
        if (!(p instanceof View)) return false;
        for (Class<?> k = p.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("androidx.recyclerview.widget.RecyclerView")
                    || n.endsWith(".RecyclerView") || n.endsWith(".RecyclerViewV2")) return true;
        }
        return false;
    }
}
