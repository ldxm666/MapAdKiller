package io.github.ldxm666.bmapclean

import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.HashMap
import java.util.HashSet

/** Native STY level tables: identify point icons/text from the host's own drawing
 * definitions, retain feature identities and non-point drawing references.
 * Hidden references use the host's existing uint32 -1 sentinel. Native readers
 * expect a draw array for every feature; an empty array is not an equivalent rule.
 * Definition, geometry, palette and unknown fields remain unchanged.
 *
 * <p><b>22.1.0 起支持按分类隐藏。</b>每个绘制定义里带一个图标资源名（field 4，
 * 例如 {@code MapRes/meishi}、{@code MapRes/jiudian}），{@link #filterByCategories}
 * 用它给 feature 归类，再决定藏不藏。这样「只留地铁、关掉餐饮」是可行的，
 * 而 {@link #hideAll} 那个一刀切保留下来当总开关用。
 */
class BaiduLabelStyle private constructor() {

    /** 一条判定：给定 feature id 与它引用的点图标名，返回是否隐藏。 */
    interface Rule {
        fun hide(feature: Int, icons: Set<String>): Boolean
    }

    class Result {
        @JvmField
        val bytes: ByteArray
        @JvmField
        val levels: Int
        @JvmField
        val features: Int
        @JvmField
        val drawings: Int

        constructor(bytes: ByteArray, levels: Int, features: Int, drawings: Int) {
            this.bytes = bytes
            this.levels = levels
            this.features = features
            this.drawings = drawings
        }
    }

    private class Cursor(val bytes: ByteArray) {
        var pos = 0
        var start = 0
        var payloadStart = 0
        var payloadSize = 0
        var number = 0
        var wire = 0
        var value = 0L

        fun varint(): Long {
            var v = 0L
            for (i in 0 until 10) {
                if (pos >= bytes.size) throw bad("Truncated STY varint")
                val b = bytes[pos++].toInt() and 255
                if (i == 9 && b > 1) throw bad("Overflowed STY varint")
                v = v or ((b and 127).toLong() shl (i * 7))
                if (b < 128) return v
            }
            throw bad("Invalid STY varint")
        }

        fun next(): Boolean {
            if (pos == bytes.size) return false
            start = pos
            val tag = varint()
            if (tag <= 0L || tag > 0xffffffffL || (tag ushr 3) > 0x1fffffffL) throw bad("Invalid STY field")
            number = (tag ushr 3).toInt()
            wire = (tag and 7L).toInt()
            if (number == 0) throw bad("Invalid STY field number")
            if (wire == 0) value = varint()
            else if (wire == 2) {
                val size = varint()
                if (size < 0L || size > bytes.size - pos) throw bad("Truncated STY payload")
                payloadStart = pos
                payloadSize = size.toInt()
                pos += payloadSize
            } else if (wire == 1 || wire == 5) {
                val size = if (wire == 1) 8 else 4
                if (size > bytes.size - pos) throw bad("Truncated STY fixed field")
                pos += size
            } else throw bad("Unsupported STY wire type")
            return true
        }

        fun payload(): ByteArray {
            return java.util.Arrays.copyOfRange(bytes, payloadStart, payloadStart + payloadSize)
        }

        fun copy(out: ByteArrayOutputStream) {
            out.write(bytes, start, pos - start)
        }
    }

