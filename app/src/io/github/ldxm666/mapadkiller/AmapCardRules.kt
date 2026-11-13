package io.github.ldxm666.mapadkiller

import org.json.JSONObject
import org.json.JSONArray

/** One classifier for HTTP, cached card lists and home_qs streaming envelopes. */
object AmapCardRules {
    private val fields = arrayOf("cardName", "card_name", "bizName", "biz_name", "dataType", "data_type", "dataKey", "data_key", "name")

    @JvmStatic
    fun key(name: String?): String? {
        val id = name.orEmpty().lowercase(java.util.Locale.ROOT).replace(Regex("[_\\-\\s]"), "")
        return when {
            id.contains("sharetriptaxi") || id == "taxiqs" || id == "taxiqsmarket" -> Config.K_TAXI_CARD
            id == "frequentlocations" || id == "frequentlocation" -> Config.K_HOME_CHIPS
            id.contains("weather") -> Config.K_FEED_WEATHER
            id.contains("tipsranking") || id.contains("ranking") || id.contains("bangdan") || id.contains("榜单") -> Config.K_FEED_BOARD
            id == "ridesharerecruit" || id.contains("festival") || id.contains("promote") || id.contains("activity") || id.contains("coupon") -> Config.K_FEED_FESTIVAL
            id.contains("contentcenter") || id.contains("contentfeed") || id == "feed" || id.startsWith("recommend") -> Config.K_FEED_CONTENT
            else -> null
        }
    }

    @JvmStatic
    fun key(card: JSONObject): String? {
        val component = card.optJSONObject("component")
        // Inspect identities, never arbitrary text, URLs or nested business payloads.
        val nodes = arrayOf(card, component, card.optJSONObject("extra_info"), card.optJSONObject("extraInfo"),
                component?.optJSONObject("extra_info"), component?.optJSONObject("extraInfo"))
        for (node in nodes) if (node != null) for (field in fields) {
            val found = key(node.optString(field))
            if (found != null) return found
        }
        return null
    }

    @JvmStatic
    fun visible(card: JSONObject, settings: AmapData.Settings): Boolean =
            key(card)?.let { settings.enabled(it) } ?: true

    /** Returns the original string if unchanged; preserves mapBizList and status metadata. */
    @JvmStatic
    fun response(raw: String, settings: AmapData.Settings): String {
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: return raw
        val cards = data.optJSONArray("cardList") ?: return raw
        val kept = JSONArray()
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i)
            if (card == null || visible(card, settings)) kept.put(cards.opt(i))
        }
        if (kept.length() == cards.length()) return raw
        data.put("cardList", kept)
        return root.toString()
    }

    @JvmStatic
    fun cached(raw: String, settings: AmapData.Settings): String =
            if (raw.contains("\"cardList\"") && raw.contains("\"mapBizList\"")) response(raw, settings) else raw
}
