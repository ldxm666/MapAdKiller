package io.github.ldxm666.mapclean

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Versioned user settings only. Preference groups keep identically named keys separate. */
class ConfigBackup private constructor() {

    interface Store {
        fun read(group: String): Map<String, *>

        /** A null value removes the key, preserving an unset/default preference. */
        fun write(group: String, values: Map<String, Any?>): Boolean
    }

    companion object {

        const val AMAP = "amap_enhancer_config"
        const val BAIDU = "bmapclean_config"
        const val UI = "mak_ui"
        const val LEARNED = "sdk_learned"

        @JvmStatic
        fun encode(store: Store, schema: Map<String, Set<String>>, app: String?): JSONObject {
            val groups = JSONObject()
            for (group in schema.entries) {
                val stored = store.read(group.key)
                val values = JSONObject()
                for (key in group.value) {
                    val raw = stored[key]
                    val value = if (group.key == AMAP && key == FeedCardConfig.KEY && (raw == null || raw is Boolean))
                        FeedCardConfig.enabled(stored) else raw
                    if (value is Set<*> && LEARNED == key) values.put(key, JSONArray(value))
                    else if (value is String && key == ToolOrderCodec.KEY) values.put(key, ToolOrderCodec.encode(ToolOrderCodec.decode(value)))
                    else if (value == null || value is Boolean) values.put(key, if (value == null) JSONObject.NULL else value)
                    else throw IllegalArgumentException("配置类型错误：" + key)
                }
                groups.put(group.key, values)
            }
            return JSONObject().put("_app", app).put("_ver", 2).put("groups", groups)
        }

        @JvmStatic
        fun decode(backup: JSONObject, schema: Map<String, Set<String>>): MutableMap<String, MutableMap<String, Any?>> {
            val result: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()
            if (backup.has("groups")) {
                if (backup.optInt("_ver", 0) != 2) throw IllegalArgumentException("不支持的配置版本")
                val groups = backup.getJSONObject("groups")
                for (group in schema.entries) {
                    if (!groups.has(group.key)) continue
                    val values = decodeGroup(groups.getJSONObject(group.key), group.value)
                    if (group.key == AMAP && group.value.contains(FeedCardConfig.KEY)) FeedCardConfig.migrateImport(values)
                    if (values.isNotEmpty()) result[group.key] = values
                }
            } else {
                // Legacy flat exports contain AMap settings only; never change Baidu on import.
                val values = decodeGroup(backup, schema[AMAP])
                if (schema[AMAP]?.contains(FeedCardConfig.KEY) == true) FeedCardConfig.migrateImport(values)
                if (values.isNotEmpty()) result[AMAP] = values
            }
            if (result.isEmpty()) throw IllegalArgumentException("没有可识别的配置项")
            return result
        }

        private fun decodeGroup(obj: JSONObject, allowed: Set<String>?): MutableMap<String, Any?> {
            val result: MutableMap<String, Any?> = LinkedHashMap()
            if (allowed == null) return result
            for (key in allowed) {
                if (!obj.has(key)) continue
                var value: Any? = obj.get(key)
                if (value === JSONObject.NULL) value = null
                else if (value is JSONArray && LEARNED == key) {
                    val list = value
                    val roots: MutableSet<String> = LinkedHashSet()
                    for (i in 0 until list.length()) {
                        val item = list.get(i)
                        if (item !is String) throw IllegalArgumentException("SDK 清单类型错误")
                        roots.add(item)
                    }
                    value = roots
                } else if (value is String && key == ToolOrderCodec.KEY) {
                    value = ToolOrderCodec.encode(ToolOrderCodec.decode(value))
                } else if (value !is Boolean) throw IllegalArgumentException("开关类型错误：" + key)
                result[key] = value
            }
            return result
        }

        @JvmStatic
        fun apply(store: Store, groups: Map<String, Map<String, Any?>>): Int {
            val before: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()
            for (group in groups.entries) {
                val current = store.read(group.key)
                val values: MutableMap<String, Any?> = LinkedHashMap()
                for (key in group.value.keys) values[key] = current[key]
                before[group.key] = values
            }
            val attempted: MutableList<String> = ArrayList<String>()
            var count = 0
            try {
                for (group in groups.entries) {
                    attempted.add(group.key)
                    if (!store.write(group.key, group.value)) throw IllegalStateException("配置写入失败")
                    count += group.value.size
                }
                return count
            } catch (failure: RuntimeException) {
                var restored = true
                for (i in attempted.size - 1 downTo 0) {
                    val group = attempted[i]
                    try {
                        val snapshot = before[group]
                        restored = restored and (snapshot != null && store.write(group, snapshot))
                    } catch (ignored: RuntimeException) {
                        restored = false
                    }
                }
                throw IllegalStateException(
                    if (restored) "恢复失败，已保留原配置" else "恢复失败，部分配置回退失败，请重新导入", failure
                )
            }
        }

        // ══════════════════════════════════════════════════════════════════════════
        // 文件导入 / 导出（SAF：ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT）
        // ══════════════════════════════════════════════════════════════════════════
        //
        // 为什么用 SAF 而不是直接写路径：免 root 内置版没有存储权限，也不该申请；
        // SAF 让用户自己选位置，系统把流交给我们 —— 零权限、零路径猜测。
        //
        // 旧格式兼容：encode() 产出的就是 {"_app":..,"_ver":2,"groups":{..}}，
        // 所以**历史上复制粘贴出去的文本本身就是合法 JSON**，可以直接当文件导入。
        // 另外额外接受 {"app":..,"version":..,"values":{..}} 这种扁平形态（见 decodeAny）。

        /** requestCode：导出 / 导入。 */
        const val REQ_EXPORT = 0x4A01
        const val REQ_IMPORT = 0x4A02

        /** 超过这个大小直接拒绝（防误选大文件）。 */
        const val MAX_BYTES: Long = 256L * 1024L

        /** 由设置页在启动时绑定一次（Store/schema/app 都在那边）。 */
        @Volatile
        private var boundStore: Store? = null

        @Volatile
        private var boundSchema: Map<String, Set<String>>? = null

        @Volatile
        private var boundApp = "MapAdKiller"

        /** 导入预览（解析成功但尚未写入）。 */
        @Volatile
        private var pending: MutableMap<String, MutableMap<String, Any?>>? = null

        @Volatile
        private var pendingUnknown = ""

        @Volatile
        private var lastError: String? = null

        @JvmStatic
        fun bind(store: Store?, schema: Map<String, Set<String>>?, app: String?) {
            boundStore = store
            boundSchema = schema
            if (!app.isNullOrEmpty()) boundApp = app
        }

        /** 导出：弹系统「保存到…」对话框。 */
        @JvmStatic
        fun startExport(a: Activity, reqCode: Int) {
            try {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/json"
                i.putExtra(Intent.EXTRA_TITLE, "MapAdKiller-" + stamp + ".json")
                a.startActivityForResult(i, reqCode)
            } catch (t: Throwable) {
                lastError = "无法打开保存对话框：" + t.javaClass.simpleName
            }
        }

        /** 导入：弹系统「选择文件」对话框。 */
        @JvmStatic
        fun startImport(a: Activity, reqCode: Int) {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                // 有些文件管理器给 .json 的 MIME 是 application/octet-stream，
                // 用 */* + 内容校验更稳。
                i.type = "*/*"
                a.startActivityForResult(i, reqCode)
            } catch (t: Throwable) {
                lastError = "无法打开选择文件对话框：" + t.javaClass.simpleName
            }
        }

