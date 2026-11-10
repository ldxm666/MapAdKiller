package io.github.ldxm666.tmapclean

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Business POIs, derived from the host's own point-style -> icon catalogue.
 * Tencent 11.7.0 uses FlatBuffers (mapconfig_crc.bin); point styles are group 5,
 * type 0x10000. Their text-style references are SHARED, so never disable fonts,
 * line styles, the annotation layer, or a guessed numeric category range.
 */
object TmapPoiStyles {
    @JvmField val KEYS = arrayOf("ui_poi_food", "ui_poi_shopping", "ui_poi_medical",
        "ui_poi_education", "ui_poi_transport", "ui_poi_hotel", "ui_poi_leisure",
        "ui_poi_finance", "ui_poi_services")
    @JvmField val TITLES = arrayOf("餐饮", "购物", "医疗与急救", "学校与教育",
        "交通设施", "住宿", "景点与休闲", "金融", "生活与公共服务")

    // These are resource NAMES, not catalogue indices. Numeric image indices can
    // change after a skin update. Unknown/ambiguous icons deliberately stay visible.
    private val icons = arrayOf(
        setOf("45", "39", "165", "166", "167", "281", "282", "283", "md_10"),
        setOf("32", "33", "171", "172", "173", "220", "258", "260", "265", "md_5"),
        setOf("25", "42", "296", "md_4"),
        setOf("6", "126", "299", "300", "md_3"),
        setOf("4", "14", "16", "18", "20", "50", "51", "55", "59", "170", "177", "217", "218", "219", "223", "291", "md_9"),
        setOf("26", "73", "240", "245", "259"),
        setOf("8", "11", "13", "17", "24", "29", "37", "114", "306", "307", "308", "309", "310", "311", "312", "315", "md_1", "md_6"),
        setOf("65", "66", "67", "68", "69", "70", "71", "74"),
        setOf("30", "41", "43", "61", "62")
    )

    @JvmStatic fun category(icon: String?): Int {
        if (icon == null || !icon.endsWith(".png")) return -1
        val name = icon.removeSuffix(".png")
        return icons.indexOfFirst { name in it }
    }

