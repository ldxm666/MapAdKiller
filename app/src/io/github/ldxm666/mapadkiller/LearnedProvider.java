package io.github.ldxm666.mapadkiller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LearnedProvider —— hook 进程把"新发现的广告 SDK"直接写进来的通道。
 *
 * 为什么不用广播：实测高德/百度进程 `sendBroadcast` 时，HyperOS 不会把设置 App 拉起来，
 * 广播静默丢失（日志里 hook 侧有 "SDK report sent"，App 侧一条都没收到）。
 * ContentProvider 的 `ContentResolver.call()` 是**按需唤起**目标进程的，
 * 不依赖广播投递策略，这条路可靠得多。
 *
 * 为什么不让 hook 侧自己存：hook 侧拿到的 RemotePreferences 是只读实现
 * （实测 `UnsupportedOperationException: Read only implementation`），写不进去。
 *
 * 链路：hook 扫到新厂商 → ContentResolver.call → 这里 → 本地 prefs 暂存（必成）
 *      → 再推 RemotePreferences（hook 侧读的就是它）。
 */
public final class LearnedProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.ldxm666.mapadkiller.learned";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_PUT = "putRoots";
    public static final String KEY_ROOTS = "roots";
    public static final String KEY_TOTAL = "total";

    private static final String TAG = MainHook.TAG;
    private static final int MAX = 300;
    private static final Pattern PKGNAME =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,5}$");

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            if (!METHOD_PUT.equals(method) || extras == null) return out;
            java.util.ArrayList<String> raw =
                    extras.getStringArrayList(KEY_ROOTS);
            if (raw == null || raw.isEmpty()) return out;

            // 上报方是别的 App 进程，所以内容一律过白名单校验：
            // 只收包名形状的字符串，去重限量，避免被塞垃圾把正常功能拦坏。
            Set<String> clean = new LinkedHashSet<>();
            for (String s : raw) {
                if (s == null) continue;
                String v = s.trim().toLowerCase();
                if (v.length() < 6 || v.length() > 60) continue;
                if (!PKGNAME.matcher(v).matches()) continue;
                clean.add(v);
                if (clean.size() >= MAX) break;
            }
            if (clean.isEmpty()) return out;

            Context ctx = getContext();
            if (ctx == null) return out;

            // 1) 本地 prefs 暂存 —— 这步一定成功，保证不丢
            Set<String> staged = new LinkedHashSet<>(readLocal(ctx));
            staged.addAll(clean);
            writeLocal(ctx, staged);

            // 2) 推 RemotePreferences（hook 侧读它）
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

            out.putInt(KEY_TOTAL, merged.size());
            H.log(Log.INFO, TAG, "learned via provider: +" + clean.size()
                    + " staged=" + staged.size() + " total=" + merged.size()
                    + (saved ? " (saved)" : " (staged, service not ready)"));
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "provider call err " + t);
        }
        return out;
    }

    static Set<String> readLocal(Context c) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Set<String> s = c.getSharedPreferences("mak_learned", Context.MODE_PRIVATE)
                    .getStringSet("sdk_learned", null);
            if (s != null) out.addAll(s);
        } catch (Throwable ignored) {}
        return out;
    }

    static void writeLocal(Context c, Set<String> v) {
        try {
            c.getSharedPreferences("mak_learned", Context.MODE_PRIVATE)
                    .edit().putStringSet("sdk_learned", new LinkedHashSet<>(v)).commit();
        } catch (Throwable ignored) {}
    }

    /** App 侧启动/绑定时调用：把暂存的推去 RemotePreferences */
    public static void flushToRemote(Context c) {
        Set<String> staged = readLocal(c);
        if (staged.isEmpty()) return;
        try {
            Set<String> merged = new LinkedHashSet<>();
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) {
                Set<String> o = s.getRemotePreferences(Config.PREF_GROUP)
                        .getStringSet("sdk_learned", null);
                if (o != null) merged.addAll(o);
            }
            merged.addAll(staged);
            if (App.writeStringSet("sdk_learned", merged)) {
                H.log(Log.INFO, TAG, "learned flush ok total=" + merged.size());
            }
        } catch (Throwable ignored) {}
    }

    // ---- ContentProvider 必须实现的其余成员（本 Provider 只当 RPC 用）----
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