        /** 取走上一次 start* 的失败原因（没有就返回 null）。 */
        @JvmStatic
        fun takeError(): String? {
            val e = lastError
            lastError = null
            return e
        }

        /** 处理导出结果。返回**给用户看的文案**。 */
        @JvmStatic
        fun handleExportResult(a: Activity, reqCode: Int, resultCode: Int, data: Intent?): String {
            try {
                val uri = if (resultCode != Activity.RESULT_OK || data == null) null else data.data
                if (uri == null) {
                    return "已取消导出"
                }
                val store = boundStore
                val schema = boundSchema
                if (store == null || schema == null) return "导出失败：设置页未初始化"
                val out = encode(store, schema, boundApp).toString(2).toByteArray(Charsets.UTF_8)
                val os = a.contentResolver.openOutputStream(uri)
                    ?: return "导出失败：无法写入所选位置"
                try {
                    os.write(out)
                    os.flush()
                } finally {
                    try {
                        os.close()
                    } catch (ignored: Throwable) {
                    }
                }
                val rawName = try {
                    uri.lastPathSegment
                } catch (ignored: Throwable) {
                    null
                }
                var total = 0
                for (group in schema.entries) total += group.value.size
                return "已导出 " + (if (rawName.isNullOrEmpty()) "配置文件" else rawName) +
                        "（" + total + " 项，" + out.size + " 字节）"
            } catch (t: Throwable) {
                return "导出失败：" + reason(t)
            }
        }

