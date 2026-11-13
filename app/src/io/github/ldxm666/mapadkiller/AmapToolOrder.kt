package io.github.ldxm666.mapadkiller

import io.github.ldxm666.mapclean.ToolOrderCodec

/** Stable IDs, independent of names, network ordering and temporary visibility. */
object AmapToolOrder {
    @JvmStatic fun current(): List<String> = ToolOrderCodec.read(Config.getString(Config.K_TOOLS_ORDER, null))

    @JvmStatic fun reconcile(saved: List<String>, available: List<String>): List<String> {
        val present = LinkedHashSet(available)
        val out = ArrayList<String>()
        for (id in saved) if (id != AmapData.MORE_TOOLS && present.remove(id)) out.add(id)
        for (id in available) if (id != AmapData.MORE_TOOLS && present.remove(id)) out.add(id)
        if (present.remove(AmapData.MORE_TOOLS)) out.add(AmapData.MORE_TOOLS)
        return out
    }

    /** Replace visible positions, retaining the remembered positions of disabled tools. */
    @JvmStatic fun remember(saved: List<String>, visible: List<String>): List<String> {
        val unique = LinkedHashSet(visible.filter { it != AmapData.MORE_TOOLS })
        val active = unique.iterator()
        val out = ArrayList<String>()
        for (id in saved.distinct()) {
            if (id == AmapData.MORE_TOOLS) continue
            if (id in unique) { if (active.hasNext()) out.add(active.next()) }
            else out.add(id)
        }
        while (active.hasNext()) out.add(active.next())
        return out
    }

    @JvmStatic fun enabledIds(settings: AmapData.Settings, saved: List<String>): List<String> {
        val selecting = object : AmapData.Settings {
            override fun enabled(key: String): Boolean = key == Config.K_TOOLS_VISIBLE || settings.enabled(key)
            override fun selected(key: String): Boolean = settings.selected(key)
        }
        val ids = AmapToolCatalog.ENTRIES.filter {
            it[0] != AmapData.MORE_TOOLS && AmapData.toolFill(it[0], selecting)
        }.map { it[0] }
        return reconcile(saved, ids)
    }
}
