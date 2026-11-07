package io.github.ldxm666.bmapclean

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收 hook 进程（百度地图进程内）发来的回报广播，落进模块 App 自己的本地存储。
 *
 * 安全：hook 侧回报会带上 App 侧写的随机 token，不匹配就丢弃（防别的 App 伪造状态）。
 * 首次运行 token 还没写、或服务未连接时 token 为空 —— 此时放行，保证诊断仍然可用
 * （这条通道只承载文本状态，最坏情况是被伪造一条误导性文字，不构成权限风险）。
 */
class ReportReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = Report.ACTION
        const val P = "bmapclean_report"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            if (context == null || intent == null) return
            val want: String? = App.token()
            var got = intent.getStringExtra("token")
            if (got == null) got = ""
            // 严格拒绝「带了错 token」的；空 token 放行 —— hook 进程可能在本模块 App
            // 生成 token 之前就已经起来了（RemotePreferences 只读快照），不该因此丢诊断。
            if (want != null && want.length > 0 && got.length > 0 && want != got) {
                Log.w("BMapCleanApp", "report rejected: token mismatch")
                return
            }
            val stage = intent.getStringExtra("stage")
            val detail = intent.getStringExtra("detail")
            val ts = intent.getLongExtra("ts", System.currentTimeMillis())

            // 宫格新文案回报（stage="tools"）：只补开关，不覆盖诊断状态。
            // 这里必须 merge + 落盘：本进程可能随时被回收，重开百度后
            // BaiduHooks.install → Spec.loadDiscovered() 会把它们读回来。
            if ("tools" == stage) {
                val one = java.util.ArrayList<String?>()
                one.add(detail)
                if (Spec.mergeDiscovered(one)) {
                    Spec.persistDiscovered()
                    Log.i("BMapCleanApp", "discovered tool label merged: " + detail)
                }
                App.notifyReport()
                return
            }

            val sp = context.getSharedPreferences(P, Context.MODE_PRIVATE)
            val prev = sp.getLong("hook_ts", 0L)
            val e = sp.edit()
                    .putString("stage", stage)
                    .putString("detail", detail)
                    .putLong("ts", ts)
                    .putInt("count", sp.getInt("count", 0) + 1)
            if ("hook" == stage) e.putLong("hook_ts", ts)
            else e.putLong("scan_ts", ts)
            if (prev == 0L) e.putLong("first_ts", ts)
            e.commit()

            App.notifyReport()
        } catch (t: Throwable) {
            Log.w("BMapCleanApp", "report receive failed: " + t)
        }
    }
}