package io.github.ldxm666.mapadkiller

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

import java.util.LinkedHashSet
import java.util.regex.Pattern

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
class LearnedProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        try {
            if (METHOD_PUT != method || extras == null) return out
            val raw = extras.getStringArrayList(KEY_ROOTS)
            if (raw == null || raw.isEmpty()) return out

            // 上报方是别的 App 进程，所以内容一律过白名单校验：
            // 只收包名形状的字符串，去重限量，避免被塞垃圾把正常功能拦坏。
            val clean = LinkedHashSet<String>()
            for (s in raw) {
                if (s == null) continue
                val v = s.trim().lowercase()
                if (v.length < 6 || v.length > 60) continue
                if (!PKGNAME.matcher(v).matches()) continue
                clean.add(v)
                if (clean.size >= MAX) break
            }
            if (clean.isEmpty()) return out

            val ctx = context ?: return out

            // 1) 本地 prefs 暂存 —— 这步一定成功，保证不丢
            val staged = LinkedHashSet<String>(readLocal(ctx))
            staged.addAll(clean)
            writeLocal(ctx, staged)

            // 2) 推 RemotePreferences（hook 侧读它）
            val merged = LinkedHashSet<String>()
            try {
                val s = App.svc()
                if (s != null) {
                    val old = s.getRemotePreferences(Config.PREF_GROUP)
                            .getStringSet("sdk_learned", null)
                    if (old != null) merged.addAll(old)
                }
            } catch (ignored: Throwable) {}
            merged.addAll(staged)

            val saved = App.writeStringSet("sdk_learned", merged)
            if (!saved) App.addPendingLearned(staged)

            out.putInt(KEY_TOTAL, merged.size)
            H.log(Log.INFO, TAG, "learned via provider: +" + clean.size
                    + " staged=" + staged.size + " total=" + merged.size
                    + (if (saved) " (saved)" else " (staged, service not ready)"))
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "provider call err " + t)
        }
        return out
    }

    // ---- ContentProvider 必须实现的其余成员（本 Provider 只当 RPC 用）----
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
        return null
    }

    override fun getType(u: Uri): String? {
        return null
    }

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        return null
    }

    override fun delete(u: Uri, s: String?, a: Array<String>?): Int {
        return 0
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int {
        return 0
    }

    companion object {

        const val AUTHORITY = "io.github.ldxm666.mapadkiller.learned"
        @JvmField
        val URI: Uri = Uri.parse("content://" + AUTHORITY)
        const val METHOD_PUT = "putRoots"
        const val KEY_ROOTS = "roots"
        const val KEY_TOTAL = "total"

        private val TAG = MainHook.TAG
        private const val MAX = 300
        private val PKGNAME: Pattern =
                Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,5}$")

        @JvmStatic
        fun readLocal(c: Context?): MutableSet<String> {
            val out = LinkedHashSet<String>()
            if (c == null) return out
            try {
                val s = c.getSharedPreferences("mak_learned", Context.MODE_PRIVATE)
                        .getStringSet("sdk_learned", null)
                if (s != null) out.addAll(s)
            } catch (ignored: Throwable) {}
            return out
        }

        @JvmStatic
        fun writeLocal(c: Context?, v: Set<String>) {
            if (c == null) return
            try {
                c.getSharedPreferences("mak_learned", Context.MODE_PRIVATE)
                        .edit().putStringSet("sdk_learned", LinkedHashSet(v)).commit()
            } catch (ignored: Throwable) {}
        }

        /** App 侧启动/绑定时调用：把暂存的推去 RemotePreferences */
        @JvmStatic
        fun flushToRemote(c: Context?) {
            val staged = readLocal(c)
            if (staged.isEmpty()) return
            try {
                val merged = LinkedHashSet<String>()
                val s = App.svc()
                if (s != null) {
                    val o = s.getRemotePreferences(Config.PREF_GROUP)
                            .getStringSet("sdk_learned", null)
                    if (o != null) merged.addAll(o)
                }
                merged.addAll(staged)
                if (App.writeStringSet("sdk_learned", merged)) {
                    H.log(Log.INFO, TAG, "learned flush ok total=" + merged.size)
                }
            } catch (ignored: Throwable) {}
        }
    }
}
