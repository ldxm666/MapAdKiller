package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

import java.util.HashMap

/**
 * 配置持久化层（内置版 LSPatch 的救命通道）。
 *
 * <h3>为什么需要它</h3>
 * 15.21 免 root 内置版里，LSPatch 的 `getRemotePreferences` 走
 * `/data/data/<host>/databases/lspatch-xposed-remote.db`。实测在真机上会出现
 * `SQLiteCantOpenDatabaseException`（DB 被旧 uid 占用 / 文件锁 / 权限变化），
 * 此时：
 * <pre>
 *   event=cfg_write_fail key=tab_探索 svc=ok stage=svc reason=io err=SQLiteCantOpenDatabaseException
 *   event=cfg_write_fail key=tab_探索 svc=ok stage=prefs reason=commit_false
 *   event=cfg_write_ok   key=tab_探索 via=module_prefs   ← 只是内存态
 * </pre>
 * 结果就是「面板里关掉 → 当场生效 → 重进恢复原样」。
 *
 * <h3>策略</h3>
 * <ul>
 *   <li>内置版：**本地 `mapclean_cfg` 为准**（宿主自己的 SharedPreferences，永远可写），
 *       远程只做 best-effort 镜像；读取时 remote 打底、local 覆盖，避免失败的镜像写入
 *       用旧值盖掉已成功保存到本地的新值。</li>
 *   <li>模块版（LSPosed）：保持原来的 remote 优先逻辑，不受影响。</li>
 *   <li>每次写入都做 write→read 自校验，不一致打 `event=cfg_roundtrip_fail`。</li>
 * </ul>
 */
object CfgStore {

    const val LOCAL_PREFS = "mapclean_cfg"

    @JvmStatic
    fun local(): SharedPreferences? {
        try {
            val c = Config.hostCtx() ?: return null
            return c.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            return null
        }
    }

    @JvmStatic
    fun remote(): SharedPreferences? {
        try {
            val m = H.module ?: return null
            return m.getRemotePreferences(Config.PREF_GROUP)
        } catch (t: Throwable) {
            return null
        }
    }

    /** 内置版（LSPatch）判定：mapclean.App.embedded()，反射调用避免编译期依赖。 */
    @JvmStatic
    fun embedded(): Boolean {
        // ① 宿主 APK 的 assets/lspatch/config.json —— 最早可用、不依赖 App 是否已 attach
        try {
            if (HostInfo.isEmbedded()) return true
        } catch (ignored: Throwable) {}
        // ② App.embedded()（attachEmbedded 之后才为 true）
        try {
            val c = Class.forName("io.github.ldxm666.mapclean.App")
            val v = c.getMethod("embedded").invoke(null)
            return v is Boolean && v
        } catch (t: Throwable) {
            return false
        }
    }

    /** 内置版 local 为准；模块版保持 remote 优先。未设置的键仍从另一后端补齐。 */
    @JvmStatic
    fun readAll(): Map<String, Any?> {
        val emb = embedded()
        // 早期 remote 是暂用值；现在 Context 可用时，先完成旧配置迁移及本地后端切换。
        if (emb) Config.prefs()
        var localValues: Map<String, *> = emptyMap<String, Any?>()
        var remoteValues: Map<String, *> = emptyMap<String, Any?>()
        val lp = local()
        if (lp != null) {
            try { localValues = lp.all } catch (ignored: Throwable) {}
        }
        val rp = remote()
        if (rp != null) {
            try { remoteValues = rp.all } catch (ignored: Throwable) {}
        }
        return mergeValues(emb, localValues, remoteValues)
    }

    @JvmStatic
    fun mergeValues(embedded: Boolean, local: Map<String, *>, remote: Map<String, *>): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        out.putAll(if (embedded) remote else local)
        out.putAll(if (embedded) local else remote)
        return out
    }

    @JvmStatic
    fun read(key: String, fallback: Boolean): Boolean {
        try {
            val all = readAll()
            val v = all[key]
            return if (v is Boolean) v else fallback
        } catch (t: Throwable) {
            return fallback
        }
    }

    @JvmStatic fun writeString(key: String, value: String): Boolean {
        val lp = local()
        val rp = remote()
        val primary = (if (embedded()) lp else rp ?: lp) ?: return false
        if (!io.github.ldxm666.mapclean.ToolOrderCodec.persist(primary, key, value)) return false
        val mirror = if (primary === lp) rp else lp
        if (mirror != null && mirror !== primary) io.github.ldxm666.mapclean.ToolOrderCodec.persist(mirror, key, value)
        return try { readAll()[key] == value } catch (_: Throwable) { false }
    }

    /**
     * 写布尔键：内置版以 local 为准 + remote 镜像；模块版 remote 优先 + local 镜像。
     * 返回「至少一个后端 commit 成功」。
     */
    @JvmStatic
    fun write(key: String, value: Boolean): Boolean {
        val emb = embedded()
        val okLocal = commit(local(), key, value)
        val okRemote = commit(remote(), key, value)
        val ok = if (emb) okLocal else (okRemote || okLocal)
        // write→read 自校验：用与读取完全相同的合并路径再读一次
        try {
            val back = read(key, !value)
            if (back != value) {
                H.log(Log.WARN, MainHook.TAG, "event=cfg_roundtrip_fail key=" + key
                        + " want=" + value + " got=" + back + " local=" + okLocal + " remote=" + okRemote)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=cfg_write key=" + key + " value=" + value
                        + " backend=" + (if (emb) "local" else "remote") + " local=" + okLocal
                        + " remote=" + okRemote + " commit=" + ok)
            }
        } catch (ignored: Throwable) {}
        return ok
    }

    /** 合并读 int，与布尔使用同一优先级。 */
    @JvmStatic
    fun readInt(key: String, fallback: Int): Int {
        try {
            val all = readAll()
            val v = all[key]
            return if (v is Int) v else fallback
        } catch (t: Throwable) {
            return fallback
        }
    }

    /**
     * 写 int 键。和 [write] 同一套语义：
     * 内置版以 local 为准 + remote 镜像，模块版 remote 优先 + local 镜像。
     *
     * <p>为什么必须补这个：`Config.put(String,int)` 以前直接写 remote，
     * 内置版里 remote 是只读代理，commit 返回 true 但下次读不回来 ——
     * 用户调好的「自动巡航触发值」关一次悬浮窗就回到默认。
     */
    @JvmStatic
    fun writeInt(key: String, value: Int): Boolean {
        val emb = embedded()
        val okLocal = commitInt(local(), key, value)
        val okRemote = commitInt(remote(), key, value)
        val ok = if (emb) okLocal else (okRemote || okLocal)
        try {
            val back = readInt(key, if (value == 0) 1 else 0)
            H.log(if (back == value) Log.INFO else Log.WARN, MainHook.TAG,
                    "event=cfg_write_int key=" + key + " value=" + value + " readback=" + back
                            + " backend=" + (if (emb) "local" else "remote")
                            + " local=" + okLocal + " remote=" + okRemote)
        } catch (ignored: Throwable) {}
        return ok
    }

    private fun commitInt(p: SharedPreferences?, key: String, value: Int): Boolean {
        if (p == null) return false
        try {
            return p.edit().putInt(key, value).commit()
        } catch (t: Throwable) {
            return false
        }
    }

    private fun commit(p: SharedPreferences?, key: String, value: Boolean): Boolean {
        if (p == null) return false
        try {
            return p.edit().putBoolean(key, value).commit()
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=cfg_write_fail key=" + key
                    + " backend=" + (if (p === local()) "local" else "remote")
                    + " err=" + t.javaClass.simpleName)
            return false
        }
    }
}
