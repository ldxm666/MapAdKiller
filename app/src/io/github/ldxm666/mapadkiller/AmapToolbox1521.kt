package io.github.ldxm666.mapadkiller

import org.json.JSONObject

import io.github.libxposed.api.XposedInterface

/**
 * 15.21.0.2038 专用：首页工具宫格逐项开关。
 *
 * <h3>15.21 与 17.02 的差异（DEX 实测）</h3>
 * <ul>
 *   <li>`BootBizDataPreloaderImpl.getToolboxData()`（classes4 @0x5617d8）读 MMKV
 *       `Key_toolbox_data`，为空时回落到方法内联的 **15 工具** JSON；
 *       字段 schema 与 17.02 完全一致：`id<i>/schema<i>/name<i>/imageClass<i>/
 *       imageToken<i>/size<i>/itemDataForClick<i>`。</li>
 *   <li>面板 `ToolboxPanelV2.init(Context,String,int,boolean)`（classes4 @0x4b8ca4）
 *       自己 `getToolboxData()` 后按 `id<i>/size<i>` 循环建格，
 *       **没有** 17.02 的 `toolbox.a.a(...)` 工厂。</li>
 *   <li>面板一屏按 `size<i>` 排 3 行 × 5 列 = **15 格**；写超过 15 个槽位不会渲染，
 *       所以这里以「宿主自己的槽位数」为上限，让用户打开的工具**顶掉**被关掉的默认工具，
 *       而不是默默写在第 16 个槽里看不见。</li>
 * </ul>
 *
 * <h3>为什么不写死槽位数</h3>
 * 不同版本/皮肤默认槽位数不同（15.21=15，17.02 首页=8）。这里读宿主 payload 的
 * `id0..idN` 连续段长度作为上限；读不到才退回 15。
 */
object AmapToolbox1521 {
    private const val VERSION_PREFIX = "15.21"

    @Volatile
    private var installed = false

    @JvmStatic
    fun matches(cl: ClassLoader): Boolean {
        val p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP)
        return p != null && p.versionName != null && p.versionName.startsWith(VERSION_PREFIX)
    }

    @JvmStatic
    fun install(cl: ClassLoader) {
        if (!matches(cl)) return
        if (installed) return
        installed = true
        val preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl")
        if (preloader == null) {
            H.log("event=tools1521_miss what=BootBizDataPreloaderImpl")
            return
        }
        H.hookSig(preloader, "getToolboxData", "amap_tool_boot_1521b", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (result !is JSONObject) return result
                try {
                    val before = result
                    val hostSlots = hostSlots(before)
                    val out = AmapData.toolbox(before, AmapData.CURRENT, true, hostSlots)
                    val b = countSlots(before)
                    val a = countSlots(out)
                    H.log("event=tools1521_before " + dump(before))
                    H.log("event=tools1521_after " + dump(out))
                    if (a != b) {
                        H.log("event=tools1521_filter before=" + b + " after=" + a
                                + " slots=" + hostSlots + " ids=" + AmapData.slotIds(out))
                    } else if (out !== before) {
                        H.log("event=tools1521_inject added=" + added(before, out) + " slots=" + hostSlots)
                    }
                    return out
                } catch (t: Throwable) {
                    H.log("event=tools1521_filter_failed err=" + t.javaClass.simpleName)
                    return result
                }
            }
        })
        H.log("event=tools1521_install version=15.21")
    }

    /** 宿主 payload 的连续槽位数（id0..idN）。读不到退回 15（15.21 实测值）。 */
    private fun hostSlots(o: JSONObject?): Int {
        val n = countSlots(o)
        return if (n > 0) n else 15
    }

    private fun countSlots(o: JSONObject?): Int {
        var n = 0
        while (n < 64 && o != null && o.has("id" + n)) n++
        return n
    }

    /** 打印前 32 个槽位的 id/size/name，确认 size 与 id 对齐。 */
    private fun dump(o: JSONObject?): String {
        val sb = StringBuilder("items=[")
        if (o != null) {
            var i = 0
            while (i < 32 && o.has("id" + i)) {
                if (i > 0) sb.append(" | ")
                sb.append(i).append(':').append(o.optString("id" + i))
                        .append('/').append(o.optString("size" + i))
                        .append('/').append(o.optString("name" + i))
                i++
            }
        }
        return sb.append(']').toString()
    }

    /** 输出里出现、而原 payload 没有的工具数。 */
    private fun added(before: JSONObject, after: JSONObject): Int {
        val old = java.util.HashSet<String>()
        for (i in 0 until countSlots(before)) old.add(before.optString("id" + i))
        var n = 0
        for (i in 0 until countSlots(after)) {
            val id = after.optString("id" + i)
            if (id.length > 0 && !old.contains(id)) n++
        }
        return n
    }
}
