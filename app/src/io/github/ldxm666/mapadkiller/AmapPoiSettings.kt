package io.github.ldxm666.mapadkiller

/** Bits match style::poiCategory in native/style_proto.h. Bit 0 is the existing
 * all-POI master, so older native callers retain their original semantics.
 */
object AmapPoiSettings {
    @JvmField val KEYS = arrayOf("map_poi_food", "map_poi_shopping", "map_poi_medical",
        "map_poi_education", "map_poi_transport", "map_poi_hotel", "map_poi_leisure",
        "map_poi_finance", "map_poi_services")
    @JvmField val TITLES = arrayOf("餐饮", "购物", "医疗", "学校与科教文化", "交通设施",
        "住宿", "景点与休闲", "金融", "生活服务")

    @JvmStatic fun mask(master: Boolean, visible: BooleanArray): Int {
        if (!master) return 1
        var mask = 0
        for (i in KEYS.indices) if (i < visible.size && !visible[i]) mask = mask or (1 shl (i + 1))
        return mask
    }

    @JvmStatic fun hiddenMask(): Int = mask(Config.visible(Config.K_MAP_POI),
        BooleanArray(KEYS.size) { Config.visible(KEYS[it]) })
}
