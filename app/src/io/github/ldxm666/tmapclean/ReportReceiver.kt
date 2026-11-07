package io.github.ldxm666.tmapclean

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收 hook 进程（腾讯地图进程内）发来的回报广播，落进模块 App 自己的本地存储。
 *
 * <p>安全：hook 侧回报会带上 App 侧写的随机 token，不匹配就丢弃（防别的 App 伪造状态）。
 * 首次运行 token 还没写、或服务未连接时 token 为空 —— 此时放行，保证诊断仍然可用。
 */
class ReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            if (context == null || intent == null) return
            val want = App.token()
            var got = intent.getStringExtra("token")
            if (got == null) got = ""
            if (want != null && want.length > 0 && got.length > 0 && want != got) {
                Log.w("TMapCleanApp", "report rejected: token mismatch")
                return
            }
            record(
                context, intent.getStringExtra("stage"), intent.getStringExtra("detail"),
                intent.getLongExtra("ts", System.currentTimeMillis())
            )
        } catch (t: Throwable) {
            Log.w("TMapCleanApp", "report receive failed: " + t)
        }
    }

    companion object {

        @JvmField
        val ACTION: String = Report.ACTION

        const val P = "tmapclean_report"

        /** 学到的工具目录在本地存储里的键：每行 "id\t文案" */
        const val K_CATALOG = "tools_catalog"

        /**
         * 落盘一份回报（不做 token 校验 —— 调用方要么已校验，要么就是产出方自己）。
         *
         * <p>免 root 内置版里 [Report.send] 直接调本方法：钩子与悬浮球面板在<b>同一个</b>
         * 宿主进程里，面板读的就是这个进程自己的 {@code SharedPreferences}。
         * 广播那条路在这里走不通 —— LSPatch 只把模块的 dex/资源并进宿主，
         * <b>不会</b>把模块 manifest 里的 {@code receiver} 声明并进宿主清单
         * （真机 {@code dumpsys package com.tencent.map} 里查不到本 receiver），
         * 所以「学到的工具目录」以前永远到不了面板，逐项开关只剩 BUILTIN 那份静态表。
         */
        @JvmStatic
        fun record(context: Context?, stage: String?, detail: String?, ts: Long) {
            if (context == null) return
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
            if ("tools" == stage && detail != null && detail.length > 0) {
                e.putString(K_CATALOG, detail)
            }
            e.commit()

            App.notifyReport()
        }

        /** 读取学到的工具目录：每行 "id\t文案"。返回 null 表示还没学到。 */
        @JvmStatic
        fun catalog(context: Context?): Array<Array<String>>? {
            try {
                if (context == null) return null
                val sp = context.getSharedPreferences(P, Context.MODE_PRIVATE)
                val raw = sp.getString(K_CATALOG, null)
                if (raw == null || raw.length == 0) return null
                val lines = raw.split("\n")
                val ids = java.util.ArrayList<String>()
                val titles = java.util.ArrayList<String>()
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.length == 0) continue
                    val tab = line.indexOf('\t')
                    val id = if (tab < 0) line else line.substring(0, tab)
                    val title = if (tab < 0) line else line.substring(tab + 1)
                    if (id.length == 0) continue
                    ids.add(id)
                    titles.add(title)
                }
                if (ids.isEmpty()) return null
                return arrayOf(ids.toTypedArray(), titles.toTypedArray())
            } catch (t: Throwable) {
                return null
            }
        }
    }
}
