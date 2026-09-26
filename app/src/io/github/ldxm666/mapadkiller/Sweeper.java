package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/** 在 Activity onResume 后延迟清扫视图树（异步/懒加载广告兜底）。API 102 hook。 */
public final class Sweeper {

    private Sweeper() {}

    public static void install(final ViewKiller killer) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume)
              .setId("sweep_activity")
              .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
              .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                  @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                      Object r = chain.proceed();
                      final Activity act = (Activity) chain.getThisObject();
                      Handler h = new Handler(Looper.getMainLooper());
                      // v1.0.13：三轮全树扫砍到两轮。资源名已经按 id 缓存，
                      // 单轮成本大幅下降，再少一轮进一步降负载。
                      h.postDelayed(new Runnable() {
                          @Override public void run() { killer.sweep(act); }
                      }, 300);
                      h.postDelayed(new Runnable() {
                          @Override public void run() { killer.sweep(act); }
                      }, 2500);
                      return r;
                  }
              });
            H.log(Log.INFO, MainHook.TAG, "sweeper installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweeper fail " + t);
        }
    }
}
