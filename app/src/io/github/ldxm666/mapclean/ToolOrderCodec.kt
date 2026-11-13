package io.github.ldxm666.mapclean

import org.json.JSONArray

/** Typed, bounded preference/backup format. No dependency on a map adapter. */
object ToolOrderCodec {
    const val KEY = "ui_tools_order"
    private val ID = Regex("[0-9]{1,12}")

    @JvmStatic fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val array = try { JSONArray(raw) } catch (t: Throwable) {
            throw IllegalArgumentException("工具排序格式错误", t)
        }
        require(array.length() <= 256) { "工具排序项目过多" }
        val ids = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val id = array.get(i)
            require(id is String && ID.matches(id)) { "工具排序 ID 类型错误" }
            ids.add(id)
        }
        return ids.toList()
    }

    @JvmStatic fun read(raw: String?): List<String> = try { decode(raw) } catch (_: Throwable) { emptyList() }
    @JvmStatic fun encode(ids: List<String>): String = JSONArray(decode(JSONArray(ids).toString())).toString()

    /** SharedPreferences may update memory even when commit reports a disk failure. */
    @JvmStatic fun persist(prefs: android.content.SharedPreferences, key: String, value: String): Boolean {
        val had = try { prefs.contains(key) } catch (_: Throwable) { return false }
        val before = try { prefs.getString(key, null) } catch (_: Throwable) { return false }
        val success = try {
            prefs.edit().putString(key, value).commit() && prefs.getString(key, null) == value
        } catch (_: Throwable) { false }
        if (!success) try {
            val restore = prefs.edit()
            if (had) restore.putString(key, before) else restore.remove(key)
            restore.commit()
        } catch (_: Throwable) { }
        return success
    }
}
