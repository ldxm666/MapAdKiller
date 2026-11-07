package io.github.ldxm666.mapadkiller

import android.os.SystemClock
import android.util.Log

import java.util.ArrayList
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap

/**
 * 冷启动耗时探针：给**每一个** hook 计时，并按累计耗时排行榜输出。
 *
 * <h3>为什么要有这个类</h3>
 * 高德内置版冷启动实测 `am start -W` TotalTime ≈ 7.2~8.6 s，而模块自身安装只花 ~35 ms
 * （`event=install_done` 时间戳差）。也就是说"装 hook 很快，但 hook 一旦跑起来就慢"，
 * 光看安装日志永远查不出来 —— 必须知道**每个 hook 被调用多少次、累计吃掉多少毫秒**。
 *
 * <h3>为什么不在每个 hook 体里手写 nanoTime</h3>
 * 那样要改几十处、漏一处就少一个数据点，而且以后新增 hook 又会忘。这里挂在
 * [H.hookMethod] 这唯一一处包装点上：所有 hook 自动获得计时，零遗漏。
 *
 * <h3>开销</h3>
 * 每次调用 = 两次 `System.nanoTime()`（~20 ns/次）+ 三个 long 字段自增。
 * 只有**超过阈值**（默认 8 ms）的那几次才写 logcat，且每个 id 最多写 [LOG_CAP] 条，
 * 所以探针本身不会把日志刷爆、也不会成为新的瓶颈。
 * 关掉只需 `Perf.enabled = false`（一次 volatile 读即返回）。
 *
 * <h3>怎么读结果</h3>
 * 进程起来 ~20 s 后自动打一份排行榜：
 * <pre>
 *   event=slow_hook_rank n=1 id=amap_tool_default_raw calls=812 total_ms=4312 max_ms=39
 * </pre>
 * `total_ms` 是**包含子调用**的墙钟累计（外层 hook 的 proceed 里跑了内层 hook，
 * 两边都会计入），所以排行榜回答的是"谁在热路径上吃时间"，不是"谁自身耗时最多"。
 */
object Perf {

    private val TAG = MainHook.TAG

    /** 超过这个阈值才算"慢"，打一条 event=slow_hook。改成 0 可全量记录（仅排障用）。 */
    @JvmField
    @Volatile
    var thresholdNs: Long = 8L * 1000L * 1000L

    /** 总开关。关掉后 [begin] 直接返回 0，[end] 立刻返回。 */
    @JvmField
    @Volatile
    var enabled: Boolean = true

    /** 每个 hook id 最多打几条瞬时慢日志，防止一个死循环把 logcat 淹掉。 */
    private const val LOG_CAP = 4

    /** 排行榜里最多列多少个 id。 */
    private const val RANK_LIMIT = 25

    /** 本进程"起点"：类第一次被加载的时刻。用来给各阶段打绝对时间戳。 */
    @JvmField
    val START: Long = SystemClock.uptimeMillis()

    private val SLOTS: ConcurrentHashMap<String, Slot> = ConcurrentHashMap<String, Slot>()

    @Volatile
    private var dumperStarted = false

    /** 一个 hook 的计数器。字段故意用朴素 long：单线程热点写，原子性对排行没有意义。 */
    class Slot internal constructor(@JvmField val id: String) {
        @JvmField
        var calls: Long = 0L
        @JvmField
        var totalNs: Long = 0L
        @JvmField
        var maxNs: Long = 0L
        var logs: Int = 0          // 已打印的慢日志条数（只在慢路径上访问）

        fun calls(): Long = calls
        fun totalMs(): Long = totalNs / 1000000L
        fun maxMs(): Long = maxNs / 1000000L
    }

    /** 取（或建）某个 hook id 的计数器。只在**安装期**调用一次，拦截器里直接用返回值。 */
    @JvmStatic
    fun slot(id: String?): Slot {
        val s = SLOTS.get(id)
        if (s != null) return s
        val created = Slot(if (id == null) "?" else id)
        val raced = SLOTS.putIfAbsent(created.id, created)
        return if (raced != null) raced else created
    }

    /** 取时间戳；探针关掉时返回 0，[end] 见 0 直接返回，等于零成本。 */
    @JvmStatic
    fun begin(): Long {
        return if (enabled) System.nanoTime() else 0L
    }

    @JvmStatic
    fun end(slot: Slot?, beginNs: Long) {
        if (beginNs == 0L || slot == null) return
        val ns = System.nanoTime() - beginNs
        val calls = slot.calls + 1L
        slot.calls = calls
        slot.totalNs = slot.totalNs + ns
        if (ns > slot.maxNs) slot.maxNs = ns
        // 慢路径才拼字符串：阈值判断是一次 volatile 读，绝大多数调用到此为止。
        val threshold = thresholdNs
        if (ns >= threshold && slot.logs < LOG_CAP) {
            slot.logs++
            H.log(Log.WARN, TAG, "event=slow_hook id=" + slot.id
                    + " ms=" + (ns / 1000000L) + " call=" + calls
                    + " total_ms=" + (slot.totalNs / 1000000L))
        }
    }

    /** 阶段打点：`event=phase name=X at_ms=Y d_ms=Z`。用于把冷启动切成时间轴。 */
    @JvmStatic
    fun phase(name: String?) {
        try {
            val now = SystemClock.uptimeMillis()
            H.log(Log.INFO, TAG, "event=phase name=" + name
                    + " at_ms=" + (now - START) + " sys_ms=" + now)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 起一个后台守护线程，`delayMs` 后打一次排行榜。
     *
     * <p>用后台线程而不是主线程 postDelayed：首页稳定后主线程还有一大堆活儿，
     * 探针不该再插一脚。延迟默认 20 s —— 短了首页还没画完，长了用户已经滑动了。
     */
    @JvmStatic
    fun startDumper(delayMs: Long) {
        if (dumperStarted) return
        dumperStarted = true
        try {
            val t = Thread(object : Runnable {
                override fun run() {
                    try {
                        Thread.sleep(delayMs)
                    } catch (ignored: Throwable) {
                    }
                    dump()
                }
            }, "mapclean-perf-dump")
            t.isDaemon = true
            t.start()
        } catch (ignored: Throwable) {
        }
    }

    /** 打一份按累计耗时排序的排行榜。 */
    @JvmStatic
    fun dump() {
        try {
            val all = ArrayList<Slot>(SLOTS.values)
            java.util.Collections.sort(all, object : Comparator<Slot> {
                override fun compare(a: Slot, b: Slot): Int {
                    if (a.totalNs == b.totalNs) return a.id.compareTo(b.id)
                    return if (a.totalNs < b.totalNs) 1 else -1
                }
            })
            H.log(Log.INFO, TAG, "event=slow_hook_rank begin slots=" + all.size
                    + " uptime_ms=" + (SystemClock.uptimeMillis() - START))
            var n = 0
            for (s in all) {
                if (n >= RANK_LIMIT) break
                if (s.calls == 0L) continue
                n++
                H.log(Log.INFO, TAG, "event=slow_hook_rank n=" + n
                        + " id=" + s.id
                        + " calls=" + s.calls
                        + " total_ms=" + s.totalMs()
                        + " max_ms=" + s.maxMs())
            }
            H.log(Log.INFO, TAG, "event=slow_hook_rank end")
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=slow_hook_rank failed", t)
        }
    }
}
