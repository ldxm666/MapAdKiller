package io.github.ldxm666.mapadkiller

import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.HashMap

import org.json.JSONObject

/** Uses AMap's own asynchronous image loader and its disk/memory caches. */
object AmapToolIcons {
    private val MAIN = Handler(Looper.getMainLooper())

    /**
     * 图标键名的全部拼写，按优先级排列。
     *
     * <p>宿主有三套数据、各用一套拼写：boot 槽位是 `imageToken<i>`、工具目录是
     * `imageToken / imageLegacy / icon / iconV2`、首页 chips 是 `iconToken`。
     * 只认一套，就必然出现「有的格子有图、有的格子空白」——这正是本模块之前的实际表现。
     *
     * <p>顺序上 **token 在前、URL 在后**：token 指向高德自己的设计令牌图标，也就是工具行
     * 该有的原生线性图标；而目录里的 `iconV2` 是首页金刚位的 135px 彩色宣传图，
     * 画进工具格子会明显不像原生。
     */
    private val IMAGE_KEYS = arrayOf(
        "imageToken", "iconToken", "imageLegacy", "icon",
        "iconV2", "imageV2", "imageUrlRender", "image"
    )

    /**
     * 载荷里一个可用图标都取不到时，退到高德自己的 drawable。
     *
     * <p>只登记已经确认存在的资源：`getIdentifier` 在宿主包上查，查不到返回 0，
     * 所以漏登记只会回到「留白」，绝不会画错或崩溃。
     */
    private val NATIVE_DRAWABLE: Map<String, String> = buildNativeDrawable()

    private fun buildNativeDrawable(): Map<String, String> {
        val map = HashMap<String, String>()
        map.put("86", "child_more")     // 更多工具：该 token 在部分构建里没有对应 drawable
        return Collections.unmodifiableMap(map)
    }

    /**
     * 通用兜底链，按顺序尝试。
     *
     * <p>这几个全是高德**自己的**工具箱默认图标（`resources.arsc` 里实测存在），所以
     * 「载荷没给图标」的格子看起来就是高德原生的默认态，而不是一块留白，也不是模块自绘的
     * 假图标。`getIdentifier` 查不到就返回 0 并继续下一个候选，因此顺序写多写少都安全。
     */
    private val NATIVE_FALLBACK = arrayOf(
        "ic_toolbox_default", "tool_default", "child_default_icon", "ic_toolbox"
    )

    @JvmStatic
    fun load(cl: ClassLoader, view: ImageView, image: String?, boot: JSONObject?, id: String?) {
        val raw = first(image)
        // 裸 token（不带 @）也要认。宿主 tool_list_data 与 boot 槽位给的 imageToken
        // 都不带 @ 前缀，旧代码只认 "@" ⇒ token 分支与 URL 分支都不进 ⇒ 图标静默消失。
        var token = tokenOf(raw)
        // boot 只作**兜底**，绝不覆盖调用方已经解析出来的图标。
        // 旧代码用 boot 的 imageToken 覆盖目录的 iconV2，宿主只要留一个过期 token，
        // 整格图标就被打成空白（注册表 §〇「图标回归」记录的正是这一条）。
        if (token.length == 0 && raw.length == 0 && id != null && boot != null) {
            for (i in 0 until 10) if (id == boot.optString("id" + i)) {
                val fromBoot = tokenOf(boot.optString("imageToken" + i))
                if (fromBoot.length > 0) {
                    token = fromBoot
                    break
                }
            }
        }
        val imageToken = token
        val isUrl = raw.startsWith("https://") || raw.startsWith("http://")
        val itemId = id
        val bind = object : Runnable {
            override fun run() {
                var bound = false
                // 已登记原生 drawable 的 id 直接用它。理由：那个 token 在本构建里可能根本没有
                // 对应 drawable，而 proxy().s() **不会报错**、只会静默显示空白，事后无法检测。
                // applyDrawable 查不到资源会返回 false，此时照旧走 token / URL，行为不退化。
                if (itemId != null && NATIVE_DRAWABLE.containsKey(itemId)
                        && applyDrawable(view, NATIVE_DRAWABLE[itemId])) return
                try {
                    if (imageToken.isNotEmpty()) {
                        val proxy = view.javaClass.getMethod("proxy").invoke(view)
                        proxy.javaClass.getMethod("s", String::class.java).invoke(proxy, imageToken)
                        bound = true
                    }
                    if (isUrl) {
                        val loader = cl.loadClass("com.amap.AppInterfaces").getMethod("getImageLoader").invoke(null)
                        cl.loadClass("com.amap.imageloader.api.IImageLoader")
                            .getMethod("bind", ImageView::class.java, String::class.java).invoke(loader, view, raw)
                        bound = true
                    }
                } catch (e: Exception) {
                    if (Config.debugLog()) H.log("amap_tool_icon failed=" + e.javaClass.simpleName)
                }
                // 一条都没走通 ⇒ 这一格会留白。退到高德自己的 drawable：宁可暂时共用同一个
                // 原生图标，也不要在工具行里挖一个洞。
                if (!bound) applyNative(view, itemId)
            }
        }
        // The native BootUIPreloader builds this view on its HandlerThread.
        // Picasso requires the main looper; an icon failure must not replace the tool list.
        if (Looper.myLooper() == Looper.getMainLooper()) bind.run()
        else MAIN.post(bind)
    }

