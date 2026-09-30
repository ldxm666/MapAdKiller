package io.github.ldxm666.bmapclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收 hook 进程（百度地图进程内）发来的回报广播，落进模块 App 自己的本地存储。
 *
 * 安全：hook 侧回报会带上 App 侧写的随机 token，不匹配就丢弃（防别的 App 伪造状态）。
 * 首次运行 token 还没写、或服务未连接时 token 为空 —— 此时放行，保证诊断仍然可用
 * （这条通道只承载文本状态，最坏情况是被伪造一条误导性文字，不构成权限风险）。
 */
public final class ReportReceiver extends BroadcastReceiver {

    public static final String ACTION = Report.ACTION;
    public static final String P = "bmapclean_report";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            String want = App.token();
            String got = intent.getStringExtra("token");
            if (got == null) got = "";
            // 严格拒绝「带了错 token」的；空 token 放行 —— hook 进程可能在本模块 App
            // 生成 token 之前就已经起来了（RemotePreferences 只读快照），不该因此丢诊断。
            if (want != null && want.length() > 0 && got.length() > 0 && !want.equals(got)) {
                Log.w("BMapCleanApp", "report rejected: token mismatch");
                return;
            }
            String stage = intent.getStringExtra("stage");
            String detail = intent.getStringExtra("detail");
            long ts = intent.getLongExtra("ts", System.currentTimeMillis());

            android.content.SharedPreferences sp = context.getSharedPreferences(P, Context.MODE_PRIVATE);
            long prev = sp.getLong("hook_ts", 0L);
            android.content.SharedPreferences.Editor e = sp.edit()
                    .putString("stage", stage)
                    .putString("detail", detail)
                    .putLong("ts", ts)
                    .putInt("count", sp.getInt("count", 0) + 1);
            if ("hook".equals(stage)) e.putLong("hook_ts", ts);
            else e.putLong("scan_ts", ts);
            if (prev == 0L) e.putLong("first_ts", ts);
            e.commit();

            App.notifyReport();
        } catch (Throwable t) {
            Log.w("BMapCleanApp", "report receive failed: " + t);
        }
    }
}
