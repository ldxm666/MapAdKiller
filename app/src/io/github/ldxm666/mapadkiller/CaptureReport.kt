package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 抓取状态回报通道（宿主进程 → 设置页）。
 *
 * <p><b>为什么不用 RemotePreferences 回写</b>：libxposed API 102 在 hook 侧拿到的
 * RemotePreferences 是<b>只读实现</b>，`edit().commit()` 必抛
 * `UnsupportedOperationException: Read only implementation`（真机 logcat 实测，
 * 与 `bmapclean.Report` 里记录的是同一个坑）。所以宿主进程抓完数据**没法**把
 * `baseline_captured_v<code>` 写回给设置页看 —— 这也是用户看到「抓取一直失败、
 * 首页一直显示未抓取」的直接原因。
 *
 * <p>方向反过来就行：<b>宿主进程发一条显式广播，模块 App 收到后落到自己的
 * `capture_report` prefs</b>。设置页只读这份本地记录，不再依赖 root（模块 App
 * 不在 KernelSU 白名单时 `su` 必失败）。
 */
object CaptureReport {

    /** 与 AndroidManifest 里 `CaptureReportReceiver` 的 action 一致。 */
    const val ACTION = "io.github.ldxm666.mapadkiller.CAPTURE_STATUS"

    /** 接收端落盘用的 prefs 名。 */
    const val P = "capture_report"

    const val K_KNOWN = "known"
    const val K_STAGE = "stage"
    const val K_CAPTURED = "captured"
    const val K_FILES = "files"
    const val K_VERSION = "version"
    const val K_AT = "at"
    const val K_ERROR = "error"

    /** stage 取值：state=启动时上报现状，done=抓取完成，failed=抓取失败，pending=面板已请求。 */
    const val S_STATE = "state"
    const val S_DONE = "done"
    const val S_FAILED = "failed"
    const val S_PENDING = "pending"

    @Volatile
    private var sCtx: Context? = null

    /** 记下宿主 Application（抓取线程里没有 Activity，用它发包）。 */
    @JvmStatic
    fun attach(ctx: Context?) {
        try {
            if (ctx != null) sCtx = ctx.applicationContext
        } catch (ignored: Throwable) { }
    }

    /**
     * 发一条状态回报。失败只记日志，绝不影响抓取本身。
     *
     * @param stage  [S_STATE] / [S_DONE] / [S_FAILED]
     * @param files  抓到的配置文件数
     * @param error  失败原因（可为 null）
     */
    @JvmStatic
    fun send(ctx: Context?, stage: String?, captured: Boolean, files: Int,
             version: String?, error: String?) {
        val c: Context? = ctx ?: sCtx
        if (c == null) return
        try {
            val i = Intent(ACTION)
            i.setPackage(MainHook.PKG_SELF)
            i.putExtra("stage", stage)
            i.putExtra("captured", captured)
            i.putExtra("files", files)
            i.putExtra("version", if (version == null) "" else version)
            i.putExtra("error", if (error == null) "" else error)
            i.putExtra("ts", System.currentTimeMillis())
            c.sendBroadcast(i)
            H.log(Log.INFO, MainHook.TAG, "event=capture_report stage=" + stage
                    + " captured=" + captured + " files=" + files)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_report_failed err=" + t)
        }
    }
}