    /** Malformed/new formats return no IDs; the caller then keeps the native map. */
    @JvmStatic fun catalogue(data: ByteArray): Map<Int, Int> = try {
        require(data.size in 32..(8 * 1024 * 1024))
        val f = Flat(data)
        val root = f.u(0)
        require(f.number(root, 0) == 12000000)
        val groups = f.vector(f.field(root, 2), 8)
        require(groups.isNotEmpty())
        var common: MutableMap<Int, Int>? = null
        for (group in groups) {
            val images = HashMap<Int, String>()
            for (image in f.vector(f.field(group, 2), 4096)) {
                val id = f.number(image, 0)
                require(id > 0 && !images.containsKey(id))
                images[id] = f.string(f.field(image, 1))
            }
            val classified = HashMap<Int, Int>()
            for (point in f.vector(f.field(group, 5), 8192)) {
                val id = f.number(point, 0)
                require(id and -65536 == 65536) // Point styles only.
                val categories = HashSet<Int>()
                for (rule in f.vector(f.field(point, 2), 128)) {
                    val icon = f.number(rule, 3, -1)
                    val c = category(images[icon])
                    if (c >= 0) categories.add(c)
                }
                // A style that changes business category across zoom levels is
                // ambiguous. It must not be hidden by either category.
                if (categories.size == 1) classified[id] = categories.first()
            }
            val previous = common
            if (previous == null) common = classified
            else previous.entries.removeAll { classified[it.key] != it.value }
        }
        common ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    /** Preserve the complete original style and add ONLY private point-style masks. */
    @JvmStatic fun filter(original: String, catalogue: Map<Int, Int>, mask: Int): String? {
        if (mask == 0 || catalogue.isEmpty()) return null
        val selected = catalogue.filter { (id, category) ->
            id and -65536 == 65536 && category in KEYS.indices && mask and (1 shl category) != 0
        }.keys.sorted()
        if (selected.isEmpty()) return null
        return try {
            val root = parseStyle(original)
            if (root.optString("version") != "1.0") return null
            val base = root.optJSONObject("base") ?: return null
            if (base.has("disable") && base.optJSONObject("disable") == null) return null
            val disable = base.optJSONObject("disable") ?: JSONObject().also { base.put("disable", it) }
            for (id in selected) disable.put(id.toString(), JSONArray().put(
                JSONObject().put("range", JSONArray().put(0).put(30)).put("disable", 1)))
            root.toString()
        } catch (_: Exception) { null }
    }

    /** Tencent's native reader accepts comments and trailing commas. Android's
     * JSONObject accepts comments, but rejects trailing OBJECT commas. Normalize
     * only those tokens, never punctuation inside a quoted style/resource string.
     */
    @JvmStatic fun parseStyle(source: String): JSONObject {
        require(source.length <= 2 * 1024 * 1024)
        val clean = StringBuilder(source.length)
        var quote = '\u0000'
        var escaped = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quote != '\u0000') {
                clean.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = '\u0000'
                i++
            } else if (c == '"' || c == '\'') {
                quote = c; clean.append(c); i++
            } else if (c == '/' && source.getOrNull(i + 1) == '/') {
                clean.append(' '); i += 2
                while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
            } else if (c == '/' && source.getOrNull(i + 1) == '*') {
                val end = source.indexOf("*/", i + 2)
                require(end >= 0)
                clean.append(' '); i = end + 2
            } else { clean.append(c); i++ }
        }
        require(quote == '\u0000')
        val normalized = StringBuilder(clean.length)
        quote = '\u0000'; escaped = false; i = 0
        while (i < clean.length) {
            val c = clean[i]
            if (quote != '\u0000') {
                normalized.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c; normalized.append(c)
            } else if (c == ',') {
                var next = i + 1
                while (next < clean.length && clean[next].isWhitespace()) next++
                if (next == clean.length || (clean[next] != '}' && clean[next] != ']')) normalized.append(c)
            } else normalized.append(c)
            i++
        }
        return JSONObject(normalized.toString())
    }

    private class Flat(private val b: ByteArray) {
        private val v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private fun check(p: Int, size: Int) { require(p >= 0 && size >= 0 && p.toLong() + size <= b.size) }
        fun u(p: Int): Int { check(p, 4); return v.getInt(p).also { require(it >= 0) } }
        private fun ushort(p: Int): Int { check(p, 2); return v.getShort(p).toInt() and 65535 }
        fun field(table: Int, index: Int): Int {
            check(table, 4)
            val vt = table.toLong() - v.getInt(table)
            require(vt in 0..(b.size - 4).toLong())
            val p = vt.toInt()
            val size = ushort(p)
            val objectSize = ushort(p + 2)
            require(size >= 4 && size % 2 == 0 && objectSize >= 4)
            check(p, size); check(table, objectSize)
            if (4 + index * 2 >= size) return 0
            val offset = ushort(p + 4 + index * 2)
            if (offset == 0) return 0
            require(offset >= 4 && offset + 4 <= objectSize)
            return table + offset
        }
        fun number(table: Int, index: Int, default: Int = 0): Int {
            val p = field(table, index)
            return if (p == 0) default else { check(p, 4); v.getInt(p) }
        }
        private fun reference(p: Int): Int {
            require(p > 0)
            val out = p.toLong() + u(p)
            require(out > p && out <= b.size - 4L)
            return out.toInt()
        }
        fun vector(p: Int, max: Int): List<Int> {
            val start = reference(p)
            val size = u(start)
            require(size <= max)
            check(start + 4, size * 4)
            return (0 until size).map { reference(start + 4 + it * 4) }
        }
        fun string(p: Int): String {
            val start = reference(p)
            val size = u(start)
            require(size <= 1024)
            check(start + 4, size + 1)
            require(b[start + 4 + size] == 0.toByte())
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, start + 4, size)).toString()
        }
    }
}
