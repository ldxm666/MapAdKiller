package io.github.ldxm666.mapadkiller

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 17.02 原生打车首页运营资源适配。标识来自原包的 TaxiOpearteEnum / OperateResourceRequest，
 * 不按文案、图片或卡片顺序判断。共享 content_info 仅处理打车首页 skin，保留安全和订单资源。
 */
object AmapTaxiData {
    const val RESOURCE = "/ws/promotion-web/resource"
    const val HOME = "/ws/promotion-web/resource/home"
    const val LEGACY = "/ws/sharedtrip/taxi/operation"
    const val CONTENT_INFO = "/ws/boss/car/order/content_info"

    @JvmStatic
    fun handles(path: String?): Boolean = path == RESOURCE || path == HOME || path == LEGACY || path == CONTENT_INFO

    /** 同一资源接口也用于等车、支付和顺风车：只有纯首页 position 请求可以过滤。 */
    @JvmStatic
    fun isHomeRequest(options: JSONObject): Boolean {
        return try {
            val url = options.optString("url")
            val path = AmapData.requestPath(url)
            if (!handles(path)) return false
            val params = JSONObject()
            val query = url.indexOf('?')
            if (query >= 0) copyParams(params, url.substring(query + 1).substringBefore('#'))
            copyParams(params, options.opt("body"))
            val node = params.optString("node")
            if (node.isNotEmpty() && node != "Amap") return false
            for (key in arrayOf("amapOrderId", "orderId", "order_id", "curAmapOrderId", "travelId")) {
                if (params.has(key) && !params.isNull(key) && params.optString(key).isNotEmpty()) return false
            }
            if (path == CONTENT_INFO) {
                // ResourceAutoRequest / ResourceConstantsNew：同一接口还承载行中安全与订单 UI。
                // 只命中乘客端、即时打车首页请求，不能按接口名一刀切。
                if (params.optString("pageId") !in arrayOf("homePage", "tabHomePage")) return false
                if (params.optString("typeId") != "Passenger" || params.optString("gdServiceId") != "1") return false
                val ossNode = params.optString("ossNode")
                if (ossNode.isNotEmpty() && ossNode != "Amap") return false
                if ("skin" !in params.optString("resources").split(',')) return false
                if (!params.has("position")) return true
            }
            val position = params.opt("position") ?: return false
            val positions = if (position is JSONArray) position else {
                val text = position.toString().trim()
                if (text.startsWith("[")) JSONArray(text) else JSONArray().put(text)
            }
            if (positions.length() == 0) return false
            for (i in 0 until positions.length()) {
                if (positions.opt(i).toString() !in arrayOf("9001", "9002", "9020")) return false
            }
            true
        } catch (_: Exception) { false }
    }

