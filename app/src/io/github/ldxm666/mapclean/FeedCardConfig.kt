package io.github.ldxm666.mapclean

/** Shared read/migration rule for AMap's single non-weather card switch. */
object FeedCardConfig {
    const val KEY = "feed_other"
    @JvmField val LEGACY_KEYS = arrayOf("feed_board", "feed_content", "feed_festival")

    @JvmStatic fun owns(key: String?): Boolean = key == KEY || LEGACY_KEYS.contains(key)

    /** New writes win; older installations keep an enabled choice until explicitly changed. */
    @JvmStatic fun enabled(values: Map<String, *>): Boolean =
            values[KEY] as? Boolean ?: LEGACY_KEYS.any { values[it] == true }

    /** Importing an old backup must also replace a newer merged setting already on the device. */
    @JvmStatic fun migrateImport(values: MutableMap<String, Any?>) {
        if (!values.containsKey(KEY) && LEGACY_KEYS.any { values.containsKey(it) }) {
            values[KEY] = enabled(values)
        } else if (values.containsKey(KEY) && values[KEY] == null) {
            // Explicit unset means the default OFF, rather than an old target value leaking back.
            for (legacy in LEGACY_KEYS) if (!values.containsKey(legacy)) values[legacy] = null
        }
    }
}