        /**
         * 处理导入结果：**只解析 + 预览，不写入**。
         * 用户确认后调用 [confirmImport] 才真正写。
         */
        @JvmStatic
        fun handleImportResult(a: Activity, reqCode: Int, resultCode: Int, data: Intent?): String {
            pending = null
            pendingUnknown = ""
            try {
                val uri = if (resultCode != Activity.RESULT_OK || data == null) null else data.data
                if (uri == null) {
                    return "已取消导入"
                }
                val raw = readAll(a, uri)
                    ?: return "导入失败：无法读取文件（或超过 " + (MAX_BYTES / 1024) + " KB）"
                val text = String(raw, Charsets.UTF_8).trim()
                if (text.isEmpty()) return "导入失败：文件是空的"
                if (text[0] != '{') return "导入失败：不是 JSON（文件开头不是 { ）"

                val root: JSONObject
                try {
                    root = JSONObject(text)
                } catch (t: Throwable) {
                    return "导入失败：不是合法 JSON（" + reason(t) + "）"
                }
                val schema = boundSchema ?: return "导入失败：设置页未初始化"

                val groups = decodeAny(root, schema)
                if (groups == null || groups.isEmpty()) {
                    return "导入失败：缺少 values/groups 字段（这个文件不是本模块导出的）"
                }
                pending = groups

                var known = 0
                var unknown = 0
                val uk = StringBuilder()
                for (g in groups.entries) {
                    val allowed = schema[g.key]
                    for (k in g.value.keys) {
                        if (allowed != null && allowed.contains(k)) {
                            known++
                        } else {
                            unknown++
                            if (uk.length < 200) uk.append(if (uk.length == 0) "" else ", ").append(k)
                        }
                    }
                }
                pendingUnknown = if (unknown == 0) "" else "；忽略未知键 " + unknown + " 项（" + uk + "）"
                return "解析成功：可导入 " + known + " 项" + pendingUnknown + "。确认后会覆盖同名配置。"
            } catch (t: Throwable) {
                pending = null
                return "导入失败：" + reason(t)
            }
        }

        /** 有没有待确认的导入。 */
        @JvmStatic
        fun hasPendingImport(): Boolean = pending != null

        /** 待确认导入的分组明细，例如「高德 42 项 · 百度 18 项」。 */
        @JvmStatic
        fun pendingSummary(): String {
            val p = pending ?: return ""
            val sb = StringBuilder()
            for (group in p.entries) {
                if (sb.length > 0) sb.append(" · ")
                sb.append(label(group.key)).append(' ').append(group.value.size).append(" 项")
            }
            return sb.toString()
        }

        private fun label(group: String): String {
            if (AMAP == group) return "高德"
            if (BAIDU == group) return "百度"
            if (UI == group) return "界面"
            return group
        }

        /** 用户确认后真正写入；返回文案。 */
        @JvmStatic
        fun confirmImport(): String {
            val p = pending ?: return "没有待导入的配置"
            val store = boundStore ?: return "导入失败：设置页未初始化"
            try {
                val detail = pendingSummary()
                val n = apply(store, p)
                val extra = pendingUnknown
                pending = null
                pendingUnknown = ""
                return "已导入 " + n + " 项" + (if (detail.isEmpty()) "" else "（" + detail + "）") + extra
            } catch (t: Throwable) {
                pending = null
                return "导入失败：" + reason(t)
            }
        }

        // ── 内部工具 ────────────────────────────────────────────────────────────

        private fun readAll(a: Activity, uri: Uri): ByteArray? {
            try {
                val input = a.contentResolver.openInputStream(uri) ?: return null
                try {
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var n = input.read(buf)
                    var total = 0L
                    while (n > 0) {
                        total += n
                        if (total > MAX_BYTES) return null   // 超限直接放弃
                        bos.write(buf, 0, n)
                        n = input.read(buf)
                    }
                    return bos.toByteArray()
                } finally {
                    try {
                        input.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                return null
            }
        }

        /**
         * 接受两种形态：
         * <ol>
         *   <li>本模块的 {"_app":..,"_ver":2,"groups":{..}}（encode 产出，也是旧版复制粘贴的文本）；</li>
         *   <li>扁平形态 {"app":..,"version":..,"values":{..}} —— 归到 [AMAP] 组。</li>
         * </ol>
         */
        private fun decodeAny(
            root: JSONObject,
            schema: Map<String, Set<String>>
        ): MutableMap<String, MutableMap<String, Any?>>? {
            if (root.has("groups")) return decode(root, schema)
            if (root.has("values")) {
                val groups = JSONObject()
                groups.put(AMAP, root.getJSONObject("values"))
                return decode(JSONObject().put("_app", boundApp).put("_ver", 2).put("groups", groups), schema)
            }
            return null
        }

        private fun reason(t: Throwable?): String {
            if (t == null) return "未知错误"
            val m = t.message
            return if (m.isNullOrEmpty()) t.javaClass.simpleName else m
        }
    }
}