    private fun copyParams(target: JSONObject, value: Any?) {
        if (value == null || value === JSONObject.NULL) return
        val source = if (value is JSONObject) value else {
            val text = value.toString().trim()
            if (text.startsWith("{")) JSONObject(text) else {
                val form = JSONObject()
                for (pair in text.split('&')) {
                    val split = pair.indexOf('=')
                    if (split <= 0) continue
                    val key = URLDecoder.decode(pair.substring(0, split), "UTF-8")
                    val field = URLDecoder.decode(pair.substring(split + 1), "UTF-8")
                    // 重复的作用域参数不猜优先级，拒绝过滤这次请求。
                    require(!form.has(key))
                    form.put(key, field)
                }
                form
            }
        }
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // URL 与 body 也不能互相覆盖作用域；否则混合订单请求可能被误判为首页。
            if (key in arrayOf("position", "node", "amapOrderId", "orderId", "order_id",
                    "curAmapOrderId", "travelId", "pageId", "typeId", "gdServiceId", "ossNode", "resources")) {
                require(!target.has(key))
            }
            target.put(key, source.get(key))
        }
    }

    @JvmStatic
    fun keyFor(type: String): String? = when (type) {
        // 17.02 的活动专区使用 UnionCard + dataList[].protocol.show_info，旧协议仍用 activity。
        "activity", "union" -> Config.K_TAXI_ACTIVITY
        "coupon" -> Config.K_TAXI_COUPON
        "ad" -> Config.K_TAXI_AD
        "operation" -> Config.K_TAXI_OPERATION
        "task", "newcomer" -> Config.K_TAXI_TASK
        "vip" -> Config.K_TAXI_VIP
        "vip_exchange" -> Config.K_TAXI_MILEAGE
        else -> null // 新区块 / 未知标识保留，不扩大到交易和出行组件。
    }

    /** 调用者必须先用原请求 isHomeRequest 校验；只处理成功资源响应中的已确认推广区域。 */
    @JvmStatic
    fun response(path: String, raw: String, settings: AmapData.Settings): String {
        if (!handles(path) || raw.isEmpty()) return raw
        return try {
            val root = JSONObject(raw)
            if (path == CONTENT_INFO) return removeTopBanner(root, raw)
            val owner = bannerOwner(path, root) ?: return raw
            val banners = owner.optJSONArray("banner") ?: return raw
            val kept = JSONArray()
            for (i in 0 until banners.length()) {
                val item = banners.opt(i)
                val key = (item as? JSONObject)?.optString("banner_type")?.let { keyFor(it) }
                if (key == null || settings.enabled(key)) kept.put(item)
            }
            if (kept.length() == banners.length()) raw else {
                owner.put("banner", kept)
                root.toString()
            }
        } catch (_: Exception) { raw }
    }

    private fun skinResources(root: JSONObject): JSONObject? {
        if (root.optString("code") != "1") return null
        return root.optJSONObject("data")?.optJSONObject("lubanData")
    }

    private fun isTopBanner(item: Any?): Boolean = (item as? JSONObject)?.optJSONObject("protocol")
        ?.optJSONArray("skin_list")?.let { it.length() > 0 } == true

    /** 上车地址上方的营销横幅由 ResourceSkin 渲染；直接移除，原生空数据会自行收起。 */
    private fun removeTopBanner(root: JSONObject, raw: String): String {
        val skin = skinResources(root)?.optJSONObject("skin") ?: return raw
        val entries = skin.optJSONArray("dataList") ?: return raw
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.opt(i)
            if (!isTopBanner(item)) kept.put(item)
        }
        if (kept.length() == entries.length()) return raw
        skin.put("dataList", kept)
        return root.toString()
    }

    private fun bannerOwner(path: String, root: JSONObject): JSONObject? {
        if (root.has("code") && root.optString("code") != "1") return null
        val data = root.optJSONObject("data") ?: return null
        if (path != HOME) return data
        val envelope = data.optJSONObject("banner") ?: return null
        if (envelope.has("code") && envelope.optString("code") != "1") return null
        return envelope.optJSONObject("data")
    }

    /** 仅供 debug：固定白名单类型与数量，不输出未知标识、文案、地址、账户或跳转链接。 */
    @JvmStatic
    fun summary(path: String, raw: String): String {
        if (!handles(path)) return "unavailable"
        return try {
            val root = JSONObject(raw)
            if (path == CONTENT_INFO) {
                val resources = skinResources(root) ?: return "unavailable"
                val entries = resources.optJSONObject("skin")?.optJSONArray("dataList") ?: JSONArray()
                var bannerItems = 0
                for (i in 0 until entries.length()) if (isTopBanner(entries.opt(i))) bannerItems++
                val safety = resources.optJSONObject("securityTips")?.optJSONArray("dataList")?.length() ?: 0
                val compliance = resources.optJSONObject("complianceLinks")?.optJSONArray("dataList")?.length() ?: 0
                return "skinItems=${entries.length()} topBanners=$bannerItems securityItems=$safety complianceItems=$compliance"
            }
            val banners = bannerOwner(path, root)?.optJSONArray("banner")
                ?: return "unavailable"
            val counts = linkedMapOf<String, Int>()
            var unknown = 0
            var unionCards = 0
            for (i in 0 until banners.length()) {
                val item = banners.optJSONObject(i)
                val type = item?.optString("banner_type") ?: ""
                if (keyFor(type) == null) unknown++ else counts[type] = (counts[type] ?: 0) + 1
                if (type == "union") {
                    val entries = item?.optJSONArray("dataList") ?: continue
                    for (j in 0 until entries.length()) {
                        unionCards += entries.optJSONObject(j)?.optJSONObject("protocol")
                            ?.optJSONArray("show_info")?.length() ?: 0
                    }
                }
            }
            "total=${banners.length()} types=${counts.entries.joinToString(",") { "${it.key}:${it.value}" }} unknown=$unknown unionCards=$unionCards"
        } catch (_: Exception) { "unavailable" }
    }
}
