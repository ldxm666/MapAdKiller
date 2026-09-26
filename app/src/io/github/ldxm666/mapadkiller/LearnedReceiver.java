package io.github.ldxm666.mapadkiller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LearnedReceiver —— 广告 SDK 学习结果的**广播兜底通道**。
 *
 * 主通道是 LearnedProvider（ContentResolver#call，按需唤起本 App 进程，HyperOS 拦不住）。
 * 只有当 provider 那条路返回空时，hook 侧才会退回 sendBroadcast(ACTION_LEARNED)。
 * AndroidManifest 里一直声明着这个 receiver，但 v1.0.6 及以前**没有对应的类文件**，
 * 于是兜底一旦被用到就会静默失败（系统日志里是一条 Unable to instantiate receiver）。
 * 这里把它补齐。
 *
 * 校验规则与 LearnedProvider 完全一致：上报方是别的 App 进程，内容一律过包名白名单。
 */
public final class LearnedReceiver extends BroadcastReceiver {

    private static final String TAG = MainHook.TAG;
    private static final int MAX = 300;
    private static final Pattern PKGNAME =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,5}$");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!SdkAutoBlock.ACTION_LEARNED.equals(intent.getAction())) return;
        try {
            ArrayList<String> raw = intent.getStringArrayListExtra(SdkAutoBlock.EXTRA_ROOTS);
            if (raw == null || raw.isEmpty()) return;

            Set<String> clean = new LinkedHashSet<>();
            for (String s : raw) {
                if (s == null) continue;
                String v = s.trim().toLowerCase();
                if (v.length() < 6 || v.length() > 60) continue;
                if (!PKGNAME.matcher(v).matches()) continue;
                clean.add(v);
                if (clean.size() >= MAX) break;
            }
            if (clean.isEmpty()) return;

            Set<String> staged = new LinkedHashSet<>(LearnedProvider.readLocal(context));
            staged.addAll(clean);
            LearnedProvider.writeLocal(context, staged);

            // 服务已绑定就直接推 RemotePreferences，否则先暂存，绑定后由 App 补推。
            Set<String> merged = new LinkedHashSet<>();
            try {
                io.github.libxposed.service.XposedService s = App.svc();
                if (s != null) {
                    Set<String> old = s.getRemotePreferences(Config.PREF_GROUP)
                            .getStringSet("sdk_learned", null);
                    if (old != null) merged.addAll(old);
                }
            } catch (Throwable ignored) {}
            merged.addAll(staged);
            boolean saved = App.writeStringSet("sdk_learned", merged);
            if (!saved) App.addPendingLearned(staged);

            H.log(Log.INFO, TAG, "learned via broadcast: +" + clean.size()
                    + " staged=" + staged.size() + " total=" + merged.size()
                    + (saved ? " (saved)" : " (staged, service not ready)"));
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "learned receiver err " + t);
        }
    }
}
