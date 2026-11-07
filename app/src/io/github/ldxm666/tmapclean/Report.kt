package io.github.ldxm666.tmapclean

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Hook 侧 → 设置页的回报通道。
 *
 * <p>为什么不走 RemotePreferences 回写：libxposed API 102 在 hook 侧拿到的是
 * <b>只读</b>实现，{@code edit().commit()} 必抛
 * {@code UnsupportedOperationException: Read only implementation}（真机实测，百度侧同一结论）。
 * 方向反过来就对 —— App 侧可写、hook 侧可读：App 侧写随机 token 进 RemotePreferences，
 * hook 侧读出来、带着它给模块 App 发显式广播。
 *
 * <p>本模块用它承载两件事：
 * <ul>
 *   <li>{@code stage=hook} —— 钩子已注入（设置页据此显示"已生效"，不依赖 LSPosed 服务状态）；</li>
 *   <li>{@code stage=tools} —— 在真实进程里学到的首页工具目录（id + 文案），
 *       设置页据此渲染逐项开关。</li>
 * </ul>
 */
object Report {

    const val ACTION = "io.github.ldxm666.tmapclean.REPORT"

    /** 距上次发送不足这个间隔就丢弃（避免高频刷新反复拉起设置页进程） */
    private const val MIN_GAP_MS = 1500L

    private val LAST_AT = java.util.HashMap<String, Long>()

    @Volatile
    private var appCtx: Context? = null

    @JvmStatic
    fun attach(ctx: Context?) {
        if (ctx != null) appCtx = ctx.applicationContext
    }

    /**
     * @param force true = 忽略节流（用于「钩子装好了」这种必须送达的一次性事件）
     */
    @JvmStatic
    fun send(ctx: Context?, stage: String, detail: String?, force: Boolean) {
        val c = ctx ?: appCtx
        if (c == null) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        synchronized(LAST_AT) {
            val last = LAST_AT[stage]
            if (!force && last != null && elapsed - last < MIN_GAP_MS) return
            LAST_AT.put(stage, elapsed)
        }
        val now = System.currentTimeMillis()
        // 1) 本进程直接落盘。免 root 内置版里钩子与悬浮球面板同进程，
        //    面板读的就是这个进程自己的 SharedPreferences —— 见 ReportReceiver#record。
        //    这条是「学到的首页工具目录」能到面板的唯一通路：宿主清单里没有本 receiver
        //    （LSPatch 不合并模块 manifest 的组件声明），广播到宿主没人收。
        try {
            ReportReceiver.record(c, stage, detail, now)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "local report failed: " + t)
        }
        // 2) 再广播给独立模块 App：非整合安装（普通 LSPosed）时设置页在另一个包里，
        //    它读的是自己的存储，只能靠这条广播。
        try {
            val i = Intent(ACTION)
            i.setPackage(MainHook.PKG_SELF)
            i.putExtra("token", Cfg.token())
            i.putExtra("stage", stage)
            i.putExtra("detail", detail)
            i.putExtra("ts", now)
            c.sendBroadcast(i)
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "report sent stage=" + stage)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "report send failed: " + t)
        }
    }
}
