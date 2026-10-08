package io.github.ldxm666.mapadkiller;
import android.util.Log;
import java.lang.reflect.Method;
public final class SplashContainerHooks {
    static void installContainerProbe(ClassLoader cl, String clsName, final String tag) {
        Class<?> c = H.cls(cl, clsName);
        if (c == null) {
            H.log(Log.INFO, MainHook.TAG, tag + " splash container NOT FOUND: " + clsName);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String nm = m.getName();
            boolean want = nm.equals("addView")
                    || nm.equals("onAttachedToWindow")
                    || nm.equals("setContentView")
                    || nm.equals("show");
            if (!want) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (nm.equals("addView") && (ps.length == 0 || !android.view.View.class.isAssignableFrom(ps[0]))) continue;
            if (nm.equals("setContentView") && (ps.length == 0 || !android.view.View.class.isAssignableFrom(ps[0]))) continue;
            if (H.module == null) continue;
            H.module.hook(m).setId(tag + "_probe_" + nm)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof android.view.View) {
                                    final android.view.View v = (android.view.View) self;
                                    // 立即探一次 + 接下来 90 帧持续探（广告内容常是异步塞进来的）
                                    probeLoop(v, tag);
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            n++;
        }
        H.log(Log.INFO, MainHook.TAG, tag + " splash container probe on " + clsName + " hooks=" + n);
    }

    /** 帧驱动探测：命中即摘广告子树，最多盯 90 帧 */
    private static void probeLoop(final android.view.View root, final String tag) {
        if (root == null) return;
        if (!(root instanceof android.view.ViewGroup)) return;
        try {
            root.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int n;
                @Override public boolean onPreDraw() {
                    try {
                        if (root instanceof android.view.ViewGroup) {
                            SplashProbe.Result res = SplashProbe.scan(root);
                            if (res.isAd()) {
                                android.view.ViewGroup g = (android.view.ViewGroup) root;
                                SplashProbe.stripAdChildren(g);
                                if (SplashProbe.first(tag + ":" + res.hit)) {
                                    H.log(Log.INFO, MainHook.TAG,
                                            tag + " splash ad subtree stripped hit=" + res.hit);
                                }
                            }
                        }
                        if (++n > 90) {
                            try { root.getViewTreeObserver().removeOnPreDrawListener(this); }
                            catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }
}