    companion object {

        private const val NO_DRAW = 0xffffffffL

        private val PRESERVED: Set<Int> = preserved()

        private fun preserved(): Set<Int> {
            val out = HashSet<Int>()
            for (id in BaiduLabelSettings.PRESERVED_FEATURES) out.add(id)
            return Collections.unmodifiableSet(out)
        }

        @JvmStatic
        fun hiddenFeatures(mask: Int): Set<Int> {
            val out = HashSet<Int>()
            if ((mask and 1) != 0) for (id in BaiduLabelSettings.FEATURES[0]) out.add(id)
            return out
        }

        @JvmStatic
        fun filter(source: ByteArray, hidden: Set<Int>): ByteArray {
            if (hidden.isEmpty()) return source
            return transform(source, object : Rule {
                override fun hide(feature: Int, icons: Set<String>): Boolean {
                    return hidden.contains(feature)
                }
            }).bytes
        }

        /** 一刀切：所有点绘制都隐藏（PRESERVED 里的地名/路名除外）。总开关用。 */
        @JvmStatic
        fun hideAll(source: ByteArray): Result {
            return transform(source, null)
        }

        /**
         * 按分类隐藏。
         *
         * @param hiddenCats 要隐藏的分类 key 集合（{@link BaiduLabelSettings#CATS} 的 key
         *                   加上 {@link BaiduLabelSettings#KEY_OTHER}）。空集合 = 一类都不藏。
         */
        @JvmStatic
        fun filterByCategories(source: ByteArray, hiddenCats: Set<String>?): Result {
            return transform(source, object : Rule {
                override fun hide(feature: Int, icons: Set<String>): Boolean {
                    if (hiddenCats == null || hiddenCats.isEmpty()) return false
                    if (icons.isEmpty()) return hiddenCats.contains(BaiduLabelSettings.KEY_OTHER)
                    for (icon in icons) {
                        val cat = BaiduLabelSettings.categoryOf(icon)
                        if (cat != null && hiddenCats.contains(cat)) return true
                    }
                    return false
                }
            })
        }

        @JvmStatic
        fun transform(source: ByteArray?, rule: Rule?): Result {
            if (source == null || source.size > 16 * 1024 * 1024) throw bad("Invalid STY size")
            var root = Cursor(source)
            val points = HashSet<Long>()
            val kinds = HashMap<Long, Int>()
            val icons = HashMap<Long, String>()
            var definitions = 0
            var levels = 0
            while (root.next()) {
                if (root.number == 2 && root.wire == 2) levels++
                if (root.number == 3 && root.wire == 2) {
                    definitions++
                    val draws = Cursor(root.payload())
                    while (draws.next()) {
                        if (draws.wire != 2 || draws.number < 1 || draws.number > 7) continue
                        val style = Cursor(draws.payload())
                        var identity: Long? = null
                        var icon: String? = null
                        while (style.next()) {
                            if (style.number == 1 && style.wire == 0) {
                                if (identity != null) throw bad("Duplicate STY drawing identity")
                                identity = style.value
                            } else if (style.number == 4 && style.wire == 2 && icon == null) {
                                // 图标资源名（MapRes/xxx）。不是每个绘制都有，没有就留 null。
                                icon = String(style.payload(), Charsets.UTF_8)
                            }
                        }
                        if (identity == null) throw bad("Missing STY drawing identity")
                        val previous = kinds.put(identity, draws.number)
                        if (previous != null && previous.toInt() != draws.number) throw bad("Conflicting STY drawing type")
                        if (draws.number == 1 || draws.number == 2) {
                            points.add(identity)
                            if (icon != null && icon.length > 0) icons.put(identity, icon)
                        }
                    }
                }
            }
            if (definitions != 1 || levels < 1 || levels > 64) throw bad("Unsupported STY scene schema")
            val counts = intArrayOf(0, 0)
            root = Cursor(source)
            val out = ByteArrayOutputStream(source.size)
            while (root.next()) {
                if (root.number == 2 && root.wire == 2) message(out, 2, filterLevel(root.payload(), points, icons, rule, counts))
                else root.copy(out)
            }
            return Result(if (counts[1] == 0) source else out.toByteArray(), levels, counts[0], counts[1])
        }

        private fun filterLevel(source: ByteArray, points: Set<Long>, icons: Map<Long, String>, rule: Rule?, counts: IntArray): ByteArray {
            val level = Cursor(source)
            val out = ByteArrayOutputStream(source.size)
            while (level.next()) {
                if (level.number == 1 && level.wire == 2) {
                    val original = level.payload()
                    val filtered = filterRule(original, points, icons, rule, counts)
                    if (filtered === original) level.copy(out) else message(out, 1, filtered)
                } else level.copy(out)
            }
            return out.toByteArray()
        }

        private fun filterRule(source: ByteArray, points: Set<Long>, icons: Map<Long, String>, rule: Rule?, counts: IntArray): ByteArray {
            val scan = Cursor(source)
            var feature: Int? = null
            val names = HashSet<String>()
            while (scan.next()) {
                if (scan.number == 1 && scan.wire == 0) {
                    if (feature != null || scan.value < 0L || scan.value > 0xffffffffL) throw bad("Invalid STY feature identity")
                    feature = scan.value.toInt()
                } else if (scan.number == 2) {
                    // 先把这条规则引用的点图标名收齐，供分类判定。
                    if (scan.wire == 0) collectName(scan.value, points, icons, names)
                    else if (scan.wire == 2) {
                        val values = Cursor(scan.payload())
                        while (values.pos < values.bytes.size) collectName(values.varint(), points, icons, names)
                    }
                }
            }
            if (feature == null) throw bad("Missing STY feature identity")
            if (PRESERVED.contains(feature)) return source
            // rule == null ⇒ 一刀切（隐藏所有点）。否则交给分类判定。
            if (rule != null && !rule.hide(feature, names)) return source
            val ruleCursor = Cursor(source)
            val out = ByteArrayOutputStream(source.size)
            var removed = 0
            while (ruleCursor.next()) {
                if (ruleCursor.number == 2 && ruleCursor.wire == 0 && points.contains(ruleCursor.value)) {
                    removed++
                    writeVarint(out, 2L shl 3)
                    writeVarint(out, NO_DRAW)
                } else if (ruleCursor.number == 2 && ruleCursor.wire == 2) {
                    val packed = ruleCursor.payload()
                    val values = Cursor(packed)
                    val keep = ByteArrayOutputStream(packed.size)
                    val before = removed
                    while (values.pos < packed.size) {
                        val start = values.pos
                        val value = values.varint()
                        if (points.contains(value)) {
                            removed++
                            writeVarint(keep, NO_DRAW)
                        } else keep.write(packed, start, values.pos - start)
                    }
                    if (before == removed) ruleCursor.copy(out)
                    else if (keep.size() != 0) message(out, 2, keep.toByteArray())
                } else ruleCursor.copy(out)
            }
            if (removed == 0) return source
            counts[0]++
            counts[1] += removed
            return out.toByteArray()
        }

        private fun collectName(identity: Long, points: Set<Long>, icons: Map<Long, String>, into: MutableSet<String>) {
            if (!points.contains(identity)) return
            val icon = icons.get(identity)
            if (icon != null) into.add(icon)
        }

        private fun message(out: ByteArrayOutputStream, number: Int, data: ByteArray) {
            writeVarint(out, ((number shl 3) or 2).toLong())
            writeVarint(out, data.size.toLong())
            out.write(data, 0, data.size)
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            do {
                val b = (v and 127L).toInt()
                v = v ushr 7
                out.write(if (v == 0L) b else b or 128)
            } while (v != 0L)
        }

        private fun bad(why: String): IllegalArgumentException {
            return IllegalArgumentException(why)
        }
    }
}
