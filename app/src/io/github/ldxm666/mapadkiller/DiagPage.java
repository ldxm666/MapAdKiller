package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * DiagPage — 真机取证专用（只在 Config.debugLog() 打开时输出）。
 *
 * 用途：把「当前屏幕上到底是什么」按 3 秒一拍摊进日志 ——
 * 类名 / 资源名 / 尺寸 / 可见性 / 文本，并对 View 类的祖先输出到 root。
 * 这是搜索页那次排查的产物：搜索页整页是 AJX3 画布、节点无资源 id，
 * 没有这份台账根本没法判断广告是原生 View 还是画布内容。
 *
 * 交付版保持安装（不额外 hook，只挂 HomeTweaks 的 resume sink），
 * debugLog 关掉后零输出、零开销。
 */
public final class DiagPage {

    private DiagPage() {}

    private static volatile Activity activity;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int round;
    private static final Set<String> seen = new HashSet<>();

    public static void install() {
        try {
            HomeTweaks.addResumeSink(new HomeTweaks.ResumeSink() {
                @Override public void onActivityResumed(Activity act) { onResume(act); }
            });
            H.log(Log.INFO, MainHook.TAG, "diag page sink installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "diag page fail " + t);
        }
    }

    private static void onResume(final Activity act) {
        activity = act;
        round = 0;
        synchronized (seen) { seen.clear(); }
        final boolean dbg = Config.debugLog();
        if (!dbg) return;
        H.log(Log.INFO, MainHook.TAG, "DIAG sink act=" + act.getClass().getSimpleName());
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (act != activity || act.isFinishing()) return;
                    H.log(Log.INFO, MainHook.TAG, "PAGE# " + (round++)
                            + " act=" + act.getClass().getName());
                    dump(act.getWindow().getDecorView(), 0, new int[]{0});
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "diag dump err " + t);
                }
                if (round < 12) MAIN.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private static void dump(View v, int depth, int[] budget) {
        if (v == null || depth > 30 || budget[0] > 500) return;
        budget[0]++;
        try {
            String cn = v.getClass().getName();
            String id = "-";
            try {
                int rid = v.getId();
                if (rid != View.NO_ID && rid != 0) {
                    String rn = v.getResources().getResourceName(rid);
                    int s = rn.indexOf('/');
                    id = s > 0 ? rn.substring(s + 1) : rn;
                }
            } catch (Throwable ignored) {}
            String txt = null;
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 0) txt = cs.toString();
            }
            CharSequence cd = v.getContentDescription();
            boolean isText = txt != null || (cd != null && cd.length() > 0);
            boolean interesting = isText || !"-".equals(id) || v instanceof ViewGroup;
            if (interesting) {
                StringBuilder sb = new StringBuilder(128);
                for (int i = 0; i < depth; i++) sb.append('.');
                sb.append(' ').append(cn).append(" id=").append(id)
                  .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                  .append(" vis=").append(v.getVisibility() == View.VISIBLE ? "V"
                          : (v.getVisibility() == View.INVISIBLE ? "I" : "G"));
                if (txt != null) sb.append(" tv='").append(trim(txt)).append('\'');
                if (cd != null && cd.length() > 0) sb.append(" cd='").append(trim(cd.toString())).append('\'');
                String key = sb.toString();
                if (seen.add(key)) H.log(Log.INFO, MainHook.TAG, "PAGE" + key);
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, budget);
        }
    }

    private static String trim(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 28 ? s.substring(0, 28) + "~" : s;
    }
}