    /**
     * 一条 payload 条目里真正可用的图标：token 优先，URL 兜底。
     *
     * <p>这是工具格子的**唯一**图标入口。以前三处格子各自拼 key
     * （`tools()` 读 `icon`、`toolCell()` 读 `iconV2`、
     * `chips()` 走 `tokenOf`），所以同一个工具在不同行里有时有图、有时空白。
     */
    @JvmStatic
    fun imageOf(item: JSONObject?): String {
        if (item == null) return ""
        // 「工具图标统一彩绘（实验）」：优先取目录里的彩绘 PNG（iconV2/imageV2），
        // 不再让 token 抢在前面；默认关闭时保持原有 token 优先的原生行为。
        if (Config.toolColorIcon()) {
            for (key in arrayOf("iconV2", "imageV2", "imageUrlRender", "image")) {
                val value = first(item.optString(key))
                if (value.startsWith("http://") || value.startsWith("https://")) return value
            }
        }
        var url = ""
        for (key in IMAGE_KEYS) {
            val value = first(item.optString(key))
            if (value.length == 0) continue
            if (value.startsWith("http://") || value.startsWith("https://")) {
                if (url.length == 0) url = value
                continue
            }
            return tokenOf(value)      // token 命中即用，不再看后面的宣传图 URL
        }
        return url
    }

    /** 把任意写法归一成 `@token`；URL 与空值返回空串（它们不是 token）。 */
    @JvmStatic
    fun tokenOf(value: String?): String {
        val v = first(value)
        if (v.length == 0) return ""
        if (v.startsWith("http://") || v.startsWith("https://")) return ""
        return if (v.startsWith("@")) v else "@" + v
    }

    /**
     * The home-chips payload names its drawable with iconToken, while the tool list uses
     * icon/imageV2. Reading every spelling keeps one loader usable for both.
     */
    @JvmStatic
    fun tokenOf(item: JSONObject?): String {
        return tokenOf(imageOf(item))
    }

    /** 取首项并去空白：`icon` 在部分版本里是 `"a,b"` 的多形态列表。 */
    private fun first(value: String?): String {
        if (value == null) return ""
        var v = value.trim()
        val comma = v.indexOf(',')
        if (comma > 0) v = v.substring(0, comma).trim()
        return v
    }

    /**
     * 退到高德自己的 drawable：先按 id 找专属图标，再走通用兜底链。
     *
     * <p>任何一个都查不到就什么都不做 —— `getIdentifier` 在宿主包上查，返回 0 表示
     * 该构建没有这个资源，所以漏登记只会回到「留白」，绝不会画错或崩溃。
     */
    private fun applyNative(view: ImageView?, id: String?): Boolean {
        if (view == null) return false
        val specific = if (id == null) null else NATIVE_DRAWABLE[id]
        if (specific != null && applyDrawable(view, specific)) return true
        for (name in NATIVE_FALLBACK) if (applyDrawable(view, name)) return true
        return false
    }

    /** 把宿主包里名为 `name` 的 drawable 挂到这个 ImageView 上。 */
    private fun applyDrawable(view: ImageView?, name: String?): Boolean {
        if (view == null || name == null) return false
        try {
            val ctx = view.context ?: return false
            val res = ctx.resources.getIdentifier(name, "drawable", MainHook.PKG_AMAP)
            if (res == 0) return false
            val drawable = ctx.resources.getDrawable(res, ctx.theme) ?: return false
            view.setImageDrawable(drawable)
            return true
        } catch (t: Throwable) {
            if (Config.debugLog()) H.log("amap_tool_icon_native failed=" + name + "/" + t.javaClass.simpleName)
            return false
        }
    }
}
