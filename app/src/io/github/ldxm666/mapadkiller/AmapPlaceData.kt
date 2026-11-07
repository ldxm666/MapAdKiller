package io.github.ldxm666.mapadkiller

import java.util.HashSet

import org.json.JSONArray
import org.json.JSONObject

/** POI-detail BFF module IDs verified from the installed application's response. */
object AmapPlaceData {
    const val DETAIL_PATH = "/ws/shield/search/poi/detail"
    private val FEED_MODULES = arrayOf("poiDetailWaterFeedTitle", "poiDetailWaterFeed")

    @JvmStatic
    fun response(path: String?, raw: String?, showRecommendations: Boolean): String? {
        if (showRecommendations || DETAIL_PATH != path || raw == null || raw.isEmpty() ||
                raw.length > 4 * 1024 * 1024) return raw
        try {
            val out = JSONObject(raw)
            val data = out.optJSONObject("data")
            val modules: JSONObject? = if (data == null) null else data.optJSONObject("modules")
            val regions: JSONObject? = if (data == null) null else data.optJSONObject("regions")
            if (modules == null || regions == null) return raw
            val moduleNames = HashSet<String>()
            val references = HashSet<String>()
            for (name in FEED_MODULES) {
                val module = modules.optJSONObject(name)
                if (modules.has(name) && module == null) return raw
                if (module == null) continue
                moduleNames.add(name)
                references.add(name)
                val cardId = module.opt("card_id")
                if (cardId is String && cardId.isNotEmpty()) references.add(cardId)
            }
            if (moduleNames.isEmpty()) return raw
            // A shared card ID is ambiguous: retain its reference for the surviving module.
            val names = modules.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (moduleNames.contains(name)) continue
                val module = modules.optJSONObject(name)
                val cardId = if (module == null) null else module.opt("card_id")
                if (cardId is String) {
                    if (moduleNames.contains(cardId)) return raw
                    references.remove(cardId)
                }
            }
            for (name in moduleNames) modules.remove(name)
            if (regions != null) {
                val regionNames = regions.keys()
                while (regionNames.hasNext()) {
                    val name = regionNames.next()
                    val source = regions.optJSONArray(name)
                    if (source == null) continue
                    val kept = JSONArray()
                    for (i in 0 until source.length()) {
                        val item = source.opt(i)
                        if (item !is String || !references.contains(item)) kept.put(item)
                    }
                    if (kept.length() != source.length()) regions.put(name, kept)
                }
            }
            return out.toString()
        } catch (ignored: Exception) {
            return raw
        }
    }
}
